package com.limelight;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.limelight.binding.video.HdrBfiBrightnessResolver;
import com.limelight.binding.video.ResolvedHdrBfiBrightness;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Arrays;

/**
 * HDR brightness calibration — target perceived white + max emitted clamp.
 *
 * <p>Two sliders and an info row showing BFI duty cycle / emitted nits.
 * Both values are pushed live to the renderer and persisted on stop/done.
 *
 * <p>The background is deliberately flat dark — no fake nits preview.
 */
public class PaperWhiteCalibrationActivity extends AppCompatActivity {
    private static final String TAG = "PaperWhiteCal";
    private static final String STATE_PERCEIVED = "perceived_nits";
    private static final String STATE_MAX_EMITTED = "max_emitted_nits";

    static final String EXTRA_STREAM_FPS = "streamFps";
    static final String EXTRA_DISPLAY_HZ = "displayHz";

    // Ranges — single source of truth in PreferenceConfiguration.
    private static final int PW_MIN = PreferenceConfiguration.HDR_PAPER_WHITE_MIN;
    private static final int PW_MAX = PreferenceConfiguration.HDR_PAPER_WHITE_MAX;
    private static final int PW_STEP = PreferenceConfiguration.HDR_PAPER_WHITE_STEP;
    private static final int PW_DEFAULT = PreferenceConfiguration.HDR_PAPER_WHITE_DEFAULT;

    private static final int ME_MIN = PreferenceConfiguration.HDR_MAX_EMITTED_MIN;
    private static final int ME_MAX = PreferenceConfiguration.HDR_MAX_EMITTED_MAX;
    private static final int ME_STEP = PreferenceConfiguration.HDR_MAX_EMITTED_STEP;
    private static final int ME_DEFAULT = PreferenceConfiguration.HDR_MAX_EMITTED_DEFAULT;

    private PreferenceConfiguration prefConfig;
    private float streamFps;
    private float displayHz;

    private SeekBar perceivedSeek;
    private TextView perceivedValueText;
    private int pendingPerceivedNits;

    private SeekBar maxEmittedSeek;
    private TextView maxEmittedValueText;
    private int pendingMaxEmittedNits;

    private TextView infoText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logDisplayHdrCapabilities();

        prefConfig = PreferenceConfiguration.readPreferences(this);

        Intent intent = getIntent();
        streamFps = intent.getFloatExtra(EXTRA_STREAM_FPS, 0f);
        displayHz = intent.getFloatExtra(EXTRA_DISPLAY_HZ, 0f);

