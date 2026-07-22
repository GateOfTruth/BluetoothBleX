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

import android.bluetooth.BluetoothManager as FwkBluetoothManager
import android.content.Context
import androidx.annotation.IntDef
import androidx.annotation.RequiresPermission
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Entry point for BLE related operations. This class provides a way to perform Bluetooth LE
 * operations such as scanning, advertising, and connection with a respective [BluetoothDevice].
 */
class BluetoothLe(context: Context) {

    companion object {
        /** Advertise started successfully. */
        const val ADVERTISE_STARTED: Int = 10100

        /**
         * The default MTU used when connecting to a GATT server.
         * This is the maximum ATT size (512) + header (3) = 515.
         */
        const val DEFAULT_MTU: Int = GattClient.GATT_MAX_MTU
    }

    @Target(
        AnnotationTarget.PROPERTY,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.TYPE
    )
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(
        ADVERTISE_STARTED,
    )
    annotation class AdvertiseResult

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as FwkBluetoothManager?
    private val bluetoothAdapter = bluetoothManager?.adapter

    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @set:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    var advertiseImpl: AdvertiseImpl? =
        bluetoothAdapter?.bluetoothLeAdvertiser?.let(::getAdvertiseImpl)

    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @set:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    var scanImpl: ScanImpl? =
        bluetoothAdapter?.bluetoothLeScanner?.let(::getScanImpl)

    @VisibleForTesting
    @get:RestrictTo(RestrictTo.Scope.LIBRARY)
    val client: GattClient by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GattClient(context.applicationContext)
    }

    @VisibleForTesting
    @get:RestrictTo(RestrictTo.Scope.LIBRARY)
    val server: GattServer by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GattServer(context.applicationContext)
    }

    /**
     * Returns a _cold_ [Flow] to start Bluetooth LE advertising
     *
     * Note that this method may not complete if the duration is set to 0.
     * To stop advertising, in that case, you should cancel the coroutine.
     *
     * @param advertiseParams [AdvertiseParams] for Bluetooth LE advertising.
     * @return a _cold_ [Flow] of [ADVERTISE_STARTED] if advertising is started.
     *
     * @throws AdvertiseException if the advertise fails.
     * @throws IllegalArgumentException if the advertise parameters are not valid.
     */
    @RequiresPermission("android.permission.BLUETOOTH_ADVERTISE")
    fun advertise(advertiseParams: AdvertiseParams): Flow<@AdvertiseResult Int> {
        return advertiseImpl?.advertise(advertiseParams) ?: callbackFlow {
            close(AdvertiseException(AdvertiseException.UNSUPPORTED))
        }
    }

    /**
     * Returns a _cold_ [Flow] to start Bluetooth LE scanning.
     * Scanning is used to discover advertising devices nearby.
     *
     * @param filters [ScanFilter]s for finding exact Bluetooth LE devices.
     *
     * @return a _cold_ [Flow] of [ScanResult] that matches with the given scan filter.
     *
     * @throws ScanException if the scan fails.
     */
    @RequiresPermission("android.permission.BLUETOOTH_SCAN")
    fun scan(filters: List<ScanFilter> = emptyList()): Flow<ScanResult> {
        return scanImpl?.scan(filters) ?: callbackFlow {
            close(ScanException(ScanException.UNSUPPORTED))
        }
    }

    /**
     * Connects to the GATT server on the remote Bluetooth device and
     * invokes the given [block] after the connection is made.
     *
     * The block may not be run if connection fails.
     *
     * @param device a [BluetoothDevice] to connect to
     * @param mtu the MTU to request after connection. The default value is
     *            [DEFAULT_MTU] (515). The valid range is 23 to 517.
     * @param block a block of code that is invoked after the connection is made
     *
     * @throws CancellationException if connect failed or it's canceled
     * @return a result returned by the given block if the connection was successfully finished
     *         or a failure with the corresponding reason
     *
     */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    suspend fun <R> connectGatt(
        device: BluetoothDevice,
        mtu: Int = DEFAULT_MTU,
        block: suspend GattClientScope.() -> R
    ): R {
        return client.connect(device, mtu, block)
    }

    /**
     * 以**长连接**方式连接远端 GATT 服务，返回可复用的 [GattConnection]，**不会**自动关闭。
     *
     * 与 [connectGatt] 不同，该方法建立连接后不会在 block 结束时关闭 GATT，而是返回
     * 一个 [GattConnection] 句柄，调用方可多次调用 [GattConnection.withScope] 复用同一条
     * GATT 连接，直到主动调用 [GattConnection.close] 才会断开。
     *
     * 适合需要频繁与同一设备交互的场景，避免反复建链带来的开销。
     *
     * @param device a [BluetoothDevice] to connect to
     * @param mtu the MTU to request after connection. The default value is
     *            [DEFAULT_MTU] (515). The valid range is 23 to 517.
     * @return 已建立并可复用的 [GattConnection]
     * @throws CancellationException if connect failed or it's canceled
     */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    suspend fun connectGattLongLived(
        device: BluetoothDevice,
        mtu: Int = DEFAULT_MTU
    ): GattConnection {
        return client.connectLongLived(device, mtu)
    }

    /**
     * Opens a GATT server.
     *
     * Only one server at a time can be opened.
     *
     * @param services the services that will be exposed to the clients
     *
     * @see GattServerConnectRequest
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openGattServer(services: List<GattService>): GattServerConnectFlow {
        return server.open(services)
    }
}
