package com.icarme.lyrics;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 主界面：授权引导 + 服务开关 + ADB 命令展示。
 * 车机上若无桌面图标场景，可通过 adb shell am start 拉起。
 */
public class MainActivity extends Activity {

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
        title.setText("IcarLyrics\n车机悬浮歌词（BLE 接收端）");
        title.setTextSize(20);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, pad, 0, 0);
        root.addView(tvStatus);

        btnService = new Button(this);
        btnService.setText("启动/停止 歌词悬浮");
        btnService.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleService(); }
        });
        root.addView(btnService);

        TextView help = new TextView(this);
        help.setText(AdbHelper.helpText());
        help.setTextSize(13);
        ScrollView sv = new ScrollView(this);
        sv.addView(help);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = pad;
        sv.setLayoutParams(lp);
        root.addView(sv);

        setContentView(root);

        if (!Settings.canDrawOverlays(this)) {
            /* 车机无标准权限页时的兜底：走 ADB appop 授权，见下方命令说明 */
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { /* 无对应页面则忽略 */ }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean svc = OverlayService.running;
        boolean ble = BleService.running;
        tvStatus.setText("悬浮窗权限: " + (overlay ? "已授予" : "未授予（需 ADB）")
                + "\n悬浮服务: " + (svc ? "运行中" : "已停止")
                + "\nBLE 服务: " + (ble ? "运行中" : "已停止")
                + "\nBLE 广播: " + BleService.advState
                + "\n蓝牙名称: " + BleService.advertisedName());
        btnService.setText(svc ? "停止 歌词悬浮" : "启动 歌词悬浮");
    }

    private void toggleService() {
        if (OverlayService.running) {
            stopService(new Intent(this, OverlayService.class));
            stopService(new Intent(this, BleService.class));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.setText("请先用下方 ADB 命令授予悬浮窗权限");
                return;
            }
            startService(new Intent(this, OverlayService.class));
            startService(new Intent(this, BleService.class));
        }
        /* 服务/广播启动异步，延迟刷新状态 */
        new android.os.Handler(getMainLooper()).postDelayed(this::refreshStatus, 300);
        new android.os.Handler(getMainLooper()).postDelayed(this::refreshStatus, 1500);
    }
}
