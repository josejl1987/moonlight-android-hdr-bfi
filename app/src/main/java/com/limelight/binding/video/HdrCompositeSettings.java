package com.limelight.binding.video;

public final class HdrCompositeSettings {
    public int hdrMode;
    public float paperWhiteNits;
    public float peakNits;
    public int expandGamut;
    public boolean bfiEnabled;
    public int bfiCompensationMode;
    public float effectiveVisiblePeakNits;
    public float inverseTonemapStrength;

    public static final int HDR_MODE_OFF = 0;
    public static final int HDR_MODE_AUTO = 1;
    public static final int HDR_MODE_SCRGB = 2;
    public static final int HDR_MODE_HDR10 = 3;

    public static final int GAMUT_ACCURATE = 0;
    public static final int GAMUT_EXPANDED = 1;
    public static final int GAMUT_WIDE = 2;
    public static final int GAMUT_SUPER = 3;

    public static final int BFI_COMP_OFF = 0;
    public static final int BFI_COMP_CONSERVATIVE = 1;
    public static final int BFI_COMP_FULL = 2;

    public HdrCompositeSettings() {
        this.hdrMode = HDR_MODE_OFF;
        this.paperWhiteNits = 200f;
        this.peakNits = 600f;
        this.expandGamut = GAMUT_ACCURATE;
        this.bfiEnabled = false;
        this.bfiCompensationMode = BFI_COMP_OFF;
        this.effectiveVisiblePeakNits = 600f;
        this.inverseTonemapStrength = 1.0f;
    }

    public static HdrCompositeSettings fromPrefs(
            int clientHdrMode,
            int clientHdrPaperWhiteNits,
            int clientHdrPeakNits,
            int clientHdrExpandGamut,
            boolean clientBfi,
            int clientBfiCompensationMode
    ) {
        HdrCompositeSettings s = new HdrCompositeSettings();
        s.hdrMode = clientHdrMode;
        s.paperWhiteNits = Math.max(clientHdrPaperWhiteNits, 80f);
        s.peakNits = Math.max(clientHdrPeakNits, s.paperWhiteNits);
        s.expandGamut = clientHdrExpandGamut;
        s.bfiEnabled = clientBfi;
        s.bfiCompensationMode = clientBfiCompensationMode;

        float basePeak = s.peakNits;
        float paper = s.paperWhiteNits;

        switch (clientBfiCompensationMode) {
            case BFI_COMP_OFF:
                s.effectiveVisiblePeakNits = basePeak;
                s.inverseTonemapStrength = 1.0f;
                break;
            case BFI_COMP_CONSERVATIVE:
                s.effectiveVisiblePeakNits = Math.max(basePeak, paper * 2.0f);
                s.inverseTonemapStrength = 0.65f;
                break;
            case BFI_COMP_FULL:
                s.effectiveVisiblePeakNits = Math.max(basePeak, paper * 2.0f);
                s.inverseTonemapStrength = 1.0f;
                break;
        }

        return s;
    }
}
