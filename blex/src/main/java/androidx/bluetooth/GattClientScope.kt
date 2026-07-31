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

import android.bluetooth.BluetoothProfile as FwkBluetoothProfile
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * GATT 连接状态变化事件。
 *
 * @property status 框架层回调的状态码（如 [android.bluetooth.BluetoothGatt.GATT_SUCCESS]，
 *     非 0 通常表示异常断开，例如 133）
 * @property newState 新的连接状态，取值为 [android.bluetooth.BluetoothProfile.STATE_CONNECTED]、
 *     [android.bluetooth.BluetoothProfile.STATE_DISCONNECTED] 等
 */
data class ConnectionStateChange(
    val status: Int,
    val newState: Int
) {
    /** 当前是否处于已连接状态。 */
    val isConnected: Boolean
        get() = newState == FwkBluetoothProfile.STATE_CONNECTED

    /** 当前是否处于已断开状态。 */
    val isDisconnected: Boolean
        get() = newState == FwkBluetoothProfile.STATE_DISCONNECTED
}

/**
 * Scope for operations as a GATT client role.
 *
 * @see BluetoothLe.connectGatt
 */
interface GattClientScope {

    /**
     * 当前 GATT 连接状态的 Flow。
     *
     * 连接状态发生变化（连接成功、断开等）时会发射 [ConnectionStateChange]，
     * 可在任意时刻 collect 以监听蓝牙连接状态变化。
     */
    val connectionStateFlow: StateFlow<ConnectionStateChange>


    /**
     * A flow of GATT services discovered from the remote device.
     *
     * If the services of the remote device has changed, the new services will be
     * discovered and emitted automatically.
     */
    val servicesFlow: StateFlow<List<GattService>>

    /**
     * GATT services recently discovered from the remote device.
     *
     * Note that this can be changed, subscribe to [servicesFlow] to get notified
     * of services changes.
     */
    val services: List<GattService> get() = servicesFlow.value

    /**
     * Gets the service of the remote device by UUID.
     *
     * If multiple instances of the same service exist, the first instance of the services
     * is returned.
     */
    fun getService(uuid: UUID): GattService?

    /**
     * Reads the characteristic value from the server.
     *
     * @param characteristic a remote [GattCharacteristic] to read
     * @return the value of the characteristic
     */
    suspend fun readCharacteristic(characteristic: GattCharacteristic): Result<ByteArray>

    /**
     * Writes the characteristic value to the server.
     *
     * @param characteristic a remote [GattCharacteristic] to write
     * @param value a value to be written.
     * @throws IllegalArgumentException if the [characteristic] doesn't have the write
     *     property or the length of the [value] is greater than the maximum
     *     attribute length (512)
     * @return the result of the write operation
     */
    suspend fun writeCharacteristic(
        characteristic: GattCharacteristic,
        value: ByteArray
    ): Result<Unit>

    /**
     * Returns a _cold_ [Flow] that contains the indicated value of the given characteristic.
     *
     * 当 GATT 连接断开时，该 Flow 会以 [kotlinx.coroutines.CancellationException] 结束。
     */
    fun subscribeToCharacteristic(characteristic: GattCharacteristic): Flow<ByteArray> =
        subscribeToCharacteristic(characteristic, onConnectionStateChange = null)

    /**
     * 订阅特征值通知，并同时监听蓝牙连接状态变化。
     *
     * 与单参数版本的区别：
     * - 每次连接状态变化（包含订阅时的当前状态）都会回调 [onConnectionStateChange]；
     * - GATT 断开时，Flow 会以 [kotlinx.coroutines.CancellationException] 自动结束，
     *   不会无限挂起。
     *
     * @param characteristic 要订阅的远端特征值
     * @param onConnectionStateChange 连接状态变化回调，传 null 表示不关心状态回调
     * @return 特征值通知数据的 _cold_ [Flow]
     */
    fun subscribeToCharacteristic(
        characteristic: GattCharacteristic,
        onConnectionStateChange: ((ConnectionStateChange) -> Unit)?
    ): Flow<ByteArray>
}
