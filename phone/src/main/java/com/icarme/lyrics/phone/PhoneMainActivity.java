package com.icarme.lyrics.phone;

import android.Manifest;
import android.app.Activity;
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
 * 状态卡片（全链路指示灯）+ 权限/服务按钮 + 滚动歌词预览。
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

        ui.postDelayed(() -> UpdateChecker.checkAndPrompt(this,
                BuildConfig.VERSION_NAME, true), 2500);

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

        btnService = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnService.setText("2 · 启动推送服务");
        styleButton(btnService, R.drawable.btn_ghost, C_PRIMARY);
        btnService.setOnClickListener(v -> toggleService());
        box.addView(btnService);

        Button btnUpd = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnUpd.setText("3 · 检查更新（GitHub）");
        styleButton(btnUpd, R.drawable.btn_ghost, C_PRIMARY);
        btnUpd.setOnClickListener(v -> UpdateChecker.checkAndPrompt(this,
                BuildConfig.VERSION_NAME, false));
        box.addView(btnUpd);
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

    /* ---------------- 歌词滚动卡（跟随播放高亮） ---------------- */

    private static final class LrcLine {
        final long t; final String txt; final TextView view;
        LrcLine(long t, String txt, TextView view) { this.t = t; this.txt = txt; this.view = view; }
    }

    private ScrollView svLrc;
    private LinearLayout lrcBox;
    private TextView tvLrcEmpty;
    private TextView tvOffset;
    private final java.util.List<LrcLine> lrcLines = new java.util.ArrayList<>();
    private String lrcShown = " ";
    private int lrcCur = -1;

    private void styleMiniButton(Button b) {
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setTextColor(C_PRIMARY);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(36));
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
    }

    private View buildLrcCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(18), dp(14), dp(18), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        card.setLayoutParams(lp);

        LinearLayout capRow = new LinearLayout(this);
        capRow.setOrientation(LinearLayout.HORIZONTAL);
        capRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView cap = new TextView(this);
        cap.setText("歌词（跟随播放滚动）");
        cap.setTextColor(C_TEXT_DIM);
        cap.setTextSize(12);
        capRow.addView(cap, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvOffset = new TextView(this);
        tvOffset.setTextColor(C_TEXT_DIM);
        tvOffset.setTextSize(12);
        capRow.addView(tvOffset);
        Button later = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        later.setText("延后");
        styleMiniButton(later);
        later.setOnClickListener(v -> {
            PlaybackService.adjustOffsetMs(-PlaybackService.OFFSET_STEP_MS);
            refreshStatus();
        });
        capRow.addView(later);
        Button earlier = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        earlier.setText("提前");
        styleMiniButton(earlier);
        earlier.setOnClickListener(v -> {
            PlaybackService.adjustOffsetMs(PlaybackService.OFFSET_STEP_MS);
            refreshStatus();
        });
        capRow.addView(earlier);
        card.addView(capRow);

        svLrc = new ScrollView(this);
        lrcBox = new LinearLayout(this);
        lrcBox.setOrientation(LinearLayout.VERTICAL);
        svLrc.addView(lrcBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230));
        slp.topMargin = dp(6);
        svLrc.setLayoutParams(slp);
        card.addView(svLrc);

        tvLrcEmpty = new TextView(this);
        tvLrcEmpty.setTextColor(C_TEXT_DIM);
        tvLrcEmpty.setTextSize(14);
        tvLrcEmpty.setGravity(Gravity.CENTER);
        tvLrcEmpty.setPadding(0, dp(70), 0, dp(70));
        tvLrcEmpty.setText("（放歌后此处滚动显示歌词）");
        lrcBox.addView(tvLrcEmpty);

        tvCrash = new TextView(this);
        tvCrash.setTextColor(C_RED);
        tvCrash.setTextSize(12);
        tvCrash.setPadding(0, dp(6), 0, 0);
        tvCrash.setVisibility(View.GONE);
        card.addView(tvCrash);

        TextView repo = new TextView(this);
        repo.setText("项目地址：github.com/deku772/Icar03");
        repo.setTextColor(C_PRIMARY);
        repo.setTextSize(12);
        repo.setGravity(Gravity.CENTER);
        repo.setPadding(0, dp(10), 0, dp(4));
        card.addView(repo);
        return card;
    }

    /** 轻量 LRC 解析（行级，剥逐字标签与元数据行） */
    private static java.util.List<long[]> parseLrcTimes(String lrc, java.util.List<String> outText) {
        java.util.List<long[]> times = new java.util.ArrayList<>();
        if (lrc == null) return times;
        for (String ln : lrc.split("\n")) {
            java.util.regex.Matcher head =
                    java.util.regex.Pattern.compile("^\\s*((?:\\[\\d+:\\d+(?:\\.\\d+)?\\])+)")
                            .matcher(ln);
            if (!head.find()) continue;
            String txt = ln.substring(head.group(1).length())
                    .replaceAll("<\\d+:\\d+(?:\\.\\d+)?>", "").trim();
            if (txt.isEmpty() || txt.matches("^(作词|作曲|编曲|制作人|混音|母带|录音|和声|监制|出品|发行|翻译|词|曲)\\s*[：:].*")) continue;
            java.util.regex.Matcher tag =
                    java.util.regex.Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]").matcher(head.group(1));
            while (tag.find()) {
                long t = Math.round(Integer.parseInt(tag.group(1)) * 60000L
                        + Double.parseDouble(tag.group(2)) * 1000);
                times.add(new long[]{t});
                outText.add(txt);
            }
        }
        return times;
    }

    /** LRC 变化时重建行视图 */
    private void rebuildLrc(String lrc) {
        lrcLines.clear();
        lrcBox.removeAllViews();
        lrcCur = -1;
        if (lrc == null || lrc.trim().isEmpty()) {
            lrcBox.addView(tvLrcEmpty);
            return;
        }
        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.List<long[]> times = parseLrcTimes(lrc, texts);
        for (int i = 0; i < times.size(); i++) {
            TextView tv = new TextView(this);
            tv.setText(texts.get(i));
            tv.setTextSize(15);
            tv.setTextColor(C_TEXT_DIM);
            tv.setLineSpacing(dp(3), 1f);
            tv.setPadding(dp(2), dp(4), dp(2), dp(4));
            lrcBox.addView(tv);
            lrcLines.add(new LrcLine(times.get(i)[0], texts.get(i), tv));
        }
    }

    /** 跟随播放更新高亮行与滚动位置 */
    private void updateLrcScroll(PlaybackService.Monitor m) {
        String full = m.lrcFull;
        if (!full.equals(lrcShown)) {
            lrcShown = full;
            rebuildLrc(full);
        }
        if (lrcLines.isEmpty()) return;
        /* 手机侧预览偏移：正值提前（词晚于声），负值延后（词早于声） */
        long pos = m.positionMs + PlaybackService.getOffsetMs();
        int idx = -1;
        for (int i = 0; i < lrcLines.size(); i++) {
            if (lrcLines.get(i).t <= pos + 200) idx = i;
            else break;
        }
        if (idx < 0) idx = 0;
        if (idx == lrcCur) return;
        lrcCur = idx;
        for (int i = 0; i < lrcLines.size(); i++) {
            TextView tv = lrcLines.get(i).view;
            if (i == idx) {
                tv.setTextColor(C_TEXT);
                tv.setTextSize(17);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
            } else if (Math.abs(i - idx) <= 1) {
                tv.setTextColor(C_TEXT_DIM);
                tv.setTextSize(15);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
            } else {
                tv.setTextColor(0xFF5A6377);
                tv.setTextSize(14);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
        final View cur = lrcLines.get(idx).view;
        svLrc.post(() -> svLrc.smoothScrollTo(0, Math.max(0, cur.getTop() - svLrc.getHeight() / 2)));
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

        updateLrcScroll(m);
        int off = PlaybackService.getOffsetMs();
        if (tvOffset != null) {
            tvOffset.setText(off == 0 ? "偏移 0"
                    : String.format(java.util.Locale.US, "%s%.2fs",
                            off > 0 ? "提前 " : "延后 ", Math.abs(off) / 1000f));
        }
        String crash = IcarPhoneApp.readCrash();
        if (crash != null) {
            tvCrash.setVisibility(View.VISIBLE);
            tvCrash.setText("[上次崩溃] " + firstLine(crash));
        }

        btnService.setText(running ? "2 · 停止推送服务" : "2 · 启动推送服务");
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    /* ---------------- 操作 ---------------- */

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
            PlaybackService.setAutoStart(false);
            stopService(new Intent(this, PlaybackService.class));
        } else {
            PlaybackService.setAutoStart(true);
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
