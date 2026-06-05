package com.limelight;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.limelight.binding.video.GamutCycle;
import com.limelight.binding.video.LibretroHdrUniforms;
import com.limelight.binding.video.PostProcessStatusListener;
import com.limelight.binding.video.PostProcessVideoRenderer;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local test-pattern activity. Renders synthetic frames into the
 * post-process renderer's codec surface so the user can exercise the
 * libretro HDR shader and BFI scheduler with no host, no stream, and
 * no browser. The patterns are drawn with {@link Canvas} primitives via
 * {@code Surface.lockHardwareCanvas()} and flow through the same
 * pipeline as a real video stream.
 *
 * <p>Quick toggles along the bottom cycle gamut, toggle BFI, nudge
 * paper-white, and capture A/B frames without leaving the activity.
 */
public class LocalTestPatternActivity extends AppCompatActivity
        implements SurfaceHolder.Callback, PostProcessStatusListener {

    private static final int PATTERN_GRAY = 0;
    private static final int PATTERN_RAMP = 1;
    private static final int PATTERN_GAMUT = 2;
    private static final int PATTERN_CHECKER = 3;
    private static final int PATTERN_UFO_BORDER = 4;
    private static final int PATTERN_GAME_320x240 = 5;
    private static final int PATTERN_DRACULA = 6;
    private static final int PATTERN_CASTLEVANIA = 7;
    private static final int PATTERN_SONIC = 8;
    private static final String[] PATTERN_NAMES = {
            "200 nit gray", "Ramp + PLUGE", "Rec.709→2020", "ColorChecker",
            "UFO border scroll", "320×240 game frame",
            "Dracula CRT vs LCD", "Castlevania CRT Royale", "Sonic CRT Royale"
    };

    /** Default scroll speed for the UFO border pattern. */
    private static final int DEFAULT_SCROLL_PX_PER_SEC = 400;
    private static final int MAX_SCROLL_PX_PER_SEC = 2000;

    private static final int PAPER_WHITE_STEP = 25;
    private static final int PAPER_WHITE_MIN = 50;
    private static final int PAPER_WHITE_MAX = 1000;

    private SurfaceView outputView;
    private TextView statusText;
    private LinearLayout patternButtonRow;
    private LinearLayout toggleRow;
    private SeekBar speedSlider;
    private TextView speedLabel;
    private final Button[] patternButtons = new Button[9];
    private final Button[] zoomButtons = new Button[ZOOM_PRESETS.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PostProcessVideoRenderer renderer;
    private HandlerThread renderThread;
    private Handler renderHandler;
    private final AtomicBoolean renderLoopRunning = new AtomicBoolean(false);

    /** Real BlurBusters UFO-border asset, decoded as a Bitmap. Drawn
     *  tiled into the codec surface every frame to produce the
     *  classic testufo.com wrapping horizontal scroll. */
    private Bitmap ufoBorderBitmap;
    /** Classic CRT-vs-LCD comparison screenshot. Drawn centred and
     *  scaled to fit the codec surface. Used to verify the
     *  post-process pipeline on real game content (not just
     *  synthetic patterns) — the CRT scanlines + crop should be
     *  clearly visible on this image. */
    private Bitmap draculaBitmap;
    /** Castlevania (Dracula X) with the RetroArch CRT Royale shader
     *  applied. Sourced from xdaimages.com. Drawn the same way as
     *  the Dracula comparison. */
    private Bitmap castlevaniaBitmap;
    /** Sonic the Hedgehog with the RetroArch CRT Royale shader
     *  applied. Sourced from xdaimages.com. Drawn the same way. */
    private Bitmap sonicBitmap;
    private long scrollStartTimeNs;
    /** Read by the render thread, written by the UI thread on slider drag. */
    private volatile int scrollSpeedPxPerSec = DEFAULT_SCROLL_PX_PER_SEC;

    private volatile int currentPattern = PATTERN_GRAY;
    private volatile long frameCount;
    private PreferenceConfiguration prefConfig;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Force the window into wide-color-gamut (Display P3) so the
        // post-process renderer's EGL surface is 10-bit and the HDR
        // shader output is not clamped to 8-bit SDR before reaching the
        // display. This is normally set via android:colorMode in the
        // manifest, but we also set it programmatically in case the
        // manifest entry is overridden.
        try {
            int wide = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT;
            if ((getWindow().getColorMode() & wide) != wide) {
                getWindow().setColorMode(wide);
            }
        } catch (Throwable ignored) {
            // Pre-API 26 devices will throw — the test pattern is
            // still useful in SDR fallback mode.
        }
        prefConfig = PreferenceConfiguration.readPreferences(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        setContentView(R.layout.activity_local_test_pattern);
        outputView = findViewById(R.id.test_pattern_output);
        statusText = findViewById(R.id.test_pattern_status);
        patternButtonRow = findViewById(R.id.test_pattern_buttons);
        toggleRow = findViewById(R.id.test_pattern_toggles);

        textPaint.setTextSize(36);
        textPaint.setColor(Color.WHITE);

        loadUfoBorderBitmap();
        loadScreenshotBitmaps();
        wireSpeedSlider();

        buildPatternButtons();
        buildToggleRow();
        highlightActivePattern();

        outputView.getHolder().addCallback(this);
        updateStatusText();
    }

    private void loadUfoBorderBitmap() {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        ufoBorderBitmap = BitmapFactory.decodeResource(
                getResources(), R.drawable.blurbusters_ufo_border, opts);
        if (ufoBorderBitmap == null) {
            android.util.Log.w("LocalTestPattern", "UFO border bitmap failed to decode");
        } else {
            scrollStartTimeNs = System.nanoTime();
        }
    }

    /**
     * Load the three real-game screenshot bitmaps used for testing
     * the post-process pipeline on actual content (vs synthetic
     * patterns). Decoded as ARGB_8888 to match the codec surface
     * format. Failures are logged but non-fatal — the pattern
     * button for a missing bitmap just shows a blank screen.
     */
    private void loadScreenshotBitmaps() {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        draculaBitmap = BitmapFactory.decodeResource(
                getResources(), R.drawable.dracula_crt_vs_lcd, opts);
        castlevaniaBitmap = BitmapFactory.decodeResource(
                getResources(), R.drawable.castlevania_dracula_crt_royale, opts);
        sonicBitmap = BitmapFactory.decodeResource(
                getResources(), R.drawable.sonic_crt_royale, opts);
        if (draculaBitmap == null) android.util.Log.w("LocalTestPattern", "Dracula bitmap failed to decode");
        if (castlevaniaBitmap == null) android.util.Log.w("LocalTestPattern", "Castlevania bitmap failed to decode");
        if (sonicBitmap == null) android.util.Log.w("LocalTestPattern", "Sonic bitmap failed to decode");
    }

    private void wireSpeedSlider() {
        speedSlider = findViewById(R.id.test_pattern_speed_slider);
        speedLabel = findViewById(R.id.test_pattern_speed_label);
        speedSlider.setMax(MAX_SCROLL_PX_PER_SEC);
        speedSlider.setProgress(DEFAULT_SCROLL_PX_PER_SEC);
        updateSpeedLabel(DEFAULT_SCROLL_PX_PER_SEC);
        speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                scrollSpeedPxPerSec = progress;
                updateSpeedLabel(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
    }

    private void updateSpeedLabel(int pxPerSec) {
        speedLabel.setText(String.format("Scroll: %d px/sec", pxPerSec));
    }

    private void buildPatternButtons() {
        for (int i = 0; i < PATTERN_NAMES.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(PATTERN_NAMES[i]);
            b.setOnClickListener(v -> {
                currentPattern = idx;
                if (idx == PATTERN_UFO_BORDER) {
                    // Reset the scroll clock so the border starts at
                    // position 0 on each selection (rather than wherever
                    // the elapsed-since-launch time happens to be).
                    scrollStartTimeNs = System.nanoTime();
                }
                highlightActivePattern();
            });
            patternButtons[i] = b;
            patternButtonRow.addView(b);
        }
    }

    private void highlightActivePattern() {
        for (int i = 0; i < patternButtons.length; i++) {
            patternButtons[i].setAlpha(i == currentPattern ? 1.0f : 0.55f);
        }
    }

    private void buildToggleRow() {
        addToggle("Mode", v -> cycleHdrMode());
        addToggle("Cycle gamut", v -> cycleGamut());
        addToggle("BFI on/off", v -> toggleBfi());
        addToggle("Paper −", v -> nudgePaperWhite(-PAPER_WHITE_STEP));
        addToggle("Paper +", v -> nudgePaperWhite(PAPER_WHITE_STEP));
        addToggle("Reset", v -> {
            prefConfig.videoHdrPaperWhiteNits = 200;
            prefConfig.videoHdrExpandGamut = LibretroHdrUniforms.GAMUT_ACCURATE;
            prefConfig.videoBfiDarkFrames = 1;
            saveAndApply();
            updateStatusText();
            Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show();
        });
        addToggle("Done", v -> finish());
    }

    private void cycleHdrMode() {
        // SDR (0) -> HDR10 (1) -> scRGB (2) -> SDR
        int next = (prefConfig.videoHdrMode + 1) % 3;
        prefConfig.videoHdrMode = next;
        // Save the pref but DON'T call renderer.updateSettings() — the EGL
        // context is bound to a specific color mode at init time, so we
        // have to tear down and rebuild the renderer to switch modes.
        PreferenceConfiguration.writePostProcessPreferences(
                prefs.edit(), prefConfig);
        prefs.edit().apply();

        teardownRenderer();
        setupRenderer();
        updateStatusText();
        Toast.makeText(this, "Mode: " + modeName(next), Toast.LENGTH_SHORT).show();
    }

    private static String modeName(int mode) {
        switch (mode) {
            case 0: return "SDR";
            case 1: return "HDR10";
            case 2: return "scRGB";
            default: return "?";
        }
    }

    private void addToggle(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        toggleRow.addView(b);
    }

    private void cycleGamut() {
        int next = GamutCycle.next(prefConfig.videoHdrExpandGamut);
        prefConfig.videoHdrExpandGamut = next;
        saveAndApply();
        updateStatusText();
        Toast.makeText(this, "Gamut: " + GamutCycle.name(next), Toast.LENGTH_SHORT).show();
    }

    private void toggleBfi() {
        prefConfig.videoBlackFrameInsertion = !prefConfig.videoBlackFrameInsertion;
        saveAndApply();
        updateStatusText();
        Toast.makeText(this, "BFI: " + (prefConfig.videoBlackFrameInsertion ? "ON" : "off"),
                Toast.LENGTH_SHORT).show();
    }

    private void nudgePaperWhite(int delta) {
        int next = prefConfig.videoHdrPaperWhiteNits + delta;
        next = Math.max(PAPER_WHITE_MIN, Math.min(PAPER_WHITE_MAX, next));
        prefConfig.videoHdrPaperWhiteNits = next;
        saveAndApply();
        updateStatusText();
    }

    private void saveAndApply() {
        PreferenceConfiguration.writePostProcessPreferences(
                prefs.edit(), prefConfig);
        prefs.edit().apply();
        if (renderer != null) {
            renderer.updateSettings();
        }
    }

    private void updateStatusText() {
        String txt = String.format("Mode %s  |  Paper %d nits  |  Gamut %s  |  BFI %s",
                modeName(prefConfig.videoHdrMode),
                prefConfig.videoHdrPaperWhiteNits,
                GamutCycle.name(prefConfig.videoHdrExpandGamut),
                prefConfig.videoBlackFrameInsertion ? "on" : "off");
        statusText.setText(txt);
    }

    // ---------- SurfaceHolder.Callback ----------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        setupRenderer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Renderer reads surface size from EGL on vsync; nothing to do here.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        teardownRenderer();
    }

    private void setupRenderer() {
        if (renderer != null) return;
        SurfaceHolder holder = outputView.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            return;
        }
        try {
            // In-memory overrides — never saved. The renderer's SourceSize
            // uniform depends on these and the test pattern doesn't have
            // a "real" source resolution, so we pick FHD as a sane default.
            if (prefConfig.width <= 0)  prefConfig.width = 1920;
            if (prefConfig.height <= 0) prefConfig.height = 1080;
            if (prefConfig.fps <= 0)    prefConfig.fps = 60;

            renderer = new PostProcessVideoRenderer(
                    this,
                    holder.getSurface(),
                    prefConfig,
                    prefConfig.fps,
                    getWindowManager().getDefaultDisplay().getRefreshRate(),
                    false, /* hostHdrStreamActive — we ARE the "stream" */
                    getWindow(),
                    getWindowManager().getDefaultDisplay(),
                    this /* statusListener — required for HDR mode notifications */
            );
            if (!renderer.startBlocking()) {
                Toast.makeText(this, "Renderer init failed", Toast.LENGTH_LONG).show();
                renderer = null;
                return;
            }
            renderThread = new HandlerThread("LocalTestPattern");
            renderThread.start();
            renderHandler = new Handler(renderThread.getLooper());
            renderLoopRunning.set(true);
            renderHandler.post(this::renderFrame);
        } catch (Throwable t) {
            android.util.Log.e("LocalTestPattern", "setupRenderer failed", t);
            Toast.makeText(this, "Renderer init failed: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void teardownRenderer() {
        renderLoopRunning.set(false);
        if (renderHandler != null) {
            renderHandler.removeCallbacksAndMessages(null);
            renderHandler = null;
        }
        if (renderThread != null) {
            renderThread.quitSafely();
            try { renderThread.join(2000); } catch (InterruptedException ignored) {}
            renderThread = null;
        }
        if (renderer != null) {
            renderer.stop();
            renderer = null;
        }
    }

    // ---------- PostProcessStatusListener ----------

    @Override
    public void onPostProcessStatusUpdate(String text) {
        // Pipe the renderer's status line to logcat so the user can
        // see what the pipeline is actually doing.
        android.util.Log.i("LocalTestPattern", "renderer: " + text);
    }

    @Override
    public void onPostProcessHdrModeChanged(boolean hdrActive) {
        android.util.Log.i("LocalTestPattern",
                "HDR mode changed → hdrActive=" + hdrActive);
    }

    // ---------- Render thread ----------

    private void renderFrame() {
        if (!renderLoopRunning.get() || renderer == null) return;
        android.view.Surface codecSurface = renderer.getCodecSurface();
        if (codecSurface == null) {
            renderHandler.postDelayed(this::renderFrame, 16);
            return;
        }
        try {
            Canvas canvas = codecSurface.lockHardwareCanvas();
            if (canvas != null) {
                int w = canvas.getWidth();
                int h = canvas.getHeight();
                if (w > 0 && h > 0) {
                    drawPattern(canvas, w, h, frameCount);
                }
                codecSurface.unlockCanvasAndPost(canvas);
                frameCount++;
            }
        } catch (Throwable t) {
            android.util.Log.w("LocalTestPattern", "renderFrame failed", t);
        }
        renderHandler.postDelayed(this::renderFrame, 16); // ~60 fps
    }

    private void drawPattern(Canvas canvas, int w, int h, long frame) {
        switch (currentPattern) {
            case PATTERN_GRAY:   drawGray(canvas, w, h); break;
            case PATTERN_RAMP:   drawRamp(canvas, w, h); break;
            case PATTERN_GAMUT:  drawGamut(canvas, w, h); break;
            case PATTERN_CHECKER:drawChecker(canvas, w, h); break;
            case PATTERN_UFO_BORDER: drawUfoBorder(canvas, w, h); break;
            case PATTERN_GAME_320x240: drawGameFrame320x240(canvas, w, h); break;
            case PATTERN_DRACULA: drawScreenshotPattern(canvas, w, h, draculaBitmap, "Dracula CRT vs LCD"); break;
            case PATTERN_CASTLEVANIA: drawScreenshotPattern(canvas, w, h, castlevaniaBitmap, "Castlevania CRT Royale"); break;
            case PATTERN_SONIC: drawScreenshotPattern(canvas, w, h, sonicBitmap, "Sonic CRT Royale"); break;
        }
    }

    private void drawGray(Canvas canvas, int w, int h) {
        canvas.drawColor(0xFF808080);
    }

    private void drawRamp(Canvas canvas, int w, int h) {
        // Horizontal grayscale ramp from 0 (black) to 1000 nits (~white).
        int bands = 10;
        int bandW = w / bands;
        for (int i = 0; i < bands; i++) {
            int gray = (int) (255.0 * i / (bands - 1));
            int c = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            paint.setColor(c);
            canvas.drawRect(i * bandW, 0, (i + 1) * bandW, h * 2 / 3, paint);
        }
        // PLUGE row: black, near-black, near-white, white.
        int rowY = h * 2 / 3;
        int rowH = h / 3;
        int[] pluge = { 0xFF000000, 0xFF050505, 0xFFF0F0F0, 0xFFFFFFFF };
        String[] labels = { "0", "1/255", "254/255", "255" };
        int colW = w / pluge.length;
        for (int i = 0; i < pluge.length; i++) {
            paint.setColor(pluge[i]);
            canvas.drawRect(i * colW, rowY, (i + 1) * colW, rowY + rowH, paint);
            paint.setColor(i < 2 ? Color.WHITE : Color.BLACK);
            canvas.drawText(labels[i], i * colW + 16, rowY + rowH - 24, textPaint);
        }
    }

    private void drawGamut(Canvas canvas, int w, int h) {
        // Top half: Rec.709 primaries. Bottom half: Rec.2020 primaries.
        // Hue sweep across full saturation.
        int halfH = h / 2;
        for (int y = 0; y < halfH; y++) {
            float hue = 360f * y / halfH;
            paint.setColor(Color.HSVToColor(new float[]{ hue, 1f, 1f }));
            canvas.drawRect(0, y, w, y + 1, paint);
        }
        paint.setColor(0x80000000);
        canvas.drawRect(0, halfH - 4, w, halfH + 4, paint);
        for (int y = halfH; y < h; y++) {
            float hue = 360f * (y - halfH) / (h - halfH);
            // Rec.2020 has wider primaries — bias the hue toward the green
            // and red extremes to show the difference clearly.
            paint.setColor(Color.HSVToColor(new float[]{ hue, 1f, 0.9f }));
            canvas.drawRect(0, y, w, y + 1, paint);
        }
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(48);
        canvas.drawText("Rec.709", 24, 56, textPaint);
        canvas.drawText("Rec.2020 (saturated)", 24, halfH + 56, textPaint);
    }

    private void drawChecker(Canvas canvas, int w, int h) {
        // 24-patch ColorChecker-like chart in a 6x4 grid.
        int[] patch = {
            0xFF735244, 0xFFC29682, 0xFF627A9D, 0xFF576C43, 0xFF8580B1, 0xFF67BDAF,
            0xFFD7E65F, 0xFF9B9C9A, 0xFFFAF6FA, 0xFF7F7E80, 0xFF381D02, 0xFFC3C4C5,
            0xFFBCA28E, 0xFFD6C4B0, 0xFF708EBE, 0xFF70A887, 0xFF9880B7, 0xFFA2B5C4,
            0xFFD6D1C8, 0xFF515142, 0xFFCECAC5, 0xFF8B7B5A, 0xFFB19F88, 0xFFDDD2C2
        };
        int cols = 6, rows = 4;
        int patchW = w / cols;
        int patchH = h / rows;
        for (int i = 0; i < patch.length && i < cols * rows; i++) {
            int r = i / cols, c = i % cols;
            paint.setColor(patch[i]);
            canvas.drawRect(c * patchW, r * patchH, (c + 1) * patchW, (r + 1) * patchH, paint);
        }
    }

    /**
     * M2 test pattern: simulates a 320×240 game frame at 5× nearest
     * scale (i.e. the codec surface is 1600×1200 and each "game
     * pixel" is 5×5 surface pixels). Draws:
     *
     * <ul>
     *   <li>A faint pixel grid (1-px lines at every 5 surface pixels)
     *       so the user can see the integer grid lines and crop
     *       precisely at the zoom presets.</li>
     *   <li>A central "play area" rectangle (80% of the surface) in a
     *       slightly different colour to give the user a visible
     *       reference for what the M2 crop should aim for.</li>
     *   <li>A few shapes inside the play area so the user can
     *       verify that the post-process pipeline is rendering
     *       them (not just a blank colour).</li>
     * </ul>
     *
     * <p>Designed to be paired with the Zoom 2×/4× presets so the
     * pixel grid becomes clearly visible.</p>
     */
    private void drawGameFrame320x240(Canvas canvas, int w, int h) {
        // Dark background — the game content sits on top.
        canvas.drawColor(0xFF101820);

        // 5× scale: each game pixel is 5×5 surface pixels.
        int scale = 5;

        // Central "play area" rectangle (80% of the source, centred).
        // 16:12 game aspect (4:3) on a 16:9 surface would be
        // pillarboxed; the host typically scales to 16:10 or 16:9
        // so we use 80% × 80% as a representative example.
        int playW = (w * 80) / 100;
        int playH = (h * 80) / 100;
        int playX = (w - playW) / 2;
        int playY = (h - playH) / 2;
        paint.setColor(0xFF203040);
        canvas.drawRect(playX, playY, playX + playW, playY + playH, paint);

        // Pixel grid: thin lines at every 5 surface pixels.
        paint.setColor(0x33FFFFFF);
        for (int x = 0; x <= w; x += scale) {
            canvas.drawLine(x, 0, x, h, paint);
        }
        for (int y = 0; y <= h; y += scale) {
            canvas.drawLine(0, y, w, y, paint);
        }

        // Major grid lines every 32 game pixels (= 160 surface pixels).
        paint.setColor(0x77FFFFFF);
        for (int x = 0; x <= w; x += scale * 32) {
            canvas.drawLine(x, 0, x, h, paint);
        }
        for (int y = 0; y <= h; y += scale * 32) {
            canvas.drawLine(0, y, w, y, paint);
        }

        // A few content shapes inside the play area so the user can
        // verify the post-process pipeline is rendering real content.
        // All in saturated colours to make the CRT scanline effect
        // clearly visible.
        int cx = w / 2, cy = h / 2;
        paint.setColor(0xFFFF4040);
        canvas.drawRect(cx - 30 * scale, cy - 20 * scale,
                cx + 30 * scale, cy + 20 * scale, paint);
        paint.setColor(0xFF40FF40);
        canvas.drawCircle(cx, cy, 15 * scale, paint);
        paint.setColor(0xFF4080FF);
        canvas.drawCircle(cx - 20 * scale, cy + 12 * scale, 8 * scale, paint);
        canvas.drawCircle(cx + 20 * scale, cy + 12 * scale, 8 * scale, paint);

        // Label in the play area so the user can read the pattern name
        // at any zoom level.
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28);
        textPaint.setAntiAlias(true);
        canvas.drawText("320×240 game frame", playX + 20, playY + 40, textPaint);
        canvas.drawText("5× nearest scale · zoom to see pixel grid",
                playX + 20, playY + 76, textPaint);
    }

    /**
     * Draw a pre-loaded screenshot bitmap centred and scaled to fit
     * the codec surface, with the pattern name as a label in the
     * bottom-left corner. The renderer in {@link #renderFrame}
     * disables bilinear filtering on bitmap ops (via
     * {@code paint.setFilterBitmap(false)}), so the bitmap stays
     * sharp at all zoom presets — letting the user verify the CRT
     * scanline effect on real game content.
     */
    private void drawScreenshotPattern(Canvas canvas, int w, int h,
                                      Bitmap bitmap, String label) {
        if (bitmap == null) {
            canvas.drawColor(0xFF000000);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(32);
            textPaint.setAntiAlias(true);
            canvas.drawText("Bitmap not loaded: " + label, 40, h / 2, textPaint);
            return;
        }
        int bmpW = bitmap.getWidth();
        int bmpH = bitmap.getHeight();
        if (bmpW <= 0 || bmpH <= 0) return;

        // Centre, aspect-preserving scale to fit the surface.
        float scale = Math.min((float) w / bmpW, (float) h / bmpH);
        int drawW = (int) (bmpW * scale);
        int drawH = (int) (bmpH * scale);
        int x = (w - drawW) / 2;
        int y = (h - drawH) / 2;
        canvas.drawBitmap(bitmap, null,
                new android.graphics.Rect(x, y, x + drawW, y + drawH), paint);

        // Pattern label in the bottom-left corner.
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(28);
        textPaint.setAntiAlias(true);
        canvas.drawText(label, 20, h - 24, textPaint);
    }

    /**
     * Tiles the BlurBusters UFO-border bitmap across the surface and
     * scrolls it left-to-right based on {@link #scrollSpeedPxPerSec}.
     * The scroll position is computed from wall-clock time so the
     * frame rate of the codec surface doesn't affect speed.
     */
    private void drawUfoBorder(Canvas canvas, int w, int h) {
        canvas.drawColor(Color.BLACK);
        if (ufoBorderBitmap == null) return;

        int bmpW = ufoBorderBitmap.getWidth();
        int bmpH = ufoBorderBitmap.getHeight();
        if (bmpW <= 0 || bmpH <= 0) return;

        // Center the strip vertically, scale up to ~25% of the screen
        // height so the motion is clearly perceivable.
        int targetH = Math.max(bmpH, h / 4);
        float scale = (float) targetH / bmpH;
        int drawW = (int) (bmpW * scale);
        int drawH = (int) (bmpH * scale);
        int y = (h - drawH) / 2;

        // Wall-clock-driven scroll — independent of the render loop's
        // own clock so 30Hz vs 60Hz vs 120Hz don't change the perceived
        // speed.
        long elapsedNs = System.nanoTime() - scrollStartTimeNs;
        double elapsedSec = elapsedNs / 1_000_000_000.0;
        int scrollX = (int) Math.floor((elapsedSec * scrollSpeedPxPerSec) % drawW);
        if (scrollX < 0) scrollX += drawW;

        int x = -scrollX;
        while (x < w) {
            canvas.drawBitmap(ufoBorderBitmap,
                    null,
                    new android.graphics.Rect(x, y, x + drawW, y + drawH),
                    paint);
            x += drawW;
        }
    }
}
