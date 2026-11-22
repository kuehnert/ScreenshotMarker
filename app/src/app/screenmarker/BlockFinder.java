// Version: 2026-11-22
package app.screenmarker;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Finds blocks of content in a screenshot: areas that empty background separates from the rest, like a
 * game board, a card or a grid of letters. Plain Java without Android, so it can be tested on any JVM.
 *
 * Every pixel that differs from the background colour (the most common one) by more than 12 in a channel
 * counts as content; a summed-area table answers "how much content is in this rectangle" in constant time.
 * Very large pictures are looked at on a grid of cells of `cell` pixels. All public coordinates are pixels.
 */
public final class BlockFinder {

    private final int[] sat;
    private final int cell, aw, ah, w, h;
    public final int background;

    /** @param pixels the picture as ARGB, row by row (width * height values) */
    public BlockFinder(int[] pixels, int width, int height) {
        w = width;
        h = height;
        int c = 1;
        while ((long) (w / c + 1) * (h / c + 1) > 4_000_000L) c++;
        cell = c;
        aw = w / cell;
        ah = h / cell;

        HashMap<Integer, Integer> counts = new HashMap<>();
        int stepX = Math.max(1, w / 120), stepY = Math.max(1, h / 240), bg = 0xFFFFFF, most = 0;
        for (int y = 0; y < h; y += stepY)
            for (int x = 0; x < w; x += stepX) {
                int p = pixels[y * w + x] & 0xF8F8F8;
                Integer n0 = counts.get(p);
                int n = n0 == null ? 1 : n0 + 1;
                counts.put(p, n);
                if (n > most) { most = n; bg = p; }
            }
        // the exact colour: average the real pixels that fall into the winning bucket
        long sr = 0, sg = 0, sb = 0, n = 0;
        for (int y = 0; y < h; y += stepY)
            for (int x = 0; x < w; x += stepX) {
                int p = pixels[y * w + x];
                if ((p & 0xF8F8F8) == bg) { sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255; n++; }
            }
        int br = (int) (sr / n), bgG = (int) (sg / n), bb = (int) (sb / n);
        background = 0xFF000000 | (br << 16) | (bgG << 8) | bb;

        sat = new int[(aw + 1) * (ah + 1)];
        for (int y = 0; y < ah; y++) {
            int run = 0, o = (y + 1) * (aw + 1), u = y * (aw + 1), src = y * cell * w;
            for (int x = 0; x < aw; x++) {
                int p = pixels[src + x * cell];
                int d = Math.max(Math.abs(((p >> 16) & 255) - br), Math.max(Math.abs(((p >> 8) & 255) - bgG), Math.abs((p & 255) - bb)));
                if (d > 12) run++; // 12: sees pale cards on white, not JPEG noise
                sat[o + x + 1] = sat[u + x + 1] + run;
            }
        }
    }

    private int sum(int l, int t, int r, int b) {
        int W = aw + 1;
        return sat[b * W + r] - sat[t * W + r] - sat[b * W + l] + sat[t * W + l];
    }

    private boolean rowEmpty(int y, int l, int r) { return sum(l, y, r, y + 1) <= (r - l) / 500; }
    private boolean colEmpty(int x, int t, int b) { return sum(x, t, x + 1, b) <= (b - t) / 500; }

    /** Grows a block (in cells) until at least `gap` empty rows and columns surround it on every side. */
    private int[] grow(int[] start, int gap) {
        int l = start[0], t = start[1], r = start[2], b = start[3];
        for (int pass = 0; pass < 16; pass++) {
            int ol = l, ot = t, or = r, ob = b;
            int y = t - 1, run = 0, last = t;
            while (y >= 0) { if (rowEmpty(y, l, r)) { if (++run >= gap) break; } else { run = 0; last = y; } y--; }
            t = last;
            y = b; run = 0; last = b;
            while (y < ah) { if (rowEmpty(y, l, r)) { if (++run >= gap) break; } else { run = 0; last = y + 1; } y++; }
            b = last;
            int x = l - 1;
            run = 0; last = l;
            while (x >= 0) { if (colEmpty(x, t, b)) { if (++run >= gap) break; } else { run = 0; last = x; } x--; }
            l = last;
            x = r; run = 0; last = r;
            while (x < aw) { if (colEmpty(x, t, b)) { if (++run >= gap) break; } else { run = 0; last = x + 1; } x++; }
            r = last;
            if (l == ol && t == ot && r == or && b == ob) break;
        }
        return new int[]{l, t, r, b};
    }

