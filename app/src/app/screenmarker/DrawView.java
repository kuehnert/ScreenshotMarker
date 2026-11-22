// Version: 2026-11-22
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
import android.view.HapticFeedbackConstants;
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
    /** One undo step: the picture (it changes when cropping) and the shapes on it. */
    private static class State {
        final Bitmap base;
        final ArrayList<Shape> shapes;
        State(Bitmap b, ArrayList<Shape> s) { base = b; shapes = s; }
    }
    private final ArrayList<State> history = new ArrayList<>();

    // ---- cropping ----
    static final int C_L = 1, C_T = 2, C_R = 4, C_B = 8, C_MOVE = 16;
    boolean cropping = false;
    boolean snapEnabled = true;
    private final RectF crop = new RectF(), cropStart = new RectF();
    private int cropDrag = 0;
    private float dragX, dragY;
    private int snappedEdges = 0;       // which crop edges sit on a detected line right now
    private byte[] lum;                 // luminance of the picture, for line detection
    private int lumW, lumH;
    private final Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        cropping = false;
        lum = null;
        base = b;
        baseShader = new BitmapShader(base, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shapes = new ArrayList<>();
        history.clear();
        current = null;
        rebuild();
        resetView();
        changed();
    }

    private void snapshot() { history.add(new State(base, new ArrayList<>(shapes))); }

    void undo() {
        if (history.isEmpty() || cropping) return;
        State s = history.remove(history.size() - 1);
        boolean newPicture = s.base != base;
        base = s.base;
        shapes = s.shapes;
        if (newPicture) baseShader = new BitmapShader(base, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        rebuild();
        if (newPicture) resetView();
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
        // while cropping, leave room around the picture so the handles at its edges can be grabbed
        float pad = (cropping ? 26 : 8) * getResources().getDisplayMetrics().density;
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
        if (cropping) { drawCrop(c); return; }
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
        if (cropping) return cropTouch(e);
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

    // ================================ cropping ================================

    private void notifyUi() { if (listener != null) listener.run(); invalidate(); }

    void startCrop() {
        if (base == null || cropping) return;
        cropping = true;
        current = null;
        crop.set(0, 0, base.getWidth(), base.getHeight());
        snappedEdges = 0;
        computeLuminance();
        resetView();
        notifyUi();
    }

    void cancelCrop() {
        if (!cropping) return;
        cropping = false;
        lum = null;
        resetView();
        notifyUi();
    }

    void resetCrop() {
        crop.set(0, 0, base.getWidth(), base.getHeight());
        snappedEdges = 0;
        invalidate();
    }

    /** Cuts the picture to the frame. Shapes move along, so they stay where they were on the picture. */
    void applyCrop() {
        if (!cropping) return;
        int w0 = base.getWidth(), h0 = base.getHeight();
        int l = clampInt(Math.round(crop.left), 0, w0 - 1), t = clampInt(Math.round(crop.top), 0, h0 - 1);
        int r = clampInt(Math.round(crop.right), l + 1, w0), b = clampInt(Math.round(crop.bottom), t + 1, h0);
        if (l == 0 && t == 0 && r == w0 && b == h0) { cancelCrop(); return; }
        snapshot();
        base = Bitmap.createBitmap(base, l, t, r - l, b - t);
        baseShader = new BitmapShader(base, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        ArrayList<Shape> moved = new ArrayList<>();
        for (Shape s : shapes) {
            Shape n = new Shape();
            n.tool = s.tool;
            n.color = s.color;
            n.w = s.w;
            for (PointF p : s.pts) n.pts.add(new PointF(p.x - l, p.y - t));
            moved.add(n);
        }
        shapes = moved;
        cropping = false;
        lum = null;
        rebuild();
        resetView();
        changed();
    }

    /** Frames the picture without its plain-coloured margins. Returns false when there are none. */
    boolean autoCrop() {
        int w = base.getWidth(), h = base.getHeight();
        int t = 0, b = h, l = 0, r = w;
        int ref = base.getPixel(w / 2, 0);
        while (t < h - 16 && uniformRow(t, ref)) t++;
        ref = base.getPixel(w / 2, h - 1);
        while (b > t + 16 && uniformRow(b - 1, ref)) b--;
        ref = base.getPixel(0, h / 2);
        while (l < w - 16 && uniformCol(l, ref, t, b)) l++;
        ref = base.getPixel(w - 1, h / 2);
        while (r > l + 16 && uniformCol(r - 1, ref, t, b)) r--;
        if (t == 0 && b == h && l == 0 && r == w) return false;
        crop.set(l, t, r, b);
        snappedEdges = 0;
        invalidate();
        return true;
    }

    private static boolean close(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= 16 && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= 16
                && Math.abs((a & 255) - (b & 255)) <= 16;
    }

    private boolean uniformRow(int y, int ref) {
        int w = base.getWidth(), step = Math.max(1, w / 300), n = 0, ok = 0;
        for (int x = 0; x < w; x += step) { n++; if (close(base.getPixel(x, y), ref)) ok++; }
        return ok >= n * 0.98f;
    }

    private boolean uniformCol(int x, int ref, int y0, int y1) {
        int step = Math.max(1, (y1 - y0) / 300), n = 0, ok = 0;
        for (int y = y0; y < y1; y += step) { n++; if (close(base.getPixel(x, y), ref)) ok++; }
        return ok >= n * 0.98f;
    }

    private void computeLuminance() {
        lumW = base.getWidth();
        lumH = base.getHeight();
        lum = new byte[lumW * lumH];
        int[] row = new int[lumW];
        for (int y = 0; y < lumH; y++) {
            base.getPixels(row, 0, lumW, 0, y, lumW, 1);
            int o = y * lumW;
            for (int x = 0; x < lumW; x++) {
                int p = row[x];
                lum[o + x] = (byte) ((((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8);
            }
        }
    }

    private static final int EDGE = 12; // brightness step that counts as a line: low enough for pale cards on white

    /** Share of the span [a0, a1) where there is a brightness step between row y-1 and row y. */
    private float rowEdge(int y, int a0, int a1) {
        int step = Math.max(1, (a1 - a0) / 400), n = 0, hits = 0, o = y * lumW, p = (y - 1) * lumW;
        for (int x = a0; x < a1; x += step) {
            n++;
            if (Math.abs((lum[o + x] & 255) - (lum[p + x] & 255)) > EDGE) hits++;
        }
        return n == 0 ? 0 : (float) hits / n;
    }

    /** Share of the span [a0, a1) where there is a brightness step between column x-1 and column x. */
    private float colEdge(int x, int a0, int a1) {
        int step = Math.max(1, (a1 - a0) / 400), n = 0, hits = 0;
        for (int y = a0; y < a1; y += step) {
            n++;
            int o = y * lumW;
            if (Math.abs((lum[o + x] & 255) - (lum[o + x - 1] & 255)) > EDGE) hits++;
        }
        return n == 0 ? 0 : (float) hits / n;
    }

    /**
     * The nearest strong straight line to v, or NaN. A line counts when at least a third of the frame's
     * span crosses it; nearer and longer lines win. The picture's own borders always count.
     */
    private float snap(float v, boolean horizontal, float spanFrom, float spanTo) {
        if (!snapEnabled || lum == null) return Float.NaN;
        int limit = horizontal ? lumH : lumW, other = horizontal ? lumW : lumH;
        float r = Math.max(3f, 20 * getResources().getDisplayMetrics().density / scaleOf(m));
        int a0 = clampInt((int) spanFrom, 0, other), a1 = clampInt((int) spanTo, 0, other);
        if (a1 - a0 < 8) { a0 = 0; a1 = other; }
        float best = Float.NaN, bestScore = 0f;
        for (int edge : new int[]{0, limit}) {
            float dist = Math.abs(edge - v);
            if (dist <= r && 1f - 0.5f * dist / r > bestScore) { bestScore = 1f - 0.5f * dist / r; best = edge; }
        }
        int from = Math.max(1, (int) Math.floor(v - r)), to = Math.min(limit - 1, (int) Math.ceil(v + r));
        for (int i = from; i <= to; i++) {
            float s = horizontal ? rowEdge(i, a0, a1) : colEdge(i, a0, a1);
            if (s < 0.33f) continue;
            float score = s - 0.5f * Math.abs(i - v) / r;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private boolean cropTouch(MotionEvent e) {
        float d = getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                RectF vr = new RectF();
                m.mapRect(vr, crop);
                float x = e.getX(), y = e.getY(), grab = 28 * d;
                int flags = 0;
                boolean nearL = Math.abs(x - vr.left) < grab, nearR = Math.abs(x - vr.right) < grab;
                boolean nearT = Math.abs(y - vr.top) < grab, nearB = Math.abs(y - vr.bottom) < grab;
                boolean inX = x > vr.left - grab && x < vr.right + grab, inY = y > vr.top - grab && y < vr.bottom + grab;
                if (inY) { if (nearL && (!nearR || Math.abs(x - vr.left) < Math.abs(x - vr.right))) flags |= C_L; else if (nearR) flags |= C_R; }
                if (inX) { if (nearT && (!nearB || Math.abs(y - vr.top) < Math.abs(y - vr.bottom))) flags |= C_T; else if (nearB) flags |= C_B; }
                if (flags == 0 && vr.contains(x, y)) flags = C_MOVE;
                cropDrag = flags;
                cropStart.set(crop);
                dragX = x;
                dragY = y;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (cropDrag == 0 || e.getPointerCount() > 1) return true;
                float s = scaleOf(m);
                float dx = (e.getX() - dragX) / s, dy = (e.getY() - dragY) / s;
                dragCrop(dx, dy);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cropDrag = 0;
                invalidate();
                return true;
        }
        return true;
    }

    private void dragCrop(float dx, float dy) {
        float w0 = base.getWidth(), h0 = base.getHeight();
        float min = Math.max(16f, 48 * getResources().getDisplayMetrics().density / scaleOf(m));
        int was = snappedEdges;
        snappedEdges = 0;
        if (cropDrag == C_MOVE) {
            float w = cropStart.width(), h = cropStart.height();
            float l = clamp(cropStart.left + dx, 0, w0 - w), t = clamp(cropStart.top + dy, 0, h0 - h);
            // snap whichever side is nearer to a line, keeping the size
            float sl = snap(l, false, t, t + h), sr = snap(l + w, false, t, t + h);
            float al = Float.isNaN(sl) ? Float.MAX_VALUE : Math.abs(sl - l), ar = Float.isNaN(sr) ? Float.MAX_VALUE : Math.abs(sr - l - w);
            if (al <= ar && al != Float.MAX_VALUE) { l = clamp(sl, 0, w0 - w); snappedEdges |= C_L; }
            else if (ar != Float.MAX_VALUE) { l = clamp(sr - w, 0, w0 - w); snappedEdges |= C_R; }
            float st = snap(t, true, l, l + w), sb = snap(t + h, true, l, l + w);
            float at = Float.isNaN(st) ? Float.MAX_VALUE : Math.abs(st - t), ab = Float.isNaN(sb) ? Float.MAX_VALUE : Math.abs(sb - t - h);
            if (at <= ab && at != Float.MAX_VALUE) { t = clamp(st, 0, h0 - h); snappedEdges |= C_T; }
            else if (ab != Float.MAX_VALUE) { t = clamp(sb - h, 0, h0 - h); snappedEdges |= C_B; }
            crop.set(l, t, l + w, t + h);
        } else {
            float l = cropStart.left, t = cropStart.top, r = cropStart.right, b = cropStart.bottom;
            if ((cropDrag & C_L) != 0) l = clamp(l + dx, 0, r - min);
            if ((cropDrag & C_R) != 0) r = clamp(r + dx, l + min, w0);
            if ((cropDrag & C_T) != 0) t = clamp(t + dy, 0, b - min);
            if ((cropDrag & C_B) != 0) b = clamp(b + dy, t + min, h0);
            if ((cropDrag & C_L) != 0) { float v = snap(l, false, t, b); if (!Float.isNaN(v) && v <= r - min) { l = v; snappedEdges |= C_L; } }
            if ((cropDrag & C_R) != 0) { float v = snap(r, false, t, b); if (!Float.isNaN(v) && v >= l + min) { r = v; snappedEdges |= C_R; } }
            if ((cropDrag & C_T) != 0) { float v = snap(t, true, l, r); if (!Float.isNaN(v) && v <= b - min) { t = v; snappedEdges |= C_T; } }
            if ((cropDrag & C_B) != 0) { float v = snap(b, true, l, r); if (!Float.isNaN(v) && v >= t + min) { b = v; snappedEdges |= C_B; } }
            crop.set(l, t, r, b);
        }
        if ((snappedEdges & ~was) != 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void drawCrop(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        RectF vr = new RectF();
        m.mapRect(vr, crop);
        float w = getWidth(), h = getHeight();
        cropPaint.setStyle(Paint.Style.FILL);
        cropPaint.setColor(0xA6000000);
        c.drawRect(0, 0, w, vr.top, cropPaint);
        c.drawRect(0, vr.bottom, w, h, cropPaint);
        c.drawRect(0, vr.top, vr.left, vr.bottom, cropPaint);
        c.drawRect(vr.right, vr.top, w, vr.bottom, cropPaint);

        cropPaint.setStyle(Paint.Style.STROKE);
        if (cropDrag != 0) { // thirds while dragging
            cropPaint.setStrokeWidth(1 * d);
            cropPaint.setColor(0x66FFFFFF);
            for (int i = 1; i < 3; i++) {
                float x = vr.left + vr.width() * i / 3f, y = vr.top + vr.height() * i / 3f;
                c.drawLine(x, vr.top, x, vr.bottom, cropPaint);
                c.drawLine(vr.left, y, vr.right, y, cropPaint);
            }
        }
        cropPaint.setStrokeWidth(1.5f * d);
        cropPaint.setColor(0xFFFFFFFF);
        c.drawRect(vr, cropPaint);

        // edges that sit on a line in the picture light up
        cropPaint.setStrokeWidth(3 * d);
        cropPaint.setColor(MainActivity.ACCENT);
        if ((snappedEdges & C_L) != 0) c.drawLine(vr.left, vr.top, vr.left, vr.bottom, cropPaint);
        if ((snappedEdges & C_R) != 0) c.drawLine(vr.right, vr.top, vr.right, vr.bottom, cropPaint);
        if ((snappedEdges & C_T) != 0) c.drawLine(vr.left, vr.top, vr.right, vr.top, cropPaint);
        if ((snappedEdges & C_B) != 0) c.drawLine(vr.left, vr.bottom, vr.right, vr.bottom, cropPaint);

        // corner and edge handles
        cropPaint.setStrokeWidth(4 * d);
        cropPaint.setStrokeCap(Paint.Cap.ROUND);
        cropPaint.setColor(0xFFFFFFFF);
        float k = Math.min(20 * d, Math.min(vr.width(), vr.height()) / 3f);
        Path p = new Path();
        p.moveTo(vr.left, vr.top + k); p.lineTo(vr.left, vr.top); p.lineTo(vr.left + k, vr.top);
        p.moveTo(vr.right - k, vr.top); p.lineTo(vr.right, vr.top); p.lineTo(vr.right, vr.top + k);
        p.moveTo(vr.right, vr.bottom - k); p.lineTo(vr.right, vr.bottom); p.lineTo(vr.right - k, vr.bottom);
        p.moveTo(vr.left + k, vr.bottom); p.lineTo(vr.left, vr.bottom); p.lineTo(vr.left, vr.bottom - k);
        float cx = vr.centerX(), cy = vr.centerY(), e = Math.min(12 * d, k / 2f);
        p.moveTo(cx - e, vr.top); p.lineTo(cx + e, vr.top);
        p.moveTo(cx - e, vr.bottom); p.lineTo(cx + e, vr.bottom);
        p.moveTo(vr.left, cy - e); p.lineTo(vr.left, cy + e);
        p.moveTo(vr.right, cy - e); p.lineTo(vr.right, cy + e);
        c.drawPath(p, cropPaint);
        cropPaint.setStrokeCap(Paint.Cap.BUTT);

        // size in pixels, above the frame (or inside it at the very top)
        String label = Math.round(crop.width()) + " × " + Math.round(crop.height());
        hint.setTextAlign(Paint.Align.CENTER);
        float ty = vr.top - 12 * d;
        if (ty < 18 * d) ty = vr.top + 24 * d;
        cropPaint.setStyle(Paint.Style.FILL);
        cropPaint.setColor(0xCC000000);
        float tw = hint.measureText(label) / 2f + 10 * d;
        c.drawRoundRect(cx - tw, ty - 17 * d, cx + tw, ty + 7 * d, 12 * d, 12 * d, cropPaint);
        int old = hint.getColor();
        hint.setColor(0xFFFFFFFF);
        c.drawText(label, cx, ty, hint);
        hint.setColor(old);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

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
