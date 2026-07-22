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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice as FwkBluetoothDevice
import android.bluetooth.BluetoothGatt as FwkBluetoothGatt
import android.bluetooth.BluetoothGattCallback as FwkBluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic as FwkBluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor as FwkBluetoothGattDescriptor
import android.bluetooth.BluetoothGattService as FwkBluetoothGattService
import android.content.Context
import androidx.bluetooth.GattCharacteristic.Companion.PROPERTY_NOTIFY
import androidx.bluetooth.GattCharacteristic.Companion.PROPERTY_WRITE
import androidx.bluetooth.GattCharacteristic.Companion.PROPERTY_WRITE_NO_RESPONSE
import androidx.bluetooth.GattCommon.MAX_ATTR_LENGTH
import androidx.bluetooth.GattCommon.UUID_CCCD
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 表示一条已建立的 GATT 连接。
 *
 * 该类将原来在 [GattClient.connect] 内部一次性创建的连接状态（回调、Flow、订阅表等）
 * 抽取为可持有对象，从而支持：
 * - **短链接**：建立连接 → 运行一次 block → 立即 [close]（与原行为一致）。
 * - **长连接**：建立连接后多次调用 [withScope] 复用同一条 GATT，直到主动调用 [close]。
 *
 * 每个 [GattConnection] 持有独立的 [GattClient.FrameworkAdapter]，因此多条长连接可以
 * 并发存在而不会互相覆盖底层 `BluetoothGatt` 句柄。
 *
 * @property device 该连接对应的远端设备。
 */
