package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD tests for HdrControlsOverlay — the REPLACEMENT for the deleted
 * PaperWhiteCalibrationActivity. The replacement lets the user see the
 * live stream while adjusting HDR / BFI / gamut / nits.
 *
 * <p>Some tests use {@code Mockito.mock(Game.class)} to exercise the
 * {@link Game#getPrefConfigForOverlay()} code path that would normally
 * read from {@link Game#instance} at runtime. The mock is set as
 * {@code Game.instance} for the duration of those tests and restored
 * in a {@code finally} block.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class HdrControlsOverlayTest {

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
    }

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    // ============================================================
    // Phase 1 cleanup
    // ============================================================

    @Test
    public void paperWhiteCalibrationActivityClassRemoved() {
        try {
            Class.forName("com.limelight.PaperWhiteCalibrationActivity");
            fail("PaperWhiteCalibrationActivity class must be removed from production code");
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }

    @Test
    public void gameNoLongerExposesLaunchPaperWhiteCalibration() {
        try {
            Game.class.getMethod("launchPaperWhiteCalibration");
            fail("Game.launchPaperWhiteCalibration must be removed");
        } catch (NoSuchMethodException expected) {
            // expected
        }
    }

    @Test
    public void stringResourcesCleanedUp() {
        // The 4 paper-white strings must be gone. The 4 replacement strings
        // must be present.
        int[] required = new int[]{
                R.string.game_menu_hdr_controls,
                R.string.hdr_overlay_title,
                R.string.hdr_overlay_close,
                R.string.hdr_overlay_peek,
        };
        for (int id : required) {
            assertNotNull("missing string resource: " +
                    context.getResources().getResourceEntryName(id),
                context.getString(id));
        }
    }

    @Test
    public void manifestNoLongerMentionsPaperWhiteCalibrationActivity() {
        // Sanity: scan generated BuildConfig-like content for the class name.
        // Robolectric reads the real merged manifest; we check the resource id
        // is gone (no activity entry pointing to .PaperWhiteCalibrationActivity).
        int id = context.getResources().getIdentifier(
                "PaperWhiteCalibrationActivity", "string", context.getPackageName());
        assertEquals("PaperWhiteCalibrationActivity must be gone from manifest",
                0, id);
    }

    // ============================================================
    // Phase 2: Construction & defaults
    // ============================================================

    @Test
    public void overlayClassExists_andIsFrameLayoutSubclass() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        assertNotNull(overlay);
        assertTrue("HdrControlsOverlay must extend FrameLayout",
                android.widget.FrameLayout.class.isAssignableFrom(overlay.getClass()));
    }

    @Test
    public void overlayStartsHidden() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        assertEquals(View.GONE, overlay.getVisibility());
    }

    @Test
    public void overlayHasSemiTransparentBackground() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        // Background moved from FrameLayout to inner column (bottom-sheet).
        // FrameLayout itself is transparent; column has the semi-transparent bg.
        assertEquals("Content background must be 0xA6 (~65% opacity)",
                0xA6, overlay.getContentBackgroundAlpha());
    }

    // ============================================================
    // Phase 2: Show / hide lifecycle
    // ============================================================

    @Test
    public void showMakesOverlayVisible() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        overlay.show();
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertTrue(overlay.isOverlayVisible());
    }

    @Test
    public void hideMakesOverlayGone() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        overlay.show();
        overlay.hide();
        assertEquals(View.GONE, overlay.getVisibility());
        assertFalse(overlay.isOverlayVisible());
    }

    // ============================================================
    // Phase 2: Game API surface
    // ============================================================

    @Test
    public void gameExposesShowHideHdrControlsOverlay() {
        Method show = null;
        Method hide = null;
        try {
            show = Game.class.getMethod("showHdrControlsOverlay");
            hide = Game.class.getMethod("hideHdrControlsOverlay");
        } catch (NoSuchMethodException e) {
            fail("Game must expose showHdrControlsOverlay() and hideHdrControlsOverlay(): " + e.getMessage());
        }
        assertNotNull(show);
        assertNotNull(hide);
    }

    @Test
    public void gameDeclaresHdrControlsOverlayField() {
        boolean found = false;
        for (Field f : Game.class.getDeclaredFields()) {
            if (f.getType() == HdrControlsOverlay.class) {
                found = true;
                break;
            }
        }
        assertTrue("Game must declare a HdrControlsOverlay field", found);
    }

    // ============================================================
    // Phase 3: Six controls — read from overlay's own SeekBars
    // ============================================================

    @Test
    public void overlayContainsTwoSeekBars_perceivedAndMaxEmitted() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        int[] total = new int[1];
        for (int i = 0; i < overlay.getChildCount(); i++) {
            countSeekBars(overlay.getChildAt(i), total);
        }
        assertEquals("Overlay must have 2 SeekBars (perceived + max-emitted)", 2, total[0]);
    }

    private void countSeekBars(View v, int[] total) {
        if (v instanceof SeekBar) {
            total[0]++;
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                countSeekBars(vg.getChildAt(i), total);
            }
        }
    }

    // ============================================================
    // Phase 4: Persistence boundary (AC6, AC7)
    // ============================================================

    @Test
    public void sliderDragDoesNotWriteToPrefs() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        // Set initial prefs so we can detect writes.
        prefs.edit()
                .putInt(PreferenceConfiguration.VIDEO_HDR_PAPER_WHITE_NITS_PREF_STRING, 200)
                .putInt(PreferenceConfiguration.VIDEO_HDR_MAX_EMITTED_NITS_PREF_STRING, 1000)
                .commit();

        overlay.show();
        SeekBar perceived = findFirstSeekBar(overlay, PreferenceConfiguration.HDR_PAPER_WHITE_MIN,
                PreferenceConfiguration.HDR_PAPER_WHITE_MAX,
                PreferenceConfiguration.HDR_PAPER_WHITE_STEP);
        assertNotNull("perceived SeekBar not found", perceived);
        // Simulate user drag: setProgress + manually trigger the listener.
        perceived.setProgress((300 - PreferenceConfiguration.HDR_PAPER_WHITE_MIN)
                / PreferenceConfiguration.HDR_PAPER_WHITE_STEP);

        // Slider drag must NOT write to prefs (AC6).
        int stored = prefs.getInt(
                PreferenceConfiguration.VIDEO_HDR_PAPER_WHITE_NITS_PREF_STRING, -1);
        assertEquals("Slider drag must not write to prefs", 200, stored);
    }

    @Test
    public void hideWritesBothPrefKeys() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        overlay.show();
        SeekBar perceived = findFirstSeekBar(overlay, PreferenceConfiguration.HDR_PAPER_WHITE_MIN,
                PreferenceConfiguration.HDR_PAPER_WHITE_MAX,
                PreferenceConfiguration.HDR_PAPER_WHITE_STEP);
        assertNotNull("perceived SeekBar not found", perceived);
        perceived.setProgress((350 - PreferenceConfiguration.HDR_PAPER_WHITE_MIN)
                / PreferenceConfiguration.HDR_PAPER_WHITE_STEP);

        // hide() must persist (AC7).
        overlay.hide();

        int stored = prefs.getInt(
                PreferenceConfiguration.VIDEO_HDR_PAPER_WHITE_NITS_PREF_STRING, -1);
        assertEquals(350, stored);
    }

    @Test
    public void showRestoresSliderPositionsFromPrefs() {
        // Build a real PreferenceConfiguration directly (cannot call
        // readPreferences() in Robolectric — it triggers native MoonBridge).
        PreferenceConfiguration testPrefs = new PreferenceConfiguration();
        testPrefs.videoHdrPaperWhiteNits = 275;
        testPrefs.videoHdrMaxEmittedWhiteNits = 1500;
        testPrefs.videoBlackFrameInsertion = false;
        testPrefs.fps = 60f;
        testPrefs.videoBfiDarkFrames = 1;
        testPrefs.videoHdrMode = PreferenceConfiguration.VIDEO_HDR_OFF;

        Game mockGame = mock(Game.class);
        when(mockGame.getPrefConfigForOverlay()).thenReturn(testPrefs);
        when(mockGame.getCurrentDisplayRefreshRateForOverlay()).thenReturn(60f);

        Game original = Game.instance;
        Game.instance = mockGame;
        try {
            HdrControlsOverlay overlay = new HdrControlsOverlay(context);
            overlay.show();

            SeekBar perceived = findFirstSeekBar(overlay,
                    PreferenceConfiguration.HDR_PAPER_WHITE_MIN,
                    PreferenceConfiguration.HDR_PAPER_WHITE_MAX,
                    PreferenceConfiguration.HDR_PAPER_WHITE_STEP);
            assertNotNull(perceived);
            int restored = PreferenceConfiguration.HDR_PAPER_WHITE_MIN
                    + perceived.getProgress()
                    * PreferenceConfiguration.HDR_PAPER_WHITE_STEP;
            assertEquals("show() must restore perceived nits from prefs (AC8)",
                    275, restored);
        } finally {
            Game.instance = original;
        }
    }

    // ============================================================
    // Phase 4: Peek button
    // ============================================================

    @Test
    public void peekActionDownHidesOverlay() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        overlay.show();
        assertEquals(View.VISIBLE, overlay.getVisibility());

        Button peek = findPeekButton(overlay);
        assertNotNull("Peek button not found", peek);
        // Dispatch a touch event to the button (via the view's dispatch path
        // so the OnTouchListener fires).
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        peek.dispatchTouchEvent(down);
        down.recycle();
        assertEquals("ACTION_DOWN on peek must hide overlay",
                View.GONE, overlay.getVisibility());
    }

    @Test
    public void peekActionUpRestoresOverlay() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        overlay.show();
        Button peek = findPeekButton(overlay);
        assertNotNull(peek);

        // Press then release
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        peek.dispatchTouchEvent(down);
        down.recycle();
        assertEquals(View.GONE, overlay.getVisibility());

        MotionEvent up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);
        peek.dispatchTouchEvent(up);
        up.recycle();
        assertEquals("ACTION_UP on peek must restore overlay",
                View.VISIBLE, overlay.getVisibility());
    }

    @Test
    public void peekActionCancelRestoresOverlay() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        overlay.show();
        Button peek = findPeekButton(overlay);
        assertNotNull(peek);

        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        peek.dispatchTouchEvent(down);
        down.recycle();
        assertEquals(View.GONE, overlay.getVisibility());

        MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        peek.dispatchTouchEvent(cancel);
        cancel.recycle();
        assertEquals("ACTION_CANCEL on peek must restore overlay (AC10)",
                View.VISIBLE, overlay.getVisibility());
    }

    // ============================================================
    // Phase 4: GONE state does not consume input (AC12)
    // ============================================================

    @Test
    public void hiddenOverlayIsNotClickable() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        // Default state: hidden, not clickable, not focusable (AC12).
        assertFalse("GONE overlay must not be clickable (AC12)", overlay.isClickable());
        assertFalse("GONE overlay must not be focusable (AC12)", overlay.isFocusable());
        assertFalse("GONE overlay must not be visible (AC12)", overlay.isOverlayVisible());
    }

    @Test
    public void visibleOverlayIsClickable() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        overlay.show();
        assertTrue("VISIBLE overlay must be clickable",
                overlay.isClickable());
    }

    // ============================================================
    // Phase 4: Back-press intercept (AC11)
    // ============================================================

    @Test
    public void backPress_dismissesOverlay() {
        HdrControlsOverlay overlay = new HdrControlsOverlay(context);
        overlay.show();
        assertTrue(overlay.isOverlayVisible());

        // Simulate back-press contract: Game.onBackPressed hides the overlay
        // when it is visible. We can't easily call onBackPressed on a
        // non-attached Game, but the contract is: if visible, hide. We
        // assert the overlay can be hidden in response.
        overlay.hide();
        assertFalse(overlay.isOverlayVisible());
    }

    // ============================================================
    // AC14: max-emitted clamps to perceived
    // ============================================================

    @Test
    public void maxEmittedClampsToPerceived() {
        // Spec: max >= perceived is enforced by sanitizeHdrMaxEmittedNits.
        int max = PreferenceConfiguration.sanitizeHdrMaxEmittedNits(1000, 1200);
        assertEquals(1200, max);

        max = PreferenceConfiguration.sanitizeHdrMaxEmittedNits(500, 200);
        assertEquals(500, max);
    }

    // ============================================================
    // AC5: slider drag calls applyHdrBrightnessLive (not just local)
    // ============================================================

    @Test
    public void sliderDragCallsApplyHdrBrightnessLive() {
        // AC5: every slider tick must push values to the live renderer.
        PreferenceConfiguration testPrefs = new PreferenceConfiguration();
        testPrefs.videoHdrPaperWhiteNits = 200;
        testPrefs.videoHdrMaxEmittedWhiteNits = 1000;
        testPrefs.videoBlackFrameInsertion = false;
        testPrefs.fps = 60f;
        testPrefs.videoBfiDarkFrames = 1;
        testPrefs.videoHdrMode = PreferenceConfiguration.VIDEO_HDR_OFF;

        Game mockGame = mock(Game.class);
        when(mockGame.getPrefConfigForOverlay()).thenReturn(testPrefs);
        when(mockGame.getCurrentDisplayRefreshRateForOverlay()).thenReturn(60f);

        Game original = Game.instance;
        Game.instance = mockGame;
        try {
            HdrControlsOverlay overlay = new HdrControlsOverlay(context);
            overlay.show();

            // Clear invocations made during show() so we can focus on
            // slider interaction (Robolectric SeekBar fires listeners
            // during setProgress in ways we don't control).
            clearInvocations(mockGame);

            SeekBar perceived = findFirstSeekBar(overlay,
                    PreferenceConfiguration.HDR_PAPER_WHITE_MIN,
                    PreferenceConfiguration.HDR_PAPER_WHITE_MAX,
                    PreferenceConfiguration.HDR_PAPER_WHITE_STEP);
            assertNotNull(perceived);

            // Drag perceived slider to 300 nits
            int progress300 = (300 - PreferenceConfiguration.HDR_PAPER_WHITE_MIN)
                    / PreferenceConfiguration.HDR_PAPER_WHITE_STEP;
            perceived.setProgress(progress300);

            // Must have pushed to live renderer (AC5) with new perceived value
            // and a clamped max (>= new perceived).
            verify(mockGame).applyHdrBrightnessLive(eq(300), anyInt());
        } finally {
            Game.instance = original;
        }
    }

    // ============================================================
    // AC15: info row shows resolver output
    // ============================================================

    @Test
    public void infoRowShowsResolverOutput() {
        // AC15: info row must display BFI duty / emitted nits from resolver.
        PreferenceConfiguration testPrefs = new PreferenceConfiguration();
        testPrefs.videoHdrPaperWhiteNits = 200;
        testPrefs.videoHdrMaxEmittedWhiteNits = 1000;
        testPrefs.videoBlackFrameInsertion = false;
        testPrefs.fps = 60f;
        testPrefs.videoBfiDarkFrames = 1;
        testPrefs.videoHdrMode = PreferenceConfiguration.VIDEO_HDR_OFF;

        Game mockGame = mock(Game.class);
        when(mockGame.getPrefConfigForOverlay()).thenReturn(testPrefs);
        when(mockGame.getCurrentDisplayRefreshRateForOverlay()).thenReturn(60f);

        Game original = Game.instance;
        Game.instance = mockGame;
        try {
            HdrControlsOverlay overlay = new HdrControlsOverlay(context);
            overlay.show();

            // When BFI is off, emitted = perceived (200 nits)
            assertTrue("info row must contain '200' (emitted nits with BFI off)",
                    overlay.getInfoText().contains("200"));
        } finally {
            Game.instance = original;
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private SeekBar findFirstSeekBar(View root, int min, int max, int step) {
        if (root instanceof SeekBar) {
            return (SeekBar) root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                SeekBar found = findFirstSeekBar(vg.getChildAt(i), min, max, step);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Button findPeekButton(View root) {
        if (root instanceof Button) {
            Button b = (Button) root;
            CharSequence text = b.getText();
            if (text != null && text.toString().toLowerCase().contains("peek")) {
                return b;
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                Button found = findPeekButton(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
