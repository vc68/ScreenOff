package com.tile.screenoff;

import static java.lang.Math.abs;

import android.accessibilityservice.AccessibilityService;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class GlobalService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "ScreenOff";

    static final String ACTION_SEND_BINDER = "intent.screenoff.sendBinder";
    static final String ACTION_EXIT = "intent.screenoff.exit";
    static final String ACTION_SCR_OFF = "action.ScrOff";
    static final String ACTION_TOGGLE = "action.Toggle";

    /** 与 ScreenController 中的 ScreenState 一一对应 */
    static final int STATE_OFF = 0;
    static final int STATE_ON = 1;
    static final int STATE_SPECIAL = 2;

    private static final int LONG_PRESS_MS = 400;
    private static final int DOUBLE_TAP_MS = 400;
    private static final int MOVE_SLOP_PX = 4;
    private static final int DEFAULT_PORT = 20000;
    private static final int PORT_TRY_COUNT = 10;

    /**
     * 保活自检间隔。
     * 息屏运行期间，系统（尤其是国产 ROM 的省电策略）随时可能回收我们的 overlay 窗口，
     * 窗口一掉 FLAG_KEEP_SCREEN_ON 就失效，系统会按「休眠超时」真正睡死。
     * 所以必须周期性检查并重建。
     */
    private static final long KEEPALIVE_CHECK_MS = 15000L;

    /** 记录用户「想要」处于息屏运行状态，无障碍服务被回收重启后据此自动恢复。 */
    private static final String KEY_WANT_SPECIAL = "want_special";

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private ImageView view;
    private View keepAliveView;
    private WindowManager.LayoutParams keepAliveParams;
    private boolean keepAliveAttached;
    private boolean keepAliveWatchRunning;
    private SharedPreferences sp;
    private boolean exist = false, canmove, doubleTap, shake, volume, netControl, onlyLand;
    private int size, sensity, scrOnKey, scrOffKey;
    private float alphaLevel = 0.9f;
    private int screenWidth, screenHeight;
    private OrientationEventListener listener;
    private volatile IScreenOff iScreenOff = null;
    private boolean receiverRegistered = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pressed = false;

    private final Runnable longPressAction = new Runnable() {
        @Override
        public void run() {
            if (pressed) {
                pressed = false;
                screenOff(true);
            }
        }
    };

    private final BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case ACTION_SEND_BINDER: {
                    BinderContainer container = getBinderExtra(intent);
                    if (container == null) break;
                    IBinder binder = container.getBinder();
                    if (binder == null || !binder.pingBinder()) break;
                    iScreenOff = IScreenOff.Stub.asInterface(binder);
                    // 服务重新连上后把悬浮球恢复到正确状态
                    if (exist) windowManager.updateViewLayout(view, params);
                    // 控制器进程可能重启过（内存里的状态会回到 STATE_ON），
                    // 只要用户之前要的是「息屏运行」，这里重新下发一次，保证状态机一致。
                    restoreIfWanted();
                    break;
                }
                case Intent.ACTION_SCREEN_OFF: {
                    IScreenOff api = iScreenOff;
                    int now = -1;
                    if (api != null) {
                        try {
                            now = api.getNowScreenState();
                        } catch (RemoteException e) {
                            onBinderLost();
                        }
                    }
                    // 关键区分：
                    //   isInteractive()==true  → 设备还醒着，只是我们的保活窗口被系统回收了
                    //                            （国产 ROM 省电清理、锁屏切换都会触发）。
                    //                            此时绝对不能撤保活，必须立刻重建，否则接着就真睡死。
                    //   isInteractive()==false → 用户按了电源键 / 系统真的休眠，应当老实退出息屏运行。
                    if (now == STATE_SPECIAL && isInteractive()) {
                        rememberState(STATE_SPECIAL);
                        // 状态仍是息屏运行，说明用户要的就是这个，标记也要对齐，
                        // 否则心跳会因为 want_special=false 直接退出，失去自愈能力。
                        setWantSpecial(true);
                        ensureKeepAlive();
                        startKeepAliveWatch();
                        break;
                    }
                    // 设备真的进入睡眠：退出息屏运行，交还系统控制
                    if (api != null) {
                        try {
                            api.updateNowScreenState(false);
                        } catch (RemoteException e) {
                            onBinderLost();
                        }
                    }
                    rememberState(STATE_OFF);
                    setWantSpecial(false);
                    releaseKeepAlive();
                    stopKeepAliveWatch();
                    if (shake) listener.disable();
                    break;
                }
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_USER_PRESENT: {
                    IScreenOff api = iScreenOff;
                    if (api == null) break;
                    try {
                        if (api.getNowScreenState() == STATE_SPECIAL) {
                            // 息屏运行中：SurfaceControl 层屏幕仍是关的，
                            // 这里收到亮屏广播不要破坏状态机，只补一次保活。
                            ensureKeepAlive();
                            startKeepAliveWatch();
                            break;
                        }
                        api.updateNowScreenState(true);
                    } catch (RemoteException e) {
                        onBinderLost();
                    }
                    rememberState(STATE_ON);
                    break;
                }
                case ACTION_SCR_OFF:
                    screenOff(intent.getBooleanExtra("state", true));
                    break;
                case ACTION_TOGGLE:
                    toggle();
                    break;
                case ACTION_EXIT:
                    // 用户主动退出：清掉「想要息屏运行」的标记，避免服务重启后又自动关屏
                    setWantSpecial(false);
                    stopKeepAliveWatch();
                    releaseKeepAlive();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        disableSelf();
                    } else {
                        stopSelf();
                    }
                    break;
            }
        }
    };

    @SuppressWarnings("deprecation")
    private static BinderContainer getBinderExtra(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra("binder", BinderContainer.class);
            }
            return intent.getParcelableExtra("binder");
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;
        if (key.startsWith("x") || key.startsWith("y")) return;

        onlyLand = sharedPreferences.getBoolean("land", false);
        size = dpToPx(sharedPreferences.getInt("size", 50));
        alphaLevel = sharedPreferences.getInt("tran", 90) * 0.01f;
        params.height = size;
        params.width = size;
        params.alpha = alphaLevel;
        canmove = sharedPreferences.getBoolean("canmove", true);
        doubleTap = sharedPreferences.getBoolean("doubleTap", false);
        shake = sharedPreferences.getBoolean("shake", false);
        sensity = sharedPreferences.getInt("sensity", 10);
        volume = sharedPreferences.getBoolean("volume", false);
        scrOnKey = sharedPreferences.getInt("scrOnKey", 24);
        scrOffKey = sharedPreferences.getInt("scrOffKey", 25);

        boolean newNetControl = sharedPreferences.getBoolean("net", false);
        if (newNetControl != netControl) {
            netControl = newNetControl;
            if (netControl) startServer();
            else stopServer();
        }

        updateBubbleVisibility();
        floatWindow();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sp = getSharedPreferences("s", 0);
        windowManager = (WindowManager) getSystemService(Service.WINDOW_SERVICE);

        sensity = sp.getInt("sensity", 10);
        listener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                boolean wake = (orientation >= 360 - sensity || orientation <= sensity)
                        || (orientation >= 90 - sensity && orientation <= 90 + sensity)
                        || (orientation >= 180 - sensity && orientation <= 180 + sensity)
                        || (orientation >= 270 - sensity && orientation <= 270 + sensity);
                if (wake) {
                    screenOff(false);
                    this.disable();
                }
            }
        };

        measureScreen();
        size = dpToPx(sp.getInt("size", 50));
        alphaLevel = sp.getInt("tran", 90) * 0.01f;

        params = new WindowManager.LayoutParams(size, size,
                Build.VERSION.SDK_INT >= 22 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.RGBA_8888);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        params.alpha = alphaLevel;
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        params.x = sp.getInt("x" + (portrait ? "1" : "2"), 0);
        params.y = sp.getInt("y" + (portrait ? "1" : "2"), 0);

        view = new ImageView(this);
        view.setBackground(ovalBackground());
        view.setImageResource(R.drawable.fw);

        keepAliveView = new View(this);
        // 必须是全屏窗口：1x1 的小窗口在不少 ROM 上不会被 PowerManagerService 认定为
        // 「持有屏幕」的窗口，FLAG_KEEP_SCREEN_ON 形同虚设，系统照常按超时休眠。
        // 全屏 + 不可触摸 + 全透明，用户完全感知不到，但保活一定生效。
        keepAliveParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 22 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            keepAliveParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        canmove = sp.getBoolean("canmove", true);
        doubleTap = sp.getBoolean("doubleTap", false);
        shake = sp.getBoolean("shake", false);
        volume = sp.getBoolean("volume", false);
        onlyLand = sp.getBoolean("land", false);
        scrOnKey = sp.getInt("scrOnKey", 24);
        scrOffKey = sp.getInt("scrOffKey", 25);
        netControl = sp.getBoolean("net", false);

        view.setOnTouchListener(new BubbleTouchListener());

        updateBubbleVisibility();
        floatWindow();

        if (netControl) startServer();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(ACTION_SCR_OFF);
        filter.addAction(ACTION_TOGGLE);
        filter.addAction(ACTION_SEND_BINDER);
        filter.addAction(ACTION_EXIT);
        // 旧代码在 Android 13+ 上只注册了 sendBinder 一个 action，
        // 导致电源键、快捷方式、退出指令全部收不到，这里必须整体注册。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(myReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(myReceiver, filter);
        }
        receiverRegistered = true;

        sp.registerOnSharedPreferenceChangeListener(this);

        // 无障碍服务可能被系统回收后重启：如果用户之前要的是「息屏运行」，
        // 这里必须把保活和状态一起恢复，否则手机会直接睡死。
        restoreIfWanted();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private ShapeDrawable ovalBackground() {
        ShapeDrawable oval = new ShapeDrawable(new OvalShape());
        oval.getPaint().setColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(R.color.bg) : 0xffc1e8ff);
        return oval;
    }

    private class BubbleTouchListener implements View.OnTouchListener {
        int lastX, lastY, paramX, paramY;
        long lastDown = 0;
        boolean moved = false;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = (int) event.getRawX();
                    lastY = (int) event.getRawY();
                    paramX = params.x;
                    paramY = params.y;
                    params.alpha = 1f;
                    if (exist) windowManager.updateViewLayout(view, params);
                    moved = false;
                    pressed = true;
                    handler.removeCallbacks(longPressAction);
                    handler.postDelayed(longPressAction, LONG_PRESS_MS);
                    if (doubleTap) {
                        long now = System.currentTimeMillis();
                        if (now - lastDown <= DOUBLE_TAP_MS) screenOff(false);
                        lastDown = now;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) event.getRawX() - lastX;
                    int dy = (int) event.getRawY() - lastY;
                    if (abs(dx) > MOVE_SLOP_PX || abs(dy) > MOVE_SLOP_PX) {
                        if (!moved) {
                            moved = true;
                            pressed = false;
                            handler.removeCallbacks(longPressAction);
                        }
                    }
                    if (!canmove) return true;
                    params.x = paramX + dx;
                    params.y = paramY + dy;
                    if (exist) windowManager.updateViewLayout(view, params);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                default: {
                    pressed = false;
                    handler.removeCallbacks(longPressAction);
                    params.alpha = alphaLevel;
                    if (moved && canmove) snapToEdge();
                    if (exist) windowManager.updateViewLayout(view, params);
                    boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                    sp.edit()
                            .putInt("x" + (portrait ? "1" : "2"), params.x)
                            .putInt("y" + (portrait ? "1" : "2"), params.y)
                            .apply();
                    return true;
                }
            }
        }
    }

    /** 松手后贴边并限制在屏幕内，避免悬浮球被拖到屏幕外找不回来。 */
    private void snapToEdge() {
        int maxX = Math.max(0, (screenWidth - size) / 2);
        int maxY = Math.max(0, (screenHeight - size) / 2);
        if (params.x > maxX * 0.86) params.x = maxX;
        else if (params.x < -maxX * 0.86) params.x = -maxX;
        params.x = Math.max(-maxX, Math.min(maxX, params.x));
        params.y = Math.max(-maxY, Math.min(maxY, params.y));
    }

    /**
     * 建立（或重建）保活窗口。
     * <p>
     * 旧版本只在开关屏那一刻挂一次窗口，之后再不检查。一旦系统在息屏后回收了这个
     * overlay（国产 ROM 的省电清理、锁屏切换、旋转等都会触发），FLAG_KEEP_SCREEN_ON
     * 就彻底失效，手机会按系统休眠超时真正睡死 —— 这正是「刚开始好好的，过一会儿
     * 自己熄屏了」的原因。
     * <p>
     * 现在改成幂等操作：无论调用多少次、窗口是否还在，都保证最终有一个活着的
     * keep-screen-on 窗口。配合心跳自检，可以自愈。
     */
    private void ensureKeepAlive() {
        if (windowManager == null || keepAliveView == null) return;
        try {
            if (keepAliveView.getParent() == null) {
                if (keepAliveAttached) {
                    // 记录说挂着、实际已经掉了，先清掉脏状态
                    try {
                        windowManager.removeViewImmediate(keepAliveView);
                    } catch (Exception ignored) {
                    }
                    keepAliveAttached = false;
                }
                windowManager.addView(keepAliveView, keepAliveParams);
                keepAliveAttached = true;
            }
            keepAliveView.setKeepScreenOn(true);
        } catch (Exception e) {
            Log.w(TAG, "ensureKeepAlive failed", e);
            keepAliveAttached = false;
        }
    }

    /** 撤掉保活窗口，让系统恢复正常的休眠计时。 */
    private void releaseKeepAlive() {
        if (windowManager == null || keepAliveView == null) return;
        try {
            keepAliveView.setKeepScreenOn(false);
            if (keepAliveView.getParent() != null) {
                windowManager.removeViewImmediate(keepAliveView);
            }
        } catch (Exception e) {
            Log.w(TAG, "releaseKeepAlive failed", e);
        } finally {
            keepAliveAttached = false;
        }
    }

    /** 心跳：只要用户还要「息屏运行」，就周期性确认保活窗口活着。 */
    private final Runnable keepAliveCheck = new Runnable() {
        @Override
        public void run() {
            // 正在跑的时候置位，避免下面的 restoreIfWanted() 再排一次，造成心跳翻倍
            keepAliveWatchRunning = true;
            boolean want = sp != null && sp.getBoolean(KEY_WANT_SPECIAL, false);
            if (!want) {
                keepAliveWatchRunning = false;
                return; // 用户已退出，停止排队
            }
            if (isSpecialState()) {
                ensureKeepAlive();
            } else if (iScreenOff != null) {
                // 状态被意外重置（控制器重启、系统唤醒等），重新拉回息屏运行
                restoreIfWanted();
            }
            handler.removeCallbacks(this);
            handler.postDelayed(this, KEEPALIVE_CHECK_MS);
        }
    };

    private void startKeepAliveWatch() {
        if (keepAliveWatchRunning) return;
        keepAliveWatchRunning = true;
        handler.removeCallbacks(keepAliveCheck);
        handler.postDelayed(keepAliveCheck, KEEPALIVE_CHECK_MS);
    }

    private void stopKeepAliveWatch() {
        keepAliveWatchRunning = false;
        handler.removeCallbacks(keepAliveCheck);
    }

    private boolean isInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Service.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isSpecialState() {
        IScreenOff api = iScreenOff;
        if (api == null) return false;
        try {
            return api.getNowScreenState() == STATE_SPECIAL;
        } catch (RemoteException e) {
            onBinderLost();
            return false;
        }
    }

    /** 用户之前要求「息屏运行」，但服务/控制器重连过，就把状态重新拉回来。 */
    private void restoreIfWanted() {
        if (sp == null || !sp.getBoolean(KEY_WANT_SPECIAL, false)) return;
        if (iScreenOff == null) return; // 等粘性广播把 Binder 送过来再恢复
        if (isSpecialState()) {
            ensureKeepAlive();
            startKeepAliveWatch();
            return;
        }
        screenOff(true);
    }

    private void setWantSpecial(boolean want) {
        if (sp != null) sp.edit().putBoolean(KEY_WANT_SPECIAL, want).apply();
    }

    void screenOff(boolean off) {
        IScreenOff api = iScreenOff;
        if (api == null) {
            handler.post(() -> Toast.makeText(GlobalService.this, R.string.binder_lost, Toast.LENGTH_SHORT).show());
            return;
        }
        try {
            final int now = api.getNowScreenState();
            if (off) {
                // 0 = 用户真的按了电源键息屏，此时不需要（也不能）再做任何事
                if (now == STATE_OFF) return;
                if (now == STATE_SPECIAL) {
                    // 已经在息屏运行，重复触发只刷新保活，不要重复下发关屏
                    setWantSpecial(true);
                    ensureKeepAlive();
                    startKeepAliveWatch();
                    return;
                }
                api.setPowerMode(true);
                rememberState(STATE_SPECIAL);
                setWantSpecial(true);
                ensureKeepAlive();
                startKeepAliveWatch();
                if (shake) listener.enable();
            } else {
                if (now == STATE_ON) {
                    // 本来就是亮的，顺手把可能残留的保活撤掉
                    rememberState(STATE_ON);
                    setWantSpecial(false);
                    releaseKeepAlive();
                    stopKeepAliveWatch();
                    return;
                }
                api.setPowerMode(false);
                rememberState(STATE_ON);
                setWantSpecial(false);
                releaseKeepAlive();
                stopKeepAliveWatch();
                if (shake) listener.disable();
            }
        } catch (RemoteException e) {
            onBinderLost();
        }
    }

    /** 记录最近状态，供快捷开关磁贴显示。 */
    private void rememberState(int state) {
        getSharedPreferences("state", MODE_PRIVATE).edit().putInt("last", state).apply();
    }

    private void toggle() {
        IScreenOff api = iScreenOff;
        if (api == null) return;
        try {
            screenOff(api.getNowScreenState() != STATE_SPECIAL);
        } catch (RemoteException e) {
            onBinderLost();
        }
    }

    private void onBinderLost() {
        iScreenOff = null;
        handler.post(() -> Toast.makeText(GlobalService.this, R.string.binder_lost, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!volume || event.getAction() == KeyEvent.ACTION_UP) return super.onKeyEvent(event);
        IScreenOff api = iScreenOff;
        if (api == null) return super.onKeyEvent(event);
        try {
            final int keycode = event.getKeyCode();
            final int nowState = api.getNowScreenState();
            if (keycode == scrOffKey && nowState == STATE_ON) {
                screenOff(true);
                return true;
            }
            if (keycode == scrOnKey && nowState == STATE_SPECIAL) {
                screenOff(false);
                return true;
            }
        } catch (RemoteException e) {
            onBinderLost();
        }
        return super.onKeyEvent(event);
    }

    private void updateBubbleVisibility() {
        if (view == null) return;
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        view.setVisibility(portrait && onlyLand ? View.GONE : View.VISIBLE);
    }

    public void floatWindow() {
        if (view == null) return;
        boolean show = sp.getBoolean("float", true);
        try {
            if (show && !exist) {
                windowManager.addView(view, params);
                exist = true;
            } else if (!show && exist) {
                windowManager.removeViewImmediate(view);
                exist = false;
            } else if (exist) {
                windowManager.updateViewLayout(view, params);
            }
        } catch (Exception e) {
            Log.w(TAG, "floatWindow failed", e);
            exist = false;
        }
    }

    void measureScreen() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean portrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT;
        updateBubbleVisibility();
        measureScreen();
        params.x = sp.getInt("x" + (portrait ? "1" : "2"), 0);
        params.y = sp.getInt("y" + (portrait ? "1" : "2"), 0);
        view.setBackground(ovalBackground());
        if (exist) {
            try {
                windowManager.updateViewLayout(view, params);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(myReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        stopKeepAliveWatch();
        releaseKeepAlive();
        // 注意：不清 want_special。服务被系统回收后如果还能重启，要靠它恢复息屏运行。
        try {
            if (exist) windowManager.removeViewImmediate(view);
        } catch (Exception ignored) {
        }
        exist = false;
        if (listener != null) listener.disable();
        if (sp != null) sp.unregisterOnSharedPreferenceChangeListener(this);
        stopServer();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 局域网控制

    public static int port = DEFAULT_PORT;
    private SimpleTcpServer server;

    private void startServer() {
        if (server != null) return;
        for (int i = 0; i < PORT_TRY_COUNT; i++) {
            int candidate = DEFAULT_PORT + i;
            SimpleTcpServer s = new SimpleTcpServer(this::handleRequest, candidate);
            if (s.start()) {
                server = s;
                port = s.getPort();
                Log.i(TAG, "http server started on " + port);
                return;
            }
        }
        Log.w(TAG, "no free port for http server");
        handler.post(() -> Toast.makeText(GlobalService.this, R.string.net_failed, Toast.LENGTH_SHORT).show());
    }

    private void stopServer() {
        SimpleTcpServer s = server;
        server = null;
        if (s != null) s.stop();
    }

    private byte[] handleRequest(byte[] raw) {
        HttpRequest request = HttpRequestParser.parse(raw);
        if (request == null) return Http.plain(400, "bad request");

        String path = request.getPath();
        if (path == null || path.isEmpty()) path = "/";

        switch (path) {
            case "/":
            case "/index.html":
                return Http.html(200, buildIndexHtml());
            case "/favicon.ico": {
                byte[] png = loadBinary("favicon.png");
                return png == null ? Http.plain(404, "not found") : Http.png(png);
            }
            case "/state":
                return Http.json(200, stateJson());
            case "/health":
                return Http.plain(200, "ok");
            case "/1":
            case "/screen/on":
                return actionResponse(false);
            case "/2":
            case "/screen/off":
                return actionResponse(true);
            default:
                return Http.html(404, build404Html());
        }
    }

    private byte[] actionResponse(boolean off) {
        boolean ok = requestPowerMode(off);
        return Http.json(ok ? 200 : 503,
                "{\"ok\":" + ok + ",\"state\":" + nowState() + "}");
    }

    private boolean requestPowerMode(boolean off) {
        IScreenOff api = iScreenOff;
        if (api == null) return false;
        screenOff(off);
        return true;
    }

    private int nowState() {
        IScreenOff api = iScreenOff;
        if (api == null) return -1;
        try {
            return api.getNowScreenState();
        } catch (RemoteException e) {
            onBinderLost();
            return -1;
        }
    }

    private String stateJson() {
        int state = nowState();
        String label;
        switch (state) {
            case STATE_OFF:
                label = "息屏";
                break;
            case STATE_ON:
                label = "亮屏";
                break;
            case STATE_SPECIAL:
                label = "息屏运行";
                break;
            default:
                label = "未连接";
                break;
        }
        return "{\"state\":" + state + ",\"label\":\"" + label + "\"}";
    }

    private static final int BUFFER_SIZE = 8 * 1024;
    private byte[] indexHtmlCache;
    private byte[] notFoundHtmlCache;

    private byte[] buildIndexHtml() {
        if (indexHtmlCache == null) {
            byte[] raw = loadBinary("index.html");
            if (raw == null) return Http.plain(500, "index.html missing");
            indexHtmlCache = raw;
        }
        String html = new String(indexHtmlCache, StandardCharsets.UTF_8)
                .replace("{{brand}}", Build.BRAND)
                .replace("{{device}}", Build.MODEL + " · Android " + Build.VERSION.RELEASE)
                .replace("{{state}}", stateJsonLabel())
                .replace("{{version}}", BuildConfig.VERSION_NAME);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private String stateJsonLabel() {
        switch (nowState()) {
            case STATE_OFF:
                return "息屏";
            case STATE_ON:
                return "亮屏";
            case STATE_SPECIAL:
                return "息屏运行";
            default:
                return "未连接";
        }
    }

    private byte[] build404Html() {
        if (notFoundHtmlCache == null) {
            byte[] raw = loadBinary("404.html");
            notFoundHtmlCache = raw == null ? "404".getBytes(StandardCharsets.UTF_8) : raw;
        }
        return notFoundHtmlCache;
    }

    private byte[] loadBinary(String fileName) {
        try (InputStream is = getAssets().open(fileName)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE);
            byte[] chunk = new byte[BUFFER_SIZE];
            int len;
            while ((len = is.read(chunk)) > 0) {
                out.write(chunk, 0, len);
            }
            return out.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "load asset failed: " + fileName, e);
            return null;
        }
    }
}
