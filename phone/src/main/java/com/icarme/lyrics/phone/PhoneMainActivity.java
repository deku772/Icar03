package com.icarme.lyrics.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 手机端主界面：权限引导 + 服务开关 + 状态显示。
 */
public class PhoneMainActivity extends Activity {

    private static final int REQ_PERMS = 1;

    private Button btnService;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("IcarLyrics 手机端\n手机取词 → BLE 推送 → 车机渲染");
        title.setTextSize(20);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, pad, 0, 0);
        root.addView(tvStatus);

        Button btnPerms = new Button(this);
        btnPerms.setText("1. 授权（蓝牙/定位/通知使用权）");
        btnPerms.setOnClickListener(v -> requestPermissions());
        root.addView(btnPerms);

        btnService = new Button(this);
        btnService.setText("2. 启动推送服务");
        btnService.setOnClickListener(v -> toggleService());
        root.addView(btnService);

        TextView help = new TextView(this);
        help.setText("使用说明：\n\n"
                + "1) 手机蓝牙连接 iCAR 车机（系统设置里配对）\n"
                + "2) 授予权限，启动推送服务\n"
                + "3) 随便用任何音乐 App 放歌\n"
                + "4) 车机端 IcarLyrics 悬浮窗自动显示逐字歌词\n\n"
                + "车机全程不联网，歌词由手机取好后经 BLE 推送。");
        help.setTextSize(13);
        ScrollView sv = new ScrollView(this);
        sv.addView(help);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = pad;
        sv.setLayoutParams(lp);
        root.addView(sv);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        boolean nl = enabled != null && enabled.contains(getPackageName());
        tvStatus.setText("通知使用权: " + (nl ? "已授权" : "未授权")
                + "\n推送服务: " + (PlaybackService.running ? "运行中" : "已停止"));
        btnService.setText(PlaybackService.running ? "停止推送服务" : "2. 启动推送服务");
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
        /* 通知使用权跳系统设置页 */
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
        refreshStatus();
    }
}
