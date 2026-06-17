package com.gateoftruth.blesample

import android.app.Application
import android.util.Log
import androidx.bluetooth.BluetoothDevice
import androidx.bluetooth.ScanFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * describe：
 * @author：Alen
 * @date：2026/6/17
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {



    private suspend fun getDevice(): BluetoothDevice? {
        val scanFilter = ScanFilter(
            deviceName = "XXX"
        )
        val flow = BaseBleManager.scanDevice(listOf(scanFilter), getApplication())
        val device: BluetoothDevice? = withTimeoutOrNull(15.seconds) {
            flow.first().device
        }
        return device

    }

    private fun testSubscribe(){
        viewModelScope.launch {
            val device = getDevice()
            if (device == null) {
                Log.e("MainViewModel","cant find device")
            }else{
                BaseBleManager.subscribe(getApplication(),device,"XXX","xxx", disconnectedFun = { code->

                }).collect { bytes ->

                }

            }
        }
    }
}