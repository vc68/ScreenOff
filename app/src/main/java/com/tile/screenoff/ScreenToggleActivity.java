package com.tile.screenoff;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * ScrOff / ScrOn 两个快捷方式 Activity 的公共逻辑：
 * 无障碍服务已开启就直接发广播，没开启就先尝试自己打开（需要 WRITE_SECURE_SETTINGS），
 * 打不开再跳到系统设置页让用户手动开启。
 */
public abstract class ScreenToggleActivity extends Activity {

    /** true = 息屏运行，false = 退出息屏 */
    protected abstract boolean targetState();

    protected abstract String action();

    private static final long SERVICE_START_DELAY_MS = 1500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isAccessibilityEnabled()) {
            sendState();
            finish();
            return;
        }
        if (enableAccessibilityService()) {
            // 服务刚被打开，等它连上再发指令
            final Intent intent = stateIntent();
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> getApplicationContext().sendBroadcast(intent), SERVICE_START_DELAY_MS);
        }
        finish();
    }

    private Intent stateIntent() {
        return new Intent(action()).putExtra("state", targetState());
    }

    private void sendState() {
        sendBroadcast(stateIntent());
    }

    private boolean isAccessibilityEnabled() {
        String setting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return setting != null && setting.contains(getPackageName());
    }

    private boolean enableAccessibilityService() {
        final String serviceName = new ComponentName(getPackageName(), GlobalService.class.getName()).flattenToString();
        final String oldSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        final String newSetting = oldSetting == null ? serviceName : serviceName + ":" + oldSetting;
        try {
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newSetting);
            return true;
        } catch (Exception e) {
            Bundle bundle = new Bundle();
            bundle.putString(":settings:fragment_args_key", serviceName);
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .putExtra(":settings:fragment_args_key", serviceName)
                        .putExtra(":settings:show_fragment_args", bundle));
            } catch (Exception ignored) {
            }
            return false;
        }
    }
}
