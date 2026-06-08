package com.limelight;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.limelight.binding.video.BfiBrightnessCompensation;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Arrays;

/**
 * HDR brightness calibration — target perceived white + max emitted clamp.
 *
 * <p>Two controls:
 * <ol>
 *   <li><b>Target perceived SDR white</b> (50–1000 nits) — the brightness
 *       the user wants to perceive after BFI duty-cycle averaging.</li>
 *   <li><b>Max emitted HDR white</b> (80–2000 nits) — safety ceiling for
 *       the visible-frame boost, protecting against panel peak limits.</li>
 * </ol>
 *
 * <p>An info row shows the BFI duty cycle and the resulting emitted nits.
 * Both values are pushed live to the renderer on every slider movement and
 * persisted on stop/done.
 *
 * <p>The background is deliberately flat dark — no fake nits preview.
 */
public class PaperWhiteCalibrationActivity extends AppCompatActivity {
    private static final String TAG = "PaperWhiteCal";
    private static final String STATE_PERCEIVED = "perceived_nits";
    private static final String STATE_MAX_EMITTED = "max_emitted_nits";

    /** Perceived white range. */
    private static final int MIN_PERCEIVED_NITS = 50;
    private static final int MAX_PERCEIVED_NITS = 1000;
    private static final int STEP_PERCEIVED = 25;
    private static final int DEFAULT_PERCEIVED = 200;

    /** Max emitted clamp range. */
    private static final int MIN_MAX_EMITTED = 80;
    private static final int MAX_MAX_EMITTED = 2000;
    private static final int STEP_MAX_EMITTED = 50;
    private static final int DEFAULT_MAX_EMITTED = 1000;

    private PreferenceConfiguration prefConfig;

    // Perceived white controls
    private SeekBar perceivedSeek;
    private TextView perceivedValueText;
    private int pendingPerceivedNits;

    // Max emitted controls
    private SeekBar maxEmittedSeek;
    private TextView maxEmittedValueText;
    private int pendingMaxEmittedNits;

    // Info row
    private TextView infoText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logDisplayHdrCapabilities();

        prefConfig = PreferenceConfiguration.readPreferences(this);

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

        int density = (int) getResources().getDisplayMetrics().density;

