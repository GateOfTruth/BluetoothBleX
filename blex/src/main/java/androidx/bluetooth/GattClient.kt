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

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice as FwkBluetoothDevice
import android.bluetooth.BluetoothGatt as FwkBluetoothGatt
import android.bluetooth.BluetoothGattCallback as FwkBluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic as FwkBluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor as FwkBluetoothGattDescriptor
import android.bluetooth.BluetoothGattService as FwkBluetoothGattService
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import androidx.bluetooth.GattCommon.MAX_ATTR_LENGTH
import java.util.UUID

/**
 * A class for handling operations as a GATT client role.
 *
 * 该类同时支持两种连接方式：
 * - [connect]：短链接，block 执行完毕后立即关闭 GATT（与原有行为完全一致）。
 * - [connectLongLived]：长连接，返回可复用的 [GattConnection]，由调用方决定何时 [GattConnection.close]。
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class GattClient(private val context: Context) {

    @VisibleForTesting
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    companion object {
        private const val TAG = "GattClient"

        /**
         * The maximum ATT size + header(3).
         * This is the default MTU used when connecting to a GATT server.
         */
        const val GATT_MAX_MTU = MAX_ATTR_LENGTH + 3
    }

    interface FrameworkAdapter {
        var fwkBluetoothGatt: FwkBluetoothGatt?
        fun connectGatt(
            context: Context,
            fwkDevice: FwkBluetoothDevice,
            fwkCallback: FwkBluetoothGattCallback
        ): Boolean
        fun requestMtu(mtu: Int)
        fun discoverServices()
        fun getServices(): List<FwkBluetoothGattService>
        fun getService(uuid: UUID): FwkBluetoothGattService?
        fun readCharacteristic(fwkCharacteristic: FwkBluetoothGattCharacteristic)
        fun writeCharacteristic(
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int
        )
        fun writeDescriptor(fwkDescriptor: FwkBluetoothGattDescriptor, value: ByteArray)
        fun setCharacteristicNotification(
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            enable: Boolean
        )
        fun closeGatt()
    }

    @VisibleForTesting
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    var fwkAdapter: FrameworkAdapter = createFrameworkAdapter()

    /**
     * 创建一个新的 [FrameworkAdapter] 实例，依据当前 API level 选择合适的实现。
     *
     * 长连接场景下每条 [GattConnection] 持有独立的 adapter，避免并发连接互相覆盖
     * `BluetoothGatt` 句柄。
     */
    @VisibleForTesting
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    fun createFrameworkAdapter(): FrameworkAdapter =
        if (Build.VERSION.SDK_INT >= 33) FrameworkAdapterApi33()
        else if (Build.VERSION.SDK_INT >= 31) FrameworkAdapterApi31()
        else FrameworkAdapterBase()

    /**
     * 短链接：连接远端 GATT 服务，执行 [block]，结束后立即关闭 GATT。
     *
     * 与原有行为完全一致 —— 复用 [GattConnection] 实现，在 finally 中调用
     * [GattConnection.close] 释放底层资源。
     *
     * @param device 目标设备
     * @param mtu 连接后请求的 MTU，默认 [GATT_MAX_MTU] (515)，有效范围 23..517
     * @param block 连接成功后执行的操作
     * @throws kotlinx.coroutines.CancellationException 连接失败或被取消
     */
    @SuppressLint("MissingPermission")
    suspend fun <R> connect(
        device: BluetoothDevice,
        mtu: Int = GATT_MAX_MTU,
        block: suspend GattClientScope.() -> R
    ): R {
        // 短链接复用 GattClient 自身的 fwkAdapter 实例，保持与既有测试注入行为一致。
        val connection = GattConnection(context, device, mtu, fwkAdapter)
        connection.connect()
        return try {
            connection.withScope(block)
        } finally {
            connection.close()
        }
    }

    /**
     * 长连接：连接远端 GATT 服务并返回可复用的 [GattConnection]，**不会**自动关闭。
     *
     * 调用方可在同一连接上多次执行 [GattConnection.withScope] 复用 GATT，
     * 直到主动调用 [GattConnection.close] 才会断开。
     *
     * @param device 目标设备
     * @param mtu 连接后请求的 MTU，默认 [GATT_MAX_MTU] (515)
     * @return 已建立的 [GattConnection]
     * @throws kotlinx.coroutines.CancellationException 连接失败或被取消
     */
    @SuppressLint("MissingPermission")
    suspend fun connectLongLived(
        device: BluetoothDevice,
        mtu: Int = GATT_MAX_MTU
    ): GattConnection {
        val connection = GattConnection(context, device, mtu, createFrameworkAdapter())
        connection.connect()
        return connection
    }

    private open class FrameworkAdapterBase : FrameworkAdapter {

        override var fwkBluetoothGatt: FwkBluetoothGatt? = null

        @SuppressLint("MissingPermission")
        override fun connectGatt(
            context: Context,
            fwkDevice: FwkBluetoothDevice,
            fwkCallback: FwkBluetoothGattCallback
        ): Boolean {
            fwkBluetoothGatt = fwkDevice.connectGatt(context, /*autoConnect=*/false, fwkCallback)
            return fwkBluetoothGatt != null
        }

        @SuppressLint("MissingPermission")
        override fun requestMtu(mtu: Int) {
            fwkBluetoothGatt?.requestMtu(mtu)
        }

        @SuppressLint("MissingPermission")
        override fun discoverServices() {
            fwkBluetoothGatt?.discoverServices()
        }

        override fun getServices(): List<FwkBluetoothGattService> {
            return fwkBluetoothGatt?.services ?: listOf()
        }

        override fun getService(uuid: UUID): FwkBluetoothGattService? {
            return fwkBluetoothGatt?.getService(uuid)
        }

        @SuppressLint("MissingPermission")
        override fun readCharacteristic(fwkCharacteristic: FwkBluetoothGattCharacteristic) {
            fwkBluetoothGatt?.readCharacteristic(fwkCharacteristic)
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun writeCharacteristic(
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int
        ) {
            fwkCharacteristic.value = value
            fwkBluetoothGatt?.writeCharacteristic(fwkCharacteristic)
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun writeDescriptor(fwkDescriptor: FwkBluetoothGattDescriptor, value: ByteArray) {
            fwkDescriptor.value = value
            fwkBluetoothGatt?.writeDescriptor(fwkDescriptor)
        }

        @SuppressLint("MissingPermission")
        override fun setCharacteristicNotification(
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            enable: Boolean
        ) {
            fwkBluetoothGatt?.setCharacteristicNotification(fwkCharacteristic, enable)
        }

        @SuppressLint("MissingPermission")
        override fun closeGatt() {
            fwkBluetoothGatt?.close()
            fwkBluetoothGatt?.disconnect()
        }
    }

    @RequiresApi(31)
    private open class FrameworkAdapterApi31 : FrameworkAdapterBase() {

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun connectGatt(
            context: Context,
            fwkDevice: FwkBluetoothDevice,
            fwkCallback: FwkBluetoothGattCallback
        ): Boolean {
            return super.connectGatt(context, fwkDevice, fwkCallback)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun requestMtu(mtu: Int) {
            return super.requestMtu(mtu)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun discoverServices() {
            return super.discoverServices()
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun readCharacteristic(fwkCharacteristic: FwkBluetoothGattCharacteristic) {
            return super.readCharacteristic(fwkCharacteristic)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun writeCharacteristic(
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int
        ) {
            return super.writeCharacteristic(fwkCharacteristic, value, writeType)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun writeDescriptor(fwkDescriptor: FwkBluetoothGattDescriptor, value: ByteArray) {
            return super.writeDescriptor(fwkDescriptor, value)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun setCharacteristicNotification(
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            enable: Boolean
        ) {
            return super.setCharacteristicNotification(fwkCharacteristic, enable)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun closeGatt() {
            return super.closeGatt()
        }
    }

    @RequiresApi(33)
    private open class FrameworkAdapterApi33 : FrameworkAdapterApi31() {
        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun writeCharacteristic(
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int
        ) {
            fwkBluetoothGatt?.writeCharacteristic(fwkCharacteristic, value, writeType)
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        override fun writeDescriptor(
            fwkDescriptor: FwkBluetoothGattDescriptor,
            value: ByteArray
        ) {
            fwkBluetoothGatt?.writeDescriptor(fwkDescriptor, value)
        }
    }
}
