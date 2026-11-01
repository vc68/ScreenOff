# 更新记录

## v24 — 2026-11

真机实测暴露的两个阻断性 bug，都会导致**激活必定失败**。

### 修复

- **激活脚本被写成了 CRLF，设备端直接语法错误**
  真机报错：`starter.sh[8]: : inaccessible or not found`、`syntax error: unmatched 'if'`。
  原因是本机 `core.autocrlf=true`，`git add` 时把工作区脚本改写成了 CRLF，而 APK 是在那之后打包的；
  mksh 把行尾的 `\r` 当成命令名执行，`then\r` 也不再被识别为 `then`。
  - 新增 `.gitattributes`：`*.sh` / `gradlew` 强制 `eol=lf`，`app/src/main/assets/**` 按二进制处理不转换
  - `unzipFiles()` 增加兜底：写 `starter.sh` 时主动把 CRLF 归一化成 LF，
    即使构建环境有问题，装到手机上的脚本也一定是 LF
- **Shizuku 激活报 "process hasn't exited"**
  `Shell` 里 `process.exitValue()` 没有保护。Shizuku 的远程进程是自实现的 `Process`，
  `waitFor()` 返回 true 后 `exitValue()` 仍可能抛 `IllegalThreadStateException`。
  现在退出码只作为参考：拿不到时退化为解析脚本自己打印的标记（`错误：` / `已启动`）。
- **判定逻辑改为脚本标记优先**：脚本明确报错就是失败，明确说已启动就算成功，
  不再单纯依赖退出码——Shizuku 路径本来就拿不到可靠的退出码。

### 说明

v22 / v23 的 APK 里脚本同样是 CRLF，所以那两版的激活是**不可用的**，请直接装 v24。

## v23 — 2026-11

把「root 激活」和「Shizuku 激活」两条路径重写成**真执行、真校验**。这两条路径 v22 里都还在，
但沿用了原实现的毛病：不看退出码，脚本失败也报"激活成功"。

### 修复

- **激活失败也报成功**：原来命令写完 stdin 就弹"激活成功"。现在收集进程输出、等待退出码，
  非 0 或超时都判失败，并把脚本的原始输出显示在弹窗里（比如"找不到 dex"、"WRITE_SECURE_SETTINGS 授予失败"）。
- **root 授权被拒绝也报成功**：`su` 弹框点"拒绝"时进程返回非 0，现在能正确识别。
- **root 命令执行方式单一**：改为先 `su -c "<cmd>"`，失败再回退 `su` + stdin（兼容不支持 `-c` 的 su 实现）；
  超时不会重复重试，避免连弹两次授权框。
- **成功后固定等 1.2~1.5 秒就判定完成**：慢设备上服务根本没起来。现在改为轮询 Binder 最长 10 秒，
  真连上才算成功，超时给出"命令已执行但服务无响应"的排查提示。
- **Shizuku 三种异常分不清**：未安装、未运行、权限被拒都报同一句。现在用 `Shizuku.pingBinder()` 判断服务是否在跑，
  权限被拒时弹说明并支持重新申请，Android 6.0 以下单独提示。
- **`newProcess` 反射只按 private 找**：现在先按 public 找，找不到再按 declared 找，兼容新旧 Shizuku。
- **激活脚本释放失败无提示**：`ensureScriptReady()` 会校验 `starter.sh` 与 `ScreenController.dex` 是否真的写出来了。
- **弹窗不关**：激活成功后自动关闭激活弹窗并刷新界面状态。

### 激活脚本（starter.sh）

- 先检查 `app_process` 是否存在，缺失时明确报错
- `pm grant` 结果会打印出来（失败不影响核心功能，只是无法自动开无障碍）
- 校验 dex 存在且非空，写 `/data/local/tmp` 失败时报错退出
- `pkill` 加静默保护，老系统没有该命令也不会中断

### 测试

进程执行核心抽成 `Shell.java`，在桌面 JVM 上单测（打桩 `android.os.Build`），5 项全过：

| 用例 | 结果 |
| --- | --- |
| 成功命令返回输出 | PASS |
| 非零退出码判为失败 | PASS |
| 失败时附带脚本原始输出 | PASS |
| 超时抛专用 `Shell.Timeout` | PASS |
| API < 26 轮询分支可用 | PASS |

> 真机上的 root / Shizuku 授权流程仍需设备验证（本机无安卓设备）。

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
  accept 线程里递归调用 `start()`，`ServerSocket` 从不关闭。
  实测 230 次请求后残留 211 个阻塞在 `accept()` 的线程（线程数 2 → 213），响应耗时是新实现的 2.2 倍。
  现在改为单 accept 线程 + 连接超时 + 正确关闭，同样 230 次请求后线程数仍是 2。
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

### 测试与验证

**已实际验证：**

| 项目 | 方法 | 结果 |
| --- | --- | --- |
| 编译打包 | `assembleDebug` / `assembleRelease` | 通过，release APK 100 KB |
| APK 完整性 | aapt2 / apksigner / unzip | versionCode 22、minSdk 21、targetSdk 35，`classes.dex` 与 assets 齐全 |
| 局域网控制（新旧对比） | 桌面 JVM 打桩 `android.util.Log`，同一套请求跑两版实现 | 旧版 2.2 ms/次、230 次后线程数 2→213；新版 1.0 ms/次、线程数保持 2 |
| 请求路由与解析 | 8 组请求样本（含 `/1?123` 旧接口、404、非法请求） | 路径/查询串解析正确 |
| 控制页面渲染 | Chromium headless 截图（430 px、360 px 窄屏、暗色） | 布局无溢出/遮挡，二维码正常，深浅色可读 |
| 页面交互 | 自动点击「息屏运行」按钮，服务端有状态计数 | 请求真实发出（`off=1`），状态从「亮屏」刷新为「息屏运行」 |

**未验证（没有真机/模拟器）：**

- `SurfaceControl` 反射取屏幕 token、Android 14+ `DisplayControl` 回退链
- 无障碍服务连接、悬浮球拖动与长按、立起亮屏、音量键控制
- 快捷开关磁贴、Binder 跨进程调用、`starter.sh` 激活流程
- APP 原生界面的 inset 适配效果（只做了代码层处理）

以上都需要在设备上跑一遍才能确认。
