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

/**
 * GATT 连接模式配置。
 *
 * 用于 [BluetoothLeManager] 控制底层 GATT 连接的生命周期：
 * - [SHORT_LIVED]：短链接，每次使用完毕后自动关闭 GATT 连接（保持原有行为）。
 * - [LONG_LIVED]：长连接，复用 GATT 连接，直到主动调用关闭方法才会断开。
 */
enum class ConnectionMode {
    /**
     * 短链接模式。
     *
     * 每次 [BluetoothLeManager.connectGatt] 执行完 block 后，立即关闭并断开 GATT 连接。
     * 与直接调用 [BluetoothLe.connectGatt] 的行为完全一致。
     */
    SHORT_LIVED,

    /**
     * 长连接模式。
     *
     * GATT 连接建立后会被缓存复用，后续对同一设备的 [BluetoothLeManager.connectGatt] 调用
     * 不会再重复建立连接，直到调用 [BluetoothLeManager.closeConnection] 或
     * [BluetoothLeManager.closeAllConnections] 才会执行 `disconnect` 与 `close`。
     */
    LONG_LIVED
}
