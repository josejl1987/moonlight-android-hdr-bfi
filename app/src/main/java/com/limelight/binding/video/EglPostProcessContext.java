package com.limelight.binding.video;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;

import com.limelight.LimeLog;

public final class EglPostProcessContext {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int EGL_GL_COLORSPACE_KHR = 0x309D;
    private static final int EGL_GL_COLORSPACE_SRGB_KHR = 0x3089;
    private static final int EGL_GL_COLORSPACE_LINEAR_KHR = 0x308A;
    private static final int EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT = 0x3340;
    private static final int EGL_GL_COLORSPACE_SCRGB_EXT = 0x3358;
    private static final int EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3341;
    private static final int EGL_GL_COLORSPACE_BT2020_LINEAR_EXT = 0x333F;
    private static final int EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT = 0x3490;

    private static final int EGL_OPENGL_ES3_BIT = 0x0040;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private final Surface outputSurface;
    private final String requestMode;
    private String actualMode;
    private int actualGlEsVersion;
    private boolean initialized;

    private boolean extColorspaceScrgbLinear;
    private boolean extColorspaceBt2020Pq;
    private boolean extColorspaceBt2020Linear;
    private boolean extColorspaceDisplayP3;

    public EglPostProcessContext(Surface outputSurface, String requestMode) {
        this.outputSurface = outputSurface;
        this.requestMode = requestMode;
        this.actualMode = "SDR";
        this.actualGlEsVersion = 2;
    }

    public boolean initialize() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            LimeLog.warning("PostProcess: eglGetDisplay failed");
            return false;
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            LimeLog.warning("PostProcess: eglInitialize failed");
            return false;
        }

        probeExtensions();

        String mode = requestMode;
        while (true) {
            EGLConfig config = chooseConfig(mode);
            if (config != null) {
                int glEsVersion = (mode.equals("scRGB") && extColorspaceScrgbLinear) ? 3 : 2;
                if (createContext(config, glEsVersion) && createSurface(config, mode)) {
                    if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        actualMode = mode;
                        actualGlEsVersion = glEsVersion;
                        initialized = true;
                        LimeLog.info("PostProcess: EGL initialized in " + actualMode + " mode (GL ES " + glEsVersion + ")");
                        return true;
                    }
                    destroySurface();
                }
                destroyContext();
            }
            mode = nextFallback(mode);
            if (mode == null) {
                LimeLog.warning("PostProcess: all EGL configs failed");
                release();
                return false;
            }
            LimeLog.info("PostProcess: falling back to " + mode);
        }
    }

    public boolean makeCurrent() {
        if (!initialized) return false;
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            LimeLog.warning("PostProcess: eglMakeCurrent failed");
            return false;
        }
        return true;
    }

    public boolean swapBuffers() {
        if (!initialized) return false;
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    public void release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            );
            destroySurface();
            destroyContext();
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getActualMode() {
        return actualMode;
    }

    public int getGlEsVersion() {
        return actualGlEsVersion;
    }

    public boolean supportsScRgb() {
        return extColorspaceScrgbLinear;
    }

    private void probeExtensions() {
        String eglExtensions;
        try {
            eglExtensions = EGL14.eglQueryString(eglDisplay, 0x3055);
        } catch (Throwable t) {
            eglExtensions = "";
        }
        if (eglExtensions == null) eglExtensions = "";

        extColorspaceScrgbLinear = eglExtensions.contains("EGL_EXT_gl_colorspace_scrgb_linear");
        extColorspaceBt2020Pq = eglExtensions.contains("EGL_EXT_gl_colorspace_bt2020_pq");
        extColorspaceBt2020Linear = eglExtensions.contains("EGL_EXT_gl_colorspace_bt2020_linear");
        extColorspaceDisplayP3 = eglExtensions.contains("EGL_EXT_gl_colorspace_display_p3_passthrough");

        LimeLog.info("PostProcess: EGL ext scRGB_linear=" + extColorspaceScrgbLinear
                + " BT2020_PQ=" + extColorspaceBt2020Pq
                + " BT2020_linear=" + extColorspaceBt2020Linear
                + " DisplayP3=" + extColorspaceDisplayP3);
    }

    private EGLConfig chooseConfig(String mode) {
        int[] baseAttribs;
        int renderableType;

        switch (mode) {
            case "scRGB":
                if (!extColorspaceScrgbLinear) return null;
                baseAttribs = new int[] {
                        EGL14.EGL_RED_SIZE, 16,
                        EGL14.EGL_GREEN_SIZE, 16,
                        EGL14.EGL_BLUE_SIZE, 16,
                        EGL14.EGL_ALPHA_SIZE, 16
                };
                renderableType = EGL_OPENGL_ES3_BIT | EGL14.EGL_OPENGL_ES2_BIT;
                break;
            case "Display P3":
                if (!extColorspaceDisplayP3) return null;
                baseAttribs = new int[] {
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8
                };
                renderableType = EGL14.EGL_OPENGL_ES2_BIT;
                break;
            default:
                baseAttribs = new int[] {
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8
                };
                renderableType = EGL14.EGL_OPENGL_ES2_BIT;
                break;
        }

        int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                baseAttribs[0], baseAttribs[1],
                baseAttribs[2], baseAttribs[3],
                baseAttribs[4], baseAttribs[5],
                baseAttribs[6], baseAttribs[7],
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            return null;
        }
        return configs[0];
    }

    private boolean createContext(EGLConfig config, int glEsVersion) {
        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, glEsVersion,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            LimeLog.warning("PostProcess: eglCreateContext failed for GL ES " + glEsVersion);
            return false;
        }
        return true;
    }

    private boolean createSurface(EGLConfig config, String mode) {
        int colorspaceValue = 0;
        boolean hasColorspace = true;

        switch (mode) {
            case "scRGB":
                colorspaceValue = EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT;
                break;
            case "Display P3":
                colorspaceValue = EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT;
                break;
            default:
                hasColorspace = false;
                break;
        }

        int[] surfaceAttribs;
        if (hasColorspace) {
            surfaceAttribs = new int[] {
                    EGL_GL_COLORSPACE_KHR, colorspaceValue,
                    EGL14.EGL_NONE
            };
        } else {
            surfaceAttribs = new int[] {
                    EGL14.EGL_NONE
            };
        }

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            LimeLog.warning("PostProcess: eglCreateWindowSurface failed for " + mode
                    + " (error " + EGL14.eglGetError() + ")");
            return false;
        }
        return true;
    }

    private void destroySurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
    }

    private void destroyContext() {
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL14.EGL_NO_CONTEXT;
        }
    }

    private static String nextFallback(String mode) {
        switch (mode) {
            case "scRGB":
                return "Display P3";
            case "Display P3":
                return "SDR";
            default:
                return null;
        }
    }
}
