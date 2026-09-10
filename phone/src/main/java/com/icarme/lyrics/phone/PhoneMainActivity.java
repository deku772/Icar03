package com.icarme.lyrics.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 手机端主界面（v2.2 深色卡片风格）：
 * 状态卡片（全链路指示灯）+ 权限/服务按钮 + 歌词预览卡。
 *
 * v2.0 角色对调后手机为被动 GATT 服务端：无需选择车机，
 * 车机按 IcarLyrics 服务 UUID 扫描发现手机并连接。
 */
public class PhoneMainActivity extends Activity {

    private static final int REQ_PERMS = 1;

    /* 深色主题色板（与车机端一致） */
    private static final int C_BG        = 0xFF0B0F17;
    private static final int C_TEXT      = 0xFFEFF2F8;
    private static final int C_TEXT_DIM  = 0xFF8A93A8;
    private static final int C_PRIMARY   = 0xFF5B93F0;
    private static final int C_GREEN     = 0xFF4ADE80;
    private static final int C_RED       = 0xFFF87171;
    private static final int C_AMBER     = 0xFFFBBF24;

    private static final int ROW_COUNT = 8;
    private static final int R_NOTIF = 0, R_SERVICE = 1, R_BLE = 2, R_LISTENER = 3,
            R_MEDIA = 4, R_TRACK = 5, R_FETCH = 6, R_STATS = 7;
    private final View[] rowDots = new View[ROW_COUNT];
    private final TextView[] rowValues = new TextView[ROW_COUNT];