class GattConnection internal constructor(
    private val context: Context,
    val device: BluetoothDevice,
    private val mtu: Int,
    private val fwkAdapter: GattClient.FrameworkAdapter,
) {

    internal companion object {
        private const val TAG = "GattConnection"
        internal const val CONNECT_TIMEOUT_MS = 30_000L
    }

    private sealed interface CallbackResult {
        class OnCharacteristicRead(
            val characteristic: GattCharacteristic,
            val value: ByteArray,
            val status: Int
        ) : CallbackResult

        class OnCharacteristicWrite(
            val characteristic: GattCharacteristic,
            val status: Int
        ) : CallbackResult

        class OnDescriptorRead(
            val fwkDescriptor: FwkBluetoothGattDescriptor,
            val value: ByteArray,
            val status: Int
        ) : CallbackResult

        class OnDescriptorWrite(
            val fwkDescriptor: FwkBluetoothGattDescriptor,
            val status: Int
        ) : CallbackResult
    }

    private interface SubscribeListener {
        fun onCharacteristicNotification(value: ByteArray)
        fun finish()
    }

    /**
     * 该连接专属的协程作用域，使用 [SupervisorJob] 以保证单个通知处理协程的异常不会
     * 拖垮整条连接。其 [kotlinx.coroutines.Job] 被取消时（例如 [close] 或远端断开），
     * 会通过 `invokeOnCompletion` 关闭底层 GATT。
     */
    private val connectionScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 连接建立（MTU 协商 + 服务发现）完成的信号。 */
    private val connectResult = CompletableDeferred<Unit>(
        parent = connectionScope.coroutineContext.job
    )

    /** GATT 操作（read/write/descriptor）回调结果的广播通道。 */
    private val callbackResultsFlow =
        MutableSharedFlow<CallbackResult>(extraBufferCapacity = Int.MAX_VALUE)

    /** 特征值通知订阅表，key 为框架层特征值对象。 */
    private val subscribeMap = mutableMapOf<FwkBluetoothGattCharacteristic, SubscribeListener>()
    private val subscribeMutex = Mutex()

    /** 远端服务映射。 */
    private val attributeMap = AttributeMap()

    @Volatile
    private var connected: Boolean = false

    private val servicesFlowImpl = MutableStateFlow<List<GattService>>(listOf())

    /** 服务发现结果 Flow，服务变更时会自动重新发现并发射。 */
    val servicesFlow: StateFlow<List<GattService>> = servicesFlowImpl.asStateFlow()

    /** 最近一次发现的服务列表。 */
    val services: List<GattService> get() = servicesFlowImpl.value

    private val disconnectedFlow = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)

    /**
     * 连接断开时发射事件，携带与
     * [android.bluetooth.BluetoothGattCallback.onConnectionStateChange] 相同的 status。
     *
     * 当该 Flow 发射时，连接即将被关闭，后续 [withScope] 调用会抛出 [CancellationException]。
     */
    val onDisconnected: Flow<Int> = disconnectedFlow

    /**
     * 连接是否仍处于可用状态（已建立且未被关闭）。
     */
    val isActive: Boolean
        get() = connected && connectionScope.coroutineContext.job.isActive

    /**
     * 串行化 GATT 任务的互斥锁，连接级别共享，保证同一条连接上的读写操作不会并发。
     */
    private val taskMutex = Mutex()

    @Volatile
    private var closed: Boolean = false

    init {
        // 当连接作用域被取消（主动 close 或远端断开）时，关闭底层 GATT 资源。
        connectionScope.coroutineContext.job.invokeOnCompletion {
            connected = false
            fwkAdapter.closeGatt()
        }
    }

    private val fwkCallback = object : FwkBluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: FwkBluetoothGatt?,
            status: Int,
            newState: Int
        ) {
            if (newState == FwkBluetoothGatt.STATE_CONNECTED) {
                fwkAdapter.requestMtu(mtu)
            } else {
                disconnectedFlow.tryEmit(status)
                connectionScope.cancel("GATT disconnected (status=$status)")
            }
        }

        override fun onMtuChanged(gatt: FwkBluetoothGatt?, mtu: Int, status: Int) {
            if (status == FwkBluetoothGatt.GATT_SUCCESS) {
                fwkAdapter.discoverServices()
            } else {
                connectionScope.cancel("mtu request failed (status=$status)")
            }
        }

        override fun onServicesDiscovered(gatt: FwkBluetoothGatt?, status: Int) {
            attributeMap.updateWithFrameworkServices(fwkAdapter.getServices())
            servicesFlowImpl.tryEmit(attributeMap.getServices())
            if (connectResult.isActive) {
                if (status == FwkBluetoothGatt.GATT_SUCCESS) connectResult.complete(Unit)
                else connectResult.cancel("service discover failed (status=$status)")
            }
        }

        override fun onServiceChanged(gatt: FwkBluetoothGatt) {
            // TODO: under API 31, we have to subscribe to the service changed characteristic.
            fwkAdapter.discoverServices()
        }

        override fun onCharacteristicRead(
            fwkBluetoothGatt: FwkBluetoothGatt,
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            attributeMap.fromFwkCharacteristic(fwkCharacteristic)?.let {
                callbackResultsFlow.tryEmit(
                    CallbackResult.OnCharacteristicRead(it, value, status)
                )
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            fwkBluetoothGatt: FwkBluetoothGatt,
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            status: Int
        ) {
            onCharacteristicRead(
                fwkBluetoothGatt,
                fwkCharacteristic,
                fwkCharacteristic.value,
                status
            )
        }

        override fun onCharacteristicWrite(
            fwkBluetoothGatt: FwkBluetoothGatt,
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            status: Int
        ) {
            attributeMap.fromFwkCharacteristic(fwkCharacteristic)?.let {
                callbackResultsFlow.tryEmit(
                    CallbackResult.OnCharacteristicWrite(it, status)
                )
            }
        }

        override fun onDescriptorRead(
            fwkBluetoothGatt: FwkBluetoothGatt,
            fwkDescriptor: FwkBluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            callbackResultsFlow.tryEmit(
                CallbackResult.OnDescriptorRead(fwkDescriptor, value, status)
            )
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onDescriptorRead(
            fwkBluetoothGatt: FwkBluetoothGatt,
            fwkDescriptor: FwkBluetoothGattDescriptor,
            status: Int
        ) {
            onDescriptorRead(fwkBluetoothGatt, fwkDescriptor, status, fwkDescriptor.value)
        }

        override fun onDescriptorWrite(
            fwkBluetoothGatt: FwkBluetoothGatt,
            fwkDescriptor: FwkBluetoothGattDescriptor,
            status: Int
        ) {
            callbackResultsFlow.tryEmit(
                CallbackResult.OnDescriptorWrite(fwkDescriptor, status)
            )
        }

        override fun onCharacteristicChanged(
            fwkBluetoothGatt: FwkBluetoothGatt,
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            value: ByteArray
        ) {
            connectionScope.launch {
                subscribeMutex.withLock {
                    subscribeMap[fwkCharacteristic]?.onCharacteristicNotification(value)
                }
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            fwkBluetoothGatt: FwkBluetoothGatt,
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
        ) {
            onCharacteristicChanged(
                fwkBluetoothGatt,
                fwkCharacteristic,
                fwkCharacteristic.value
            )
        }
    }

    /**
     * 建立底层 GATT 连接（connectGatt → MTU 协商 → 服务发现）。
     *
     * 成功返回后即可通过 [withScope] 执行 GATT 操作。失败时抛出 [CancellationException]。
     */
    @SuppressLint("MissingPermission")
    suspend fun connect() {
        if (!fwkAdapter.connectGatt(context, device.fwkDevice, fwkCallback)) {
            throw CancellationException("failed to connect")
        }
        withTimeout(CONNECT_TIMEOUT_MS) {
            connectResult.await()
        }
        connected = true
    }

    /**
     * 在当前已建立的连接上执行 [block]。
     *
     * **不会**在 block 结束后关闭 GATT —— 这是与短链接 [GattClient.connect] 的关键区别。
     * 多次调用本方法会复用同一条底层 GATT 连接。
     *
     * 如果连接已被关闭或远端断开，会抛出 [CancellationException]。
     *
     * @param block 在 [GattClientScope] 上执行的操作
     * @return block 的返回值
     */
    suspend fun <R> withScope(block: suspend GattClientScope.() -> R): R = coroutineScope {
        val connJob = connectionScope.coroutineContext.job
        if (!connJob.isActive) {
            throw CancellationException("GATT connection is closed")
        }
        val clientScope = createClientScope()
        // 当连接被取消时（远端断开或主动 close），同步取消当前 block 所在的协程作用域，
        // 使正在 await 回调结果的挂起点（如 readCharacteristic）能够及时抛出。
        val handle = connJob.invokeOnCompletion { cause ->
            if (cause != null) this.cancel("GATT connection closed: ${cause.message}")
        }
        try {
            clientScope.block()
        } finally {
            handle.dispose()
        }
    }

    /**
     * 主动关闭并断开该 GATT 连接。
     *
     * 会依次调用 `BluetoothGatt.disconnect()` 与 `BluetoothGatt.close()`，并取消连接作用域。
     * 该方法可安全地多次调用。
     */
    fun close() {
        if (closed) return
        closed = true
        connected = false
        // init 中注册的 invokeOnCompletion 也会再次 closeGatt，幂等，无副作用。
        fwkAdapter.closeGatt()
        connectionScope.cancel("connection closed by user")
    }

    /**
     * 按 UUID 获取远端服务，无需进入 [withScope] 即可查询缓存的服务表。
     */
    fun getService(uuid: UUID): GattService? {
        return fwkAdapter.getService(uuid)?.let { attributeMap.fromFwkService(it) }
    }

    private fun createClientScope(): GattClientScope = object : GattClientScope {

        suspend fun <R> runTask(block: suspend () -> R): R {
            taskMutex.withLock {
                return block()
            }
        }

        override val onDisconnected: Flow<Int> = disconnectedFlow

        override val servicesFlow: StateFlow<List<GattService>> =
            this@GattConnection.servicesFlow

        override fun getService(uuid: UUID): GattService? {
            return this@GattConnection.getService(uuid)
        }

        override suspend fun readCharacteristic(
            characteristic: GattCharacteristic
        ): Result<ByteArray> {
            if (characteristic.properties and GattCharacteristic.PROPERTY_READ == 0) {
                return Result.failure(IllegalArgumentException("can't read the characteristic"))
            }
            return runTask {
                fwkAdapter.readCharacteristic(characteristic.fwkCharacteristic)
                val res = takeMatchingResult<CallbackResult.OnCharacteristicRead>(
                    callbackResultsFlow
                ) {
                    it.characteristic == characteristic
                }

                if (res.status == FwkBluetoothGatt.GATT_SUCCESS) Result.success(res.value)
                // TODO: throw precise reason if we can gather the info
                else Result.failure(CancellationException("fail"))
            }
        }

        override suspend fun writeCharacteristic(
            characteristic: GattCharacteristic,
            value: ByteArray
        ): Result<Unit> {
            val writeType =
                if (characteristic.properties and PROPERTY_WRITE_NO_RESPONSE != 0)
                    FwkBluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else if (characteristic.properties and PROPERTY_WRITE != 0)
                    FwkBluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else throw IllegalArgumentException("can't write to the characteristic")

            if (value.size > MAX_ATTR_LENGTH) {
                throw IllegalArgumentException("too long value to write")
            }

            return runTask {
                fwkAdapter.writeCharacteristic(
                    characteristic.fwkCharacteristic, value, writeType
                )
                val res = takeMatchingResult<CallbackResult.OnCharacteristicWrite>(
                    callbackResultsFlow
                ) {
                    it.characteristic == characteristic
                }
                if (res.status == FwkBluetoothGatt.GATT_SUCCESS) Result.success(Unit)
                // TODO: throw precise reason if we can gather the info
                else Result.failure(CancellationException("fail with error = ${res.status}"))
            }
        }

        override fun subscribeToCharacteristic(
            characteristic: GattCharacteristic
        ): Flow<ByteArray> {
            if (!characteristic.isSubscribable) {
                return emptyFlow()
            }
            val cccd = characteristic.fwkCharacteristic.getDescriptor(UUID_CCCD)
                ?: return emptyFlow()

            return callbackFlow {
                val listener = object : SubscribeListener {
                    override fun onCharacteristicNotification(value: ByteArray) {
                        trySend(value)
                    }

                    override fun finish() {
                        close()
                    }
                }
                if (!registerSubscribeListener(characteristic.fwkCharacteristic, listener)) {
                    throw IllegalStateException("already subscribed")
                }

                runTask {
                    fwkAdapter.setCharacteristicNotification(
                        characteristic.fwkCharacteristic, /*enable=*/true
                    )

                    val cccdValue =
                        // Prefer notification over indication
                        if ((characteristic.properties and PROPERTY_NOTIFY) != 0)
                            FwkBluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else FwkBluetoothGattDescriptor.ENABLE_INDICATION_VALUE

                    fwkAdapter.writeDescriptor(cccd, cccdValue)
                    val res = takeMatchingResult<CallbackResult.OnDescriptorWrite>(
                        callbackResultsFlow
                    ) {
                        it.fwkDescriptor == cccd
                    }
                    if (res.status != FwkBluetoothGatt.GATT_SUCCESS) {
                        cancel("failed to set notification")
                    }
                }

                awaitClose {
                    launch {
                        unregisterSubscribeListener(characteristic.fwkCharacteristic)
                    }
                    fwkAdapter.setCharacteristicNotification(
                        characteristic.fwkCharacteristic, /*enable=*/false
                    )
                    fwkAdapter.writeDescriptor(
                        cccd,
                        FwkBluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    )
                }
            }
        }

        private suspend fun registerSubscribeListener(
            fwkCharacteristic: FwkBluetoothGattCharacteristic,
            callback: SubscribeListener
        ): Boolean {
            subscribeMutex.withLock {
                if (subscribeMap.containsKey(fwkCharacteristic)) {
                    return false
                }
                subscribeMap[fwkCharacteristic] = callback
                return true
            }
        }

        private suspend fun unregisterSubscribeListener(
            fwkCharacteristic: FwkBluetoothGattCharacteristic
        ) {
            subscribeMutex.withLock {
                subscribeMap.remove(fwkCharacteristic)
            }
        }
    }

    private suspend inline fun <reified R : CallbackResult> takeMatchingResult(
        flow: SharedFlow<CallbackResult>,
        crossinline predicate: (R) -> Boolean
    ): R {
        return flow.filter { it is R && predicate(it) }.first() as R
    }
}
