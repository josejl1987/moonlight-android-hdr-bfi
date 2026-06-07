package com.limelight.binding.video;

import com.limelight.binding.video.RenderModeResolver.RenderMode;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RenderModeResolverTest {
    @Test
    public void noRendererIsDirect() {
        assertEquals(RenderMode.DIRECT, RenderModeResolver.resolve(false, false));
        assertEquals(RenderMode.DIRECT, RenderModeResolver.resolve(false, true));
    }

    @Test
    public void rendererWithoutBfiIsPostprocess() {
        assertEquals(RenderMode.POSTPROCESS, RenderModeResolver.resolve(true, false));
    }

    @Test
    public void rendererWithBfiIsPostprocessBfi() {
        assertEquals(RenderMode.POSTPROCESS_BFI, RenderModeResolver.resolve(true, true));
    }
}
