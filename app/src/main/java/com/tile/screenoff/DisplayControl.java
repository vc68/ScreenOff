package com.tile.screenoff;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 通过加载 services.jar 里的 com.android.server.display.DisplayControl 拿到物理屏幕 token。
 * Android 14 起 SurfaceControl.getInternalDisplayToken 被隐藏，只能走这条路。
 */
@SuppressLint({"PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi"})
public final class DisplayControl {

    private static final String TAG = "ScreenOff";

    private static final Class<?> CLASS;

    static {
        Class<?> displayControlClass = null;
        try {
            Class<?> classLoaderFactoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod("createClassLoader",
                    String.class, String.class, String.class,
                    ClassLoader.class, int.class, boolean.class, String.class);
            ClassLoader classLoader = (ClassLoader) createClassLoaderMethod.invoke(null,
                    "/system/framework/services.jar", null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);

            displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl");

            Method loadMethod = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            loadMethod.setAccessible(true);
            loadMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers");
        } catch (Throwable t) {
            Log.w(TAG, "could not initialize DisplayControl: " + t);
        }
        CLASS = displayControlClass;
    }

    private static Method getPhysicalDisplayTokenMethod;
    private static Method getPhysicalDisplayIdsMethod;

    private DisplayControl() {
    }

    public static boolean isAvailable() {
        return CLASS != null;
    }

    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        if (CLASS == null) return null;
        try {
            if (getPhysicalDisplayTokenMethod == null) {
                getPhysicalDisplayTokenMethod = CLASS.getMethod("getPhysicalDisplayToken", long.class);
            }
            return (IBinder) getPhysicalDisplayTokenMethod.invoke(null, physicalDisplayId);
        } catch (Throwable t) {
            Log.w(TAG, "getPhysicalDisplayToken failed: " + t);
            return null;
        }
    }

    public static long[] getPhysicalDisplayIds() {
        if (CLASS == null) return null;
        try {
            if (getPhysicalDisplayIdsMethod == null) {
                getPhysicalDisplayIdsMethod = CLASS.getMethod("getPhysicalDisplayIds");
            }
            return (long[]) getPhysicalDisplayIdsMethod.invoke(null);
        } catch (Throwable t) {
            Log.w(TAG, "getPhysicalDisplayIds failed: " + t);
            return null;
        }
    }
}
