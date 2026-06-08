package com.limelight;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.limelight.binding.video.HdrBfiBrightnessResolver;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * In-game transparent overlay for live HDR / BFI / gamut / paper-white /
 * clamp controls. Replaces the deleted {@code PaperWhiteCalibrationActivity}
 * so the user can see the live stream while adjusting.
 *
 * <p>Designed as a sibling of {@code streamContainer} inside
 * {@code Game.rootView}. Lives on top of the stream with a
 * {@code #A6000000} (~65% opacity) background and bottom gravity.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #show()} restores slider positions from prefs, refreshes the
 *       info row, and sets visibility to {@link View#VISIBLE}.</li>
 *   <li>{@link #hide()} persists slider values to {@link SharedPreferences}
 *       and sets visibility to {@link View#GONE}.</li>
 * </ul>
 *
 * <h2>Slider events</h2>
 * <p>Every {@code onProgressChanged} tick calls
 * {@link Game#applyHdrBrightnessLive(int, int)} so the renderer updates
 * live. Persists to {@link SharedPreferences} only on hide (dismiss).
 *
 * <h2>Peek button</h2>
 * <p>An {@link android.view.View.OnTouchListener} that hides the overlay
 * on {@code ACTION_DOWN} and restores on {@code ACTION_UP} or
 * {@code ACTION_CANCEL} (e.g. incoming call interrupts the gesture).
 */
public class HdrControlsOverlay extends FrameLayout {

    /** Background alpha (0xA6 ≈ 65% opacity black). Required by spec AC3/AC4. */
    static final int OVERLAY_BG_ALPHA = 0xA6;
    static final int OVERLAY_BG_COLOR = 0xA6000000;

    // Slider ranges — single source of truth in PreferenceConfiguration.
    private static final int PW_MIN = PreferenceConfiguration.HDR_PAPER_WHITE_MIN;
    private static final int PW_MAX = PreferenceConfiguration.HDR_PAPER_WHITE_MAX;
    private static final int PW_STEP = PreferenceConfiguration.HDR_PAPER_WHITE_STEP;

    private static final int ME_MIN = PreferenceConfiguration.HDR_MAX_EMITTED_MIN;
    private static final int ME_MAX = PreferenceConfiguration.HDR_MAX_EMITTED_MAX;
    private static final int ME_STEP = PreferenceConfiguration.HDR_MAX_EMITTED_STEP;

    // Controls
    private SeekBar perceivedSeek;
    private TextView perceivedValueText;
    private int perceivedNits;

    private SeekBar maxEmittedSeek;
    private TextView maxEmittedValueText;
    private int maxEmittedNits;

    private Button hdrModeButton;
    private Button peekButton;
    private Button closeButton;
    private TextView infoText;

    private final Game game;

    public HdrControlsOverlay(Context context) {
        super(context);
        this.game = (context instanceof Game) ? (Game) context : Game.instance;
        // Spec: overlay starts GONE; show() flips it to VISIBLE.
        setVisibility(GONE);
        setClickable(false);
        setFocusable(false);
        initLayout();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initLayout() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(OVERLAY_BG_COLOR);
        column.setPadding(dp(16), dp(12), dp(16), dp(16));

        // ---- Title row ----
        TextView title = new TextView(getContext());
        title.setText(R.string.hdr_overlay_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        column.addView(title);

        // ---- HDR mode button: cycles + forces reconnect ----
        hdrModeButton = makeLifecycleButton(getContext().getString(
                R.string.hdr_overlay_hdr_mode_format,
                getContext().getString(hdrModeLabelResId(PreferenceConfiguration.VIDEO_HDR_OFF))));
        hdrModeButton.setOnClickListener(v -> cycleHdrMode());
        column.addView(hdrModeButton, buttonParams());

        // ---- BFI button: hides overlay then delegates ----
        Button bfiButton = makeLifecycleButton(
                getContext().getString(R.string.hdr_overlay_bfi));
        bfiButton.setOnClickListener(v -> {
            // Spinner dialog is a separate Window — it appears on top of the
            // overlay. No need to hide the overlay first.
            if (game != null) {
                game.cycleRenderMode();
            }
            refreshAll();
        });
        column.addView(bfiButton, buttonParams());

        // ---- Gamut button: live, no spinner ----
        Button gamutButton = makeLifecycleButton(
                getContext().getString(R.string.hdr_overlay_gamut));
        gamutButton.setOnClickListener(v -> {
            if (game != null) {
                game.cycleGamut();
            }
            refreshAll();
        });
        column.addView(gamutButton, buttonParams());

        // ---- Perceived slider ----
        perceivedValueText = addLabelValueRow(column, R.string.hdr_overlay_perceived_label, dp(8));
        perceivedSeek = new SeekBar(getContext());
        perceivedSeek.setMax((PW_MAX - PW_MIN) / PW_STEP);
        perceivedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                onPerceivedChanged(PW_MIN + progress * PW_STEP);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        column.addView(perceivedSeek);

        // ---- Info row ----
        infoText = new TextView(getContext());
        infoText.setTextColor(0xFF88CCFF);
        infoText.setTextSize(14);
        infoText.setPadding(0, dp(8), 0, dp(4));
        column.addView(infoText);

        // ---- Max-emitted slider ----
        maxEmittedValueText = addLabelValueRow(column, R.string.hdr_overlay_max_emitted_label, dp(8));
        maxEmittedSeek = new SeekBar(getContext());
        maxEmittedSeek.setMax((ME_MAX - ME_MIN) / ME_STEP);
        maxEmittedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                onMaxEmittedChanged(ME_MIN + progress * ME_STEP);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        column.addView(maxEmittedSeek);

        // ---- Action row (peek + close) ----
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);

        peekButton = makeLifecycleButton(
                getContext().getString(R.string.hdr_overlay_peek));
        peekButton.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    setVisibility(GONE);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_UP:
                    setVisibility(VISIBLE);
                    v.performClick();
                    return true;
            }
            return false;
        });
        actions.addView(peekButton, buttonParams());

        // Spacer
        TextView spacer = new TextView(getContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        actions.addView(spacer);

        closeButton = makeLifecycleButton(
                getContext().getString(R.string.hdr_overlay_close));
        closeButton.setOnClickListener(v -> {
            if (game != null) {
                game.hideHdrControlsOverlay();
            }
        });
        actions.addView(closeButton, buttonParams());

        column.addView(actions);

        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
    }

    /** Small action chip — no white fill, compact. */
    private Button makeLifecycleButton(String text) {
        Button b = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        return b;
    }

    /** WRAP_CONTENT for lifecycle action chips. */
    private static LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView addLabelValueRow(LinearLayout parent, int labelResId, int topPadPx) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, topPadPx, 0, 0);

        TextView l = new TextView(getContext());
        l.setText(labelResId);
        l.setTextColor(0xFFBBBBBB);
        l.setTextSize(14);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(getContext());
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        row.addView(v);

        parent.addView(row);
        return v;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    // ============================================================
    // Public API
    // ============================================================

    public void show() {
        PreferenceConfiguration prefs = (game != null) ? game.getPrefConfigForOverlay() : null;
        if (prefs != null) {
            perceivedNits = prefs.videoHdrPaperWhiteNits;
            maxEmittedNits = prefs.videoHdrMaxEmittedWhiteNits;
        }
        perceivedSeek.setProgress((perceivedNits - PW_MIN) / PW_STEP);
        maxEmittedSeek.setProgress((maxEmittedNits - ME_MIN) / ME_STEP);
        refreshAll();
        setClickable(true);
        setFocusable(true);
        setVisibility(VISIBLE);
    }

    public void hide() {
        // Persistence boundary — write only on dismiss.
        Context ctx = getContext();
        if (ctx != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(PreferenceConfiguration.VIDEO_HDR_PAPER_WHITE_NITS_PREF_STRING, perceivedNits);
            editor.putInt(PreferenceConfiguration.VIDEO_HDR_MAX_EMITTED_NITS_PREF_STRING, maxEmittedNits);
            editor.apply();
        }
        setClickable(false);
        setFocusable(false);
        setVisibility(GONE);
    }

    public boolean isOverlayVisible() {
        return getVisibility() == VISIBLE;
    }

    // Package-private for testing (AC15 info-row verification).
    String getInfoText() {
        return infoText != null ? infoText.getText().toString() : "";
    }

    // Package-private for testing — background moved to inner column.
    int getContentBackgroundAlpha() {
        if (getChildCount() > 0 && getChildAt(0) instanceof View) {
            android.graphics.drawable.Drawable bg = ((View) getChildAt(0)).getBackground();
            return bg != null ? bg.getAlpha() : 0;
        }
        return 0;
    }

    // ============================================================
    // Slider event handlers — live update, no persistence
    // ============================================================

    private void onPerceivedChanged(int value) {
        perceivedNits = value;
        // Enforce max ≥ perceived (AC14 / spec).
        int sanitized = PreferenceConfiguration.sanitizeHdrMaxEmittedNits(maxEmittedNits, perceivedNits);
        if (sanitized != maxEmittedNits) {
            maxEmittedNits = sanitized;
            maxEmittedSeek.setProgress((maxEmittedNits - ME_MIN) / ME_STEP);
        }
        pushLiveAndRefresh();
    }

    private void onMaxEmittedChanged(int value) {
        maxEmittedNits = Math.max(value, perceivedNits);
        if (maxEmittedNits != value) {
            maxEmittedSeek.setProgress((maxEmittedNits - ME_MIN) / ME_STEP);
        }
        pushLiveAndRefresh();
    }

    private void pushLiveAndRefresh() {
        if (game != null) {
            game.applyHdrBrightnessLive(perceivedNits, maxEmittedNits);
        }
        refreshAll();
    }

    private void refreshAll() {
        perceivedValueText.setText(getContext().getString(
                R.string.hdr_overlay_nits_format, perceivedNits));
        maxEmittedValueText.setText(getContext().getString(
                R.string.hdr_overlay_nits_format, maxEmittedNits));
        updateInfoRow();
        PreferenceConfiguration prefs = (game != null) ? game.getPrefConfigForOverlay() : null;
        if (hdrModeButton != null && prefs != null) {
            hdrModeButton.setText(getContext().getString(
                    R.string.hdr_overlay_hdr_mode_format,
                    getContext().getString(hdrModeLabelResId(prefs.videoHdrMode))));
        }
    }

    private void updateInfoRow() {
        if (game == null || infoText == null) return;
        PreferenceConfiguration prefs = game.getPrefConfigForOverlay();
        if (prefs == null) return;
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(
                prefs,
                prefs.fps,
                game.getCurrentDisplayRefreshRateForOverlay());
        String dutyPct = Math.round(r.dutyCycle * 100f) + "%";
        if (r.bfiActive) {
            infoText.setText(getContext().getString(
                    R.string.hdr_overlay_bfi_duty_format, dutyPct, r.emittedNits));
        } else if (prefs.videoBlackFrameInsertion) {
            infoText.setText(getContext().getString(
                    R.string.hdr_overlay_bfi_configured_format, r.darkFrames));
        } else {
            infoText.setText(getContext().getString(
                    R.string.hdr_overlay_bfi_off_format, r.emittedNits));
        }
    }

    private void cycleHdrMode() {
        if (game == null) return;
        PreferenceConfiguration prefs = game.getPrefConfigForOverlay();
        if (prefs == null) return;
        int cur = prefs.videoHdrMode;
        int next;
        if (cur == PreferenceConfiguration.VIDEO_HDR_OFF) {
            next = PreferenceConfiguration.VIDEO_HDR_SCRGB;
        } else if (cur == PreferenceConfiguration.VIDEO_HDR_SCRGB) {
            next = PreferenceConfiguration.VIDEO_HDR_HDR10;
        } else {
            next = PreferenceConfiguration.VIDEO_HDR_OFF;
        }
        prefs.videoHdrMode = next;
        Context ctx = getContext();
        if (ctx != null) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
            sp.edit().putInt(PreferenceConfiguration.VIDEO_HDR_MODE_PREF_STRING, next).apply();
        }
        // HDR mode change requires EGL surface recreation — force reconnect.
        game.reconnectStream();
    }

    private static int hdrModeLabelResId(int mode) {
        switch (mode) {
            case PreferenceConfiguration.VIDEO_HDR_SCRGB: return R.string.hdr_overlay_mode_scrgb;
            case PreferenceConfiguration.VIDEO_HDR_HDR10: return R.string.hdr_overlay_mode_hdr10;
            default: return R.string.hdr_overlay_mode_off;
        }
    }
}
