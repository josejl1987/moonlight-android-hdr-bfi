package com.limelight;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * Full-screen paper-white calibration wizard. Background is 200-nit
 * gray ({@code #808080}, sRGB), with a SeekBar that drives the HDR
 * paper-white nits value live (50 to 1000, step 25) and a Done/Reset
 * row. Dragging the SeekBar persists and pushes the change into the
 * live renderer through {@link Game#applyPostProcessSettingsLive()}.
 */
public class PaperWhiteCalibrationActivity extends AppCompatActivity {
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
    private FrameLayout root;

    /**
     * Map a paper-white nits value to an sRGB gray for the live preview.
     * The reference (200 nits) is fixed at #808080; lower nits goes dimmer,
     * higher nits goes brighter. Uses a gamma-2 inverse so the perceived
     * brightness scales the way the eye sees it.
     */
    private static int nitsToGrayColor(int nits) {
        double t = nits / 200.0;
        double srgb = Math.pow(t, 0.4545) * 128.0;
        int gray = Math.max(20, Math.min(240, (int) srgb));
        return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 200-nit gray background and matching system bars so the user can
        // see what their panel's paper white looks like against a known
        // reference. sRGB #808080 is the canonical 200 nit gray.
        getWindow().setStatusBarColor(0xFF808080);
        getWindow().setNavigationBarColor(0xFF808080);

        prefConfig = PreferenceConfiguration.readPreferences(this);
        currentNits = (savedInstanceState != null)
                ? savedInstanceState.getInt(STATE_NITS, prefConfig.videoHdrPaperWhiteNits)
                : prefConfig.videoHdrPaperWhiteNits;

        root = new FrameLayout(this);
        root.setBackgroundColor(nitsToGrayColor(currentNits));
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
                currentNits = nits;
                valueText.setText(nits + " nits");
                root.setBackgroundColor(nitsToGrayColor(nits));
                persist(nits);
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
            // onProgressChanged will persist and push.
        });
        buttonRow.addView(resetBtn);

        Button doneBtn = new Button(this);
        doneBtn.setText(R.string.paper_white_done);
        doneBtn.setOnClickListener(v -> finish());
        buttonRow.addView(doneBtn);

        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        rowParams.bottomMargin = padTop;
        root.addView(buttonRow, rowParams);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_NITS, currentNits);
    }

    private void persist(int nits) {
        prefConfig.videoHdrPaperWhiteNits = nits;
        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        PreferenceConfiguration.writePostProcessPreferences(editor, prefConfig);
        editor.apply();
    }

    private void pushToLiveRenderer() {
        Game game = Game.instance;
        if (game != null) {
            game.applyPostProcessSettingsLive();
        }
    }
}
