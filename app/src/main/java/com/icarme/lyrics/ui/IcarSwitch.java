package com.icarme.lyrics.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import com.icarme.lyrics.R;

/**
 * 原车 MBSwitch 几何的项目控件：64x36 坐标空间绘制，触控约 73x41dp。
 * 实现 Checkable + Switch 无障碍类名；不叠加系统 SwitchCompat inset。
 */
public class IcarSwitch extends View implements android.widget.Checkable {

    private static final float SRC_W = 64f;
    private static final float SRC_H = 36f;
    private static final float THUMB_OUTER = 30f;
    private static final float PAD = 2f;

    private boolean checked;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF thumbRect = new RectF();
    private OnCheckedChangeListener listener;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(IcarSwitch v, boolean isChecked);
    }

    public IcarSwitch(Context context) {
        super(context);
        setMinimumWidth((int) dp(73));
        setMinimumHeight((int) dp(41));
        setClickable(true);
        setFocusable(true);
        setContentDescription("开关");
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f);
        ringPaint.setColor(0x55FFFFFF);
        setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(checked);
            }
        });
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    @Override
    public boolean isChecked() {
        return checked;
    }

    @Override
    public void setChecked(boolean checked) {
        if (this.checked == checked) return;
        this.checked = checked;
        invalidate();
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    @Override
    public void toggle() {
        setChecked(!checked);
    }

    @Override
    public boolean performClick() {
        toggle();
        return super.performClick();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize((int) dp(73), widthMeasureSpec),
                resolveSize((int) dp(41), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int off = color(R.color.icar_switch_off, 0x66727272);
        int thumb = color(R.color.icar_switch_thumb, 0xFFFFFFFF);

        float scale = Math.min(getWidth() / SRC_W, getHeight() / SRC_H);
        float ox = (getWidth() - SRC_W * scale) * 0.5f;
        float oy = (getHeight() - SRC_H * scale) * 0.5f;
        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(scale, scale);

        trackPaint.setColor(checked ? ThemeAccent.accentColor() : off);
        trackRect.set(0, 0, SRC_W, SRC_H);
        canvas.drawRoundRect(trackRect, SRC_H * 0.5f, SRC_H * 0.5f, trackPaint);

        float travel = SRC_W - THUMB_OUTER - PAD * 2f;
        float left = PAD + (checked ? travel : 0f);
        float top = (SRC_H - THUMB_OUTER) * 0.5f;
        thumbRect.set(left, top, left + THUMB_OUTER, top + THUMB_OUTER);
        thumbPaint.setColor(thumb);
        thumbPaint.setStyle(Paint.Style.FILL);
        canvas.drawOval(thumbRect, thumbPaint);
        canvas.drawOval(thumbRect, ringPaint);

        canvas.restore();
    }

    private int color(int resId, int fallback) {
        try {
            return getResources().getColor(resId);
        } catch (Exception e) {
            return fallback;
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