    private Button btnService;
    private TextView tvLrc;
    private TextView tvCrash;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(28), pad, pad);
        scroll.addView(root);

        root.addView(buildHeader());
        root.addView(buildStatusCard());
        root.addView(buildButtons());
        root.addView(buildLrcCard());
        setContentView(scroll);

        /* 实时监控：每秒刷新链路状态 */
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                refreshStatus();
                ui.postDelayed(this, 1000);
            }
        }, 500);
    }

    /* ---------------- 界面构建 ---------------- */

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable badge = new GradientDrawable();
        badge.setCornerRadius(dp(16));
        badge.setColor(0xFF101725);
        badge.setStroke(dp(1), 0xFF232C42);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setBackground(badge);
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.rightMargin = dp(14);
        logo.setLayoutParams(lp);
        h.addView(logo);

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("IcarLyrics");
        title.setTextColor(C_TEXT);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText("手机端 · 取词与 BLE 推送");
        sub.setTextColor(C_TEXT_DIM);
        sub.setTextSize(13);
        t.addView(title);
        t.addView(sub);
        h.addView(t);
        return h;
    }

    private View buildStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        card.setLayoutParams(lp);

        String[] labels = {"通知使用权", "推送服务", "BLE", "监听服务", "媒体检测", "当前曲目", "取词", "推送统计"};
        for (int i = 0; i < ROW_COUNT; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));

            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0xFF4B5563);
            row.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(C_TEXT_DIM);
            label.setTextSize(13);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    dp(86), LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = dp(12);
            label.setLayoutParams(llp);
            row.addView(label);

            TextView value = new TextView(this);
            value.setTextColor(C_TEXT);
            value.setTextSize(13);
            value.setSingleLine(false);
            value.setMaxLines(2);
            row.addView(value, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            rowDots[i] = dot;
            rowValues[i] = value;
            card.addView(row);
        }
        return card;
    }

    private View buildButtons() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        box.setLayoutParams(blp);

        Button btnPerms = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnPerms.setText("1 · 授权（蓝牙 / 通知使用权）");
        styleButton(btnPerms, R.drawable.btn_primary, 0xFFFFFFFF);
        btnPerms.setOnClickListener(v -> requestPermissions());
        box.addView(btnPerms);

        Button btnMac = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnMac.setText("2 · 查看本机 MAC");
        styleButton(btnMac, R.drawable.btn_ghost, C_PRIMARY);
        btnMac.setOnClickListener(v -> showOwnMac());
        box.addView(btnMac);

        btnService = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnService.setText("3 · 启动推送服务");
        styleButton(btnService, R.drawable.btn_ghost, C_PRIMARY);
        btnService.setOnClickListener(v -> toggleService());
        box.addView(btnService);
        return box;
    }

    private void styleButton(Button b, int bg, int textColor) {
        b.setBackgroundResource(bg);
        b.setTextColor(textColor);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
    }

    private View buildLrcCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        card.setLayoutParams(lp);

        TextView cap = new TextView(this);
        cap.setText("歌词预览（取词结果前几行）");
        cap.setTextColor(C_TEXT_DIM);
        cap.setTextSize(12);
        card.addView(cap);

        tvLrc = new TextView(this);
        tvLrc.setTextColor(C_TEXT);
        tvLrc.setTextSize(15);
        tvLrc.setLineSpacing(dp(3), 1f);
        tvLrc.setPadding(0, dp(8), 0, 0);
        card.addView(tvLrc);

        tvCrash = new TextView(this);
        tvCrash.setTextColor(C_RED);
        tvCrash.setTextSize(12);
        tvCrash.setPadding(0, dp(6), 0, 0);
        tvCrash.setVisibility(View.GONE);
        card.addView(tvCrash);
        return card;
    }

    /* ---------------- 状态刷新 ---------------- */

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void setDot(int row, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        rowDots[row].setBackground(d);
    }

    private void refreshStatus() {
        try {
            PlaybackService.refreshWriteStats();
        } catch (Exception ignored) {}

        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        boolean nl = enabled != null && enabled.contains(getPackageName());

        PlaybackService.Monitor m = PlaybackService.mon;
        boolean running = PlaybackService.running;

        setDot(R_NOTIF, nl ? C_GREEN : C_RED);
        rowValues[R_NOTIF].setText(nl ? "已授权" : "未授权（点「1 · 授权」）");

        setDot(R_SERVICE, running ? C_GREEN : 0xFF4B5563);
        rowValues[R_SERVICE].setText(running ? "运行中" : "已停止");

        String ble = running ? m.bleState : "未启动";
        setDot(R_BLE, ble.contains("已连接") ? C_GREEN
                : ble.contains("订阅") || ble.contains("连接") ? C_PRIMARY : C_AMBER);
        rowValues[R_BLE].setText(ble);

        setDot(R_LISTENER, running && m.listenerState.contains("已绑定") ? C_GREEN : C_AMBER);
        rowValues[R_LISTENER].setText(running && !m.listenerState.isEmpty() ? m.listenerState : "—");

        rowValues[R_MEDIA].setText(running && !m.diag.isEmpty() ? m.diag : "—");
        setDot(R_MEDIA, running && m.diag.contains("播放中") ? C_GREEN : 0xFF4B5563);

        if (!m.track.isEmpty()) {
            setDot(R_TRACK, C_GREEN);
            rowValues[R_TRACK].setText(m.track + (m.artist.isEmpty() ? "" : " · " + m.artist));
        } else {
            setDot(R_TRACK, 0xFF4B5563);
            rowValues[R_TRACK].setText("—");
        }

        setDot(R_FETCH, m.fetchState.startsWith("已获取") ? C_GREEN
                : m.fetchState.equals("未找到") ? C_AMBER : 0xFF4B5563);
        rowValues[R_FETCH].setText(m.fetchState.isEmpty() ? "—" : m.fetchState
                + (m.fetchSource.isEmpty() ? "" : "（" + m.fetchSource + "）"));

        rowValues[R_STATS].setText("歌词 " + m.pushCount + " · 进度 " + m.progressCount
                + " · ACK " + m.writeAck
                + (m.writeFail > 0 ? " · 失败 " + m.writeFail : ""));
        setDot(R_STATS, m.writeFail > 0 ? C_AMBER
                : m.pushCount > 0 || m.progressCount > 0 ? C_GREEN : 0xFF4B5563);

        String pv = m.lrcPreview;
        tvLrc.setText(pv.isEmpty() ? "（放歌后此处显示取到的歌词前几行）" : pv);
        tvLrc.setTextColor(pv.isEmpty() ? C_TEXT_DIM : C_TEXT);

        String crash = IcarPhoneApp.readCrash();
        if (crash != null) {
            tvCrash.setVisibility(View.VISIBLE);
            tvCrash.setText("[上次崩溃] " + firstLine(crash));
        }

        btnService.setText(running ? "3 · 停止推送服务" : "3 · 启动推送服务");
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    /* ---------------- 操作 ---------------- */

    private void showOwnMac() {
        String mac = PlaybackService.getOwnMac();
        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,
                android.R.style.Theme_Material_Dialog))
                .setTitle("本机蓝牙 MAC")
                .setMessage((mac == null ? "未读取到（请先开启蓝牙）" : mac)
                        + "\n\n车机端通常无需手填：车机自动扫描发现手机即可连接。\n"
                        + "如需诊断，可在车机执行：\n"
                        + "am broadcast -a com.icarme.lyrics.SET_PHONE --es mac " + (mac == null ? "<本机MAC>" : mac))
                .setPositiveButton("好", null)
                .show();
    }

    private void requestPermissions() {
        String[] perms = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        boolean need = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { need = true; break; }
        }
        if (need) {
            requestPermissions(perms, REQ_PERMS);
        }
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception ignored) {}
    }

    private void toggleService() {
        if (PlaybackService.running) {
            stopService(new Intent(this, PlaybackService.class));
        } else {
            startForegroundService(new Intent(this, PlaybackService.class));
        }
        ui.postDelayed(this::refreshStatus, 150);
        ui.postDelayed(this::refreshStatus, 500);
        ui.postDelayed(this::refreshStatus, 1200);
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }
}
