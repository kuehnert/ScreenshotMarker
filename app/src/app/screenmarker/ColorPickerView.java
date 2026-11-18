// Version: 2026-11-18
package app.screenmarker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Saturation/value square, hue bar and a preview strip comparing old and new color. */
public class ColorPickerView extends View {

    private final float[] hsv = new float[3];
    private final int oldColor;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sv = new RectF(), hue = new RectF(), prevOld = new RectF(), prevNew = new RectF();
    private int dragging = 0; // 1 = square, 2 = hue bar
    private Runnable onChange;

    public ColorPickerView(Context c, int initial) {
        super(c);
        oldColor = initial;
        Color.colorToHSV(initial, hsv);
        d = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * d);
    }

    void setOnChange(Runnable r) { onChange = r; }

    int getColor() { return Color.HSVToColor(hsv); }

    void setColor(int c) {
        Color.colorToHSV(c, hsv);
        invalidate();
        if (onChange != null) onChange.run();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED ? (int) (300 * d) : MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (230 * d + 34 * d + 40 * d));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float m = 4 * d;
        sv.set(m, m, w - m, m + 220 * d);
        hue.set(m, sv.bottom + 14 * d, w - m, sv.bottom + 14 * d + 26 * d);
        prevOld.set(m, hue.bottom + 14 * d, w / 2f, hue.bottom + 14 * d + 30 * d);
        prevNew.set(w / 2f, prevOld.top, w - m, prevOld.bottom);
    }

    @Override
    protected void onDraw(Canvas c) {
        int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        p.setShader(new LinearGradient(sv.left, 0, sv.right, 0, 0xFFFFFFFF, pure, Shader.TileMode.CLAMP));
        c.drawRoundRect(sv, 8 * d, 8 * d, p);
        p.setShader(new LinearGradient(0, sv.top, 0, sv.bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(sv, 8 * d, 8 * d, p);

        int[] hues = new int[7];
        for (int i = 0; i < 7; i++) hues[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
        p.setShader(new LinearGradient(hue.left, 0, hue.right, 0, hues, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(hue, 13 * d, 13 * d, p);
        p.setShader(null);

        float sx = sv.left + hsv[1] * sv.width(), sy = sv.top + (1 - hsv[2]) * sv.height();
        ring.setColor(hsv[2] > 0.6f && hsv[1] < 0.5f ? 0xFF000000 : 0xFFFFFFFF);
        c.drawCircle(sx, sy, 10 * d, ring);
        float hx = hue.left + hsv[0] / 360f * hue.width();
        ring.setColor(0xFFFFFFFF);
        c.drawCircle(hx, hue.centerY(), 12 * d, ring);

        p.setColor(oldColor);
        c.drawRect(prevOld, p);
        p.setColor(getColor());
        c.drawRect(prevNew, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (y <= sv.bottom + 7 * d) dragging = 1;
            else if (y <= hue.bottom + 10 * d) dragging = 2;
            else dragging = 0;
            if (dragging != 0) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (dragging == 1) {
            hsv[1] = clamp((x - sv.left) / sv.width());
            hsv[2] = 1 - clamp((y - sv.top) / sv.height());
        } else if (dragging == 2) {
            hsv[0] = clamp((x - hue.left) / hue.width()) * 359.9f;
        }
        if (dragging != 0) {
            invalidate();
            if (onChange != null) onChange.run();
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging = 0;
        return true;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
