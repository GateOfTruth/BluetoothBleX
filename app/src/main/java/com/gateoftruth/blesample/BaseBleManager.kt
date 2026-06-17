package com.gateoftruth.blesample

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.bluetooth.BluetoothDevice
import androidx.bluetooth.BluetoothLe
import androidx.bluetooth.BluetoothLe.Companion.DEFAULT_MTU
import androidx.bluetooth.GattCharacteristic
import androidx.bluetooth.GattClientScope
import androidx.bluetooth.ScanFilter
import androidx.bluetooth.ScanResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * describe：
 * @author：Alen
 * @date：2026/6/17
 */
object BaseBleManager {
    const val TAG= "BaseBleManager"

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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
     * 检查所有必要的蓝牙权限是否已全部被授予。
     *
     * 内部调用 [getRequiredPermissions] 获取当前设备所需权限列表，
     * 再逐个调用 [ContextCompat.checkSelfPermission] 校验。
     *
     * @param context Android 上下文
     * @return `true` 表示所有必要权限均已授予，`false` 表示至少有一项未授权
     */
    fun areAllPermissionsGranted(context: Context): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 打开应用设置页面，引导用户手动授予权限。
     *
     * 当用户勾选"不再询问"后权限被永久拒绝时调用，
     * 跳转到系统设置中当前应用详情页。
     *
     * @param context Android 上下文
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 通过系统对话框请求打开蓝牙（推荐方式）。
     *
     * Android 13+ 禁止通过 [BluetoothAdapter.enable] 静默开启蓝牙，
     * 必须使用 [BluetoothAdapter.ACTION_REQUEST_ENABLE] 弹出系统授权对话框。
     * 此方法内部使用 [ComponentActivity.registerForActivityResult]
     * 注册 onActivityResult 回调，支持直接拿到用户操作结果。
     *
     * @param failedFun 用户拒绝授权时调用的回调
     */
    @SuppressLint("MissingPermission")
    @Composable
    fun TryOpenBluetooth(failedFun: () -> Unit) {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        val launcher =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode!= Activity.RESULT_OK){
                    failedFun()
                }
            }
        LaunchedEffect(Unit) {
            launcher.launch(enableBtIntent)
        }
    }

    /**
     * 检查系统蓝牙是否已开启。
     *
     * 通过 [BluetoothManager] 获取默认 [BluetoothAdapter] 并检查启用状态。
     * 注意：此方法仅判断蓝牙开关状态，不检查运行时权限。
     *
     * @param context Android [Activity] 上下文
     * @return `true` 蓝牙已开启，`false` 蓝牙关闭或不可用
     */
    fun isBluetoothOpen(context: Context): Boolean {
        // 1. 获取BluetoothAdapter实例
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter
        return bluetoothAdapter.isEnabled
    }

    fun isGPSEnabled(context: Context): Boolean {
        // 需要 ACCESS_FINE_LOCATION 权限（仅调用 isProviderEnabled 不需要，但建议先检查）
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 没有权限，返回 false 或提示请求权限
            return false
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // 检查 GPS 提供者是否已启用
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /**
     * 调用方法解决蓝牙相关权限请求
     */
    @Composable
    fun BlePermissionCheck(context:ComponentActivity) {
        val allPermissionsGranted = areAllPermissionsGranted(context)
        //后续如果要弹出dialog，可以用这个变量
        var isShowSettingDialog by remember { mutableStateOf(false) }
        // 1. 创建多权限请求的启动器
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissionsMap: Map<String, Boolean> ->
            val noGranted = permissionsMap.values.contains(false)
            if (noGranted) {
                isShowSettingDialog = true
            }
        }
        LaunchedEffect(Unit) {
            if (!allPermissionsGranted) {
                launcher.launch(getRequiredPermissions())
            }
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
            Log.e(TAG,("$serviceUUID get null"))
            return null
        } else {
            val characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID))
            return characteristic
        }
    }


    /**
     * 向指定设备的 GATT 特征写入数据。
     *
     * 在 [GattClientScope] 内部自动建立 GATT 连接，查找目标特征，
     * 写入后返回 [Result]。特征未找到时返回 `Result.failure`。
     *
     * 典型调用场景：发送协议帧到 BLE 外设。
     *
     * @param context             Android 上下文
     * @param device              目标 BLE 设备
     * @param serviceUUID         目标服务 UUID 字符串
     * @param characteristicUUID  目标特征 UUID 字符串
     * @param value               要写入的字节数组（如 [BleProtocol.buildFrame] 的输出）
     * @return [Result.success] 写入成功；[Result.failure] 特征未找到或写入失败
     */
    @SuppressLint("MissingPermission")
    suspend fun write(
        context: Context,
        device: BluetoothDevice,
        serviceUUID: String,
        characteristicUUID: String,
        value: ByteArray,
        mtu:Int = DEFAULT_MTU
    ): Result<String> {
        val bluetoothLe = BluetoothLe(context)
        return try {
            bluetoothLe.connectGatt(device,mtu) {
                val characteristic = getSafeCharacteristic(serviceUUID, characteristicUUID)
                if (characteristic != null) {
                    writeCharacteristic(characteristic, value)
                    Result.success("already write")
                } else {
                    Result.failure(Throwable("characteristic is null"))
                }
            }
        } catch (e: CancellationException) {
            Result.failure(e)
        }
    }

    /**
     * 从指定设备的 GATT 特征读取数据。
     *
     * 在 [GattClientScope] 内部自动建立 GATT 连接，查找目标特征，
     * 读取后返回 [Result]<[ByteArray]>。特征未找到时返回 `Result.failure`。
     *
     * @param context             Android 上下文
     * @param device              目标 BLE 设备
     * @param serviceUUID         目标服务 UUID 字符串
     * @param characteristicUUID  目标特征 UUID 字符串
     * @return [Result.success] 携带读取到的字节数组；[Result.failure] 特征未找到或读取失败
     */
    @SuppressLint("MissingPermission")
    suspend fun read(
        context: Context,
        device: BluetoothDevice,
        serviceUUID: String,
        characteristicUUID: String,
        mtu:Int = DEFAULT_MTU
    ): Result<ByteArray> {
        val bluetoothLe = BluetoothLe(context)
        return try {
            bluetoothLe.connectGatt(device,mtu) {
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
     * @param context             Android 上下文
     * @param device              目标 BLE 设备
     * @param serviceUUID         目标服务 UUID 字符串
     * @param characteristicUUID  目标特征 UUID 字符串（需支持 NOTIFY/INDICATE）
     * @return 持续产生 [ByteArray] 的 [Flow]，特征未找到时为空流
     */
    @SuppressLint("MissingPermission")
    suspend fun subscribe(
        context: Context,
        device: BluetoothDevice,
        serviceUUID: String,
        characteristicUUID: String,
        disconnectedFun:(code:Int)-> Unit,
        mtu:Int = DEFAULT_MTU
    ): Flow<ByteArray> {
        val bluetoothLe = BluetoothLe(context)
        return try {
            bluetoothLe.connectGatt(device,mtu) {
                onDisconnected.collect { code->
                    Log.e(TAG,"device:${device.id}disconnected,code:$code")
                    disconnectedFun(code)
                }
                val characteristic = getSafeCharacteristic(serviceUUID, characteristicUUID)
                if (characteristic == null) {
                    emptyFlow()
                } else {
                    subscribeToCharacteristic(characteristic)
                }
            }
        } catch (e: CancellationException) {
            e.printStackTrace()
            emptyFlow()
        }

    }
}


