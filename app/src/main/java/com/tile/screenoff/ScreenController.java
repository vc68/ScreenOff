package com.tile.screenoff;

import android.app.IApplicationThread;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 以 shell(uid 2000) 或 root(uid 0) 身份运行的后台进程。
 * 通过反射调用 SurfaceControl 关闭/打开物理屏幕，并把一个 Binder 用粘性广播交给 App。
 * <p>
 * 用法（一般由 starter.sh 调起）：
 * app_process /system/bin com.tile.screenoff.ScreenController [--off|--on|--toggle|--state]
 * 不带参数时作为常驻服务运行。
 */
public class ScreenController {

    private static final String TAG = "ScreenOff";
    private static final String ACTION_SEND_BINDER = "intent.screenoff.sendBinder";
    private static final long WATCHDOG_INTERVAL_MS = 20000L;

    public enum ScreenState {
        STATE_OFF, STATE_ON, STATE_SPECIAL
    }

    public static final int POWER_MODE_OFF = 0;
    public static final int POWER_MODE_NORMAL = 2;

    private static final Class<?> SURFACE_CONTROL_CLASS;

    static {
        Class<?> clazz = null;
        try {
            clazz = Class.forName("android.view.SurfaceControl");
        } catch (Throwable t) {
            Log.e(TAG, "SurfaceControl not found", t);
        }
        SURFACE_CONTROL_CLASS = clazz;
    }

    private static volatile ScreenState screenState = ScreenState.STATE_ON;
    private static volatile IBinder displayToken;

    private static Method setDisplayPowerModeMethod;

    private static boolean isBroadcastSent = false;
    private static Intent binderIntent = null;
    private static Object activityManager = null;

    // ------------------------------------------------------------------ 显示控制

    /** 取得物理屏幕 token，带缓存，避免每次开关屏都重新反射/加载 services.jar。 */
    private static IBinder getDisplayToken() {
        IBinder token = displayToken;
        if (token != null && token.pingBinder()) return token;
        token = resolveDisplayToken();
        displayToken = token;
        return token;
    }

