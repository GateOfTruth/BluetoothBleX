/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.bluetooth

import android.content.Context
import androidx.annotation.RequiresPermission
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 蓝牙 LE 连接管理器。
 *
 * 持有 [BluetoothLe] 实例，并通过 [connectionMode] 控制底层 GATT 连接的生命周期：
 *
 * - [ConnectionMode.SHORT_LIVED]（短链接，默认）：每次 [connectGatt] 执行完 block 后立即
 *   关闭并断开 GATT，行为与直接调用 [BluetoothLe.connectGatt] 完全一致。
 * - [ConnectionMode.LONG_LIVED]（长连接）：对同一设备复用已建立的 [GattConnection]，
 *   不会在 block 结束后关闭 GATT，直到调用 [closeConnection] 或 [closeAllConnections]
 *   才会执行 `disconnect` 与 `close`。
 *
 * 典型用法：
 * ```
 * val manager = BluetoothLeManager(context).apply {
 *     connectionMode = ConnectionMode.LONG_LIVED
 * }
 *
 * // 长连接模式下，第一次调用建立连接并缓存，后续调用复用
 * manager.connectGatt(device) { writeCharacteristic(char, data) }
 * manager.connectGatt(device) { readCharacteristic(char) }
 *
 * // 用完主动关闭
 * manager.closeConnection(device)
 * ```
 *
 * @param bluetoothLe 被管理的 [BluetoothLe] 实例
 */
class BluetoothLeManager(private val bluetoothLe: BluetoothLe) {

    /**
     * 通过 [Context] 构造管理器，内部创建独立的 [BluetoothLe] 实例。
     */
    constructor(context: Context) : this(BluetoothLe(context))

    /**
     * 连接模式配置。
     *
     * 修改该值只影响后续 [connectGatt] 调用，不会改变已建立的长连接。
     * 默认为 [ConnectionMode.SHORT_LIVED]，保持与原 [BluetoothLe.connectGatt] 一致的行为。
     */
    var connectionMode: ConnectionMode = ConnectionMode.SHORT_LIVED

    /**
     * 长连接模式下是否在连接意外断开后自动重连。
     *
     * 仅 [ConnectionMode.LONG_LIVED] 生效。默认为 `true`：当缓存的连接失效时，
     * 下次 [connectGatt] 会自动重建连接。设为 `false` 则抛出 [CancellationException]。
     */
    var autoReconnect: Boolean = true

    /**
     * 长连接模式下，按设备 id 缓存的 [GattConnection]。
     */
    private val connections = mutableMapOf<UUID, GattConnection>()

    private val connectionsMutex = Mutex()

    /**
     * 当前已缓存（长连接）的设备列表。
     */
    val connectedDevices: List<BluetoothDevice>
        get() = synchronized(connections) {
            connections.values.map { it.device }
        }

    /**
     * 连接到远端 GATT 服务并执行 [block]。
     *
     * 行为由当前 [connectionMode] 决定：
     * - [ConnectionMode.SHORT_LIVED]：直接委托 [BluetoothLe.connectGatt]，block 结束即关闭 GATT。
     * - [ConnectionMode.LONG_LIVED]：复用缓存的 [GattConnection]，block 结束**不**关闭 GATT。
     *
     * @param device 目标设备
     * @param mtu 连接后请求的 MTU，默认 [BluetoothLe.DEFAULT_MTU] (515)
     * @param block 连接成功后执行的操作
     * @return block 的返回值
     * @throws CancellationException 连接失败、被取消，或在长连接模式下连接已断开且 [autoReconnect] 为 false
     */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    suspend fun <R> connectGatt(
        device: BluetoothDevice,
        mtu: Int = BluetoothLe.DEFAULT_MTU,
        block: suspend GattClientScope.() -> R
    ): R {
        return when (connectionMode) {
            ConnectionMode.SHORT_LIVED -> bluetoothLe.connectGatt(device, mtu, block)
            ConnectionMode.LONG_LIVED -> connectLongLived(device, mtu, block)
        }
    }

    /**
     * 主动关闭指定设备的长连接。
     *
     * 如果当前为短链接模式或该设备没有缓存的长连接，则不做任何事。
     * 调用后会执行 `BluetoothGatt.disconnect()` 与 `BluetoothGatt.close()`。
     */
    fun closeConnection(device: BluetoothDevice) {
        val conn = synchronized(connections) { connections.remove(device.id) }
        conn?.close()
    }

    /**
     * 关闭并清理所有缓存的长连接。
     *
     * 建议在 Activity/Service 的 `onDestroy` 等生命周期回调中调用，避免连接泄漏。
     */
    fun closeAllConnections() {
        val snapshot = synchronized(connections) {
            val list = connections.values.toList()
            connections.clear()
            list
        }
        snapshot.forEach { it.close() }
    }

    /**
     * 获取指定设备当前缓存的长连接（可能为 null）。
     *
     * 主要用于监听 [GattConnection.onDisconnected] 或查询 [GattConnection.services]。
     */
    fun getConnection(device: BluetoothDevice): GattConnection? {
        return synchronized(connections) { connections[device.id] }
    }

    private suspend fun <R> connectLongLived(
        device: BluetoothDevice,
        mtu: Int,
        block: suspend GattClientScope.() -> R
    ): R {
        while (true) {
            val connection = getOrCreateConnection(device, mtu)
            if (!connection.isActive) {
                // 缓存的连接已失效，移除后重试
                removeConnection(device.id)
                if (!autoReconnect) {
                    throw CancellationException("GATT connection is closed and autoReconnect is false")
                }
                continue
            }
            return try {
                connection.withScope(block)
            } catch (e: CancellationException) {
                // 如果是连接本身被关闭导致的取消，移除并按需重连
                if (!connection.isActive) {
                    removeConnection(device.id)
                    if (autoReconnect && e.message?.startsWith("GATT connection closed") == true) {
                        continue
                    }
                }
                throw e
            }
        }
    }

    private suspend fun getOrCreateConnection(
        device: BluetoothDevice,
        mtu: Int
    ): GattConnection {
        // 先快速检查缓存
        synchronized(connections) { connections[device.id] }?.let { return it }
        connectionsMutex.withLock {
            // 双重检查，避免并发重复建链
            synchronized(connections) { connections[device.id] }?.let { return it }
            val connection = bluetoothLe.connectGattLongLived(device, mtu)
            synchronized(connections) { connections[device.id] = connection }
            return connection
        }
    }

    private fun removeConnection(id: UUID) {
        synchronized(connections) { connections.remove(id) }
    }
}
