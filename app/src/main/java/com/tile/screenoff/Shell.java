package com.tile.screenoff;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 执行外部命令并按**退出码**判定结果。
 * <p>
 * 旧实现把命令写进 stdin 就当作成功，用户点「拒绝授权」也会提示激活成功。
 * 这里统一成：收集输出 → 等退出码 → 非 0 或超时都算失败，并把原始输出带回去。
 * <p>
 * 注意：Shizuku 的远程进程是自实现的 {@link Process}，`waitFor()` 返回 true 之后
 * `exitValue()` 仍可能抛 {@link IllegalThreadStateException}（"process hasn't exited"），
 * 所以退出码只作为**参考**：拿不到时退化为看脚本自己打印的标记。
 * 抽成独立类是为了能脱离设备做单元测试。
 */
public final class Shell {

    /** 脚本失败时的固定前缀，见 starter.sh */
    public static final String ERROR_MARK = "错误：";
    /** 脚本成功时的固定标记，见 starter.sh */
    public static final String SUCCESS_MARK = "已启动";

    /** 命令等待超时（区别于普通失败，避免重复弹 root 授权框）。 */
    public static final class Timeout extends IOException {
        public Timeout(String message) {
            super(message);
        }
    }

    private Shell() {
    }

    public static String waitAndCollect(final Process process, long timeoutMs) throws Exception {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[512];
                int len;
                while ((len = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, len);
                }
            } catch (IOException ignored) {
            }
        }, "shell-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = waitFor(process, timeoutMs);
        reader.join(800);

        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();

        // 脚本明确报错时，无论退出码如何都算失败
        if (output.contains(ERROR_MARK)) {
            throw new IOException("脚本执行失败" + appendOutput(output));
        }
        // 脚本明确说已启动时，不必依赖退出码（Shizuku 路径拿不到退出码）
        if (output.contains(SUCCESS_MARK)) {
            return output;
        }
        if (!finished) {
            process.destroy();
            throw new Timeout("等待超时（可能在等待 root 授权）");
        }
        Integer exitCode = exitCodeOrNull(process);
        if (exitCode != null && exitCode != 0) {
            throw new IOException("退出码 " + exitCode + appendOutput(output));
        }
        return output;
    }

    /** 拿不到退出码时返回 null，而不是抛异常。 */
    private static Integer exitCodeOrNull(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException notExited) {
            return null;
        }
    }

    /** Process.waitFor(timeout) 需要 API 26，低版本用轮询兜底。 */
    public static boolean waitFor(Process process, long timeoutMs) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (IllegalThreadStateException ignored) {
                // 少数自实现的 Process 会在这里抛，退回到轮询
            }
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                Thread.sleep(100);
            }
        }
        return false;
    }

    public static String appendOutput(String output) {
        if (output == null || output.trim().isEmpty()) return "";
        return "\n\n脚本输出：\n" + output.trim();
    }
}
