package com.icarme.lyrics.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.icarme.lyrics.BuildConfig;
import com.icarme.lyrics.BleService;
import com.icarme.lyrics.OverlayService;
import com.icarme.lyrics.R;

/**
 * 设置内容渲染：只把偏好/状态映射到视图，不持有播放或窗口状态机。
 * MainActivity 负责分类切换与动作转发。
 */
public final class LyricsSettingsRenderer {

    public interface Host {
        Context context();
        void onScanPhones();
        void onToggleService();
        void onRestartService();
        void onDownloadApk();
        void onToggleHelp();
        boolean isHelpOpen();
        void refreshChrome();
    }

    private final Host host;

    public LyricsSettingsRenderer(Host host) {
        this.host = host;
    }

    public View renderCategory(String category) {
        Context c = host.context();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);
        int padL = UiKit.px(c, R.dimen.icar_content_pad_l);
        int padR = UiKit.px(c, R.dimen.icar_content_pad_r);
        int padV = UiKit.px(c, R.dimen.icar_content_pad_v);
        box.setPadding(padL, padV, padR, padV);

        if (SettingsPages.LYRICS.equals(category)) {
            renderLyrics(c, box);
        } else if (SettingsPages.SERVICE.equals(category)) {
            renderService(c, box);
        } else if (SettingsPages.TIP.equals(category)) {
            renderTipOnly(c, box);
        } else if (SettingsPages.ABOUT.equals(category)) {
            renderAbout(c, box);
        }
        return box;
    }

    private void renderTipOnly(Context c, LinearLayout box) {
        box.addView(UiKit.groupTitle(c, "微信赞赏"));
        TextView tip = UiKit.caption2(c, "若项目对你有帮助，可微信扫码支持开发。");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = UiKit.px(c, R.dimen.icar_group_title_gap);
        tip.setLayoutParams(tlp);
        box.addView(tip);
        appendQrPair(c, box);
    }

    private void renderLyrics(Context c, LinearLayout box) {
        box.addView(UiKit.groupTitle(c, "通用"));

        boolean auto = OverlayService.isAutoStart();
        box.addView(UiKit.switchCard(c, "开机自启动",
                "授予悬浮窗权限后，开机自动启动歌词服务。", auto,
                (v, on) -> {
                    OverlayService.setAutoStart(on);
                    host.refreshChrome();
                }));

        boolean dbg = OverlayService.isDebug();
        box.addView(UiKit.switchCard(c, "悬浮调试信息",
                "在悬浮层显示连接提示与分片统计，排障时再开。", dbg,
                (v, on) -> {
                    OverlayService.setDebug(on);
                    host.refreshChrome();
                }));

        String color = OverlayService.getColor();
        int colorIdx = "white".equals(color) ? 0 : "black".equals(color) ? 1 : 2;
        box.addView(UiKit.groupTitle(c, "歌词颜色"));
        box.addView(UiKit.groupDesc(c, "默认跟随系统主题强调色；也可固定白/黑。"));
        final int[] colorIdxHolder = {colorIdx};
        String[] colorLabels = {"白", "黑", "跟随系统"};
        box.addView(UiKit.segmented(c, colorLabels, colorIdxHolder[0], new UiKit.Action[]{
                () -> {
                    OverlayService.setColor("white");
                    host.refreshChrome();
                },
                () -> {
                    OverlayService.setColor("black");
                    host.refreshChrome();
                },
                () -> {
                    OverlayService.setColor("system");
                    OverlayService.pushAccent(ThemeAccent.accentColor());
                    host.refreshChrome();
                }
        }));

        box.addView(UiKit.groupTitle(c, "歌词来源"));
        box.addView(UiKit.groupDesc(c, "蓝牙推送：手机取词后 BLE 推到车机（零车机流量）。在线获取：车机自己联网取词（需车机有流量/Wi‑Fi）。"));
        String src = OverlayService.getLyricsSourceMode();
        int srcIdx = "online".equals(src) ? 1 : 0;
        box.addView(UiKit.segmented(c, new String[]{"蓝牙推送", "在线获取"}, srcIdx,
                new UiKit.Action[]{
                        () -> {
                            OverlayService.setLyricsSourceMode("bluetooth");
                            host.refreshChrome();
                        },
                        () -> {
                            OverlayService.setLyricsSourceMode("online");
                            host.refreshChrome();
                        }
                }));

        box.addView(UiKit.groupTitle(c, "显示模式"));
        box.addView(UiKit.groupDesc(c, "自动：地图/TBT 时隐身避让；始终显示：不因地图隐藏；原车规则：贴近 03 歌词避让。"));
        String dm = OverlayService.getDisplayMode();
        int dmIdx = "always".equals(dm) ? 1 : "car".equals(dm) ? 2 : 0;
        box.addView(UiKit.segmented(c, new String[]{"自动", "始终显示", "原车规则"}, dmIdx,
                new UiKit.Action[]{
                        () -> {
                            OverlayService.setDisplayMode("auto");
                            host.refreshChrome();
                        },
                        () -> {
                            OverlayService.setDisplayMode("always");
                            host.refreshChrome();
                        },
                        () -> {
                            OverlayService.setDisplayMode("car");
                            host.refreshChrome();
                        }
                }));

        box.addView(UiKit.groupTitle(c, "歌词位置"));
        box.addView(UiKit.groupDesc(c, "壁纸模式下歌词安全区靠左或靠右；系统组件/TBT 卡出现时自动避让，空间不足则隐藏。"));
        String wallPos = OverlayService.getWallpaperPosition();
        int wallIdx = "left".equals(wallPos) ? 0 : 1;
        box.addView(UiKit.segmented(c, new String[]{"左侧", "右侧"}, wallIdx,
                new UiKit.Action[]{
                        () -> {
                            OverlayService.setWallpaperPosition("left");
                            host.refreshChrome();
                        },
                        () -> {
                            OverlayService.setWallpaperPosition("right");
                            host.refreshChrome();
                        }
                }));

        String align = OverlayService.getAlign();
        int alignIdx = "left".equals(align) ? 0 : "right".equals(align) ? 2 : 1;
        box.addView(UiKit.groupTitle(c, "歌词位置（横向）"));
        box.addView(UiKit.groupDesc(c, "靠左 / 居中 / 靠右；与壁纸左右窗口是不同设置。"));
        box.addView(UiKit.segmented(c, new String[]{"靠左", "居中", "靠右"}, alignIdx,
                new UiKit.Action[]{
                        () -> {
                            OverlayService.setAlign("left");
                            host.refreshChrome();
                        },
                        () -> {
                            OverlayService.setAlign("center");
                            host.refreshChrome();
                        },
                        () -> {
                            OverlayService.setAlign("right");
                            host.refreshChrome();
                        }
                }));

        box.addView(UiKit.groupTitle(c, "歌词偏移"));
        int off = OverlayService.getOffsetMs();
        String offText = off == 0
                ? "已同步。词慢于声点「提前」，词快于声点「延后」。"
                : String.format(java.util.Locale.US, "%s%.2fs · 词%s声",
                off > 0 ? "提前 " : "延后 ", Math.abs(off) / 1000f,
                off > 0 ? "晚于" : "早于");
        box.addView(UiKit.groupDesc(c, offText));
        final int step = OverlayService.offsetStepMs();
        final int coarse = OverlayService.offsetCoarseMs();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View later = UiKit.actionCard(c, "延后", "点按 0.5s · 长按 2s", () -> {
            OverlayService.adjustOffsetMs(-step);
            host.refreshChrome();
        });
        View earlier = UiKit.actionCard(c, "提前", "点按 0.5s · 长按 2s", () -> {
            OverlayService.adjustOffsetMs(step);
            host.refreshChrome();
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        later.setLayoutParams(half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int gap = UiKit.px(c, R.dimen.icar_switch_card_gap);
        half2.leftMargin = gap;
        earlier.setLayoutParams(half2);
        row.addView(later);
        row.addView(earlier);
        box.addView(row);
    }

    private void renderService(Context c, LinearLayout box) {
        box.addView(UiKit.groupTitle(c, "当前状态"));

        boolean overlayPerm = android.provider.Settings.canDrawOverlays(c);
        boolean svc = OverlayService.running;
        boolean ble = BleService.running;
        String conn = BleService.advState;
        String phone = BleService.phoneMacText();

        box.addView(UiKit.infoRow(c, "悬浮窗权限", overlayPerm ? "已授予" : "未授予（见「关于」ADB 帮助）"));
        box.addView(UiKit.infoRow(c, "悬浮歌词服务", svc ? "运行中" : "已停止"));
        box.addView(UiKit.infoRow(c, "BLE 接收", ble ? "运行中" : "已停止"));
        box.addView(UiKit.infoRow(c, "歌词来源", OverlayService.lyricsSourceStatus()));
        box.addView(UiKit.infoRow(c, "连接状态", conn == null || conn.isEmpty() ? "未启动" : conn));
        box.addView(UiKit.infoRow(c, "手机地址", phone));

        Integer sceneTop = com.icarme.lyrics.IcarA11yService.leftSceneTopPx;
        box.addView(UiKit.infoRow(c, "地图/TBT 避让",
                com.icarme.lyrics.DisplayPolicy.tbtShow() == 1
                        ? ("TBT 显示中 · guide=" + com.icarme.lyrics.DisplayPolicy.tbtGuide()
                        + " · 模式=" + OverlayService.getDisplayMode())
                        : ("无 TBT 卡 · 模式=" + OverlayService.getDisplayMode())));
        box.addView(UiKit.infoRow(c, "系统组件层", OverlayService.a11yStatusText()));
        box.addView(UiKit.actionCard(c, "打开系统无障碍设置",
                "授权「IcarLyrics」后，才能读取座椅/雨刮等场景层几何。",
                () -> {
                    try {
                        android.content.Intent i = new android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(i);
                    } catch (Exception ignored) {}
                }));

        box.addView(UiKit.groupTitle(c, "操作"));
        box.addView(UiKit.actionCard(c,
                svc ? "停止悬浮歌词" : "启动悬浮歌词",
                svc ? "同时停止 BLE 接收；手动停止后不自启。" : "需要已授予悬浮窗权限。",
                host::onToggleService));
        box.addView(UiKit.actionCard(c, "扫描发现手机",
                "按服务 UUID 扫描 10 秒，发现后选择连接。",
                host::onScanPhones));
        box.addView(UiKit.actionCard(c, "重启悬浮歌词",
                "停止后立即按当前偏好重新启动。",
                host::onRestartService));
        box.addView(UiKit.actionCard(c, "下载 APK（可选镜像）",
                "获取车机端 / 手机端 Release 安装包。",
                host::onDownloadApk));
    }

    private void renderAbout(Context c, LinearLayout box) {
        box.addView(UiKit.groupTitle(c, "版本号"));
        box.addView(UiKit.infoRow(c, "版本号", BuildConfig.VERSION_NAME));

        box.addView(UiKit.groupTitle(c, "用户协议"));
        TextView repo = UiKit.caption2(c, "项目地址：github.com/deku772/Icar03");
        repo.setTextColor(ThemeAccent.accentColor());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = UiKit.dpI(c, 20);
        repo.setLayoutParams(rlp);
        box.addView(repo);

        box.addView(UiKit.actionCard(c,
                host.isHelpOpen() ? "收起 ADB 授权帮助" : "ADB 授权帮助",
                "悬浮窗 / 定位 / 通知监听的一次性授权命令。",
                host::onToggleHelp));

        if (host.isHelpOpen()) {
            LinearLayout helpWrap = new LinearLayout(c);
            helpWrap.setOrientation(LinearLayout.VERTICAL);
            helpWrap.setBackgroundResource(R.drawable.icar_card_neutral);
            int pad = UiKit.dpI(c, 29);
            helpWrap.setPadding(pad, pad, pad, pad);
            TextView help = UiKit.caption2(c, com.icarme.lyrics.AdbHelper.helpText());
            help.setLineSpacing(UiKit.dp(c, 3), 1f);
            helpWrap.addView(help);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hlp.bottomMargin = UiKit.px(c, R.dimen.icar_group_title_gap);
            helpWrap.setLayoutParams(hlp);
            box.addView(helpWrap);
        }

        box.addView(UiKit.groupTitle(c, "下载手机端"));
        TextView tip = UiKit.caption2(c, "扫码下载手机端 APK；赞赏请见左栏「赞赏」页。");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = UiKit.px(c, R.dimen.icar_group_title_gap);
        tip.setLayoutParams(tlp);
        box.addView(tip);
        appendQrPair(c, box, false);
    }

    /** 双列二维码：includeTip=true 时右侧为赞赏 */
    private void appendQrPair(Context c, LinearLayout box) {
        appendQrPair(c, box, true);
    }

    private void appendQrPair(Context c, LinearLayout box, boolean includeTip) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int qs = UiKit.px(c, R.dimen.icar_qr_size);
        LinearLayout dlCol = new LinearLayout(c);
        dlCol.setOrientation(LinearLayout.VERTICAL);
        android.widget.ImageView dl = new android.widget.ImageView(c);
        try {
            dl.setImageBitmap(com.icarme.lyrics.QrEncoder.encode(
                    "https://github.com/deku772/Icar03/releases/latest/download/IcarLyrics-Phone.apk", 4));
        } catch (Exception ignored) {}
        dlCol.addView(dl, new LinearLayout.LayoutParams(qs, qs));
        dlCol.addView(UiKit.caption2(c, "下载手机端"));
        row.addView(dlCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (includeTip) {
            LinearLayout tipCol = new LinearLayout(c);
            tipCol.setOrientation(LinearLayout.VERTICAL);
            android.widget.ImageView tipIv = new android.widget.ImageView(c);
            tipIv.setImageResource(R.drawable.wechat_qr);
            tipCol.addView(tipIv, new LinearLayout.LayoutParams(qs, qs));
            tipCol.addView(UiKit.caption2(c, "微信赞赏"));
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tipLp.leftMargin = UiKit.px(c, R.dimen.icar_switch_card_gap);
            row.addView(tipCol, tipLp);
        }
        box.addView(row);
    }

    /** 分类 id 常量，与左导航一致。 */
    public static final class SettingsPages {
        public static final String LYRICS = "lyrics";
        public static final String SERVICE = "service";
        public static final String TIP = "tip";
        public static final String ABOUT = "about";
        private SettingsPages() {}
    }
}
