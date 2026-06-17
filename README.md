# BluetoothBleX
众所周知，自从AndroidX jetpack蓝牙库发布已经好几年了，但是目前还是在1.0.0-alpha02的版本，而且估计也不会更新了。但是不得不说，Android官方封装的蓝牙库还是好用的，但是这个蓝牙库本身并没有单独的lib，所以我把1.0.0-alpha02的包解压之后复制出来，增加了一些功能，并发布出来，方便自用和感兴趣的人一起修改完善。同时，我把包名也设置的和Google的一样，这样万一以后Google抽风更新了，直接换一下lib就行了。不存在包名大改的问题。

### 目前实现的功能
- 支持配置MTU
- 增加连接断开的时候的回调

### 使用方法
参考比如[BaseBleManager](https://github.com/GateOfTruth/BluetoothBleX/blob/main/app/src/main/java/com/gateoftruth/blesample/BaseBleManager.kt)
和对应的viewmodel。总的来说，我基于现有的lib进一步做了一下封装，用起来更加的简单顺手了。有问题可以提issue

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
implementation("com.github.GateOfTruth:BluetoothBleX:1.0.0")
```