    private static IBinder resolveDisplayToken() {
        // Android 14 起 getInternalDisplayToken 被隐藏，改用 DisplayControl
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                long[] ids = DisplayControl.getPhysicalDisplayIds();
                if (ids != null && ids.length > 0) {
                    IBinder token = DisplayControl.getPhysicalDisplayToken(ids[0]);
                    if (token != null) return token;
                }
            } catch (Throwable t) {
                Log.w(TAG, "DisplayControl unavailable, falling back", t);
            }
        }
        if (SURFACE_CONTROL_CLASS == null) return null;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Method method = SURFACE_CONTROL_CLASS.getMethod("getBuiltInDisplay", int.class);
                return (IBinder) method.invoke(null, 0);
            }
            Method method = SURFACE_CONTROL_CLASS.getMethod("getInternalDisplayToken");
            return (IBinder) method.invoke(null);
        } catch (Throwable t) {
            Log.e(TAG, "could not get display token", t);
            return null;
        }
    }

    private static Method getSetDisplayPowerModeMethod() {
        Method method = setDisplayPowerModeMethod;
        if (method == null && SURFACE_CONTROL_CLASS != null) {
            try {
                method = SURFACE_CONTROL_CLASS.getMethod("setDisplayPowerMode", IBinder.class, int.class);
                setDisplayPowerModeMethod = method;
            } catch (Throwable t) {
                Log.e(TAG, "setDisplayPowerMode not found", t);
            }
        }
        return method;
    }

    private static boolean setDisplayPowerMode(IBinder token, int mode) {
        if (token == null) return false;
        Method method = getSetDisplayPowerModeMethod();
        if (method == null) return false;
        try {
            method.invoke(null, token, mode);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setDisplayPowerMode failed", t);
            return false;
        }
    }

    /** 直接应用一次开关屏（供命令行与 Binder 共用）。 */
    private static boolean applyPowerMode(boolean turnOff) {
        IBinder token = getDisplayToken();
        if (token == null) {
            System.err.println("Could not get display token.");
            return false;
        }
        // 不再用内存里的 screenState 做前置判断。
        // 控制器进程一旦重启，screenState 会回到 STATE_ON，而物理屏幕其实还是关的，
        // 旧逻辑下「点亮屏」会因为状态不匹配而什么都不做，用户只能按电源键。
        // setDisplayPowerMode 本身幂等，直接下发最稳。
        if (!setDisplayPowerMode(token, turnOff ? POWER_MODE_OFF : POWER_MODE_NORMAL)) return false;
        screenState = turnOff ? ScreenState.STATE_SPECIAL : ScreenState.STATE_ON;
        return true;
    }

    /**
     * 看门狗：息屏运行期间周期性重申 POWER_MODE_OFF。
     * 系统（通知唤醒、DPC 重新评估、厂商策略）可能把 display 又点亮，
     * 只靠一次性下发会出现「过一会儿屏幕自己亮/自己灭」的抖动。
     */
    private static void startWatchdog() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (screenState == ScreenState.STATE_SPECIAL) {
                    IBinder token = getDisplayToken();
                    if (token != null) setDisplayPowerMode(token, POWER_MODE_OFF);
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }, WATCHDOG_INTERVAL_MS);
    }

    // ------------------------------------------------------------------ 入口

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        int uid = android.os.Process.myUid();
        if (uid != 0 && uid != 2000) {
            System.err.printf("Insufficient permission! Need to be launched by adb (uid 2000) or root (uid 0), but your uid is %d\n", uid);
            System.exit(-1);
            return;
        }

        String command = args != null && args.length > 0 ? args[0] : "";
        switch (command) {
            case "--off":
                System.exit(applyPowerMode(true) ? 0 : 1);
                return;
            case "--on":
                System.exit(applyPowerMode(false) ? 0 : 1);
                return;
            case "--toggle":
                System.exit(applyPowerMode(screenState != ScreenState.STATE_SPECIAL) ? 0 : 1);
                return;
            case "--state":
                System.out.println(screenState.name());
                System.exit(0);
                return;
            default:
                break;
        }

        System.out.println("Start ScreenController Service.");

        isBroadcastSent = sendBinderToAppByStickyBroadcast();
        if (!isBroadcastSent) {
            System.err.println("Failed to send broadcast!");
            System.exit(-1);
            return;
        }

        startWatchdog();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (isBroadcastSent) isBroadcastSent = !removeStickyBroadcast();
            }
        });

        Looper.loop();

        if (isBroadcastSent) isBroadcastSent = !removeStickyBroadcast();
        System.out.println("Stop ScreenController Service.\n");
        System.exit(0);
    }

    // ------------------------------------------------------------------ Binder

    private static final class ScreenOffBinder extends IScreenOff.Stub {
        @Override
        public void setPowerMode(boolean turnOff) throws RemoteException {
            applyPowerMode(turnOff);
        }

        @Override
        public int getNowScreenState() throws RemoteException {
            return screenState.ordinal();
        }

        @Override
        public void updateNowScreenState(boolean isScreenOn) throws RemoteException {
            screenState = isScreenOn ? ScreenState.STATE_ON : ScreenState.STATE_OFF;
        }

        @Override
        public void closeAndExit() throws RemoteException {
            if (isBroadcastSent) isBroadcastSent = !removeStickyBroadcast();
            System.out.println("Stop ScreenController Service.\n");
            System.exit(0);
        }
    }

    private static boolean sendBinderToAppByStickyBroadcast() {
        try {
            IBinder binder = new ScreenOffBinder();
            BinderContainer container = new BinderContainer(binder);

            binderIntent = new Intent(ACTION_SEND_BINDER);
            binderIntent.putExtra("binder", container);

            activityManager = getActivityManager();
            if (activityManager == null) {
                Log.e(TAG, "could not get ActivityManager");
                return false;
            }

            Method broadcast = findBroadcastIntentMethod();
            if (broadcast == null) {
                Log.e(TAG, "could not find broadcastIntent method");
                return false;
            }
            broadcast.invoke(activityManager, buildBroadcastArgs(broadcast.getParameterTypes()));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "send binder failed", t);
            return false;
        }
    }

    private static Object getActivityManager() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Object serviceManagerBinder = Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class)
                    .invoke(null, "activity");
            return Class.forName("android.app.IActivityManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, serviceManagerBinder);
        }
        Class<?> activityManagerNative = Class.forName("android.app.ActivityManagerNative");
        return activityManagerNative.getMethod("getDefault").invoke(null);
    }

    /**
     * broadcastIntent 的参数在不同 Android 版本上有增减，
     * 这里按参数类型自动填充，避免写死 13 个参数在某个版本上直接抛异常。
     */
    private static Method findBroadcastIntentMethod() {
        try {
            Class<?> clazz = Class.forName("android.app.IActivityManager");
            Method best = null;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"broadcastIntent".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 3 || types[1] != Intent.class) continue;
                if (best == null || types.length > best.getParameterTypes().length) {
                    best = method;
                }
            }
            return best;
        } catch (Throwable t) {
            Log.e(TAG, "find broadcastIntent failed", t);
            return null;
        }
    }

    private static Object[] buildBroadcastArgs(Class<?>[] types) {
        int n = types.length;
        Object[] args = new Object[n];
        for (int i = 0; i < n; i++) {
            Class<?> type = types[i];
            if (type == Intent.class) {
                args[i] = binderIntent;
            } else if (type == int.class) {
                if (i == n - 1) args[i] = -1;        // userId
                else if (i == 4) args[i] = -1;       // resultCode
                else args[i] = 0;                    // appOp
            } else if (type == boolean.class) {
                args[i] = (i == n - 2);              // sticky
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static boolean removeStickyBroadcast() {
        if (binderIntent == null || activityManager == null) return true;
        try {
            Class<?> clazz = Class.forName("android.app.IActivityManager");
            Method unbroadcast = null;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"unbroadcastIntent".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length >= 2 && types[1] == Intent.class) {
                    unbroadcast = method;
                    break;
                }
            }
            if (unbroadcast == null) return false;

            Class<?>[] types = unbroadcast.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == Intent.class) args[i] = binderIntent;
                else if (types[i] == int.class) args[i] = -1;
                else args[i] = null;
            }
            unbroadcast.invoke(activityManager, args);
            binderIntent = null;
            activityManager = null;
            return true;
        } catch (Throwable t) {
            System.err.println("Failed to remove broadcast!");
            return false;
        }
    }
}
