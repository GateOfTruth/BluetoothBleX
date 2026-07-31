# BluetoothBleX
众所周知，自从AndroidX jetpack蓝牙库发布已经好几年了，但是目前还是在1.0.0-alpha02的版本，应该是不会更新了。不得不说，Android官方封装的蓝牙库还是好用的，尤其是采用了flow和协程等一些新的设计理念，个人认为比其他的一些蓝牙lib更好用一些，也更适配jetpack组件等。但是这个蓝牙库本身并没有单独的lib，所以我把1.0.0-alpha02的包解压之后复制出来，增加了一些功能，并发布出来，方便自用和感兴趣的人一起修改完善。

### 目前实现的功能
- 支持配置MTU
- 增加订阅的时候，蓝牙断开的时候的回调
- 增加长连接选项

### 使用方法
参考[BaseBleManager](https://github.com/GateOfTruth/BluetoothBleX/blob/main/app/src/main/java/com/gateoftruth/blesample/BaseBleManager.kt)
。这是我实际生产环境比较成熟的使用方法，目前用下来没发现啥问题。不过仅供参考吧，也给自己留个档

### 接入方法 以最新版的Android studio为例，你需要在settings.gradle.kts中进行配置，主要是引入jitpack
```
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
            setUrl("https://jitpack.io")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            setUrl("https://jitpack.io")
        }
    }
}
```
然后在app的build.gradle
```
implementation("com.github.GateOfTruth:BluetoothBleX:1.0.4")
```

