package com.gateoftruth.blesample

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.bluetooth.BluetoothDevice
import androidx.bluetooth.BluetoothLe
import androidx.bluetooth.BluetoothLeManager
import androidx.bluetooth.ConnectionMode
import androidx.bluetooth.ScanFilter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * describe：
 * @author：Alen
 * @date：2026/7/22
 */
object demo {
    const val serviceUUID = "55e405d2-af9f-a98f-e54a-7dfe43535355"

    const val notifyCharacteristic = "b39b7234-beec-d4a8-f443-418843535349"

    const val writeCharacteristic = "16962447-c623-61ba-d94b-4d1e43535349"

    const val deviceName = "AI_THINK"
}

/** 将字节数组格式化为可读的十六进制字符串，如 "01 0A FF"。 */
private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

/**
 * 基于 blex 库的长连接 demo。
 *
 * 流程：扫描 [demo.deviceName] 指定设备 → 以**长连接**方式建立 GATT → 写 0x01 → 读回 →
 * 复用同一条连接订阅 notify。
 *
 * 长连接模式下，首次 [BluetoothLeManager.connectGatt] 建链并缓存，[doWriteAndRead] 结束后
 * GATT **不会**被关闭；[doSubscribeNotify] 直接复用该连接，体现"长连接复用"。
 * 最终调用 [stop] 才会执行 `disconnect` + `close`。
 *
 * 注意：运行时权限（BLUETOOTH_SCAN / BLUETOOTH_CONNECT）需在调用前由 Activity 授予。
 */
class BleLongConnectionDemo(private val context: Context) {

    private val manager = BluetoothLeManager(context).apply {
        // 长连接：复用 GATT，直到主动 close
        connectionMode = ConnectionMode.LONG_LIVED
        // 连接意外断开时自动重连
        autoReconnect = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 暂时写入的字节，按需求固定为 0x01。 */
    private val payload: ByteArray = byteArrayOf(0x01)

    /**
     * 启动：扫描设备 → 长连接写读 → 复用连接订阅 notify。
     */
    fun start() {
        scope.launch {
            val device = scanDevice()
            if (device == null) {
                Log.e(TAG, "扫描超时，未找到设备: ${demo.deviceName}")
                return@launch
            }
            Log.i(TAG, "扫描到设备: ${device.name}")

            // ① 长连接：写 0x01 并读回（block 结束后 GATT 不关闭，连接被缓存复用）
            doWriteAndRead(device)

            // ② 复用同一条长连接：订阅 notify（持续运行直到 stop 或断开）
            doSubscribeNotify(device)
        }
    }

    /**
     * 主动关闭所有长连接并停止订阅。
     *
     * 建议在 Activity 的 onDestroy 中调用，避免连接泄漏。
     */
    fun stop() {
        manager.closeAllConnections()
    }

    /** 扫描 [demo.deviceName] 指定设备，15 秒内未找到返回 null。 */
    @SuppressLint("MissingPermission")
    private suspend fun scanDevice(): BluetoothDevice? {
        val bluetoothLe = BluetoothLe(context)
        val flow = bluetoothLe.scan(listOf(ScanFilter(deviceName = demo.deviceName)))
        return withTimeoutOrNull(15.seconds) {
            flow.first().device
        }
    }

    /** 复用长连接执行写 0x01 与读操作。 */
    @SuppressLint("MissingPermission")
    private suspend fun doWriteAndRead(device: BluetoothDevice) {
        manager.connectGatt(device) {
            val service = getService(UUID.fromString(demo.serviceUUID))
            if (service == null) {
                Log.e(TAG, "未找到 service: ${demo.serviceUUID}")
                return@connectGatt
            }
            val writeChar = service.getCharacteristic(UUID.fromString(demo.writeCharacteristic))
            if (writeChar == null) {
                Log.e(TAG, "未找到 write characteristic: ${demo.writeCharacteristic}")
                return@connectGatt
            }

            // 写 0x01
            val writeResult = writeCharacteristic(writeChar, payload)
            Log.i(TAG, "写入 0x01 结果: success=${writeResult.isSuccess}")

            // 读回（读到同一特征值的内容，便于验证）
            val readResult = readCharacteristic(writeChar)
            val readBytes = readResult.getOrNull()
            Log.i(TAG, "读取结果: ${readBytes?.toHexString() ?: readResult}")
        }
        // 此处 GATT 仍保持连接（长连接模式），下次 connectGatt 会复用
        Log.i(TAG, "写读完成，长连接保持，等待复用订阅 notify")
    }

    /** 复用长连接订阅 notify，持续接收直到连接断开。 */
    @SuppressLint("MissingPermission")
    private suspend fun doSubscribeNotify(device: BluetoothDevice) {
        manager.connectGatt(device) {
            val service = getService(UUID.fromString(demo.serviceUUID))
            if (service == null) {
                Log.e(TAG, "未找到 service: ${demo.serviceUUID}")
                return@connectGatt
            }
            val notifyChar = service.getCharacteristic(UUID.fromString(demo.notifyCharacteristic))
            if (notifyChar == null) {
                Log.e(TAG, "未找到 notify characteristic: ${demo.notifyCharacteristic}")
                return@connectGatt
            }

            // 订阅 notify，持续收集远端推送的数据
            subscribeToCharacteristic(notifyChar).collect { bytes ->
                Log.i(TAG, "收到 notify: ${bytes.toHexString()}")
            }
        }
    }

    companion object {
        private const val TAG = "BleLongConnectionDemo"
    }
}
