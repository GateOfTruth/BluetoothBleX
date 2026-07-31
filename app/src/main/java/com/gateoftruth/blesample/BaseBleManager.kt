package com.gateoftruth.blesample

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.bluetooth.BluetoothDevice
import androidx.bluetooth.BluetoothLe
import androidx.bluetooth.BluetoothLeManager
import androidx.bluetooth.ConnectionMode
import androidx.bluetooth.ConnectionStateChange
import androidx.bluetooth.GattCharacteristic
import androidx.bluetooth.GattClientScope
import androidx.bluetooth.ScanFilter
import androidx.bluetooth.ScanResult
import com.gateoftruth.blesample.BaseBleManager.closeAllConnection
import com.gateoftruth.blesample.BaseBleManager.init
import com.gateoftruth.blesample.BaseBleManager.read
import com.gateoftruth.blesample.BaseBleManager.subscribe
import com.gateoftruth.blesample.BaseBleManager.takeWriteChannel
import com.gateoftruth.blesample.BaseBleManager.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 这是我生产环境中的一个manager类，仅供参考
 * ⚠使用注意事项：
 * @see init 1.init方法在application里初始化
 * @see closeAllConnection 2.如果确定要断开连接，记得调用这个方法
 * 3.大部分方法都应该在viewmodel里调用
 * @see takeWriteChannel 4.是考虑下位机无法短时间内处理大量写入信息，
 * 所以做了一个queue用来写入，应该在viewmodel里的init方法里调用，
 * 传入viewModelScope
 * @see subscribe 5.没有try cache，是因为flow是冷流，
 * 要在拿到flow，collect之后，用flow的cache操作符来捕获异常，同时长连接的时候，
 * 如果autoReconnect是false，记得cancel对应的CoroutineScope，
 * 则subscribe会报错：IllegalStateException("already subscribed")
 */
object BaseBleManager {

    var connectedBleService: BluetoothDevice? = null

    const val TAG ="BaseBleManager"

    lateinit var manager: BluetoothLeManager

    val writeQueue = ConcurrentLinkedQueue<BleInfoClass>()


    /**
     * 在application里初始化
     */
    fun init(context: Context) {
        manager = BluetoothLeManager(context.applicationContext).apply {
            connectionMode = ConnectionMode.LONG_LIVED
        }
    }


    /**
     * 根据设备 Android 版本返回当前需要请求的蓝牙权限列表。
     *
     * Android 12 (API 31) 引入了 BLUETOOTH_SCAN / BLUETOOTH_CONNECT 精细权限，
     * 替代旧版 BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION。
     * 此方法按 SDK 级别自动选择正确的权限组合。
     *
     * @return 当前设备所需的蓝牙权限数组
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            //android 14 申请权限
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT,
                //这两个权限是给蓝牙前台服务用的，如果未来蓝牙操作不放到viewmodel里，放到service里，则需要
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            //android 13 申请权限
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT,
                //这个权限是给蓝牙前台服务用的，如果未来蓝牙操作不放到viewmodel里，放到service里，则需要
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：使用新的精细权限
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // Android 7.0–11：使用旧版权限，蓝牙扫描还需位置权限
            arrayOf(
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }




    /**
     * 启动 BLE 设备扫描（不过滤），返回扫描结果流。
     *
     * 等价于 [scanDevice(filters, context)] 传入空过滤器列表。
     * 调用方通过 [Flow.collect] 消费 [ScanResult]，取消收集时自动停止扫描。
     *
     * @param context Android 上下文
     * @return 扫描结果 [Flow]，每个结果为一个 [ScanResult]
     */
    fun scanDevice(context: Context): Flow<ScanResult> {
        return scanDevice(emptyList(), context)
    }


    /**
     * 启动 BLE 设备扫描，支持 [ScanFilter] 过滤，返回扫描结果流。
     *
     * @param filters 蓝牙扫描过滤器列表，可过滤广播名/服务 UUID 等，传空表示不过滤
     * @param context Android 上下文
     * @return 扫描结果 [Flow]，调用方取消协程收集即终止扫描
     */
    @SuppressLint("MissingPermission")
    fun scanDevice(filters: List<ScanFilter>, context: Context): Flow<ScanResult> {
        val bluetoothLe = BluetoothLe(context)
        return bluetoothLe.scan(filters)
    }

