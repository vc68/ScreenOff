# 更新记录

## v22 — 2026-11

本次主要做了三件事：修掉一批「新版系统上直接失效」的 bug、把局域网控制重写成正经的 HTTP 服务、
补齐了若干易用性细节。**没有新增任何权限。**

### 修复

- **Android 13+ 电源键 / 快捷方式 / 退出指令全部失效**
  `GlobalService` 在 API 33+ 分支里只注册了 `intent.screenoff.sendBinder` 一个 action，
  其余（`ACTION_SCREEN_OFF`、`ACTION_SCREEN_ON`、`ACTION_USER_PRESENT`、`action.ScrOff`、
  `intent.screenoff.exit`）全部漏注册，导致：息屏后按电源键状态不同步、桌面快捷方式点了没反应、
  关掉开关服务也不会停。现在改为一次性注册完整 filter。
- **悬浮球首次点击会莫名息屏**
  长按判定用的是「距离上一次抬手的时间」，`lastUp` 初值为 0，第一次按下 400ms 后必然误判成长按。
  改为按下/移动/抬起三态判定，并在移动时取消长按计时。
- **关掉悬浮球后息屏运行会被系统睡死**
  旧实现依赖悬浮球窗口的 `KEEP_SCREEN_ON`，关掉悬浮球后没有任何窗口持有该标记，系统照常休眠。
  现在单独维护一个 1px 透明窗口来持有 `KEEP_SCREEN_ON`。
- **Binder 断开后必崩**
  `iScreenOff` 为 null 时多处直接调用（`screenoff`、`onKeyEvent`、HTTP 输出、状态查询），
  控制器进程被杀后点任何东西都会 NPE。现在统一做空值与 `RemoteException` 保护，并提示重新激活。
- **局域网控制短路径崩溃**：`target.substring(0, 3)` 对 `/1` 这类 2 字符路径抛
  `StringIndexOutOfBoundsException`，异常被吞掉后浏览器一直转圈。改用精确路径匹配。
- **`BinderContainer` 空指针**：广播里取不到 extra 时直接 NPE，现在做空判断。
- **输入框崩溃**：键值/大小/透明度输入框解析失败会抛 `NumberFormatException`，现已全部容错并做范围钳制。
- **`stopServer()` 空指针**：服务器从未启动成功时 `server.stop()` 会 NPE。
- **`DisplayControl` 初始化失败必崩**：`CLASS` 为 null 时 `CLASS.getMethod` 抛 NPE，
  但只 catch 了 `NoSuchMethodException`。
- **快捷方式 Activity 主题**：`Theme.NoDisplay` 在「需要跳转系统设置」的分支里可能触发
  "did not call finish()" 崩溃，改用透明主题。
- **投屏/多屏**：`getBuiltInDisplay()` 里 `for` 循环第一个元素就 `return`，代码可读性差且空数组会返回 null，
  现已重写并加入回退链。

### 性能

- **局域网控制不再线程爆炸**：旧实现每个响应 `new Thread`，`restart()` 时还会在
  accept 线程里递归调用 `start()`，`ServerSocket` 从不关闭 —— 请求几次之后就会漏 fd、
  多线程抢同一个 socket。现在改为单 accept 线程 + 连接超时 + 正确关闭。
- **去掉每次 read 的 1MB 分配**：旧实现每轮循环 `new byte[1024*1024]` 再 `Arrays.copyOfRange`，
  纯内存垃圾。改为固定 1KB 读缓冲 + 按需扩容的 `ByteArrayOutputStream`。
- **屏幕 token 缓存**：`getBuiltInDisplay()` 每次开关屏都要反射 + 加载 `services.jar`，
  现在缓存 token 并用 `pingBinder()` 校验。
- **HTML/图标不再每次请求都读 assets**：模板与 404 页缓存为字节数组。
- **请求解析去掉 O(n²) 拼接**：`HttpRequestParser` 改为一次性无状态解析。
- **悬浮球拖动**：`new Handler()` 从每次 ACTION_DOWN 变成单例 Handler；拖动时不再重复读 SharedPreferences。
- **dex 释放**：大小一致时跳过重复写入（原来每次打开激活弹窗都重写整个 dex）。
- 删除从未被调用的 `getevent` 音量键监听线程（死代码，且会常驻读输入设备）。

### 易用性

- 磁贴改为**真正的开关**：点一下在「息屏运行 / 退出息屏」之间切换，图标状态跟随实际状态。
- 局域网页面重做：状态实时刷新（3 秒轮询 `/state`）、深浅色自适应、按钮有执行反馈、可一键复制链接。
- 新增 `/state` JSON 接口，方便脚本/自动化集成。
- 端口被占用时自动顺延（20000 → 20009），不再静默失败。
- 激活成功后自动重新接收 Binder，不用再退出重进 APP。
- Shizuku 激活改用 `sh <script>` 直接执行并 `waitFor()`，失败会有明确提示。
- 打开「局域网控制」后标题栏自动显示地址，长按可复制。
- Android 15（targetSdk 35）强制边到边，补上了窗口 inset 处理，内容不再被状态栏/导航栏遮挡。
- 帮助弹窗显示当前版本号。

### 构建 / 工程

- 移除硬编码签名路径 `D:\key.keystore` 与明文密码，改为 `gradle.properties` / 环境变量注入，
  未配置时回退 debug 签名，任何人都能直接 `assembleRelease`。
- 新增 `.gitignore`（不再提交 `local.properties`、`build/`、签名文件等）。
- `lintOptions` 迁移到 `lint {}`，补 `buildFeatures.buildConfig`。
- Shizuku API `13.1.0` → `13.1.5`。
- 版本号 `21` → `22`。
- README 重写：原理、激活、接口、编译、已知限制。
