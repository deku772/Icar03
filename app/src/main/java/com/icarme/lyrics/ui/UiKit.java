package com.icarme.lyrics.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.icarme.lyrics.R;

/** 模式 A 控件组装：文案层级、开关卡、分段轨、整行动作卡、信息弹窗面。 */
public final class UiKit {

    public interface Action {
        void run();
    }

    private UiKit() {}

    public static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int dpI(Context c, float v) {
        return (int) (dp(c, v) + 0.5f);
    }

    /** 读取 dimens 资源（已是目标 px），禁止再按 dp 二次换算。 */
    public static int px(Context c, int dimenRes) {
        return c.getResources().getDimensionPixelSize(dimenRes);
    }

    public static int color(Context c, int id) {
        return c.getResources().getColor(id);
    }

    public static TextView body1(Context c, String text) {
        return text(c, text, R.dimen.icar_body1, R.color.icar_text_primary, false);
    }

    public static TextView body2(Context c, String text) {
        return text(c, text, R.dimen.icar_body2, R.color.icar_text_primary, false);
    }

    public static TextView caption2(Context c, String text) {
        return text(c, text, R.dimen.icar_caption2, R.color.icar_text_secondary, false);
    }

    private static TextView text(Context c, String s, int sizeRes, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color(c, colorRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, c.getResources().getDimension(sizeRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static View groupTitle(Context c, String title) {
        TextView t = body2(c, title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(c, R.dimen.icar_group_gap);
        lp.bottomMargin = px(c, R.dimen.icar_group_title_gap);
        t.setLayoutParams(lp);
        return t;
    }

    public static View groupDesc(Context c, String desc) {
        TextView t = caption2(c, desc);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(c, R.dimen.icar_group_title_gap);
        t.setLayoutParams(lp);
        return t;
    }

    /** 开关卡：标题 + 任务说明 + IcarSwitch；整卡可点。 */
    public static View switchCard(Context c, String title, String desc, boolean checked,
                                  IcarSwitch.OnCheckedChangeListener ls) {
        int cardH = px(c, R.dimen.icar_switch_card_h);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.icar_card_neutral);
        card.setPadding(dpI(c, 29), dpI(c, 20), dpI(c, 29), dpI(c, 20));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardH);
        clp.bottomMargin = px(c, R.dimen.icar_switch_card_gap);
        card.setLayoutParams(clp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = body2(c, title);
        TextView t2 = caption2(c, desc);
        col.addView(t1);
        col.addView(t2);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final IcarSwitch sw = new IcarSwitch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(ls);
        card.addView(sw);
        card.setOnClickListener(v -> sw.toggle());
        return card;
    }

    /**
     * 分段控件：单轨道，选中块跟随 accent。
     * labels 与 values 等长；selected 为当前下标。
     */
    public static View segmented(Context c, String[] labels, int selected, Action[] onSelect) {
        LinearLayout track = new LinearLayout(c);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundResource(R.drawable.icar_seg_track);
        int pad = px(c, R.dimen.icar_seg_pad);
        track.setPadding(pad, pad, pad, pad);
        int h = px(c, R.dimen.icar_seg_height);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.bottomMargin = px(c, R.dimen.icar_group_title_gap);
        track.setLayoutParams(lp);

        int accent = ThemeAccent.accentColor();
        int onAccent = ThemeAccent.onAccentTextColor(accent);
        int inactive = color(c, R.color.icar_text_inactive);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView cell = new TextView(c);
            cell.setText(labels[i]);
            cell.setGravity(Gravity.CENTER);
            cell.setSingleLine(true);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, c.getResources().getDimension(R.dimen.icar_body2));
            boolean on = i == selected;
            cell.setTextColor(on ? onAccent : inactive);
            if (on) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(accent);
                bg.setCornerRadius(c.getResources().getDimension(R.dimen.icar_seg_radius_inner));
                cell.setBackground(bg);
            } else {
                cell.setBackgroundColor(0x00000000);
            }
            cell.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) clp.leftMargin = pad;
            cell.setLayoutParams(clp);
            cell.setOnClickListener(v -> {
                if (onSelect != null && idx < onSelect.length && onSelect[idx] != null) {
                    onSelect[idx].run();
                }
            });
            track.addView(cell);
        }
        return track;
    }

    /** 一次性命令：整行动作卡，图标位 + 标题 + 说明；不伪装成开关。 */
    public static View actionCard(Context c, String title, String desc, Action onClick) {
        int h = px(c, R.dimen.icar_action_row_h);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.icar_action_card);
        card.setPadding(dpI(c, 29), dpI(c, 20), dpI(c, 29), dpI(c, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.bottomMargin = px(c, R.dimen.icar_switch_card_gap);
        card.setLayoutParams(lp);

        // 方向/刷新示意：用强调色短杆，避免引入原车私有图标
        View icon = new View(c);
        GradientDrawable bar = new GradientDrawable();
        bar.setCornerRadius(dpI(c, 3));
        bar.setColor(ThemeAccent.accentColor());
        icon.setBackground(bar);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dpI(c, 8), dpI(c, 48));
        ilp.rightMargin = dpI(c, 24);
        icon.setLayoutParams(ilp);
        card.addView(icon);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = body2(c, title);
        t1.setTextColor(color(c, R.color.icar_text_primary));
        TextView t2 = caption2(c, desc);
        col.addView(t1);
        col.addView(t2);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = body2(c, "›");
        arrow.setTextColor(ThemeAccent.accentColor());
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                c.getResources().getDimension(R.dimen.icar_body1));
        card.addView(arrow);

        if (onClick != null) card.setOnClickListener(v -> onClick.run());
        return card;
    }

    /** 只读信息行：左标签 + 右值，不带开关语义。 */
    public static View infoRow(Context c, String label, String value) {
        int h = px(c, R.dimen.icar_info_row_h);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.icar_card_neutral);
        row.setPadding(dpI(c, 29), dpI(c, 16), dpI(c, 29), dpI(c, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.bottomMargin = px(c, R.dimen.icar_group_title_gap);
        row.setLayoutParams(lp);

        TextView l = caption2(c, label);
        l.setTextColor(color(c, R.color.icar_text_secondary));
        row.addView(l, new LinearLayout.LayoutParams(dpI(c, 140), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = body2(c, value);
        v.setTextColor(color(c, R.color.icar_text_primary));
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 组内两张半宽开关卡并排。 */
    public static View switchPair(Context c,
                                  String t1, String d1, boolean c1, IcarSwitch.OnCheckedChangeListener l1,
                                  String t2, String d2, boolean c2, IcarSwitch.OnCheckedChangeListener l2) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int gap = px(c, R.dimen.icar_switch_card_gap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = gap;
        row.setLayoutParams(lp);

        View a = switchCard(c, t1, d1, c1, l1);
        View b = switchCard(c, t2, d2, c2, l2);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a.setLayoutParams(half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = gap;
        b.setLayoutParams(half2);
        row.addView(a);
        row.addView(b);
        return row;
    }
}