        pendingPerceivedNits = (savedInstanceState != null)
                ? savedInstanceState.getInt(STATE_PERCEIVED, prefConfig.videoHdrPaperWhiteNits)
                : prefConfig.videoHdrPaperWhiteNits;
        pendingMaxEmittedNits = (savedInstanceState != null)
                ? savedInstanceState.getInt(STATE_MAX_EMITTED, prefConfig.videoHdrMaxEmittedWhiteNits)
                : prefConfig.videoHdrMaxEmittedWhiteNits;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A1A);
        root.setPadding(32, 0, 32, 0);
        setContentView(root);

        int dp = (int) getResources().getDisplayMetrics().density;

        // Title
        TextView title = new TextView(this);
        title.setText(R.string.paper_white_title);
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp * 32, 0, dp * 8);
        root.addView(title);

        // Perceived white slider
        perceivedValueText = addLabelValueRow(root, "Target perceived SDR white", dp * 20);
        perceivedSeek = addNitsSlider(root, PW_MIN, PW_MAX, PW_STEP, pendingPerceivedNits,
                value -> onPerceivedChanged(value));
        updatePerceivedReadout();

        // Info row
        infoText = new TextView(this);
        infoText.setTextColor(0xFF88CCFF);
        infoText.setTextSize(15);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(0, dp * 12, 0, dp * 4);
        root.addView(infoText);

        // Max emitted clamp slider
        maxEmittedValueText = addLabelValueRow(root, "Max emitted HDR clamp", dp * 16);
        maxEmittedSeek = addNitsSlider(root, ME_MIN, ME_MAX, ME_STEP, pendingMaxEmittedNits,
                value -> onMaxEmittedChanged(value));
        updateMaxEmittedReadout();

        // Button row
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, dp * 24, 0, dp * 32);

        Button resetBtn = new Button(this);
        resetBtn.setText(R.string.paper_white_reset);
        resetBtn.setOnClickListener(v -> {
            perceivedSeek.setProgress((PW_DEFAULT - PW_MIN) / PW_STEP);
            maxEmittedSeek.setProgress((ME_DEFAULT - ME_MIN) / ME_STEP);
        });
        buttonRow.addView(resetBtn);

        Button doneBtn = new Button(this);
        doneBtn.setText(R.string.paper_white_done);
        doneBtn.setOnClickListener(v -> { persistBoth(); finish(); });
        buttonRow.addView(doneBtn);

        root.addView(buttonRow);

        updateInfoRow();
    }

    // ---- slider event handlers ----

    private void onPerceivedChanged(int value) {
        pendingPerceivedNits = value;
        enforceMaxNotBelowPerceived();
        updatePerceivedReadout();
        updateMaxEmittedReadout();
        updateInfoRow();
        pushToLiveRenderer();
    }

    private void onMaxEmittedChanged(int value) {
        pendingMaxEmittedNits = value;
        if (pendingMaxEmittedNits < pendingPerceivedNits) {
            pendingMaxEmittedNits = pendingPerceivedNits;
            maxEmittedSeek.setProgress((pendingMaxEmittedNits - ME_MIN) / ME_STEP);
        }
        updateMaxEmittedReadout();
        updateInfoRow();
        pushToLiveRenderer();
    }

    // ---- UI helper builders ----

    /** Add a label + value readout row returning the value TextView. */
    private TextView addLabelValueRow(LinearLayout root, String label, int topPadPx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, topPadPx, 0, 0);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFBBBBBB);
        l.setTextSize(14);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setTextColor(Color.WHITE);
        v.setTextSize(18);
        row.addView(v);

        root.addView(row);
        return v;
    }

    /** Add a labelled nits slider returning the SeekBar. */
    private SeekBar addNitsSlider(LinearLayout root, int min, int max, int step, int value,
                                   java.util.function.IntConsumer onValue) {
        SeekBar s = new SeekBar(this);
        s.setMax((max - min) / step);
        s.setProgress((value - min) / step);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                onValue.accept(min + progress * step);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(s);
        return s;
    }

    // ---- readout helpers ----

    private void updatePerceivedReadout() {
        perceivedValueText.setText(pendingPerceivedNits + " nits");
    }

    private void updateMaxEmittedReadout() {
        maxEmittedValueText.setText(pendingMaxEmittedNits + " nits");
    }

    /**
     * Update the info row via {@link HdrBfiBrightnessResolver} — same resolver
     * the renderer uses, so they always agree.
     */
    private void updateInfoRow() {
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(
                prefConfig.videoBlackFrameInsertion,
                prefConfig.videoBfiDarkFrames,
                prefConfig.videoHdrMode,
                pendingPerceivedNits,
                pendingMaxEmittedNits,
                streamFps, displayHz);

        String dutyPct = Math.round(r.dutyCycle * 100f) + "%";
        String clamped = r.emittedNits < Math.round(pendingPerceivedNits / Math.max(r.dutyCycle, 0.01f))
                ? " (clamped)" : "";

        if (r.bfiActive) {
            infoText.setText("BFI duty: " + dutyPct + "  →  Emitted: " + r.emittedNits + " nits" + clamped);
        } else if (prefConfig.videoBlackFrameInsertion && streamFps > 0f) {
            infoText.setText("BFI configured (" + r.darkFrames + " dark frames) — "
                    + "not active at current refresh/FPS cadence");
        } else {
            infoText.setText("BFI off — emitted = " + r.emittedNits + " nits");
        }
    }

    // ---- enforcement ----

    private void enforceMaxNotBelowPerceived() {
        int sanitized = PreferenceConfiguration.sanitizeHdrMaxEmittedNits(
                pendingMaxEmittedNits, pendingPerceivedNits);
        if (sanitized != pendingMaxEmittedNits) {
            pendingMaxEmittedNits = sanitized;
            maxEmittedSeek.setProgress((pendingMaxEmittedNits - ME_MIN) / ME_STEP);
            updateMaxEmittedReadout();
        }
    }

    // ---- persistence ----

    private void persistBoth() {
        pendingMaxEmittedNits = PreferenceConfiguration.sanitizeHdrMaxEmittedNits(
                pendingMaxEmittedNits, pendingPerceivedNits);
        prefConfig.videoHdrPaperWhiteNits = pendingPerceivedNits;
        prefConfig.videoHdrMaxEmittedWhiteNits = pendingMaxEmittedNits;

        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putInt(PreferenceConfiguration.VIDEO_HDR_PAPER_WHITE_NITS_PREF_STRING, pendingPerceivedNits);
        editor.putInt(PreferenceConfiguration.VIDEO_HDR_MAX_EMITTED_NITS_PREF_STRING, pendingMaxEmittedNits);
        editor.apply();
    }

    // ---- live push ----

    private void pushToLiveRenderer() {
        Game game = Game.instance;
        if (game == null || game.isFinishing()) return;
        game.applyHdrBrightnessLive(pendingPerceivedNits, pendingMaxEmittedNits);
    }

    // ---- lifecycle ----

    @Override
    protected void onStop() {
        super.onStop();
        persistBoth();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PERCEIVED, pendingPerceivedNits);
        outState.putInt(STATE_MAX_EMITTED, pendingMaxEmittedNits);
    }

    // ---- diagnostics ----

    private void logDisplayHdrCapabilities() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.i(TAG, "HDR capabilities: not available (API < N)");
            return;
        }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        Display display = wm.getDefaultDisplay();
        Display.HdrCapabilities caps = display.getHdrCapabilities();
        Log.i(TAG, "Supported HDR types: " + Arrays.toString(caps.getSupportedHdrTypes()));
        Log.i(TAG, "Desired max luminance: " + caps.getDesiredMaxLuminance() + " cd/m²");
        Log.i(TAG, "Desired max avg luminance: " + caps.getDesiredMaxAverageLuminance() + " cd/m²");
        Log.i(TAG, "Desired min luminance: " + caps.getDesiredMinLuminance() + " cd/m²");
    }
}
