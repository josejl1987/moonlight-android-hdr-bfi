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
    private static final int EGL_COLOR_COMPONENT_TYPE_EXT = 0x3339;
    private static final int EGL_COLOR_COMPONENT_TYPE_FIXED_EXT = 0x333A;
    private static final int EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B;

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
    private EGLConfig eglConfig;

    private boolean extColorspaceScrgbLinear;
    private boolean extPixelFormatFloat;
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
            eglConfig = config;
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
        eglConfig = null;
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
     * One-shot, cached probe for the device's BT.2020 PQ EGL surface
     * support. Used by {@code StreamSettings} to enable the BFI checkbox
     * in the host-HDR + no-renderer case (the BFI fast path requires a
     * PQ EGL surface).
     *
     * <p>The probe opens a temporary EGL display, queries the extension
     * string, then terminates the display. The result is cached in a
     * static {@code AtomicReference} for the rest of the process
     * lifetime (a few ms on first call, O(1) thereafter). The
     * {@code context} parameter is currently unused but is accepted for
     * API symmetry with the rest of the project.</p>
     *
     * @return {@code true} if the device exposes
     *         {@code EGL_EXT_gl_colorspace_bt2020_pq}; {@code false} on
     *         any probe failure, missing extension, or platform that
     *         does not return a usable EGL display.
     */
    public static boolean deviceSupportsHdr10Egl(android.content.Context context) {
        Boolean cached = sHdr10EglProbeResult.get();
        if (cached != null) {
            return cached;
        }
        synchronized (sHdr10EglProbeLock) {
            cached = sHdr10EglProbeResult.get();
            if (cached != null) {
                return cached;
            }
            boolean result = probeHdr10EglSupport();
            sHdr10EglProbeResult.set(result);
            return result;
        }
    }

    private static boolean probeHdr10EglSupport() {
        android.opengl.EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            return false;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            return false;
        }
        try {
            String ext = EGL14.eglQueryString(display, 0x3055);
            if (ext == null) {
                return false;
            }
            return ext.contains("EGL_EXT_gl_colorspace_bt2020_pq");
        } catch (Throwable t) {
            LimeLog.warning("BfiOnly: EGL HDR10 probe failed: " + t.getMessage());
            return false;
        } finally {
            try {
                EGL14.eglTerminate(display);
            } catch (Throwable t) {
                // EGL display may already be terminated; safe to ignore.
            }
        }
    }

    // Process-wide cache for the HDR10 EGL probe. AtomicReference (not
    // AtomicBoolean) so we can distinguish "not probed yet" (null) from
    // "probed, returned false" (Boolean.FALSE).
    private static final java.util.concurrent.atomic.AtomicReference<Boolean> sHdr10EglProbeResult =
            new java.util.concurrent.atomic.AtomicReference<>(null);
    private static final Object sHdr10EglProbeLock = new Object();

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
        extPixelFormatFloat = eglExtensions.contains("EGL_EXT_pixel_format_float");
        extColorspaceBt2020Pq = eglExtensions.contains("EGL_EXT_gl_colorspace_bt2020_pq");
        extColorspaceBt2020Linear = eglExtensions.contains("EGL_EXT_gl_colorspace_bt2020_linear");
        extColorspaceDisplayP3 = eglExtensions.contains("EGL_EXT_gl_colorspace_display_p3_passthrough");

        LimeLog.info("PostProcess: EGL ext scRGB_linear=" + extColorspaceScrgbLinear
                + " pixelFormatFloat=" + extPixelFormatFloat
                + " BT2020_PQ=" + extColorspaceBt2020Pq
                + " BT2020_linear=" + extColorspaceBt2020Linear
                + " DisplayP3=" + extColorspaceDisplayP3);
    }

    private EGLConfig chooseConfig(String mode) {
        int[] baseAttribs;
        int renderableType;

        switch (mode) {
            case "scRGB":
                if (!extColorspaceScrgbLinear || !extPixelFormatFloat) return null;
                baseAttribs = new int[] {
                        EGL14.EGL_RED_SIZE, 16,
                        EGL14.EGL_GREEN_SIZE, 16,
                        EGL14.EGL_BLUE_SIZE, 16,
                        EGL14.EGL_ALPHA_SIZE, 16,
                        EGL_COLOR_COMPONENT_TYPE_EXT, EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT
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
        int[] configAttribs = new int[baseAttribs.length + 7];
        int i = 0;
        configAttribs[i++] = EGL14.EGL_RENDERABLE_TYPE;
        configAttribs[i++] = renderableType;
        for (int attr : baseAttribs) {
            configAttribs[i++] = attr;
        }
        configAttribs[i++] = EGL14.EGL_SURFACE_TYPE;
        configAttribs[i++] = EGL14.EGL_WINDOW_BIT;
        configAttribs[i] = EGL14.EGL_NONE;

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
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL_COLOR_COMPONENT_TYPE_EXT, value, 0);
        int componentType = value[0];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_NATIVE_VISUAL_ID, value, 0);
        int visualId = value[0];
        EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_CONFIG_CAVEAT, value, 0);
        int caveat = value[0];
        String caveatStr = caveat == EGL14.EGL_NONE ? "none" : (caveat == EGL14.EGL_SLOW_CONFIG ? "slow" : "unknown");
        LimeLog.info("PostProcess: EGL config R=" + red + " G=" + green + " B=" + blue
                + " A=" + alpha + " D=" + depth + " visual=0x" + Integer.toHexString(visualId)
                + " componentType=" + componentTypeName(componentType)
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
        int componentType = EGL_COLOR_COMPONENT_TYPE_FIXED_EXT;
        if (eglConfig != null) {
            int[] value = new int[1];
            if (EGL14.eglGetConfigAttrib(eglDisplay, eglConfig, EGL_COLOR_COMPONENT_TYPE_EXT, value, 0)) {
                componentType = value[0];
            }
        }
        String format;
        if (componentType == EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT && red[0] == 16 && green[0] == 16 && blue[0] == 16) {
            format = "RGBA16_FLOAT / FP16";
        } else if (red[0] == 10 && green[0] == 10 && blue[0] == 10) {
            format = "RGB10A2";
        } else if (componentType == EGL_COLOR_COMPONENT_TYPE_FIXED_EXT && red[0] == 16 && green[0] == 16 && blue[0] == 16) {
            format = "RGBA16_FIXED";
        } else if (red[0] == 16 && green[0] == 16) {
            format = "RGB16";
        } else if (red[0] == 8 && green[0] == 8 && blue[0] == 8) {
            format = totalBits == 32 ? "RGBA8" : "RGB8";
        } else {
            format = "unknown";
        }
        framebufferFormatDescription = format;
        String fbMsg = "PostProcess: framebuffer format R=" + red[0] + " G=" + green[0] + " B=" + blue[0]
                + " A=" + alpha[0] + " total=" + totalBits + " componentType=" + componentTypeName(componentType)
                + " -> " + format;
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
                return new String[] {"HDR10", "SDR"};
            case "scRGB":
                return new String[] {"scRGB", "SDR"};
            case "Display P3":
                return new String[] {"Display P3", "SDR"};
            default:
                return new String[] {"SDR"};
        }
    }

    private static String componentTypeName(int componentType) {
        if (componentType == EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT) {
            return "FLOAT";
        }
        if (componentType == EGL_COLOR_COMPONENT_TYPE_FIXED_EXT) {
            return "FIXED";
        }
        return "0x" + Integer.toHexString(componentType);
    }
}
