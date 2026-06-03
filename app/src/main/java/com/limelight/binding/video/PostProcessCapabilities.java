package com.limelight.binding.video;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.view.Display;
import android.view.Window;

import com.limelight.LimeLog;

public final class PostProcessCapabilities {
    public final boolean supportsPostProcess;
    public final boolean supportsWideColor;
    public final boolean supportsScRgb;
    public final boolean supportsFp16;
    public final String selectedBackend;
    public final String selectedOutputMode;
    public final String disabledReason;

    private PostProcessCapabilities(
            boolean supportsPostProcess,
            boolean supportsWideColor,
            boolean supportsScRgb,
            boolean supportsFp16,
            String selectedBackend,
            String selectedOutputMode,
            String disabledReason
    ) {
        this.supportsPostProcess = supportsPostProcess;
        this.supportsWideColor = supportsWideColor;
        this.supportsScRgb = supportsScRgb;
        this.supportsFp16 = supportsFp16;
        this.selectedBackend = selectedBackend;
        this.selectedOutputMode = selectedOutputMode;
        this.disabledReason = disabledReason;
    }

    public static PostProcessCapabilities probe(Context context, Window window, Display display) {
        String disabledReason = null;
        boolean supportsWideColor = false;
        boolean supportsScRgb = false;
        boolean supportsFp16 = false;
        String selectedBackend = "none";
        String selectedOutputMode = "SDR";

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            disabledReason = "Post-process renderer requires Android 8.0+";
            LimeLog.info("PostProcess: " + disabledReason);
            return new PostProcessCapabilities(false, false, false, false, selectedBackend, selectedOutputMode, disabledReason);
        }

        if (window != null) {
            int colorMode = window.getColorMode();
            supportsWideColor = (colorMode == ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);
        }

        if (display != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                supportsWideColor = supportsWideColor || display.isWideColorGamut();
            }
        }

        boolean[] eglInfo = probeEglAndGles();
        boolean eglScrgb = eglInfo[0];
        boolean glesFp16 = eglInfo[1];
        boolean eglBt2020Linear = eglInfo[2];
        boolean eglDisplayP3 = eglInfo[3];

        supportsScRgb = eglScrgb && glesFp16 && supportsWideColor;
        supportsFp16 = glesFp16;

        selectedBackend = "GL";
        if (supportsScRgb) {
            selectedOutputMode = "scRGB";
        } else if (eglDisplayP3 && supportsWideColor) {
            selectedOutputMode = "Display P3";
        } else {
            selectedOutputMode = "SDR";
        }

        LimeLog.info("PostProcess: selected backend " + selectedBackend);
        LimeLog.info("PostProcess: selected output " + selectedOutputMode);
        LimeLog.info("PostProcess: wideColor=" + supportsWideColor
                + " scRGB=" + supportsScRgb
                + " FP16=" + supportsFp16
                + " EGL_scRGB=" + eglScrgb
                + " GLES_FP16=" + glesFp16
                + " EGL_BT2020=" + eglBt2020Linear
                + " EGL_P3=" + eglDisplayP3);

        return new PostProcessCapabilities(true, supportsWideColor, supportsScRgb,
                supportsFp16, selectedBackend, selectedOutputMode, disabledReason);
    }

    private static boolean[] probeEglAndGles() {
        boolean eglScrgb = false;
        boolean glesFp16 = false;
        boolean eglBt2020Linear = false;
        boolean eglDisplayP3 = false;

        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            return new boolean[]{false, false, false, false};
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            return new boolean[]{false, false, false, false};
        }

        try {
            String eglExt = EGL14.eglQueryString(display, 0x3055);
            if (eglExt != null) {
                eglScrgb = eglExt.contains("EGL_EXT_gl_colorspace_scrgb_linear");
                eglBt2020Linear = eglExt.contains("EGL_EXT_gl_colorspace_bt2020_linear");
                eglDisplayP3 = eglExt.contains("EGL_EXT_gl_colorspace_display_p3_passthrough");
            }

            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };

            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                EGLConfig config = configs[0];
                int[] contextAttribs = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL14.EGL_NONE
                };
                EGLContext context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
                if (context != EGL14.EGL_NO_CONTEXT) {
                    int[] pbufferAttribs = {
                            EGL14.EGL_WIDTH, 1,
                            EGL14.EGL_HEIGHT, 1,
                            EGL14.EGL_NONE
                    };
                    EGLSurface pbuffer = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0);
                    if (pbuffer != EGL14.EGL_NO_SURFACE) {
                        if (EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                            String glExt = GLES20.glGetString(GLES20.GL_EXTENSIONS);
                            if (glExt != null) {
                                glesFp16 = glExt.contains("GL_EXT_color_buffer_half_float")
                                        || glExt.contains("GL_EXT_color_buffer_float");
                            }
                            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                        }
                        EGL14.eglDestroySurface(display, pbuffer);
                    }
                    EGL14.eglDestroyContext(display, context);
                }
            }
        } catch (Throwable t) {
            LimeLog.warning("PostProcess: EGL/GLES probe failed: " + t.getMessage());
        } finally {
            EGL14.eglTerminate(display);
        }

        return new boolean[]{eglScrgb, glesFp16, eglBt2020Linear, eglDisplayP3};
    }
}
