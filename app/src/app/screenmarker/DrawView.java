// Version: 2026-11-18
package app.screenmarker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/** Shows the image, lets one finger draw and two fingers zoom/pan. All shapes live in image pixels. */
public class DrawView extends View {

    // tools
    static final int MARKER = 0, PEN = 1, ARROW = 2, RECT = 3, CIRCLE = 4, ERASER = 5;
    // stroke kinds produced by the eraser
    static final int K_ERASE = 10, K_COVER = 11;
    // eraser modes
    static final int E_OBJECTS = 0, E_PIXELS = 1, E_COVER = 2;

    // stroke widths per size level, in units of baseW()
    static final float[] SHAPE_W = {2.0f, 4.0f, 7.0f};
    static final float[] MARKER_W = {9f, 14.5f, 22f};
    static final float[] ERASER_W = {6f, 12f, 22f};

    static class Shape {
        int tool, color;
        float w;
        final ArrayList<PointF> pts = new ArrayList<>();
    }

    int tool = MARKER;
    int color = 0xFFF5C400;
    int sizeLevel = 0;
    int eraserMode = E_OBJECTS;
    boolean coverAuto = true;
    int coverColor = 0xFFFFFFFF;
    int version = 0;

    private Bitmap base, committed;
    private Canvas commitCanvas;
    private BitmapShader baseShader;
    private ArrayList<Shape> shapes = new ArrayList<>();
    private final ArrayList<ArrayList<Shape>> history = new ArrayList<>();
    private Shape current;
    private boolean erasing = false, erasedSomething = false;
    private float cursorX, cursorY;
    private boolean showCursor = false;

    private final Matrix m = new Matrix(), inv = new Matrix(), startM = new Matrix();
    private float fitScale = 1f;
    private boolean gesture = false;
    private float startDist, startMx, startMy;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Runnable listener;