    /** A block found by a tap; `next` grows it to the next larger one. */
    public final class Block {
        private final int[] cells;
        private final int gap;
        private Block(int[] cells, int gap) { this.cells = cells; this.gap = gap; }

        /** The block in pixels: left, top, right, bottom. */
        public int[] rect() { return new int[]{cells[0] * cell, cells[1] * cell, Math.min(w, cells[2] * cell), Math.min(h, cells[3] * cell)}; }

        /** The next larger block around this one, or null when it already is the largest. */
        public Block next() {
            int g = gap;
            while (g < Math.max(aw, ah)) {
                g += Math.max(1, g / 12);
                int[] b = grow(cells, g);
                if (!Arrays.equals(b, cells)) return new Block(b, g);
            }
            return null;
        }
    }

    /**
     * The smallest block at (x, y): starts at the nearest content pixel within `reach` pixels and grows
     * until empty space surrounds it. Null when there is no content within reach.
     */
    public Block blockAt(float x, float y, float reach) {
        int cx = (int) (x / cell), cy = (int) (y / cell), rc = Math.max(2, (int) (reach / cell));
        if (cx < 0 || cy < 0 || cx >= aw || cy >= ah) return null;
        for (int r = 0; r <= rc; r++)
            for (int yy = Math.max(0, cy - r); yy <= Math.min(ah - 1, cy + r); yy++)
                for (int xx = Math.max(0, cx - r); xx <= Math.min(aw - 1, cx + r); xx++)
                    if (sum(xx, yy, xx + 1, yy + 1) > 0) {
                        int gap = Math.max(3, Math.round(w / 180f / cell));
                        return new Block(grow(new int[]{xx, yy, xx + 1, yy + 1}, gap), gap);
                    }
        return null;
    }

    /** The content inside a rectangle (pixels), pulled in tight; null if the rectangle is empty. */
    public int[] tighten(float left, float top, float right, float bottom) {
        int l = clamp((int) Math.floor(left / cell), 0, aw), t = clamp((int) Math.floor(top / cell), 0, ah);
        int r = clamp((int) Math.ceil(right / cell), l, aw), b = clamp((int) Math.ceil(bottom / cell), t, ah);
        while (t < b && rowEmpty(t, l, r)) t++;
        while (b > t && rowEmpty(b - 1, l, r)) b--;
        while (l < r && colEmpty(l, t, b)) l++;
        while (r > l && colEmpty(r - 1, t, b)) r--;
        if (r - l < 2 || b - t < 2) return null;
        return new int[]{l * cell, t * cell, Math.min(w, r * cell), Math.min(h, b * cell)};
    }

    /**
     * The empty rows around y between left and right, as pixel rows [from, to) to cut out so that `keep`
     * pixels of space remain. Null when y is not in a gap, the gap touches the picture's border, or it is
     * no wider than `keep` already.
     */
    public int[] gapAround(float y, float left, float right, int keep) {
        int cy = (int) (y / cell);
        int l = clamp((int) Math.floor(left / cell), 0, aw), r = clamp((int) Math.ceil(right / cell), l, aw);
        if (cy < 0 || cy >= ah || r - l < 1 || !rowEmpty(cy, l, r)) return null;
        int t = cy, b = cy + 1;
        while (t > 0 && rowEmpty(t - 1, l, r)) t--;
        while (b < ah && rowEmpty(b, l, r)) b++;
        if (t == 0 || b == ah) return null;
        int from = t * cell + keep / 2, to = b * cell - (keep - keep / 2);
        return to - from >= 2 ? new int[]{from, to} : null;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
