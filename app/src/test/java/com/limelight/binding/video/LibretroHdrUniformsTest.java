package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LibretroHdrUniformsTest {
    @Test
    public void defaultsMatchRetroArchConfig() {
        LibretroHdrUniforms u = new LibretroHdrUniforms();
        assertEquals(200.0f, u.brightnessNits, 0.0f);
        assertEquals(LibretroHdrUniforms.HDR_MODE_OFF, u.hdrMode);
        assertEquals(LibretroHdrUniforms.GAMUT_ACCURATE, u.expandGamut);
        assertEquals(LibretroHdrUniforms.SUBPIXEL_RGB, u.subpixelLayout);
        assertEquals(0.0f, u.scanlines, 0.0f);
        assertEquals(0.0f, u.inverseTonemap, 0.0f);
        assertEquals(0.0f, u.hdr10, 0.0f);
    }

    @Test
    public void identityMvpIs4x4() {
        float[] mvp = LibretroHdrUniforms.identity4x4();
        assertEquals(16, mvp.length);
        for (int i = 0; i < 4; i++) {
            assertEquals(1.0f, mvp[i * 4 + i], 0.0f);
        }
    }

    @Test
    public void hdrModeEnumValuesMatchLibretroSpec() {
        // 0 = off, 1 = HDR10, 2 = scRGB, 3 = PQ->scRGB
        assertEquals(0, LibretroHdrUniforms.HDR_MODE_OFF);
        assertEquals(1, LibretroHdrUniforms.HDR_MODE_HDR10);
        assertEquals(2, LibretroHdrUniforms.HDR_MODE_SCRGB);
        assertEquals(3, LibretroHdrUniforms.HDR_MODE_PQ_TO_SCRGB);
    }

    @Test
    public void gamutEnumValuesMatchLibretroSpec() {
        // 0 = accurate, 1 = BT.2020, 2 = P3, 3 = expanded
        assertEquals(0, LibretroHdrUniforms.GAMUT_ACCURATE);
        assertEquals(1, LibretroHdrUniforms.GAMUT_BT2020);
        assertEquals(2, LibretroHdrUniforms.GAMUT_P3);
        assertEquals(3, LibretroHdrUniforms.GAMUT_EXPANDED);
    }
}
