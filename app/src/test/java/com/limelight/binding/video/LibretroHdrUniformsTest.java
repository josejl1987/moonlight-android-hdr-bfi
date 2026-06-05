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
        // 0 = accurate (Rec.709->Rec.2020), 1 = expanded (Expanded709->Rec.2020),
        // 2 = wide (P3->Rec.2020), 3 = super (passthrough)
        assertEquals(0, LibretroHdrUniforms.GAMUT_ACCURATE);
        assertEquals(1, LibretroHdrUniforms.GAMUT_EXPANDED);
        assertEquals(2, LibretroHdrUniforms.GAMUT_WIDE);
        assertEquals(3, LibretroHdrUniforms.GAMUT_SUPER);
    }

    @Test
    public void subpixelEnumValuesMatchLibretroSpec() {
        assertEquals(0, LibretroHdrUniforms.SUBPIXEL_RGB);
        assertEquals(1, LibretroHdrUniforms.SUBPIXEL_RBG);
        assertEquals(2, LibretroHdrUniforms.SUBPIXEL_BGR);
    }

    @Test
    public void applyLibretroHdrModeSetsFlagsLikeRetroArch() {
        LibretroHdrUniforms u = new LibretroHdrUniforms();

        PostProcessVideoRenderer.applyLibretroHdrMode(u, LibretroHdrUniforms.HDR_MODE_OFF);
        assertEquals(0.0f, u.inverseTonemap, 0.0f);
        assertEquals(0.0f, u.hdr10, 0.0f);

        PostProcessVideoRenderer.applyLibretroHdrMode(u, LibretroHdrUniforms.HDR_MODE_HDR10);
        assertEquals(1.0f, u.inverseTonemap, 0.0f);
        assertEquals(1.0f, u.hdr10, 0.0f);

        PostProcessVideoRenderer.applyLibretroHdrMode(u, LibretroHdrUniforms.HDR_MODE_SCRGB);
        assertEquals(0.0f, u.inverseTonemap, 0.0f);
        assertEquals(0.0f, u.hdr10, 0.0f);

        PostProcessVideoRenderer.applyLibretroHdrMode(u, LibretroHdrUniforms.HDR_MODE_PQ_TO_SCRGB);
        assertEquals(0.0f, u.inverseTonemap, 0.0f);
        assertEquals(0.0f, u.hdr10, 0.0f);
    }

}
