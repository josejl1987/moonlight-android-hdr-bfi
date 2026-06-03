package com.limelight.binding.video;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Window;

import com.limelight.LimeLog;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public final class PostProcessCapabilities {
    public final boolean supportsPostProcess;
    public final boolean supportsWideColor;
    public final boolean supportsScRgb;
    public final boolean supportsHdr10;
    public final boolean supportsFp16;
    public final boolean supportsRgb10a2;
    public final String selectedBackend;
    public final String selectedOutputMode;
    public final String disabledReason;

    private PostProcessCapabilities(
            boolean supportsPostProcess,
            boolean supportsWideColor,
            boolean supportsScRgb,
            boolean supportsHdr10,
            boolean supportsFp16,
            boolean supportsRgb10a2,
            String selectedBackend,
            String selectedOutputMode,
            String disabledReason
    ) {
        this.supportsPostProcess = supportsPostProcess;
        this.supportsWideColor = supportsWideColor;
        this.supportsScRgb = supportsScRgb;
        this.supportsHdr10 = supportsHdr10;
        this.supportsFp16 = supportsFp16;
        this.supportsRgb10a2 = supportsRgb10a2;
        this.selectedBackend = selectedBackend;
        this.selectedOutputMode = selectedOutputMode;
        this.disabledReason = disabledReason;
    }

    public static PostProcessCapabilities probe(Context context, Window window, Display display) {
        String disabledReason = null;
        boolean supportsWideColor = false;
        boolean supportsScRgb = false;
        boolean supportsHdr10 = false;
        boolean supportsFp16 = false;
        boolean supportsRgb10a2 = false;
        String selectedBackend = "none";
        String selectedOutputMode = "SDR";

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            disabledReason = "Post-process renderer requires Android 8.0+";
            LimeLog.info("PostProcess: " + disabledReason);
            return new PostProcessCapabilities(false, false, false, false, false, false, selectedBackend, selectedOutputMode, disabledReason);
        }

        if (window != null) {
            int colorMode = window.getColorMode();
            supportsWideColor = (colorMode == ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);
        }

        if (display != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                supportsWideColor = supportsWideColor || display.isWideColorGamut();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();
                if (hdrCaps != null) {
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            supportsHdr10 = true;
                        }
                    }
                }
            }
        }

        supportsScRgb = probeEglExtensions(context);
        supportsFp16 = supportsScRgb;
        supportsRgb10a2 = supportsHdr10;

        selectedBackend = "GL";
        if (supportsScRgb) {
            selectedOutputMode = "scRGB";
        } else if (supportsHdr10) {
            selectedOutputMode = "HDR10";
        } else if (supportsWideColor) {
            selectedOutputMode = "Display P3";
        } else {
            selectedOutputMode = "SDR";
        }

        LimeLog.info("PostProcess: selected backend " + selectedBackend);
        LimeLog.info("PostProcess: selected output " + selectedOutputMode);
        LimeLog.info("PostProcess: wideColor=" + supportsWideColor
                + " scRGB=" + supportsScRgb
                + " HDR10=" + supportsHdr10
                + " FP16=" + supportsFp16
                + " RGB10A2=" + supportsRgb10a2);

        return new PostProcessCapabilities(true, supportsWideColor, supportsScRgb, supportsHdr10,
                supportsFp16, supportsRgb10a2, selectedBackend, selectedOutputMode, disabledReason);
    }

    private static boolean probeEglExtensions(Context context) {
        try {
            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (display == null || display == EGL10.EGL_NO_DISPLAY) {
                return false;
            }
            int[] version = new int[2];
            if (!egl.eglInitialize(display, version)) {
                return false;
            }
            String extensions = egl.eglQueryString(display, EGL10.EGL_EXTENSIONS);
            egl.eglTerminate(display);
            if (extensions != null) {
                return extensions.contains("EGL_EXT_gl_colorspace_scrgb")
                        || extensions.contains("EGL_EXT_gl_colorspace_scrgb_linear");
            }
        } catch (Throwable t) {
            LimeLog.warning("PostProcess: EGL probe failed: " + t.getMessage());
        }
        return false;
    }
}
