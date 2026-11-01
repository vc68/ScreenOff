package com.tile.screenoff;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final String TAG = "ScreenOff";

    /** 激活命令最长等待时间（root 授权弹窗可能停留较久） */
    private static final long ACTIVATE_TIMEOUT_MS = 20000;
    /** 命令执行成功后，等待 Binder 连上的最长时间 */
    private static final long BINDER_WAIT_MS = 10000;

    private boolean isExpand = false, isServiceOK = false, isPermissionResultListenerRegistered = false;
    private boolean receiverRegistered = false, updatingSwitch = false, activating = false;
    private AlertDialog activateDialog;
    private int scrOffKey, scrOnKey;
    private IScreenOff iScreenOff = null;
    private SharedPreferences sp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            IBinder binder = null;
            try {
                BinderContainer container = getBinderExtra(intent);
                if (container != null) binder = container.getBinder();
            } catch (Throwable ignored) {
            }
            // binder 已经失去活性就不必继续解析
            if (binder == null || !binder.pingBinder()) return;
            iScreenOff = IScreenOff.Stub.asInterface(binder);
            enableScreenOffFunctions();
        }
    };

    @SuppressWarnings("deprecation")
    private static BinderContainer getBinderExtra(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra("binder", BinderContainer.class);
        }
        return intent.getParcelableExtra("binder");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.getAttributes().dimAmount = 0.5f;
        setContentView(R.layout.main);

        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_YES) == Configuration.UI_MODE_NIGHT_YES;
        applySystemBarColors(window, isNight);
        applyInsets();

        sp = getSharedPreferences("s", 0);
        if (sp.getBoolean("first", true)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.privacy)
                    .setMessage(R.string.privacypolicy)
                    .setNegativeButton(R.string.agree, (dialogInterface, i) -> {
                        help();
                        sp.edit().putBoolean("first", false).apply();
                    })
                    .setCancelable(false)
                    .setPositiveButton(R.string.disagree, (dialogInterface, i) -> finish())
                    .show();
        }

        setButtonsOnclick(isNight, sp);
        registerBinderReceiver();
    }

    /** 让内容避开状态栏 / 导航栏。targetSdk 35 起系统强制边到边，不做处理会被挡住。 */
    private void applyInsets() {
        final View root = findViewById(R.id.root);
        if (root == null) return;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void applySystemBarColors(Window window, boolean isNight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int barColor;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            barColor = landscape ? Color.TRANSPARENT : getColor(isNight ? R.color.bgBlack : R.color.bgWhite);
        } else {
            barColor = landscape ? Color.TRANSPARENT : (isNight ? 0xff303034 : 0xffe4e2e6);
        }
        window.setNavigationBarColor(barColor);
        window.setStatusBarColor(Color.TRANSPARENT);
    }

    private void registerBinderReceiver() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(mBroadcastReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        IntentFilter filter = new IntentFilter(GlobalService.ACTION_SEND_BINDER);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mBroadcastReceiver, filter, RECEIVER_EXPORTED);
            } else {
                registerReceiver(mBroadcastReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    /** 服务刚被拉起时，重新注册一次就能收到粘性广播里的 Binder。 */
    private void refreshBinder() {
        registerBinderReceiver();
        handler.postDelayed(this::syncSwitchState, 600);
    }

    private void syncSwitchState() {
        if (!isServiceOK || iScreenOff == null) return;
        try {
            int state = iScreenOff.getNowScreenState();
            Switch aSwitch = findViewById(R.id.screenoff_switch);
            updatingSwitch = true;
            aSwitch.setChecked(state == GlobalService.STATE_SPECIAL);
            updatingSwitch = false;
        } catch (RemoteException e) {
            iScreenOff = null;
        }
    }

    private void showNet() {
        StringBuilder sb = new StringBuilder();
        String firstUrl = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || !(address instanceof Inet4Address)) continue;
                    String url = "http://" + address.getHostAddress() + ":" + GlobalService.port;
                    if (firstUrl == null) firstUrl = url;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(networkInterface.getName()).append(": ")
                            .append(address.getHostAddress()).append(':').append(GlobalService.port);
                }
            }
        } catch (Exception ignored) {
        }
        TextView textView = findViewById(R.id.title_text);
        textView.setOnClickListener(null);
        if (firstUrl != null) {
            final String url = firstUrl;
            textView.setText(sb.toString());
            textView.setOnLongClickListener(v -> {
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("url", url));
                Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show();
                return true;
            });
        } else {
            textView.setText(R.string.no_network);
        }
    }

    private void setButtonsOnclick(boolean isNight, SharedPreferences sp) {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            findViewById(R.id.left).setVisibility(View.VISIBLE);
            findViewById(R.id.right).setVisibility(View.VISIBLE);
        }
        LinearLayout linearLayout = findViewById(R.id.ll);
        Switch s1 = findViewById(R.id.s1);
        Switch s2 = findViewById(R.id.s2);
        Switch s3 = findViewById(R.id.s3);
        Switch s4 = findViewById(R.id.s4);
        Switch s5 = findViewById(R.id.s5);
        Switch s6 = findViewById(R.id.s6);
        Switch s7 = findViewById(R.id.s7);
        Switch s8 = findViewById(R.id.s8);

        final String setting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        s1.setChecked(setting != null && setting.contains(getPackageName()));
        s2.setChecked(sp.getBoolean("float", true));
        s3.setChecked(sp.getBoolean("land", false));
        s4.setChecked(!sp.getBoolean("canmove", true));
        s5.setChecked(sp.getBoolean("doubleTap", false));
        s6.setChecked(sp.getBoolean("shake", false));
        s7.setChecked(sp.getBoolean("volume", false));
        s8.setChecked(sp.getBoolean("net", false));
        s3.setEnabled(s2.isChecked());
        s4.setEnabled(s2.isChecked());
        s5.setEnabled(s2.isChecked());

        SeekBar sb = findViewById(R.id.sb);
        SeekBar sc = findViewById(R.id.sc);
        SeekBar sd = findViewById(R.id.sd);
        bindSlider(sb, (EditText) findViewById(R.id.eb), "size", 50, 20, 100);
        bindSlider(sc, (EditText) findViewById(R.id.ec), "tran", 90, 10, 100);
        bindSlider(sd, (EditText) findViewById(R.id.ed), "sensity", 10, 1, 30);
        sb.setEnabled(s2.isChecked());
        sc.setEnabled(s2.isChecked());
        sd.setEnabled(s2.isChecked());

        s1.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (!isServiceOK) {
                compoundButton.setChecked(false);
                Toast.makeText(MainActivity.this, R.string.active_first, Toast.LENGTH_SHORT).show();
                return;
            }
            if (isChecked) {
                enableAccessibilityService(compoundButton);
                if (s8.isChecked()) showNet();
            } else {
                ((TextView) findViewById(R.id.title_text)).setText(R.string.shortcutoff);
                sendBroadcast(new Intent(GlobalService.ACTION_EXIT));
            }
        });
        s2.setOnCheckedChangeListener((compoundButton, b) -> {
            sp.edit().putBoolean("float", b).apply();
            s3.setEnabled(b);
            s4.setEnabled(b);
            s5.setEnabled(b);
            sb.setEnabled(b);
            sc.setEnabled(b);
            sd.setEnabled(b);
        });
        s3.setOnCheckedChangeListener((compoundButton, b) -> sp.edit().putBoolean("land", b).apply());
        s4.setOnCheckedChangeListener((compoundButton, b) -> sp.edit().putBoolean("canmove", !b).apply());
        s5.setOnCheckedChangeListener((compoundButton, b) -> sp.edit().putBoolean("doubleTap", b).apply());
        s6.setOnCheckedChangeListener((compoundButton, b) -> sp.edit().putBoolean("shake", b).apply());
        s7.setOnCheckedChangeListener((compoundButton, b) -> {
            sp.edit().putBoolean("volume", b).apply();
            findViewById(R.id.e1).setEnabled(b);
            findViewById(R.id.e2).setEnabled(b);
        });
        s8.setOnCheckedChangeListener((compoundButton, b) -> {
            sp.edit().putBoolean("net", b).apply();
            if (s1.isChecked()) {
                if (b) handler.postDelayed(this::showNet, 300);
                else ((TextView) findViewById(R.id.title_text)).setText(R.string.shortcutoff);
            }
        });
        if (s1.isChecked() && s8.isChecked()) handler.postDelayed(this::showNet, 300);

        EditText e1 = findViewById(R.id.e1);
        EditText e2 = findViewById(R.id.e2);
        e1.setEnabled(s7.isChecked());
        e2.setEnabled(s7.isChecked());
        scrOffKey = sp.getInt("scrOffKey", 25);
        scrOnKey = sp.getInt("scrOnKey", 24);
        e1.setText(String.valueOf(scrOffKey));
        e2.setText(String.valueOf(scrOnKey));
        bindKeyCodeField(e1, "scrOffKey", 25, value -> scrOffKey = value);
        bindKeyCodeField(e2, "scrOnKey", 24, value -> scrOnKey = value);

        findViewById(R.id.title_text).setOnClickListener(view -> help());

        float density = getResources().getDisplayMetrics().density;
        findViewById(R.id.activate_button).setOnClickListener(view -> showActivate());
        ShapeDrawable oval = new ShapeDrawable(new RoundRectShape(
                new float[]{30 * density, 30 * density, 30 * density, 30 * density, 0, 0, 0, 0}, null, null));
        oval.getPaint().setColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? getColor(isNight ? R.color.bgBlack : R.color.bgWhite)
                : (isNight ? 0xff303034 : 0xffe4e2e6));
        linearLayout.setBackground(oval);
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(400L);
        ObjectAnimator animator = ObjectAnimator.ofFloat(null, "scaleX", 0.0f, 1.0f);
        transition.setAnimator(LayoutTransition.APPEARING, animator);
        linearLayout.setLayoutTransition(transition);

        ScrollView scrollView = findViewById(R.id.sv);
        Switch aSwitch = findViewById(R.id.screenoff_switch);
        aSwitch.setOnCheckedChangeListener((compoundButton, b) -> {
            if (!isServiceOK || updatingSwitch || iScreenOff == null) return;
            try {
                iScreenOff.setPowerMode(!b);
            } catch (RemoteException e) {
                iScreenOff = null;
                isServiceOK = false;
                Toast.makeText(this, R.string.binder_lost, Toast.LENGTH_SHORT).show();
            }
        });

        ImageView imageView = findViewById(R.id.iv);
        final View.OnClickListener onClickListener = view -> {
            if (isExpand) {
                linearLayout.removeView(scrollView);
                ObjectAnimator a2 = ObjectAnimator.ofFloat(imageView, "rotation", 180f, 360f);
                a2.setDuration(800).setInterpolator(new AccelerateDecelerateInterpolator());
                a2.start();
            } else {
                linearLayout.addView(scrollView);
                ObjectAnimator a2 = ObjectAnimator.ofFloat(imageView, "rotation", 0f, 180f);
                a2.setDuration(800).setInterpolator(new AccelerateDecelerateInterpolator());
                a2.start();
            }
            isExpand = !isExpand;
        };
        imageView.setOnClickListener(onClickListener);
        linearLayout.setOnClickListener(onClickListener);
        findViewById(R.id.lll).setOnClickListener(onClickListener);
        linearLayout.removeView(scrollView);
    }

    private void enableAccessibilityService(CompoundButton compoundButton) {
        final String serviceName = new ComponentName(getPackageName(), GlobalService.class.getName()).flattenToString();
        final String oldSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (oldSetting != null && oldSetting.contains(serviceName)) return;
        final String newSetting = oldSetting == null ? serviceName : serviceName + ":" + oldSetting;
        try {
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newSetting);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !((PowerManager) getSystemService(Service.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) {
            compoundButton.setChecked(false);
            Toast.makeText(MainActivity.this, R.string.mannually_open, Toast.LENGTH_SHORT).show();
            Bundle bundle = new Bundle();
            bundle.putString(":settings:fragment_args_key", serviceName);
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", serviceName)
                    .putExtra(":settings:show_fragment_args", bundle));
        }
    }

    /** 拖动条 + 输入框联动，输入非法时不再崩溃（旧版本会抛 NumberFormatException）。 */
    private void bindSlider(SeekBar bar, EditText box, String key, int def, int min, int max) {
        bar.setMax(max);
        int value = clamp(sp.getInt(key, def), min, max);
        bar.setProgress(value);
        box.setText(String.valueOf(value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = clamp(progress, min, max);
                sp.edit().putInt(key, v).apply();
                box.setText(String.valueOf(v));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.setOnKeyListener((view, keyCode, keyEvent) -> {
            if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                int v = clamp(readInt(box, bar.getProgress()), min, max);
                sp.edit().putInt(key, v).apply();
                bar.setProgress(v);
                box.setText(String.valueOf(v));
            }
            return false;
        });
    }

    private void bindKeyCodeField(EditText box, String key, int def, IntSetter setter) {
        box.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                String text = charSequence.toString().trim();
                if (text.isEmpty()) return;
                int value = clamp(parse(text, def), 0, 999);
                setter.set(value);
                sp.edit().putInt(key, value).apply();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
    }

    private interface IntSetter {
        void set(int value);
    }

    private static int parse(String text, int def) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int readInt(EditText box, int def) {
        return parse(box.getText().toString().trim(), def);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void enableScreenOffFunctions() {
        isServiceOK = true;
        Button button = findViewById(R.id.activate_button);
        button.setText(getString(R.string.all_ok));
        button.setTextColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(R.color.right) : 0x00000000);
        button.setOnClickListener(null);
        button.setOnLongClickListener(view -> {
            try {
                sendBroadcast(new Intent(GlobalService.ACTION_EXIT));
                if (iScreenOff != null) iScreenOff.closeAndExit();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            Toast.makeText(this, R.string.service_closed, Toast.LENGTH_SHORT).show();
            finish();
            return false;
        });
        Switch aSwitch = findViewById(R.id.screenoff_switch);
        aSwitch.setEnabled(true);
        syncSwitchState();
    }

    @Override
    public void onBackPressed() {
        if (isExpand) {
            findViewById(R.id.iv).performClick();
        } else {
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        super.onKeyDown(keyCode, event);
        if (isExpand) {
            Toast.makeText(this, String.format(Locale.getDefault(), getString(R.string.key_pressed),
                    KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", ""), keyCode), Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!isServiceOK) return true;
        Switch aSwitch = findViewById(R.id.screenoff_switch);
        if (keyCode == scrOffKey) aSwitch.setChecked(true);
        if (keyCode == scrOnKey) aSwitch.setChecked(false);
        return true;
    }

    private final Shizuku.OnRequestPermissionResultListener RL = (requestCode, grantResult) -> check();

    private void ensurePermissionListener() {
        if (isPermissionResultListenerRegistered) return;
        Shizuku.addRequestPermissionResultListener(RL);
        isPermissionResultListenerRegistered = true;
    }

    /** 检查并申请 Shizuku 权限，拿到权限后用 Shizuku 启动 shell 脚本。 */
    private void check() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, R.string.shizuku_need_m, Toast.LENGTH_SHORT).show();
            return;
        }
        ensurePermissionListener();
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, R.string.shizuku_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.need_active)
                            .setMessage(R.string.shizuku_denied)
                            .setPositiveButton(R.string.understand, (d, i) -> Shizuku.requestPermission(0))
                            .setNegativeButton(R.string.disagree, null)
                            .show();
                } else {
                    Shizuku.requestPermission(0);
                }
                return;
            }
            activateByShizuku();
        } catch (IllegalStateException e) {
            Toast.makeText(this, R.string.shizuku_notrun, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // 老版本 Shizuku（pre-v11）没有 checkSelfPermission，退回到直接检查权限
            try {
                if (checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED) {
                    activateByShizuku();
                } else {
                    Shizuku.requestPermission(0);
                }
            } catch (Throwable ignored) {
                Toast.makeText(this, R.string.shizuku_missing, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void activateByShizuku() {
        final String script = ensureScriptReady();
        if (script == null) return;
        runActivate("shizuku", () -> {
            if (!Shizuku.pingBinder()) throw new IllegalStateException(getString(R.string.shizuku_notrun));
            Process process = newShizukuProcess(new String[]{"sh", script});
            if (process == null) throw new IOException("newProcess 返回 null");
            return Shell.waitAndCollect(process, ACTIVATE_TIMEOUT_MS);
        });
    }

    /**
     * Shizuku 13.1.5 起把 newProcess 改成了私有方法，这里用反射调用，
     * 拿到的 ShizukuRemoteProcess 本身就是 Process 的子类。
     * 先按 public 找，找不到再按 declared 找，兼容新旧版本。
     */
    private static Process newShizukuProcess(String[] command) throws Exception {
        Method method;
        try {
            method = Shizuku.class.getMethod("newProcess", String[].class, String[].class, String.class);
        } catch (NoSuchMethodException e) {
            method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        return (Process) method.invoke(null, command, null, null);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            findViewById(R.id.left).setVisibility(View.VISIBLE);
            findViewById(R.id.right).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.left).setVisibility(View.GONE);
            findViewById(R.id.right).setVisibility(View.GONE);
        }
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (isPermissionResultListenerRegistered) {
            try {
                Shizuku.removeRequestPermissionResultListener(RL);
            } catch (Throwable ignored) {
            }
            isPermissionResultListenerRegistered = false;
        }
        if (receiverRegistered) {
            try {
                unregisterReceiver(mBroadcastReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    public void finish(View view) {
        finish();
    }

    public void help() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(getString(R.string.help_conntent) + "\n\n" + getString(R.string.version_info, BuildConfig.VERSION_NAME))
                .setNegativeButton(R.string.understand, null)
                .show();
    }

    public void showActivate() {
        final String script = ensureScriptReady();
        if (script == null) return;
        final String command = "sh " + script;
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this)
                .setMessage(String.format(getString(R.string.active_steps), command))
                .setTitle(R.string.need_active)
                .setNeutralButton(R.string.copy_cmd, (dialogInterface, i) -> {
                    ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("c", "adb shell " + command));
                    Toast.makeText(MainActivity.this, String.format(getString(R.string.cmd_copy_finish), command), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.by_root, (dialoginterface, i) -> activateByRoot(command));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setPositiveButton(R.string.by_shizuku, (dialogInterface, i) -> check());
        }
        activateDialog = builder.show();
    }

    /** root 激活：优先 su -c；部分 su 实现不支持 -c（返回非 0），再用 stdin 喂命令重试一次。 */
    private void activateByRoot(final String command) {
        runActivate("root", () -> runAsRoot(command));
    }

    private String runAsRoot(String command) throws Exception {
        IOException firstError;
        try {
            Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            return Shell.waitAndCollect(p, ACTIVATE_TIMEOUT_MS);
        } catch (Shell.Timeout e) {
            // 超时说明 su 正在等用户授权，不要重试，否则会连弹两次授权框
            throw e;
        } catch (IOException e) {
            // 只有「su 不认识 -c」才值得换一种方式重试；
            // 用户点了「拒绝」也会返回非 0，那种情况必须直接失败，不能再来一次。
            if (!looksLikeUnsupportedOption(e.getMessage())) throw e;
            firstError = e;
            Log.w(TAG, "su -c 不被支持，改用 stdin 重试", e);
        }

        Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
        try (OutputStream os = p.getOutputStream()) {
            os.write((command + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        try {
            return Shell.waitAndCollect(p, ACTIVATE_TIMEOUT_MS);
        } catch (IOException second) {
            throw new IOException(describe(firstError) + "\n--- 重试 ---\n" + second.getMessage());
        }
    }

    private static boolean looksLikeUnsupportedOption(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.US);
        return m.contains("invalid option") || m.contains("unknown option") || m.contains("usage");
    }

    // ------------------------------------------------------------------ 激活流程

    private interface ShellRunner {
        String run() throws Exception;
    }

    /**
     * 执行激活命令并**真正校验结果**：
     * 1. 后台线程跑命令，拿退出码和输出；
     * 2. 退出码非 0 或超时，直接失败并展示原始输出；
     * 3. 退出码为 0 还要等 Binder 真的连上才算成功（最多 10 秒）。
     */
    private void runActivate(final String label, final ShellRunner runner) {
        if (activating) {
            Toast.makeText(this, R.string.activating, Toast.LENGTH_SHORT).show();
            return;
        }
        activating = true;
        Toast.makeText(this, R.string.activating, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String detail;
            boolean commandOk;
            try {
                String output = runner.run();
                commandOk = true;
                detail = output;
                Log.i(TAG, "activate[" + label + "] output: " + output);
            } catch (Throwable t) {
                commandOk = false;
                detail = describe(t);
                Log.w(TAG, "activate[" + label + "] failed", t);
            }
            final boolean ok = commandOk;
            final String message = detail;
            handler.post(() -> {
                if (ok) {
                    awaitBinderThenFinish(System.currentTimeMillis() + BINDER_WAIT_MS, message);
                } else {
                    activating = false;
                    showActivateFailure(message);
                }
            });
        }, "activate-" + label).start();
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
        if (t instanceof IOException && msg.contains("Cannot run program")) {
            return "su 不可用（设备未 root 或未授予 root 权限）";
        }
        return msg;
    }

    /** 命令成功后轮询 Binder，直到服务真正连上或超时。 */
    private void awaitBinderThenFinish(final long deadline, final String output) {
        if (isServiceOK) {
            onActivateSuccess();
            return;
        }
        if (System.currentTimeMillis() > deadline) {
            activating = false;
            showActivateFailure(getString(R.string.activate_timeout) + Shell.appendOutput(output));
            return;
        }
        registerBinderReceiver(); // 重新注册可再次收到粘性广播里的 Binder
        handler.postDelayed(() -> awaitBinderThenFinish(deadline, output), 500);
    }

    private void onActivateSuccess() {
        activating = false;
        if (activateDialog != null && activateDialog.isShowing()) {
            activateDialog.dismiss();
        }
        Toast.makeText(this, R.string.active_success, Toast.LENGTH_SHORT).show();
        refreshBinder();
    }

    private void showActivateFailure(String detail) {
        String message = (detail == null || detail.trim().isEmpty())
                ? getString(R.string.active_failed_tip) : detail;
        new AlertDialog.Builder(this)
                .setTitle(R.string.active_failed)
                .setMessage(message)
                .setPositiveButton(R.string.understand, null)
                .show();
    }

    /** 释放脚本和 dex，返回 starter.sh 的绝对路径；失败返回 null 并提示。 */
    private String ensureScriptReady() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            Toast.makeText(this, R.string.active_failed_tip, Toast.LENGTH_SHORT).show();
            return null;
        }
        unzipFiles();
        File script = new File(dir, "starter.sh");
        File dex = new File(dir, "ScreenController.dex");
        if (!script.exists() || !dex.exists() || dex.length() == 0) {
            Toast.makeText(this, R.string.active_failed_tip, Toast.LENGTH_SHORT).show();
            return null;
        }
        return script.getAbsolutePath();
    }

    /** 把 starter.sh 和 classes.dex 释放到外部目录，供 shell 读取。 */
    private void unzipFiles() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;

        File scriptFile = new File(dir, "starter.sh");
        try (InputStream is = getAssets().open("starter.sh")) {
            copyShellScript(is, scriptFile);
        } catch (IOException ignored) {
        }

        File dexFile = new File(dir, "ScreenController.dex");
        try (ZipFile zipFile = new ZipFile(getPackageResourcePath())) {
            ZipEntry entry = zipFile.getEntry("classes.dex");
            if (entry == null) return;
            // 大小一致就跳过，省掉每次打开界面的重复写入
            if (dexFile.exists() && dexFile.length() == entry.getSize()) return;
            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                copy(inputStream, dexFile);
            }
        } catch (IOException ignored) {
        }
    }

    private static void copy(InputStream is, File target) throws IOException {
        try (OutputStream os = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            os.flush();
        }
    }

    /**
     * 写激活脚本时把换行统一成 LF。
     * <p>
     * Windows 上 core.autocrlf=true 会把 assets 里的脚本改写成 CRLF，设备端 mksh 会报
     * "syntax error: unmatched 'if'" 和 ": inaccessible or not found"。
     * 除了 .gitattributes 约束仓库，这里再做一层兜底，确保发出去的脚本一定是 LF。
     */
    private static void copyShellScript(InputStream is, File target) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int len;
        while ((len = is.read(chunk)) > 0) {
            buffer.write(chunk, 0, len);
        }
        byte[] data = buffer.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\r' && i + 1 < data.length && data[i + 1] == '\n') continue;
            out.write(data[i]);
        }
        try (OutputStream os = new FileOutputStream(target)) {
            os.write(out.toByteArray());
            os.flush();
        }
    }
}
