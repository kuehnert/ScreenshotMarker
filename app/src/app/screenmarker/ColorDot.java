// Version: 2026-11-18
package app.screenmarker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** A color swatch (optionally numbered) or, with sizeLevel > 0, a stroke-size dot. */
public class ColorDot extends View {

    int color;
    boolean auto = false; // shows a pipette: color is taken from the image
    private final String number;
    private final int sizeLevel;
    private boolean selectedState;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public ColorDot(Context c, int color, String number, int sizeLevel) {
        super(c);
        this.color = color;
        this.number = number;
        this.sizeLevel = sizeLevel;
        d = c.getResources().getDisplayMetrics().density;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(12 * c.getResources().getDisplayMetrics().scaledDensity);
        p.setFakeBoldText(true);
        setClickable(true);
        setFocusable(true);
        setContentDescription(sizeLevel > 0 ? "Strichstärke " + sizeLevel : "Farbe " + (number != null ? number : "") + ", lange drücken zum Ändern");
    }

    void setSelectedState(boolean s) { selectedState = s; invalidate(); }
    void setColor(int c) { color = c; invalidate(); }
    void setAuto(boolean a) { auto = a; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        p.setStyle(Paint.Style.FILL);
        if (sizeLevel > 0) {
            if (selectedState) {
                p.setColor(MainActivity.CHIP);
                c.drawCircle(cx, cy, 17 * d, p);
            }
            p.setColor(MainActivity.FG);
            c.drawCircle(cx, cy, new float[]{0, 3f, 6.5f, 11f}[sizeLevel] * d, p);
            return;
        }
        float r = Math.min(17 * d, Math.min(getWidth(), getHeight()) / 2f - 4 * d);
        if (selectedState) {
            p.setColor(MainActivity.FG);
            c.drawCircle(cx, cy, r + 3 * d, p);
            p.setColor(MainActivity.BAR);
            c.drawCircle(cx, cy, r + 1 * d, p);
        }
        p.setColor(color);
        c.drawCircle(cx, cy, r - (selectedState ? 1.5f * d : 0), p);
        if (Color.luminance(color) < 0.08f) { // keep dark colors visible on the dark bar
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1 * d);
            p.setColor(0xFF55575F);
            c.drawCircle(cx, cy, r - (selectedState ? 1.5f * d : 0), p);
            p.setStyle(Paint.Style.FILL);
        }
        if (auto) {
            float rr = r - (selectedState ? 1.5f * d : 0);
            p.setColor(MainActivity.CHIP);
            c.drawCircle(cx, cy, rr, p);
            Paint ic = new Paint(Paint.ANTI_ALIAS_FLAG);
            ic.setStyle(Paint.Style.STROKE);
            ic.setStrokeCap(Paint.Cap.ROUND);
            ic.setStrokeWidth(2 * d);
            ic.setColor(MainActivity.FG);
            float u = rr / 12f; // icon grid -12..12
            c.drawLine(cx - 6 * u, cy + 6 * u, cx + 2 * u, cy - 2 * u, ic);
            ic.setStyle(Paint.Style.FILL);
            c.drawCircle(cx + 4.5f * u, cy - 4.5f * u, 3.5f * u, ic);
            c.drawCircle(cx - 6.5f * u, cy + 6.5f * u, 1.3f * u, ic);
            return;
        }
        if (number != null) {
            boolean light = Color.luminance(color) > 0.5f;
            p.setColor(light ? 0x99000000 : 0xCCFFFFFF);
            c.drawText(number, cx, cy - (p.descent() + p.ascent()) / 2f, p);
        }
    }
}
