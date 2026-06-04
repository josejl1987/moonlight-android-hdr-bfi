package com.limelight.binding.video;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;

public final class EglPostProcessContext {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int EGL_GL_COLORSPACE_KHR = 0x309D;
    private static final int EGL_GL_COLORSPACE_SRGB_KHR = 0x3089;
    private static final int EGL_GL_COLORSPACE_LINEAR_KHR = 0x308A;
    private static final int EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340;
    private static final int EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT = 0x3350;
    private static final int EGL_GL_COLORSPACE_SCRGB_EXT = 0x3351;
    private static final int EGL_GL_COLORSPACE_BT2020_LINEAR_EXT = 0x333F;
    private static final int EGL_GL_COLORSPACE_DISPLAY_P3_EXT = 0x3363;
    private static final int EGL_GL_COLORSPACE_DISPLAY_P3_LINEAR_EXT = 0x3362;
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
    private String framebufferFormatDescription;

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
            Log.d("PostProcess", "eglGetDisplay failed");
            return false;
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            LimeLog.warning("PostProcess: eglInitialize failed");
            Log.d("PostProcess", "eglInitialize failed");
            return false;
        }

        probeExtensions();

        for (String mode : fallbackChain(requestMode)) {
            EGLConfig config = chooseConfig(mode);
            if (config == null) {
                continue;
            }
            if (createContext(config, 3) && createSurface(config, mode)) {
                if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    actualMode = mode;
                    actualGlEsVersion = 3;
                    initialized = true;
                    logSurfaceFormat();
                    String msg = "PostProcess: EGL initialized in " + actualMode + " mode (GL ES 3)";
                    LimeLog.info(msg);
                    Log.d("PostProcess", msg);
                    return true;
                }
                destroySurface();
            }
            destroyContext();
            LimeLog.info("PostProcess: falling back from " + mode);
        }

        LimeLog.warning("PostProcess: all EGL configs failed");
        Log.d("PostProcess", "all EGL configs failed");
        release();
        return false;
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

    public String getFramebufferFormatDescription() {
        return framebufferFormatDescription;
    }

    public boolean supportsScRgb() {
        return extColorspaceScrgbLinear;
    }

    public boolean supportsHdr10() {
        return extColorspaceBt2020Pq;
    }

    /**
     * Returns the current EGL window surface width, or 0 if the surface is
     * not yet created or the query fails. The width reflects the actual
     * surface dimensions at the time of the call, including any
     * letterbox/scale applied by the system.
     */
    public int getSurfaceWidth() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
            return 0;
        }
        int[] value = new int[1];
        if (!EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, value, 0)) {
            return 0;
        }
        return value[0];
    }

    /**
     * Returns the current EGL window surface height, or 0 if the surface is
     * not yet created or the query fails.
     */
    public int getSurfaceHeight() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
            return 0;
        }
        int[] value = new int[1];
        if (!EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, value, 0)) {
            return 0;
        }
        return value[0];
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
            case "HDR10":
                if (!extColorspaceBt2020Pq) return null;
                // RGB10A2 PQ surface: 10 bits per channel, 2-bit alpha (RGBA1010102).
                // The OS treats the swapchain as a PQ HDR10 framebuffer when
                // EGL_GL_COLORSPACE_BT2020_PQ_EXT is selected below.
                baseAttribs = new int[] {
                        EGL14.EGL_RED_SIZE, 10,
                        EGL14.EGL_GREEN_SIZE, 10,
                        EGL14.EGL_BLUE_SIZE, 10,
                        EGL14.EGL_ALPHA_SIZE, 2
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
                renderableType = EGL_OPENGL_ES3_BIT | EGL14.EGL_OPENGL_ES2_BIT;
                break;
            default:
                baseAttribs = new int[] {
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8
                };
                renderableType = EGL_OPENGL_ES3_BIT | EGL14.EGL_OPENGL_ES2_BIT;
                break;
        }

        return tryConfig(baseAttribs, renderableType);
    }

    private EGLConfig tryConfig(int[] baseAttribs, int renderableType) {
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
        EGLConfig config = configs[0];
        logConfigAttributes(config);
        return config;
    }

    private void logConfigAttributes(EGLConfig config) {
        int[] value = new int[1];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_RED_SIZE, value, 0);
        int red = value[0];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_GREEN_SIZE, value, 0);
        int green = value[0];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_BLUE_SIZE, value, 0);
        int blue = value[0];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_ALPHA_SIZE, value, 0);
        int alpha = value[0];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_DEPTH_SIZE, value, 0);
        int depth = value[0];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_NATIVE_VISUAL_ID, value, 0);
        int visualId = value[0];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_CONFIG_CAVEAT, value, 0);
        int caveat = value[0];
        String caveatStr = caveat == EGL14.EGL_NONE ? "none" : (caveat == EGL14.EGL_SLOW_CONFIG ? "slow" : "unknown");
        LimeLog.info("PostProcess: EGL config R=" + red + " G=" + green + " B=" + blue
                + " A=" + alpha + " D=" + depth + " visual=0x" + Integer.toHexString(visualId)
                + " caveat=" + caveatStr);
    }

    private void logSurfaceFormat() {
        int[] red = new int[1];
        int[] green = new int[1];
        int[] blue = new int[1];
        int[] alpha = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_RED_BITS, red, 0);
        GLES30.glGetIntegerv(GLES30.GL_GREEN_BITS, green, 0);
        GLES30.glGetIntegerv(GLES30.GL_BLUE_BITS, blue, 0);
        GLES30.glGetIntegerv(GLES30.GL_ALPHA_BITS, alpha, 0);
        int totalBits = red[0] + green[0] + blue[0] + alpha[0];
        String format;
        if (red[0] == 16 && green[0] == 16 && blue[0] == 16) {
            format = "FP16";
        } else if (red[0] == 10 && green[0] == 10 && blue[0] == 10) {
            format = "RGB10A2";
        } else if (red[0] == 16 && green[0] == 16) {
            format = "RGB16";
        } else if (red[0] == 8 && green[0] == 8 && blue[0] == 8) {
            format = totalBits == 32 ? "RGBA8" : "RGB8";
        } else {
            format = "unknown";
        }
        framebufferFormatDescription = format;
        String fbMsg = "PostProcess: framebuffer format R=" + red[0] + " G=" + green[0] + " B=" + blue[0]
                + " A=" + alpha[0] + " total=" + totalBits + " -> " + format;
        LimeLog.info(fbMsg);
        Log.d("PostProcess", fbMsg);

        // Query colorspace attribute from the EGL surface
        int[] colorspace = new int[1];
        if (EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL_GL_COLORSPACE_KHR, colorspace, 0)) {
            String csName;
            switch (colorspace[0]) {
                case EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT:
                    csName = "scRGB_linear";
                    break;
                case EGL_GL_COLORSPACE_SCRGB_EXT:
                    csName = "scRGB";
                    break;
                case EGL_GL_COLORSPACE_BT2020_PQ_EXT:
                    csName = "BT2020_PQ";
                    break;
                case EGL_GL_COLORSPACE_BT2020_LINEAR_EXT:
                    csName = "BT2020_linear";
                    break;
                case EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT:
                    csName = "Display_P3";
                    break;
                case EGL_GL_COLORSPACE_SRGB_KHR:
                    csName = "sRGB";
                    break;
                case EGL_GL_COLORSPACE_LINEAR_KHR:
                    csName = "linear";
                    break;
                default:
                    csName = "0x" + Integer.toHexString(colorspace[0]);
                    break;
            }
            String csMsg = "PostProcess: EGL surface colorspace=" + csName;
            LimeLog.info(csMsg);
            Log.d("PostProcess", csMsg);
        } else {
            String csMsg = "PostProcess: EGL surface colorspace query failed";
            LimeLog.info(csMsg);
            Log.d("PostProcess", csMsg);
        }
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
            case "HDR10":
                colorspaceValue = EGL_GL_COLORSPACE_BT2020_PQ_EXT;
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

    private static String[] fallbackChain(String requestedMode) {
        switch (requestedMode) {
            case "HDR10":
                return new String[] {"HDR10", "scRGB", "Display P3", "SDR"};
            case "scRGB":
                return new String[] {"scRGB", "HDR10", "Display P3", "SDR"};
            case "Display P3":
                return new String[] {"Display P3", "SDR"};
            default:
                return new String[] {"SDR"};
        }
    }
}
