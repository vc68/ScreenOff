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

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private ImageView view;
    private View keepAliveView;
    private WindowManager.LayoutParams keepAliveParams;
    private boolean keepAliveAttached;
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
                    break;
                }
                case Intent.ACTION_SCREEN_OFF: {
                    IScreenOff api = iScreenOff;
                    if (api != null) {
                        try {
                            api.updateNowScreenState(false);
                        } catch (RemoteException e) {
                            onBinderLost();
                        }
                    }
                    rememberState(STATE_OFF);
                    setKeepAwake(false);
                    if (shake) listener.disable();
                    break;
                }
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_USER_PRESENT: {
                    IScreenOff api = iScreenOff;
                    if (api != null) {
                        try {
                            api.updateNowScreenState(true);
                        } catch (RemoteException e) {
                            onBinderLost();
                        }
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
        keepAliveParams = new WindowManager.LayoutParams(1, 1,
                Build.VERSION.SDK_INT >= 22 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);

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

    /** 不依赖悬浮球也能保持系统不自动休眠（旧版本关掉悬浮球后息屏运行会被系统睡死）。 */
    private void setKeepAwake(boolean on) {
        if (windowManager == null) return;
        try {
            if (on) {
                if (!keepAliveAttached) {
                    windowManager.addView(keepAliveView, keepAliveParams);
                    keepAliveAttached = true;
                }
                keepAliveView.setKeepScreenOn(true);
            } else {
                if (keepAliveAttached) {
                    keepAliveView.setKeepScreenOn(false);
                    windowManager.removeViewImmediate(keepAliveView);
                    keepAliveAttached = false;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "setKeepAwake failed", e);
            keepAliveAttached = false;
        }
    }

    void screenOff(boolean off) {
        IScreenOff api = iScreenOff;
        if (api == null) return;
        try {
            // 0 = 用户真的按了电源键息屏，此时不需要（也不能）再做任何事
            if (api.getNowScreenState() == STATE_OFF) return;
            api.setPowerMode(off);
            rememberState(off ? STATE_SPECIAL : STATE_ON);
            setKeepAwake(off);
            if (shake) {
                if (off) listener.enable();
                else listener.disable();
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
        setKeepAwake(false);
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
