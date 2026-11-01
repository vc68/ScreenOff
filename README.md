# ScreenOff · 息屏运行

> **本项目基于 [@WuDi-ZhanShen/ScreenOff](https://github.com/WuDi-ZhanShen/ScreenOff) 修改而来。**
> 原项目未声明开源许可证，版权归原作者所有；本仓库是个人维护的修改版本，仅用于学习与自用，
> 如果你要二次分发，请先联系原作者获得许可。

> 用 ADB / Shizuku / root 权限实现的「息屏运行」工具。
> 关掉屏幕背光，但系统和所有 App 继续正常工作 —— 和按电源键息屏完全不同。

An Android app that turns the display off **without** suspending the system, so every app keeps
running in the background. Works on Android 5.1+ with only ADB (or root / Shizuku) permission.

当前版本：**v22**（更新内容见 [CHANGELOG.md](CHANGELOG.md)）

## 它解决什么问题

国产旗舰机有「息屏运行 / 应用速冻继续跑」这类功能，但绝大多数机型没有。本项目通过反射
`android.view.SurfaceControl`（和 scrcpy 的做法相同）直接给物理屏幕下发 `POWER_MODE_OFF`，
只关背光、不停系统，因此：

- 挂机、跑脚本、听歌、下载、录屏、远控 —— 息屏后全部照常进行
- 省电、防误触、防窥屏
- 支持 Android 5.1 ~ 16（API 21+），Android 14+ 自动切换到 `DisplayControl` 取屏幕 token

## 功能

| 功能 | 说明 |
| --- | --- |
| 悬浮球 | 单击/长按息屏，可拖动、贴边、调大小与透明度 |
| 通知栏磁贴 | 一键在「息屏运行 / 退出息屏」之间切换 |
| 桌面快捷方式 | 息屏、亮屏各一个 |
| 音量键控制 | 自定义键值，按音量键息屏/亮屏 |
| 立起亮屏 | 息屏后转动手机即可亮屏，灵敏度可调 |
| 双击亮屏 | 部分机型有效（取决于厂商是否屏蔽触控） |
| 局域网控制 | 手机开热点/同一 WiFi 下，用浏览器控制，带二维码 |

## 激活

息屏需要 `shell`(uid 2000) 或 `root`(uid 0) 权限，三种方式任选：

1. **ADB**：手机开启 USB 调试连电脑，执行 APP 内「激活」弹窗里给出的命令：
   ```bash
   adb shell sh /sdcard/Android/data/com.tile.screenoff/files/starter.sh
   ```
2. **Shizuku**：安装 [Shizuku](https://shizuku.rikka.app/) 并在 APP 内点「Shizuku 激活」。
3. **root**：APP 内点「root 激活」。

激活成功后按钮会变成「服务已启动」，长按它可以关闭服务。

> 升级或重启手机后需要重新激活一次（脚本会先结束旧进程，重复执行是安全的）。

## 局域网控制

在设置里打开「局域网控制亮灭屏」，标题栏会显示访问地址（长按可复制），也可以直接扫页面上的二维码。

```
GET  /                 控制页面
GET  /state            当前状态 JSON，如 {"state":2,"label":"息屏运行"}
GET  /screen/off       息屏运行
GET  /screen/on        退出息屏
GET  /1  /2            旧版接口（兼容保留）
```

`state` 取值：`0` 真实息屏、`1` 亮屏、`2` 息屏运行、`-1` 服务未连接。

> 服务监听 `20000` 端口，被占用时自动顺延到 `20001~20009`。
> 该接口没有鉴权，只在可信网络里开启，不要在公共 WiFi 下使用。

## 编译

```bash
# 需要 JDK 17 + Android SDK（platform 35 / build-tools 35）
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk
```

发布包签名信息通过 `gradle.properties` 或环境变量提供，**不再硬编码在构建脚本里**：

```properties
SCREENOFF_KEYSTORE=/path/to/key.keystore
SCREENOFF_STORE_PASSWORD=******
SCREENOFF_KEY_ALIAS=key0
SCREENOFF_KEY_PASSWORD=******
```

未配置时 `assembleRelease` 会退回 debug 签名，保证任何人都能直接构建。

## 原理

1. `starter.sh` 把 `classes.dex` 拷到 `/data/local/tmp`，用 `app_process` 以 shell 身份启动
   `com.tile.screenoff.ScreenController`；
2. 控制器反射 `SurfaceControl` 拿到物理屏幕 token，并用粘性广播把 Binder 交给 APP；
3. APP 通过 Binder 调用 `setPowerMode()`，控制器执行 `setDisplayPowerMode(token, 0|2)`；
4. 为了「息屏但不停机」，APP 侧用 1px 透明窗口持有 `KEEP_SCREEN_ON`，让系统不进入休眠。

## 已知限制

- 大部分机型息屏后会屏蔽触控，因此「双击亮屏」只在少数设备有效；
- 部分机型息屏后会屏蔽按键，「音量键亮屏」可能无效；
- 部分 LCD 机型息屏后仍有背光（硬件/驱动决定），可先把亮度调到最低；
- 折叠屏 / 多屏设备只处理主屏。

## License

未指定。如需二次分发请先联系原作者 [@WuDi-ZhanShen](https://github.com/WuDi-ZhanShen)。
