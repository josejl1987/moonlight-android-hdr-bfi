package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ShouldUseTest {

    private PreferenceConfiguration defaultPrefs() {
        PreferenceConfiguration config = new PreferenceConfiguration();
        config.postProcessRendererMode = PreferenceConfiguration.POST_PROCESS_AUTO;
        config.videoHdrMode = PreferenceConfiguration.VIDEO_HDR_OFF;
        config.videoBlackFrameInsertion = false;
        config.fps = 60;
        return config;
    }

    @Test
    public void shouldUse_isFalse_whenPostProcessOff() {
        PreferenceConfiguration prefs = defaultPrefs();
        prefs.postProcessRendererMode = PreferenceConfiguration.POST_PROCESS_OFF;
        assertFalse(PostProcessVideoRenderer.shouldUse(prefs, 120f, false));
    }

    @Test
    public void shouldUse_isFalse_forHostHdrStream() {
        PreferenceConfiguration prefs = defaultPrefs();
        prefs.postProcessRendererMode = PreferenceConfiguration.POST_PROCESS_AUTO;
        prefs.videoBlackFrameInsertion = true;
        // Host HDR stream active → BFI not available
        assertFalse(PostProcessVideoRenderer.shouldUse(prefs, 120f, true));
    }

    @Test
    public void shouldUse_isTrue_whenForced() {
        PreferenceConfiguration prefs = defaultPrefs();
        prefs.postProcessRendererMode = PreferenceConfiguration.POST_PROCESS_FORCE;
        assertTrue(PostProcessVideoRenderer.shouldUse(prefs, 60f, false));
    }

    @Test
    public void shouldUse_autoRequiresHdrOrUsableBfi() {
        PreferenceConfiguration prefs = defaultPrefs();
        prefs.postProcessRendererMode = PreferenceConfiguration.POST_PROCESS_AUTO;
        prefs.videoHdrMode = PreferenceConfiguration.VIDEO_HDR_SCRGB;
        prefs.videoBlackFrameInsertion = false;
        // HDR on but BFI off → shouldUse = true (HDR)
        assertTrue(PostProcessVideoRenderer.shouldUse(prefs, 60f, false));
    }

    @Test
    public void shouldUse_autoFalse_whenNeitherHdrNorBfi() {
        PreferenceConfiguration prefs = defaultPrefs();
        prefs.postProcessRendererMode = PreferenceConfiguration.POST_PROCESS_AUTO;
        prefs.videoHdrMode = PreferenceConfiguration.VIDEO_HDR_OFF;
        prefs.videoBlackFrameInsertion = false;
        // Neither HDR nor BFI → false
        assertFalse(PostProcessVideoRenderer.shouldUse(prefs, 120f, false));
    }
}