        // =============================================================
        // Title
        // =============================================================
        TextView title = new TextView(this);
        title.setText(R.string.paper_white_title);
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, density * 32, 0, density * 8);
        root.addView(title);

        // =============================================================
        // Perceived white row: label + value
        // =============================================================
        LinearLayout perceivedLabelRow = new LinearLayout(this);
        perceivedLabelRow.setOrientation(LinearLayout.HORIZONTAL);
        perceivedLabelRow.setPadding(0, density * 20, 0, 0);

        TextView perceivedLabel = new TextView(this);
        perceivedLabel.setText("Target perceived SDR white");
        perceivedLabel.setTextColor(0xFFBBBBBB);
        perceivedLabel.setTextSize(14);
        perceivedLabelRow.addView(perceivedLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        perceivedValueText = new TextView(this);
        perceivedValueText.setTextColor(Color.WHITE);
        perceivedValueText.setTextSize(18);
        perceivedLabelRow.addView(perceivedValueText);
        root.addView(perceivedLabelRow);

        // Perceived white seekbar
        perceivedSeek = new SeekBar(this);
        perceivedSeek.setMax((MAX_PERCEIVED_NITS - MIN_PERCEIVED_NITS) / STEP_PERCEIVED);
        perceivedSeek.setProgress((pendingPerceivedNits - MIN_PERCEIVED_NITS) / STEP_PERCEIVED);
        updatePerceivedReadout();
        perceivedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                pendingPerceivedNits = MIN_PERCEIVED_NITS + progress * STEP_PERCEIVED;
                updatePerceivedReadout();
                updateInfoRow();
                pushToLiveRenderer();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(perceivedSeek);

        // =============================================================
        // Info row: duty cycle + emitted nits (read-only)
        // =============================================================
        infoText = new TextView(this);
        infoText.setTextColor(0xFF88CCFF);
        infoText.setTextSize(15);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(0, density * 12, 0, density * 4);
        root.addView(infoText);

        // =============================================================
        // Max emitted clamp row: label + value
        // =============================================================
        LinearLayout maxLabelRow = new LinearLayout(this);
        maxLabelRow.setOrientation(LinearLayout.HORIZONTAL);
        maxLabelRow.setPadding(0, density * 16, 0, 0);

        TextView maxLabel = new TextView(this);
        maxLabel.setText("Max emitted HDR clamp");
        maxLabel.setTextColor(0xFFBBBBBB);
        maxLabel.setTextSize(14);
        maxLabelRow.addView(maxLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        maxEmittedValueText = new TextView(this);
        maxEmittedValueText.setTextColor(Color.WHITE);
        maxEmittedValueText.setTextSize(18);
        maxLabelRow.addView(maxEmittedValueText);
        root.addView(maxLabelRow);

        // Max emitted seekbar
        maxEmittedSeek = new SeekBar(this);
        maxEmittedSeek.setMax((MAX_MAX_EMITTED - MIN_MAX_EMITTED) / STEP_MAX_EMITTED);
        maxEmittedSeek.setProgress((pendingMaxEmittedNits - MIN_MAX_EMITTED) / STEP_MAX_EMITTED);
        updateMaxEmittedReadout();
        maxEmittedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                pendingMaxEmittedNits = MIN_MAX_EMITTED + progress * STEP_MAX_EMITTED;
                updateMaxEmittedReadout();
                updateInfoRow();
                pushMaxEmittedLive();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(maxEmittedSeek);

        // =============================================================
        // Button row
        // =============================================================
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, density * 24, 0, density * 32);

        Button resetBtn = new Button(this);
        resetBtn.setText(R.string.paper_white_reset);
        resetBtn.setOnClickListener(v -> {
            perceivedSeek.setProgress((DEFAULT_PERCEIVED - MIN_PERCEIVED_NITS) / STEP_PERCEIVED);
            maxEmittedSeek.setProgress((DEFAULT_MAX_EMITTED - MIN_MAX_EMITTED) / STEP_MAX_EMITTED);
        });
        buttonRow.addView(resetBtn);

        Button doneBtn = new Button(this);
        doneBtn.setText(R.string.paper_white_done);
        doneBtn.setOnClickListener(v -> {
            persistBoth();
            finish();
        });
        buttonRow.addView(doneBtn);

        root.addView(buttonRow);

        // Initial info
        updateInfoRow();
    }

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

    // ---- readout helpers ----

    private void updatePerceivedReadout() {
        perceivedValueText.setText(pendingPerceivedNits + " nits");
    }

    private void updateMaxEmittedReadout() {
        maxEmittedValueText.setText(pendingMaxEmittedNits + " nits");
    }

    /**
     * Update the info row showing BFI duty cycle and compensated emitted nits.
     */
    private void updateInfoRow() {
        boolean bfiEnabled = prefConfig.videoBlackFrameInsertion;
        int darkFrames = Math.max(1, prefConfig.videoBfiDarkFrames);
        float duty = BfiBrightnessCompensation.dutyCycle(bfiEnabled, 1, 1 + darkFrames);
        int emitted = BfiBrightnessCompensation.emittedWhiteNits(
                pendingPerceivedNits, bfiEnabled, 1, 1 + darkFrames, pendingMaxEmittedNits);

        String dutyPct = Math.round(duty * 100f) + "%";
        String clamped = emitted >= pendingPerceivedNits / duty ? "" : " (clamped)";
        infoText.setText("BFI duty: " + dutyPct + "  →  Emitted: " + emitted + " nits" + clamped);
    }

    // ---- persistence ----

    private void persistBoth() {
        prefConfig.videoHdrPaperWhiteNits = pendingPerceivedNits;
        prefConfig.videoHdrMaxEmittedWhiteNits = pendingMaxEmittedNits;

        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        editor.putInt(
                PreferenceConfiguration.VIDEO_HDR_PAPER_WHITE_NITS_PREF_STRING,
                pendingPerceivedNits);
        editor.putInt(
                PreferenceConfiguration.VIDEO_HDR_MAX_EMITTED_NITS_PREF_STRING,
                pendingMaxEmittedNits);
        editor.apply();
    }

    // ---- live push ----

    private void pushToLiveRenderer() {
        Game game = Game.instance;
        if (game == null || game.isFinishing()) {
            return;
        }
        game.applyPaperWhiteNitsLive(pendingPerceivedNits);
    }

    private void pushMaxEmittedLive() {
        Game game = Game.instance;
        if (game == null || game.isFinishing()) {
            return;
        }
        game.applyMaxEmittedNitsLive(pendingMaxEmittedNits);
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
        Log.i(TAG, "Supported HDR types: "
                + Arrays.toString(caps.getSupportedHdrTypes()));
        Log.i(TAG, "Desired max luminance: " + caps.getDesiredMaxLuminance()
                + " cd/m²");
        Log.i(TAG, "Desired max avg luminance: "
                + caps.getDesiredMaxAverageLuminance() + " cd/m²");
        Log.i(TAG, "Desired min luminance: "
                + caps.getDesiredMinLuminance() + " cd/m²");
    }
}