    /**
     * 在 GATT 连接作用域内安全获取指定服务和特征。
     *
     * 此方法必须在 [GattClientScope]（即 [BluetoothLe.connectGatt] 的 lambda 内部）调用。
     * 先后查找指定 UUID 的 [BluetoothGattService] 和 [GattCharacteristic]，
     * 任一环节失败均返回 `null`，避免空指针。
     *
     * 配合 [write]、[read]、[subscribe] 使用，统一在连接 lambda 内操作。
     *
     * @receiver   [GattClientScope] GATT 连接作用域
     * @param serviceUUID       目标服务 UUID 字符串
     * @param characteristicUUID 目标特征 UUID 字符串
     * @return 找到的 [GattCharacteristic]，未找到返回 `null`
     */
    fun GattClientScope.getSafeCharacteristic(
        serviceUUID: String,
        characteristicUUID: String,
    ): GattCharacteristic? {
        val service = getService(UUID.fromString(serviceUUID))
        if (service == null) {
            Log.e(TAG,"$serviceUUID get null")
            return null
        } else {
            val characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID))
            return characteristic
        }
    }


    /**
     * 向指定设备的 GATT 特征写入数据。写入的数据放到一个channel里
     * 按fifo的顺序读取。需要主动调用takeWriteChannel方法
     *
     * 在 [GattClientScope] 内部自动建立 GATT 连接，查找目标特征，
     *
     *
     * 典型调用场景：发送协议帧到 BLE 外设。
     *
     * @param device              目标 BLE 设备
     * @param serviceUUID         目标服务 UUID 字符串
     * @param characteristicUUID  目标特征 UUID 字符串
     * @param value               要写入的字节数组（如 [BleProtocol.buildFrame] 的输出）
     *
     */
    fun write(
        device: BluetoothDevice,
        serviceUUID: String,
        characteristicUUID: String,
        value: ByteArray
    ) {
        val infoClass = BleInfoClass(device, serviceUUID, characteristicUUID, value)
        writeQueue.offer(infoClass)
    }

    /**
     * 从writeQueue里读取要写的数据
     */
    @SuppressLint("MissingPermission")
    fun takeWriteChannel(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val info = writeQueue.poll()
                if (info == null) {
                    delay(100.milliseconds)
                    continue
                }
                try {
                    manager.connectGatt(info.device) {
                        val characteristic =
                            getSafeCharacteristic(info.serviceUUID, info.characteristicUUID)
                        if (characteristic != null) {
                            writeCharacteristic(characteristic, info.value)
                        } else {
                            Log.e(TAG,"write characteristic null,serviceUUID:${info.serviceUUID},characteristicUUID:${info.characteristicUUID}")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(100.milliseconds)
            }
        }
    }

    /**
     * 从指定设备的 GATT 特征读取数据。
     *
     * 在 [GattClientScope] 内部自动建立 GATT 连接，查找目标特征，
     * 读取后返回 [Result]<[ByteArray]>。特征未找到时返回 `Result.failure`。
     *
     * @param device              目标 BLE 设备
     * @param serviceUUID         目标服务 UUID 字符串
     * @param characteristicUUID  目标特征 UUID 字符串
     * @return [Result.success] 携带读取到的字节数组；[Result.failure] 特征未找到或读取失败
     */
    @SuppressLint("MissingPermission")
    suspend fun read(
        device: BluetoothDevice,
        serviceUUID: String,
        characteristicUUID: String
    ): Result<ByteArray> {
        return try {
            manager.connectGatt(device) {
                val characteristic = getSafeCharacteristic(serviceUUID, characteristicUUID)
                if (characteristic == null) {
                    Result.failure(Throwable("characteristic is null"))
                } else {
                    readCharacteristic(characteristic)
                }
            }
        } catch (e: CancellationException) {
            Result.failure(e)
        }
    }

    /**
     * 订阅指定设备的 GATT 特征通知/指示，返回持续接收数据的 [Flow]。
     *
     * 调用方通过 [Flow.collect] 持续接收 BLE 外设推送的数据。
     * 取消收集时自动取消订阅并断开 GATT。
     *
     * 典型调用场景：接收针灸仪的应答帧、状态上报或心跳包。
     *
     * @param device              目标 BLE 设备
     * @param serviceUUID         目标服务 UUID 字符串
     * @param characteristicUUID  目标特征 UUID 字符串（需支持 NOTIFY/INDICATE）
     * @return 持续产生 [ByteArray] 的 [Flow]，特征未找到时为空流
     */
    @SuppressLint("MissingPermission")
    suspend fun subscribe(
        device: BluetoothDevice,
        serviceUUID: String,
        characteristicUUID: String,
        deviceDisConnected:(ConnectionStateChange)-> Unit
    ): Flow<ByteArray> {
        return  manager.connectGatt(device) {
            val characteristic = getSafeCharacteristic(serviceUUID, characteristicUUID)
            if (characteristic == null) {
                Log.e(TAG,"subscribe characteristic null，serviceuuid:${serviceUUID},characteristicUUID:$characteristicUUID")
                emptyFlow()
            } else {
                subscribeToCharacteristic(characteristic,deviceDisConnected)
            }
        }
    }

    fun closeAllConnection(){
        connectedBleService = null
        writeQueue.clear()
        manager.closeAllConnections()
    }
}

data class BleInfoClass( val device: BluetoothDevice,
                         val serviceUUID: String,
                         val characteristicUUID: String,
                         val value: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BleInfoClass

        if (device != other.device) return false
        if (serviceUUID != other.serviceUUID) return false
        if (characteristicUUID != other.characteristicUUID) return false
        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = device.hashCode()
        result = 31 * result + serviceUUID.hashCode()
        result = 31 * result + characteristicUUID.hashCode()
        result = 31 * result + value.contentHashCode()
        return result
    }
}







