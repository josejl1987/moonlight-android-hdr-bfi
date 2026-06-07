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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.Arrays;

/**
 * Paper-white paper-white nits picker.
 *
 * <p>This Activity lets the user choose a paper-white luminance value
 * (50–1000 nits) that drives the HDR inverse-tone-mapping reference white.
 * The value is persisted on stop/done and pushed to the live renderer on
 * every slider movement so the user can see the effect immediately through
 * the actual HDR output path (not through this SDR Activity's background).
 * </p>
 *
 * <p>The background is deliberately flat dark — it does NOT attempt to
 * preview nits via an SDR gray value. A calibrated HDR patch preview
 * requires rendering through the post-process renderer, which is deferred
 * to a follow-up.</p>
 *
 * <p>Display HDR capabilities are logged on start for diagnostics.</p>
 */
public class PaperWhiteCalibrationActivity extends AppCompatActivity {
    private static final String TAG = "PaperWhiteCal";
    private static final String STATE_NITS = "paper_white_nits";

    /** nits range and step — keep in sync with the libretro spec. */
    private static final int MIN_NITS = 50;
    private static final int MAX_NITS = 1000;
    private static final int STEP_NITS = 25;
    private static final int DEFAULT_NITS = 200;

    private PreferenceConfiguration prefConfig;
    private SeekBar seekBar;
    private TextView valueText;
    private int currentNits;
    private int pendingNits;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        logDisplayHdrCapabilities();

        prefConfig = PreferenceConfiguration.readPreferences(this);
        currentNits = (savedInstanceState != null)
                ? savedInstanceState.getInt(STATE_NITS, prefConfig.videoHdrPaperWhiteNits)
                : prefConfig.videoHdrPaperWhiteNits;
        pendingNits = currentNits;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1A1A1A); // flat dark — no fake nits preview
        setContentView(root);

        // --- Title at the top center -----------------------------------
        TextView title = new TextView(this);
        title.setText(R.string.paper_white_title);
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        int padTop = (int) (getResources().getDisplayMetrics().density * 32);
        titleParams.topMargin = padTop;
        root.addView(title, titleParams);

        // --- Readout under the title -----------------------------------
        valueText = new TextView(this);
        valueText.setText(currentNits + " nits");
        valueText.setTextSize(28);
        valueText.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams valueParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        valueParams.topMargin = padTop + (int) (getResources().getDisplayMetrics().density * 48);
        root.addView(valueText, valueParams);

        // --- SeekBar in the middle -------------------------------------
        seekBar = new SeekBar(this);
        seekBar.setMax((MAX_NITS - MIN_NITS) / STEP_NITS);
        seekBar.setProgress(Math.max(0, (currentNits - MIN_NITS) / STEP_NITS));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int nits = MIN_NITS + progress * STEP_NITS;
                pendingNits = nits;
                valueText.setText(nits + " nits");
                // Push to the live renderer immediately so the user sees the
                // effect through the actual HDR output path.
                pushToLiveRenderer();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        FrameLayout.LayoutParams seekParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        seekParams.gravity = Gravity.CENTER;
        int sidePad = (int) (getResources().getDisplayMetrics().density * 32);
        seekParams.leftMargin = sidePad;
        seekParams.rightMargin = sidePad;
        root.addView(seekBar, seekParams);

        // --- Done / Reset row at the bottom ----------------------------
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        Button resetBtn = new Button(this);
        resetBtn.setText(R.string.paper_white_reset);
        resetBtn.setOnClickListener(v -> {
            seekBar.setProgress((DEFAULT_NITS - MIN_NITS) / STEP_NITS);
            // onProgressChanged will push to renderer.
        });
        buttonRow.addView(resetBtn);

        Button doneBtn = new Button(this);
        doneBtn.setText(R.string.paper_white_done);
        doneBtn.setOnClickListener(v -> {
            persist(pendingNits);
            finish();
        });
        buttonRow.addView(doneBtn);

        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        rowParams.bottomMargin = padTop;
        root.addView(buttonRow, rowParams);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Persist on stop so the value is saved even if the user navigates
        // away without hitting Done. The renderer already has the live value
        // from onProgressChanged pushes.
        persist(pendingNits);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_NITS, pendingNits);
    }

    private void persist(int nits) {
        prefConfig.videoHdrPaperWhiteNits = nits;
        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        editor.putInt(
                PreferenceConfiguration.VIDEO_HDR_PAPER_WHITE_NITS_PREF_STRING,
                nits);
        editor.apply();
    }

    private void pushToLiveRenderer() {
        Game game = Game.instance;
        if (game == null || game.isFinishing()) {
            return;
        }
        game.applyPaperWhiteNitsLive(pendingNits);
    }

    /** Log display HDR capabilities for diagnostic purposes. */
    private void logDisplayHdrCapabilities() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.i(TAG, "HDR capabilies: not available (API < N)");
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
