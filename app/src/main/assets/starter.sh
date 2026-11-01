#!/system/bin/sh
# 息屏运行 - 激活脚本
# 需要以 shell(uid 2000) 或 root(uid 0) 身份执行：
#   adb shell sh /sdcard/Android/data/com.tile.screenoff/files/starter.sh
# 也可以直接 root 执行。

PKG=com.tile.screenoff
DEX_NAME=ScreenController.dex
CACHE_DIR=/data/local/tmp
TARGET=$CACHE_DIR/$DEX_NAME
MAIN_CLASS=com.tile.screenoff.ScreenController

# 1. 授予 WRITE_SECURE_SETTINGS，用于自动开启无障碍服务
pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null

DIR=$(dirname "$0")
SRC=$DIR/$DEX_NAME

if [ ! -f "$SRC" ]; then
    echo "dex not found: $SRC"
    echo "请先在 APP 内点一次『激活』按钮，让 APP 把 dex 释放出来。"
    exit 1
fi

# 2. 结束旧的控制器进程，避免重复启动
pkill -f "$MAIN_CLASS" 2>/dev/null
sleep 0.3

# 3. 部署并启动
cp -f "$SRC" "$TARGET" || exit 1
chmod 644 "$TARGET"

export CLASSPATH="$TARGET"
nohup app_process /system/bin "$MAIN_CLASS" >/dev/null 2>&1 &

echo "ScreenController started."
exit 0
