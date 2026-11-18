// Version: 2026-11-18
package app.screenmarker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small button that draws its own line icon (24-unit grid) with an optional label underneath. */
public class IconButton extends View {

    static final int I_OPEN = 0, I_UNDO = 1, I_TRASH = 2, I_SHARE = 3, I_SAVE = 4,
            I_MARKER = 5, I_PEN = 6, I_ARROW = 7, I_RECT = 8, I_CIRCLE = 9, I_ERASER = 10,
            I_E_OBJECTS = 11, I_E_PIXELS = 12, I_E_COVER = 13;

    private final int icon;
    private final String label;
    private boolean selectedState, primary, danger;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public IconButton(Context c, int icon, String label) {
        super(c);
        this.icon = icon;
        this.label = label;
        d = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
        setClickable(true);
        setFocusable(true);
        setContentDescription(label);
    }

    void setSelectedState(boolean s) { selectedState = s; invalidate(); }
    void setPrimary(boolean p) { primary = p; invalidate(); }
    void setDanger(boolean x) { danger = x; invalidate(); }

    @Override
    public void setEnabled(boolean e) { super.setEnabled(e); invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        int fg = MainActivity.FG;
        if (primary) {
            bg.setColor(MainActivity.ACCENT);
            c.drawRoundRect(new RectF(3 * d, 3 * d, w - 3 * d, h - 3 * d), 10 * d, 10 * d, bg);
            fg = 0xFF0E0F12;
        } else if (selectedState) {
            bg.setColor(MainActivity.CHIP);
            c.drawRoundRect(new RectF(3 * d, 3 * d, w - 3 * d, h - 3 * d), 10 * d, 10 * d, bg);
        } else if (!selectedState && label != null && isToolIcon()) {
            fg = MainActivity.MUTED;
        }
        if (danger) fg = 0xFFFF6B6B;
        int alpha = isEnabled() ? 255 : 90;

        float size = 22 * d;
        float top = label != null ? h / 2f - size / 2f - 7 * d : h / 2f - size / 2f;
        c.save();
        c.translate(w / 2f - size / 2f, top);
        c.scale(size / 24f, size / 24f);
        stroke.setColor(fg);
        stroke.setAlpha(alpha);
        stroke.setStrokeWidth(2f);
        drawIcon(c);
        c.restore();

        if (label != null) {
            text.setColor(fg);
            text.setAlpha(alpha);
            text.setFakeBoldText(selectedState);
            c.drawText(label, w / 2f, top + size + 13 * d, text);
        }
    }

    private boolean isToolIcon() { return icon >= I_MARKER; }

    private void drawIcon(Canvas c) {
        Path p = new Path();
        switch (icon) {
            case I_OPEN:
                c.drawRoundRect(new RectF(3, 4, 21, 20), 2, 2, stroke);
                c.drawCircle(9, 10, 2, stroke);
                p.moveTo(21, 16); p.lineTo(16, 11); p.lineTo(7, 20);
                break;
            case I_UNDO:
                p.moveTo(9, 14); p.lineTo(4, 9); p.lineTo(9, 4);
                p.moveTo(4, 9); p.lineTo(14, 9);
                p.arcTo(new RectF(8, 9, 20, 21), -90, 180, false);
                p.lineTo(11, 21);
                break;
            case I_TRASH:
                p.moveTo(4, 7); p.lineTo(20, 7);
                p.moveTo(10, 11); p.lineTo(10, 17);
                p.moveTo(14, 11); p.lineTo(14, 17);
                p.moveTo(6, 7); p.lineTo(7, 20); p.lineTo(17, 20); p.lineTo(18, 7);
                p.moveTo(9, 7); p.lineTo(9, 4); p.lineTo(15, 4); p.lineTo(15, 7);
                break;
            case I_SHARE:
                c.drawCircle(18, 5, 2.5f, stroke);
                c.drawCircle(6, 12, 2.5f, stroke);
                c.drawCircle(18, 19, 2.5f, stroke);
                p.moveTo(8.2f, 10.8f); p.lineTo(15.8f, 6.2f);
                p.moveTo(8.2f, 13.2f); p.lineTo(15.8f, 17.8f);
                break;
            case I_SAVE:
                p.moveTo(12, 4); p.lineTo(12, 15);
                p.moveTo(7, 10); p.lineTo(12, 15); p.lineTo(17, 10);
                p.moveTo(5, 20); p.lineTo(19, 20);
                break;
            case I_MARKER:
                p.moveTo(4, 20); p.lineTo(11, 20);
                p.moveTo(14, 4); p.lineTo(20, 10); p.lineTo(12, 18); p.lineTo(6, 18); p.lineTo(6, 12); p.close();
                break;
            case I_PEN:
                p.moveTo(4, 20); p.cubicTo(8, 19, 9, 14, 13, 11); p.cubicTo(17, 8, 19, 7, 20, 5);
                break;
            case I_ARROW:
                p.moveTo(5, 19); p.lineTo(19, 5);
                p.moveTo(10, 5); p.lineTo(19, 5); p.lineTo(19, 14);
                break;
            case I_RECT:
                c.drawRoundRect(new RectF(4, 6, 20, 18), 1, 1, stroke);
                break;
            case I_CIRCLE:
                c.drawOval(new RectF(4, 6, 20, 18), stroke);
                break;
            case I_ERASER:
            case I_E_PIXELS:
                p.moveTo(3, 14); p.lineTo(13, 4); p.lineTo(20, 11); p.lineTo(10, 21); p.lineTo(7, 21); p.close();
                p.moveTo(8, 9); p.lineTo(15, 16);
                p.moveTo(12, 21); p.lineTo(21, 21);
                break;
            case I_E_OBJECTS:
                c.drawRoundRect(new RectF(3, 5, 15, 15), 1, 1, stroke);
                c.drawCircle(15.5f, 15.5f, 5.5f, stroke);
                p.moveTo(13.5f, 13.5f); p.lineTo(17.5f, 17.5f);
                p.moveTo(17.5f, 13.5f); p.lineTo(13.5f, 17.5f);
                break;
            case I_E_COVER:
                c.drawRoundRect(new RectF(4, 3, 18, 9), 1.5f, 1.5f, stroke);
                p.moveTo(18, 6); p.lineTo(21, 6); p.lineTo(21, 12); p.lineTo(11, 12); p.lineTo(11, 15);
                c.drawRoundRect(new RectF(9, 15, 13, 21), 1, 1, stroke);
                break;
        }
        c.drawPath(p, stroke);
    }
}