    public DrawView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        hint.setColor(MainActivity.MUTED);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(16 * c.getResources().getDisplayMetrics().scaledDensity);
        cursor.setStyle(Paint.Style.STROKE);
        cursor.setStrokeWidth(1.5f * d);
    }

    void setListener(Runnable r) { listener = r; }
    private void changed() { version++; if (listener != null) listener.run(); invalidate(); }

    boolean hasImage() { return base != null; }
    boolean canUndo() { return !history.isEmpty(); }
    boolean hasShapes() { return !shapes.isEmpty(); }

    void setImage(Bitmap b) {
        base = b;
        baseShader = new BitmapShader(base, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shapes = new ArrayList<>();
        history.clear();
        current = null;
        rebuild();
        resetView();
        changed();
    }

    private void snapshot() { history.add(new ArrayList<>(shapes)); }

    void undo() {
        if (history.isEmpty()) return;
        shapes = history.remove(history.size() - 1);
        rebuild();
        changed();
    }

    void clearAll() {
        if (shapes.isEmpty()) return;
        snapshot();
        shapes = new ArrayList<>();
        rebuild();
        changed();
    }

    Bitmap export() { return committed.copy(Bitmap.Config.ARGB_8888, false); }

    private void rebuild() {
        if (base == null) return;
        if (committed == null || committed.getWidth() != base.getWidth() || committed.getHeight() != base.getHeight()) {
            if (committed != null) committed.recycle();
            committed = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
            commitCanvas = new Canvas(committed);
        }
        commitCanvas.drawBitmap(base, 0, 0, null);
        for (Shape s : shapes) drawShape(commitCanvas, s);
    }

    private void resetView() {
        if (base == null || getWidth() == 0) return;
        float pad = 8 * getResources().getDisplayMetrics().density;
        float vw = getWidth() - 2 * pad, vh = getHeight() - 2 * pad;
        fitScale = Math.min(vw / base.getWidth(), vh / base.getHeight());
        m.reset();
        m.postScale(fitScale, fitScale);
        m.postTranslate((getWidth() - base.getWidth() * fitScale) / 2f, (getHeight() - base.getHeight() * fitScale) / 2f);
        m.invert(inv);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { resetView(); }

    private float scaleOf(Matrix mx) { float[] v = new float[9]; mx.getValues(v); return v[Matrix.MSCALE_X]; }

    private float baseW() { return Math.min(base.getWidth(), base.getHeight()) / 240f; }

    private float widthFor(int t) {
        if (t == MARKER) return baseW() * MARKER_W[sizeLevel];
        if (t == ERASER) return baseW() * ERASER_W[sizeLevel];
        return baseW() * SHAPE_W[sizeLevel];
    }

    @Override
    protected void onDraw(Canvas c) {
        if (base == null) {
            c.drawText("Tippe oben auf „Öffnen“", getWidth() / 2f, getHeight() / 2f, hint);
            return;
        }
        c.save();
        c.concat(m);
        c.drawBitmap(committed, 0, 0, bmpPaint);
        if (current != null) drawShape(c, current);
        c.restore();
        if (showCursor) {
            float r = widthFor(ERASER) / 2f * scaleOf(m);
            cursor.setColor(0xFF000000);
            c.drawCircle(cursorX, cursorY, r + 1.5f, cursor);
            cursor.setColor(0xFFFFFFFF);
            c.drawCircle(cursorX, cursorY, r, cursor);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (base == null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                gesture = false;
                PointF p = toImage(e.getX(), e.getY());
                if (tool == ERASER) {
                    showCursor = true;
                    cursorX = e.getX();
                    cursorY = e.getY();
                }
                if (tool == ERASER && eraserMode == E_OBJECTS) {
                    erasing = true;
                    erasedSomething = false;
                    snapshot();
                    eraseAt(p);
                    invalidate();
                    return true;
                }
                current = new Shape();
                if (tool == ERASER) {
                    current.tool = eraserMode == E_PIXELS ? K_ERASE : K_COVER;
                    current.color = eraserMode == E_COVER ? (coverAuto ? sampleBase(p) : coverColor) : 0;
                } else {
                    current.tool = tool;
                    current.color = color;
                }
                current.w = widthFor(tool);
                current.pts.add(p);
                current.pts.add(new PointF(p.x, p.y));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (e.getPointerCount() == 2) {
                    // second finger: zoom/pan instead of drawing
                    current = null;
                    showCursor = false;
                    if (erasing) {
                        erasing = false;
                        if (!erasedSomething) history.remove(history.size() - 1); else changed();
                    }
                    gesture = true;
                    startM.set(m);
                    startDist = dist(e);
                    startMx = (e.getX(0) + e.getX(1)) / 2f;
                    startMy = (e.getY(0) + e.getY(1)) / 2f;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (gesture && e.getPointerCount() >= 2) {
                    float d = dist(e);
                    float mx = (e.getX(0) + e.getX(1)) / 2f, my = (e.getY(0) + e.getY(1)) / 2f;
                    float f = startDist > 0 ? d / startDist : 1f;
                    float s0 = scaleOf(startM);
                    float target = Math.max(fitScale * 0.9f, Math.min(fitScale * 8f, s0 * f));
                    f = target / s0;
                    m.set(startM);
                    m.postScale(f, f, startMx, startMy);
                    m.postTranslate(mx - startMx, my - startMy);
                    m.invert(inv);
                    invalidate();
                    return true;
                }
                if (gesture) return true;
                cursorX = e.getX();
                cursorY = e.getY();
                PointF p = toImage(e.getX(), e.getY());
                if (erasing) {
                    eraseAt(p);
                    invalidate();
                } else if (current != null) {
                    if (isStroke(current.tool)) {
                        PointF last = current.pts.get(current.pts.size() - 1);
                        if (Math.hypot(p.x - last.x, p.y - last.y) * scaleOf(m) < 2f) return true;
                        if (current.pts.size() == 2 && current.pts.get(0).equals(current.pts.get(1).x, current.pts.get(1).y))
                            current.pts.remove(1);
                        current.pts.add(p);
                    } else {
                        current.pts.set(1, p);
                    }
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                showCursor = false;
                if (gesture) {
                    if (scaleOf(m) <= fitScale * 1.02f) resetView();
                    gesture = false;
                    invalidate();
                } else if (erasing) {
                    erasing = false;
                    if (!erasedSomething) { history.remove(history.size() - 1); invalidate(); }
                    else changed();
                } else if (current != null) {
                    snapshot();
                    shapes.add(current);
                    drawShape(commitCanvas, current);
                    current = null;
                    changed();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                current = null;
                gesture = false;
                showCursor = false;
                if (erasing) {
                    erasing = false;
                    if (!erasedSomething) history.remove(history.size() - 1); else changed();
                }
                invalidate();
                return true;
            }
        }
        return true;
    }

    private static boolean isStroke(int t) { return t == PEN || t == MARKER || t == K_ERASE || t == K_COVER; }

    private int sampleBase(PointF p) {
        int x = Math.max(0, Math.min(base.getWidth() - 1, Math.round(p.x)));
        int y = Math.max(0, Math.min(base.getHeight() - 1, Math.round(p.y)));
        return base.getPixel(x, y) | 0xFF000000;
    }

    /** Object eraser: removes every shape whose outline passes under the eraser circle (newest first). */
    private void eraseAt(PointF p) {
        float r = widthFor(ERASER) / 2f;
        boolean removed = false;
        for (int i = shapes.size() - 1; i >= 0; i--) {
            Shape s = shapes.get(i);
            if (s.tool == K_ERASE) continue;
            if (distanceTo(s, p.x, p.y) <= r + s.w / 2f) {
                if (!removed) shapes = new ArrayList<>(shapes); // keep the snapshot untouched
                shapes.remove(i);
                removed = true;
            }
        }
        if (removed) {
            erasedSomething = true;
            rebuild();
        }
    }

    private static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static float distanceTo(Shape s, float x, float y) {
        ArrayList<PointF> pts = s.pts;
        PointF a = pts.get(0), b = pts.get(pts.size() - 1);
        float best = Float.MAX_VALUE;
        switch (s.tool) {
            case RECT: {
                float l = Math.min(a.x, b.x), r = Math.max(a.x, b.x), t = Math.min(a.y, b.y), bo = Math.max(a.y, b.y);
                best = Math.min(Math.min(segDist(x, y, l, t, r, t), segDist(x, y, r, t, r, bo)),
                        Math.min(segDist(x, y, r, bo, l, bo), segDist(x, y, l, bo, l, t)));
                break;
            }
            case CIRCLE: {
                float cx = (a.x + b.x) / 2f, cy = (a.y + b.y) / 2f, rx = Math.abs(b.x - a.x) / 2f, ry = Math.abs(b.y - a.y) / 2f;
                float px = cx + rx, py = cy;
                for (int i = 1; i <= 72; i++) {
                    double ang = i * Math.PI * 2 / 72;
                    float nx = (float) (cx + rx * Math.cos(ang)), ny = (float) (cy + ry * Math.sin(ang));
                    best = Math.min(best, segDist(x, y, px, py, nx, ny));
                    px = nx; py = ny;
                }
                break;
            }
            case ARROW:
                best = segDist(x, y, a.x, a.y, b.x, b.y);
                break;
            default:
                if (pts.size() == 1) return (float) Math.hypot(x - a.x, y - a.y);
                for (int i = 1; i < pts.size(); i++) {
                    PointF q = pts.get(i - 1), n = pts.get(i);
                    best = Math.min(best, segDist(x, y, q.x, q.y, n.x, n.y));
                }
        }
        return best;
    }

    private float dist(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private PointF toImage(float x, float y) {
        float[] pt = {x, y};
        inv.mapPoints(pt);
        return new PointF(pt[0], pt[1]);
    }

    private void drawShape(Canvas g, Shape s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(s.color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(s.w);
        if (s.tool == MARKER) {
            p.setAlpha(107); // ~42 %
            p.setBlendMode(BlendMode.MULTIPLY);
            p.setStrokeCap(Paint.Cap.BUTT);
        } else if (s.tool == K_ERASE) {
            p.setColor(0xFFFFFFFF);
            p.setShader(baseShader); // paints the original screenshot back
        }
        ArrayList<PointF> pts = s.pts;
        PointF a = pts.get(0), b = pts.get(pts.size() - 1);
        switch (s.tool) {
            case PEN:
            case MARKER:
            case K_ERASE:
            case K_COVER: {
                Path path = new Path();
                path.moveTo(a.x, a.y);
                if (pts.size() == 1 || (pts.size() == 2 && a.equals(b.x, b.y))) {
                    path.lineTo(a.x + 0.5f, a.y);
                    if (s.tool == MARKER) p.setStrokeCap(Paint.Cap.SQUARE);
                } else {
                    for (int i = 1; i < pts.size() - 1; i++) {
                        PointF q = pts.get(i), n = pts.get(i + 1);
                        path.quadTo(q.x, q.y, (q.x + n.x) / 2f, (q.y + n.y) / 2f);
                    }
                    path.lineTo(b.x, b.y);
                }
                g.drawPath(path, p);
                break;
            }
            case RECT:
                g.drawRect(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y), p);
                break;
            case CIRCLE:
                g.drawOval(new RectF(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y)), p);
                break;
            case ARROW: {
                double ang = Math.atan2(b.y - a.y, b.x - a.x);
                float len = (float) Math.hypot(b.x - a.x, b.y - a.y);
                float head = Math.min(s.w * 3.5f + baseW() * 6f, len * 0.6f);
                float bx = (float) (b.x - Math.cos(ang) * head * 0.6), by = (float) (b.y - Math.sin(ang) * head * 0.6);
                p.setStrokeCap(Paint.Cap.BUTT);
                g.drawLine(a.x, a.y, bx, by, p);
                p.setStrokeCap(Paint.Cap.ROUND);
                g.drawCircle(a.x, a.y, s.w / 2f, fill(p));
                Path h = new Path();
                h.moveTo(b.x, b.y);
                h.lineTo((float) (b.x - head * Math.cos(ang - 0.45)), (float) (b.y - head * Math.sin(ang - 0.45)));
                h.lineTo((float) (b.x - head * Math.cos(ang + 0.45)), (float) (b.y - head * Math.sin(ang + 0.45)));
                h.close();
                g.drawPath(h, fill(p));
                break;
            }
        }
    }

    private static Paint fill(Paint p) {
        Paint f = new Paint(p);
        f.setStyle(Paint.Style.FILL);
        return f;
    }
}
