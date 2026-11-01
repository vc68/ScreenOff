#!/system/bin/sh
# 息屏运行 - 激活脚本
# 需要以 shell(uid 2000) 或 root(uid 0) 身份执行：
#   adb shell sh /sdcard/Android/data/com.tile.screenoff/files/starter.sh
# 也可以由 APP 内的「root 激活」「Shizuku 激活」按钮直接调用。
#
# 约定：成功 exit 0，失败 exit 非 0 并打印原因（APP 会把输出显示给用户）。

PKG=com.tile.screenoff
DEX_NAME=ScreenController.dex
CACHE_DIR=/data/local/tmp
TARGET=$CACHE_DIR/$DEX_NAME
MAIN_CLASS=com.tile.screenoff.ScreenController

# 0. 环境检查
if ! command -v app_process >/dev/null 2>&1; then
    echo "错误：找不到 app_process，系统环境异常"
    exit 2
fi

# 1. 授予 WRITE_SECURE_SETTINGS（用于自动开启无障碍服务）
if pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; then
    echo "WRITE_SECURE_SETTINGS: 已授予"
else
    # 已授予时部分系统会返回非 0，这不影响核心功能，只是无法自动开无障碍
    echo "WRITE_SECURE_SETTINGS: 授予失败（需手动开启无障碍服务）"
fi

DIR=$(dirname "$0")
SRC=$DIR/$DEX_NAME

if [ ! -f "$SRC" ]; then
    echo "错误：找不到 $SRC"
    echo "请先在 APP 内点一次「激活」按钮，让 APP 把 dex 释放出来。"
    exit 1
fi

if [ ! -s "$SRC" ]; then
    echo "错误：$SRC 是空文件，请卸载重装 APP 后重试"
    exit 1
fi

# 2. 结束旧的控制器进程，避免重复启动
pkill -f "$MAIN_CLASS" >/dev/null 2>&1
sleep 0.3

# 3. 部署并启动
if ! cp -f "$SRC" "$TARGET"; then
    echo "错误：无法写入 $TARGET"
    exit 1
fi
chmod 644 "$TARGET" 2>/dev/null

export CLASSPATH="$TARGET"
nohup app_process /system/bin "$MAIN_CLASS" >/dev/null 2>&1 &

echo "ScreenController 已启动（class=$MAIN_CLASS, dex=$TARGET）"
exit 0
