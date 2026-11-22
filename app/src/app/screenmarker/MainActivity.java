// Version: 2026-11-22
package app.screenmarker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    static final int BAR = 0xFF1F2026;
    static final int STAGE = 0xFF0E0F12;
    static final int FG = 0xFFECECF1;
    static final int MUTED = 0xFF9A9DAA;
    static final int CHIP = 0xFF33353E;
    static final int ACCENT = 0xFF7D9BFF;

    static final int[] DEFAULT_COLORS = {0xFFF5C400, 0xFF3FA34D, 0xFF2F6FDE, 0xFF9B4DCA, 0xFFE03131, 0xFF111111};
    static final int[] PRESETS = {
            0xFFF5C400, 0xFFFF9800, 0xFFE03131, 0xFFE91E63, 0xFF9B4DCA, 0xFF2F6FDE, 0xFF00ACC1, 0xFF3FA34D,
            0xFFF9DF6D, 0xFFA0C35A, 0xFFB0C4EF, 0xFFBA81C5, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF555555, 0xFF111111};
    static final int[] TOOLS = {DrawView.MARKER, DrawView.PEN, DrawView.ARROW, DrawView.RECT, DrawView.CIRCLE, DrawView.ERASER};
    static final String[] TOOL_LABELS = {"Marker", "Stift", "Pfeil", "Rahmen", "Kreis", "Radierer"};
    static final int[] TOOL_ICONS = {IconButton.I_MARKER, IconButton.I_PEN, IconButton.I_ARROW, IconButton.I_RECT, IconButton.I_CIRCLE, IconButton.I_ERASER};
    static final int[] ERASER_MODES = {DrawView.E_OBJECTS, DrawView.E_PIXELS, DrawView.E_COVER};
    static final String[] ERASER_LABELS = {"Objekte", "Radieren", "Übermalen"};
    static final int[] ERASER_ICONS = {IconButton.I_E_OBJECTS, IconButton.I_E_PIXELS, IconButton.I_E_COVER};
    static final String[] ERASER_HINTS = {
            "Ganze Striche und Formen antippen oder überstreichen",
            "Markierungen wegwischen, das Originalbild bleibt",
            "Im Bild übermalen – Farbe rechts wählen"};

    static final int REQ_PICK = 1;

    interface ColorCallback { void picked(int color, boolean auto); }

    DrawView draw;
    IconButton undoBtn, clearBtn, shareBtn, saveBtn, openBtn, cropBtn, snapBtn;
    LinearLayout toolsPanel, cropPanel;
    final ArrayList<IconButton> toolBtns = new ArrayList<>();
    final ArrayList<IconButton> eraserBtns = new ArrayList<>();
    final ArrayList<ColorDot> colorDots = new ArrayList<>();
    final ArrayList<ColorDot> sizeDots = new ArrayList<>();
    LinearLayout colorsBox, eraserBox;
    ColorDot coverDot;
    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());

    final int[] colors = DEFAULT_COLORS.clone();
    int colorSlot = 0;

    boolean clearArmed = false;
    final Runnable disarm = new Runnable() { public void run() { clearArmed = false; clearBtn.setDanger(false); } };

    Uri savedUri = null;      // last saved file, reused for sharing while unchanged
    int savedVersion = -1;
    boolean busy = false;

    float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        loadColors();
        getWindow().setStatusBarColor(BAR);
        getWindow().setNavigationBarColor(BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(STAGE);

        // ---- top bar ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setBackgroundColor(BAR);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) dp(4);
        top.setPadding(p, p, p, p);
        openBtn = topButton(top, IconButton.I_OPEN, "Öffnen");
        undoBtn = topButton(top, IconButton.I_UNDO, "Zurück");
        clearBtn = topButton(top, IconButton.I_TRASH, "Löschen");
        cropBtn = topButton(top, IconButton.I_CROP, "Zuschnitt");
        shareBtn = topButton(top, IconButton.I_SHARE, "Teilen");
        saveBtn = topButton(top, IconButton.I_SAVE, "Speichern");
        saveBtn.setPrimary(true);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- drawing area ----
        draw = new DrawView(this);
        draw.setBackgroundColor(STAGE);
        root.addView(draw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- bottom toolbar ----
        LinearLayout bottom = new LinearLayout(this);
        toolsPanel = bottom;
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(BAR);
        bottom.setPadding(p, p, p, p * 2);

        LinearLayout toolRow = new LinearLayout(this);
        for (int i = 0; i < TOOLS.length; i++) {
            final int tool = TOOLS[i];
            IconButton b = new IconButton(this, TOOL_ICONS[i], TOOL_LABELS[i]);
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setTool(tool); } });
            toolRow.addView(b, new LinearLayout.LayoutParams(0, (int) dp(54), 1f));
            toolBtns.add(b);
        }
        bottom.addView(toolRow);

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setGravity(Gravity.CENTER_VERTICAL);

        // colors (normal tools)
        colorsBox = new LinearLayout(this);
        colorsBox.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < colors.length; i++) {
            final int slot = i;
            ColorDot d = new ColorDot(this, colors[i], i < 4 ? String.valueOf(i + 1) : null, 0);
            d.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setColorSlot(slot); } });
            d.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { editColorSlot(slot); return true; }
            });
            colorsBox.addView(d, new LinearLayout.LayoutParams(0, (int) dp(54), 1f));
            colorDots.add(d);
        }
        secondRow.addView(colorsBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 6f));

        // eraser modes (eraser tool)
        eraserBox = new LinearLayout(this);
        eraserBox.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < ERASER_MODES.length; i++) {
            final int mode = ERASER_MODES[i];
            IconButton b = new IconButton(this, ERASER_ICONS[i], ERASER_LABELS[i]);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    setEraserMode(mode);
                    Toast.makeText(MainActivity.this, ERASER_HINTS[mode], Toast.LENGTH_SHORT).show();
                }
            });
            eraserBox.addView(b, new LinearLayout.LayoutParams(0, (int) dp(54), 1.3f));
            eraserBtns.add(b);
        }
        coverDot = new ColorDot(this, 0xFFFFFFFF, null, 0);
        coverDot.setContentDescription("Farbe zum Übermalen");
        View.OnClickListener coverClick = new View.OnClickListener() { public void onClick(View v) { editCoverColor(); } };
        coverDot.setOnClickListener(coverClick);
        coverDot.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { editCoverColor(); return true; } });
        eraserBox.addView(coverDot, new LinearLayout.LayoutParams(0, (int) dp(54), 1.1f));
        secondRow.addView(eraserBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 6f));

        View sep = new View(this);
        sep.setBackgroundColor(CHIP);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams((int) dp(1), (int) dp(28));
        sl.leftMargin = sl.rightMargin = (int) dp(4);
        secondRow.addView(sep, sl);

        LinearLayout sizesBox = new LinearLayout(this);
        sizesBox.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 3; i++) {
            final int level = i;
            ColorDot d = new ColorDot(this, FG, null, i + 1);
            d.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setSizeLevel(level); } });
            sizesBox.addView(d, new LinearLayout.LayoutParams(0, (int) dp(54), 1f));
            sizeDots.add(d);
        }
        secondRow.addView(sizesBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3.2f));
        bottom.addView(secondRow);
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- crop bar, in place of the tools while cropping ----
        cropPanel = new LinearLayout(this);
        cropPanel.setOrientation(LinearLayout.VERTICAL);
        cropPanel.setBackgroundColor(BAR);
        cropPanel.setPadding(p, p * 2, p, p * 2);
        android.widget.TextView cropHint = new android.widget.TextView(this);
        cropHint.setText("Ecken und Kanten ziehen, oder den Rahmen verschieben");
        cropHint.setTextColor(MUTED);
        cropHint.setTextSize(13);
        cropHint.setGravity(Gravity.CENTER);
        cropPanel.addView(cropHint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout cropRow = new LinearLayout(this);
        IconButton cancelCrop = cropButton(cropRow, IconButton.I_CLOSE, "Abbrechen");
        IconButton fullCrop = cropButton(cropRow, IconButton.I_FULL, "Alles");
        IconButton autoCrop = cropButton(cropRow, IconButton.I_AUTO, "Auto");
        snapBtn = cropButton(cropRow, IconButton.I_MAGNET, "Einrasten");
        IconButton applyCrop = cropButton(cropRow, IconButton.I_CHECK, "Fertig");
        applyCrop.setPrimary(true);
        cropPanel.addView(cropRow);
        cropPanel.setVisibility(View.GONE);
        root.addView(cropPanel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cropBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { draw.startCrop(); } });
        cancelCrop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { draw.cancelCrop(); } });
        fullCrop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { draw.resetCrop(); } });
        autoCrop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!draw.autoCrop()) Toast.makeText(MainActivity.this, "Keinen einfarbigen Rand gefunden", Toast.LENGTH_SHORT).show();
            }
        });
        snapBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                draw.snapEnabled = !draw.snapEnabled;
                prefs.edit().putBoolean("snap", draw.snapEnabled).apply();
                snapBtn.setSelectedState(draw.snapEnabled);
                Toast.makeText(MainActivity.this, draw.snapEnabled ? "Kanten rasten an Linien im Bild ein" : "Einrasten aus", Toast.LENGTH_SHORT).show();
            }
        });
        applyCrop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { draw.applyCrop(); } });
        draw.snapEnabled = prefs.getBoolean("snap", true);
        snapBtn.setSelectedState(draw.snapEnabled);

        setContentView(root);

        // ---- actions ----
        openBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickImage(); } });
        undoBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { draw.undo(); } });
        clearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!clearArmed) {
                    clearArmed = true;
                    clearBtn.setDanger(true);
                    Toast.makeText(MainActivity.this, "Nochmal tippen, um alles zu löschen", Toast.LENGTH_SHORT).show();
                    ui.postDelayed(disarm, 2500);
                } else {
                    ui.removeCallbacks(disarm);
                    disarm.run();
                    draw.clearAll();
                }
            }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { save(false); } });
        shareBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { save(true); } });
        draw.setListener(new Runnable() { public void run() { updateButtons(); } });

        // ---- restore settings ----
        draw.coverAuto = prefs.getBoolean("coverAuto", true);
        draw.coverColor = prefs.getInt("coverColor", 0xFFFFFFFF);
        updateCoverDot();
        setEraserMode(prefs.getInt("eraserMode", DrawView.E_OBJECTS));
        setColorSlot(Math.max(0, Math.min(colors.length - 1, prefs.getInt("colorSlot", 0))));
        setSizeLevel(Math.max(0, Math.min(2, prefs.getInt("sizeLevel", 0))));
        setTool(prefs.getInt("tool", DrawView.MARKER));
        updateButtons();

        if (!prefs.getBoolean("tipShown", false)) {
            prefs.edit().putBoolean("tipShown", true).apply();
            Toast.makeText(this, "Tipp: Farbe lange drücken, um sie zu ändern", Toast.LENGTH_LONG).show();
        }

        Uri incoming = imageFromIntent(getIntent());
        if (incoming != null) loadImage(incoming);
        else if (state == null) pickImage();
    }

    IconButton topButton(LinearLayout bar, int icon, String label) {
        IconButton b = new IconButton(this, icon, label);
        bar.addView(b, new LinearLayout.LayoutParams(0, (int) dp(54), 1f));
        return b;
    }

    // ---- settings ----
    void loadColors() {
        String s = prefs.getString("colors", null);
        if (s == null) return;
        String[] parts = s.split(",");
        for (int i = 0; i < parts.length && i < colors.length; i++) {
            try { colors[i] = (int) Long.parseLong(parts[i]); } catch (NumberFormatException ignored) { }
        }
    }

    void saveColors() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colors.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(colors[i] & 0xFFFFFFFFL);
        }
        prefs.edit().putString("colors", sb.toString()).apply();
    }

    void setTool(int t) {
        boolean known = false;
        for (int x : TOOLS) if (x == t) known = true;
        if (!known) t = DrawView.MARKER;
        draw.tool = t;
        prefs.edit().putInt("tool", t).apply();
        for (int i = 0; i < TOOLS.length; i++) toolBtns.get(i).setSelectedState(TOOLS[i] == t);
        boolean eraser = t == DrawView.ERASER;
        colorsBox.setVisibility(eraser ? View.GONE : View.VISIBLE);
        eraserBox.setVisibility(eraser ? View.VISIBLE : View.GONE);
    }

    void setColorSlot(int slot) {
        colorSlot = slot;
        draw.color = colors[slot];
        prefs.edit().putInt("colorSlot", slot).apply();
        for (int i = 0; i < colorDots.size(); i++) colorDots.get(i).setSelectedState(i == slot);
    }

    void setSizeLevel(int level) {
        draw.sizeLevel = level;
        prefs.edit().putInt("sizeLevel", level).apply();
        for (int i = 0; i < sizeDots.size(); i++) sizeDots.get(i).setSelectedState(i == level);
    }

    void setEraserMode(int mode) {
        if (mode < 0 || mode > 2) mode = DrawView.E_OBJECTS;
        draw.eraserMode = mode;
        prefs.edit().putInt("eraserMode", mode).apply();
        for (int i = 0; i < ERASER_MODES.length; i++) eraserBtns.get(i).setSelectedState(ERASER_MODES[i] == mode);
        coverDot.setVisibility(mode == DrawView.E_COVER ? View.VISIBLE : View.INVISIBLE);
    }

    void updateCoverDot() {
        coverDot.setAuto(draw.coverAuto);
        coverDot.setColor(draw.coverColor);
    }

    void editColorSlot(final int slot) {
        showColorPicker("Farbe " + (slot + 1) + " ändern", colors[slot], "Standard", false, new ColorCallback() {
            public void picked(int color, boolean neutral) {
                colors[slot] = neutral ? DEFAULT_COLORS[slot] : color;
                saveColors();
                colorDots.get(slot).setColor(colors[slot]);
                setColorSlot(slot);
            }
        });
    }

    void editCoverColor() {
        showColorPicker("Farbe zum Übermalen", draw.coverColor, "Automatisch", true, new ColorCallback() {
            public void picked(int color, boolean auto) {
                draw.coverAuto = auto;
                if (!auto) draw.coverColor = color;
                prefs.edit().putBoolean("coverAuto", draw.coverAuto).putInt("coverColor", draw.coverColor).apply();
                updateCoverDot();
                Toast.makeText(MainActivity.this, auto ? "Übermalt mit der Farbe unter dem Finger" : "Übermalt mit der gewählten Farbe", Toast.LENGTH_SHORT).show();
            }
        });
    }

    void showColorPicker(String title, int initial, String neutralLabel, final boolean neutralIsAuto, final ColorCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(18);
        box.setPadding(pad, (int) dp(8), pad, 0);

        final ColorPickerView picker = new ColorPickerView(this, initial);
        box.addView(picker);

        if (neutralIsAuto) {
            android.widget.TextView t = new android.widget.TextView(this);
            t.setText("„Automatisch“ nimmt beim Übermalen die Bildfarbe dort, wo du ansetzt.");
            t.setTextColor(MUTED);
            t.setTextSize(13);
            t.setPadding(0, (int) dp(6), 0, 0);
            box.addView(t);
        }

        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(this);
            for (int i = 0; i < 8; i++) {
                final int c = PRESETS[row * 8 + i];
                ColorDot d = new ColorDot(this, c, null, 0);
                d.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { picker.setColor(c); } });
                r.addView(d, new LinearLayout.LayoutParams(0, (int) dp(44), 1f));
            }
            box.addView(r);
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { cb.picked(picker.getColor(), false); }
                })
                .setNegativeButton("Abbrechen", null)
                .setNeutralButton(neutralLabel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { cb.picked(picker.getColor(), true); }
                })
                .show();
    }

    void updateButtons() {
        boolean has = draw.hasImage();
        boolean crop = draw.cropping;
        openBtn.setEnabled(!crop);
        undoBtn.setEnabled(!crop && draw.canUndo());
        clearBtn.setEnabled(!crop && draw.hasShapes());
        cropBtn.setEnabled(has && !crop);
        saveBtn.setEnabled(has && !busy && !crop);
        shareBtn.setEnabled(has && !busy && !crop);
        toolsPanel.setVisibility(crop ? View.GONE : View.VISIBLE);
        cropPanel.setVisibility(crop ? View.VISIBLE : View.GONE);
    }

    IconButton cropButton(LinearLayout row, int icon, String label) {
        IconButton b = new IconButton(this, icon, label);
        row.addView(b, new LinearLayout.LayoutParams(0, (int) dp(56), 1f));
        return b;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (draw.cropping) { draw.cancelCrop(); return; }
        super.onBackPressed();
    }

    // ---- loading ----
    @SuppressWarnings("deprecation")
    Uri imageFromIntent(Intent in) {
        if (in == null) return null;
        String a = in.getAction();
        if (Intent.ACTION_SEND.equals(a)) {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) return (Uri) p;
            if (in.getClipData() != null && in.getClipData().getItemCount() > 0) return in.getClipData().getItemAt(0).getUri();
        } else if (Intent.ACTION_EDIT.equals(a) || Intent.ACTION_VIEW.equals(a)) {
            return in.getData();
        }
        return null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = imageFromIntent(intent);
        if (u != null) loadImage(u);
    }

    void pickImage() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/*");
            try { startActivityForResult(g, REQ_PICK); }
            catch (Exception e2) { Toast.makeText(this, "Keine Bildauswahl gefunden", Toast.LENGTH_LONG).show(); }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) loadImage(data.getData());
    }

    void loadImage(final Uri uri) {
        new Thread(new Runnable() {
            public void run() {
                Bitmap bm = null;
                try {
                    ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
                    bm = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                        public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            d.setMutableRequired(true);
                            Size sz = info.getSize();
                            int max = Math.max(sz.getWidth(), sz.getHeight());
                            if (max > 4096) {
                                float f = 4096f / max;
                                d.setTargetSize(Math.round(sz.getWidth() * f), Math.round(sz.getHeight() * f));
                            }
                        }
                    });
                    if (bm.getConfig() != Bitmap.Config.ARGB_8888) bm = bm.copy(Bitmap.Config.ARGB_8888, true);
                } catch (Throwable t) {
                    bm = null;
                }
                final Bitmap result = bm;
                ui.post(new Runnable() {
                    public void run() {
                        if (result == null) {
                            Toast.makeText(MainActivity.this, "Das Bild konnte nicht geöffnet werden", Toast.LENGTH_LONG).show();
                        } else {
                            draw.setImage(result);
                            savedUri = null;
                        }
                        updateButtons();
                    }
                });
            }
        }).start();
    }

    // ---- saving / sharing ----
    void save(final boolean thenShare) {
        if (!draw.hasImage() || busy) return;
        if (thenShare && savedUri != null && savedVersion == draw.version) { share(savedUri); return; }
        busy = true;
        updateButtons();
        final Bitmap out = draw.export();
        final int version = draw.version;
        new Thread(new Runnable() {
            public void run() {
                Uri uri = null;
                String err = null;
                try {
                    ContentResolver cr = getContentResolver();
                    String name = "markiert_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(new Date()) + ".png";
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Markiert");
                    cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                    uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("insert");
                    OutputStream os = cr.openOutputStream(uri);
                    try { out.compress(Bitmap.CompressFormat.PNG, 100, os); } finally { os.close(); }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                } catch (Throwable t) {
                    err = "Speichern fehlgeschlagen";
                    uri = null;
                }
                out.recycle();
                final Uri u = uri;
                final String e = err;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        updateButtons();
                        if (u == null) { Toast.makeText(MainActivity.this, e, Toast.LENGTH_LONG).show(); return; }
                        savedUri = u;
                        savedVersion = version;
                        if (thenShare) share(u);
                        else Toast.makeText(MainActivity.this, "Gespeichert in Bilder/Markiert", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    void share(Uri u) {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("image/png");
        s.putExtra(Intent.EXTRA_STREAM, u);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "Teilen"));
    }
}
