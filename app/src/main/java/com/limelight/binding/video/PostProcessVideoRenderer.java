package com.limelight.binding.video;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.Surface;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PostProcessVideoRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final long FRAME_STALL_TIMEOUT_NS = 3_000_000_000L;
    private static final long INIT_TIMEOUT_MS = 2000;

    private final Context context;
    private final Surface outputSurface;
    private final PreferenceConfiguration prefConfig;
    private final float streamFps;
    private final float displayRefreshRate;
    private final boolean hostHdrStreamActive;
    private final PostProcessStatusListener statusListener;

    private SurfaceTexture surfaceTexture;
    private Surface codecSurface;
    private EglPostProcessContext eglContext;
    private volatile boolean recoveryFailed;
    private HandlerThread renderThread;
    private Handler renderHandler;

    private int program;
    private int oesAdapterProgram;
    private int textureId;
    private int sourceTexture2d;
    private int sourceFramebuffer;
    private FloatBuffer quadVertexBuffer;
    private FloatBuffer texCoordBuffer;
    private final float[] surfaceTransform = new float[16];

    private volatile boolean running;
    private volatile boolean frameAvailable;
    private volatile boolean surfaceTextureReady;
    private boolean hasValidTextureFrame;

    private final BfiScheduler bfiScheduler = new BfiScheduler();
    private final LibretroHdrUniforms hdrUniforms = new LibretroHdrUniforms();

    private boolean lastHdrModeActive;
    private Choreographer choreographer;
    private final Choreographer.FrameCallback renderFrameCallback = this::onVsyncFrame;
    private String requestedEglMode = "SDR";
    private int surfaceWidth;
    private int surfaceHeight;

    private CountDownLatch initLatch;

    // libretro HDR uniform locations. Names match the GLSL declarations in
    // libretro_hdr_common.glsl and libretro_hdr_composite.frag.
    private int uMvpLoc;
    private int uSourceSizeLoc;
    private int uOutputSizeLoc;
    private int uBrightnessNitsLoc;
    private int uSubpixelLayoutLoc;
    private int uScanlinesLoc;
    private int uExpandGamutLoc;
    private int uInverseTonemapLoc;
    private int uHdr10Loc;
    private int uHdrModeLoc;
    private int uTextureLoc;

    // Cached attribute/uniform locations (resolved at init)
    private int aPositionLoc;
    private int aTexCoordLoc;
    private int oesAPositionLoc;
    private int oesATexCoordLoc;
    private int oesTransformLoc;
    private int oesTextureLoc;

    private long lastSuccessfulUpdateTexImageNs;
    private int framesRendered;
    private int statsFrameCount;

    private static final float[] QUAD_VERTICES = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
    };

    private static final float[] TEX_COORDS = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    public PostProcessVideoRenderer(
            Context context,
            Surface outputSurface,
            PreferenceConfiguration prefs,
            float streamFps,
            float displayRefreshRate,
            boolean hostHdrStreamActive,
            PostProcessStatusListener statusListener
    ) {
        this.context = context;
        this.outputSurface = outputSurface;
        this.prefConfig = prefs;
        this.streamFps = streamFps;
        this.displayRefreshRate = displayRefreshRate;
        this.hostHdrStreamActive = hostHdrStreamActive;
        this.statusListener = statusListener;
        this.recoveryFailed = false;
    }

    public Surface getCodecSurface() {
        return codecSurface;
    }

    public boolean startBlocking() {
        if (running) return true;

        initLatch = new CountDownLatch(1);
        start();

        try {
            if (!initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LimeLog.warning("PostProcess: init timed out after " + INIT_TIMEOUT_MS + "ms");
                recoveryFailed = true;
                stop();
                return false;
            }
            if (recoveryFailed || codecSurface == null || !surfaceTextureReady) {
                LimeLog.warning("PostProcess: init failed (codecSurface=" + codecSurface
                        + " ready=" + surfaceTextureReady + " failed=" + recoveryFailed + ")");
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            LimeLog.warning("PostProcess: init interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void start() {
        if (running) return;
        refreshResolvedSettings(true);

        resetStats();

        running = true;

        renderThread = new HandlerThread("PostProcessGL");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        renderHandler.post(this::initGl);
    }

    public void stop() {
        running = false;
        Handler handler = renderHandler;
        if (handler != null) {
            final CountDownLatch done = new CountDownLatch(1);
            handler.post(() -> {
                try {
                    if (choreographer != null) {
                        choreographer.removeFrameCallback(renderFrameCallback);
                        choreographer = null;
                    }
                    releaseGl();
                    notifyHdrModeChanged();
                } finally {
                    done.countDown();
                }
            });
            renderHandler = null;
            try {
                if (!done.await(2000, TimeUnit.MILLISECONDS)) {
                    LimeLog.warning("PostProcess: stop timed out waiting for releaseGl");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (renderThread != null) {
            renderThread.quitSafely();
            try {
                renderThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            renderThread = null;
        }
    }

    public void release() {
        stop();
    }

    public static boolean shouldUse(
            PreferenceConfiguration prefs,
            float displayRefreshRate,
            boolean hostHdrStreamActive
    ) {
        int mode = prefs.postProcessRendererMode;
        if (mode == PreferenceConfiguration.POST_PROCESS_OFF || hostHdrStreamActive) {
            return false;
        }
        if (mode == PreferenceConfiguration.POST_PROCESS_FORCE) {
            return true;
        }

        boolean hdrUseful = prefs.videoHdrMode != PreferenceConfiguration.VIDEO_HDR_OFF;
        boolean bfiUseful = prefs.videoBlackFrameInsertion
                && BfiScheduler.canEnable(prefs.fps, displayRefreshRate, prefs.videoBfiDarkFrames);

        return hdrUseful || bfiUseful;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        frameAvailable = true;
    }

    private void initGl() {
        String targetMode = requestedTargetMode();
        requestedEglMode = targetMode;

        eglContext = new EglPostProcessContext(outputSurface, targetMode);
        if (!eglContext.initialize()) {
            LimeLog.warning("PostProcess: EGL init failed, falling back");
            running = false;
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        refreshResolvedSettings(false);

        updateHdrModeForCurrentSurface();

        // Initialize size uniforms. SourceSize is the decoded video frame;
        // OutputSize is the actual EGL window surface (which may differ after
        // letterbox, scale, or rotation). The scanline / subpixel masks in
        // the libretro shader depend on the source/output relationship being
        // accurate.
        hdrUniforms.sourceWidth = prefConfig.width;
        hdrUniforms.sourceHeight = prefConfig.height;
        int outW = eglContext.getSurfaceWidth();
        int outH = eglContext.getSurfaceHeight();
        hdrUniforms.outputWidth = outW > 0 ? outW : prefConfig.width;
        hdrUniforms.outputHeight = outH > 0 ? outH : prefConfig.height;

        notifyHdrModeChanged();

        LimeLog.info("PostProcess: target=" + targetMode
                + " actual=" + eglContext.getActualMode()
                + " hdrMode=" + hdrUniforms.hdrMode);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];

        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(prefConfig.width, prefConfig.height);
        surfaceTexture.setOnFrameAvailableListener(this, renderHandler);
        codecSurface = new Surface(surfaceTexture);

        createSourceTexture(prefConfig.width, prefConfig.height);

        String vertexSource = readRawResource(com.limelight.R.raw.libretro_hdr_video);
        String fragmentSource = loadFragmentWithIncludes();

        program = createProgram(vertexSource, fragmentSource);
        if (program == 0) {
            LimeLog.warning("PostProcess: shader compile failed");
            running = false;
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        String adapterVertexSource = readRawResource(com.limelight.R.raw.oes_adapter_vert);
        String adapterFragmentSource = readRawResource(com.limelight.R.raw.oes_adapter_frag);
        oesAdapterProgram = createProgram(adapterVertexSource, adapterFragmentSource);
        if (oesAdapterProgram == 0) {
            LimeLog.warning("PostProcess: OES adapter shader compile failed");
            running = false;
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        uMvpLoc            = GLES20.glGetUniformLocation(program, "MVP");
        uSourceSizeLoc     = GLES20.glGetUniformLocation(program, "SourceSize");
        uOutputSizeLoc     = GLES20.glGetUniformLocation(program, "OutputSize");
        uBrightnessNitsLoc = GLES20.glGetUniformLocation(program, "BrightnessNits");
        uSubpixelLayoutLoc = GLES20.glGetUniformLocation(program, "SubpixelLayout");
        uScanlinesLoc      = GLES20.glGetUniformLocation(program, "Scanlines");
        uExpandGamutLoc    = GLES20.glGetUniformLocation(program, "ExpandGamut");
        uInverseTonemapLoc = GLES20.glGetUniformLocation(program, "InverseTonemap");
        uHdr10Loc          = GLES20.glGetUniformLocation(program, "HDR10");
        uHdrModeLoc        = GLES20.glGetUniformLocation(program, "HDRMode");
        uTextureLoc        = GLES20.glGetUniformLocation(program, "Source");

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");

        oesAPositionLoc = GLES20.glGetAttribLocation(oesAdapterProgram, "aPosition");
        oesATexCoordLoc = GLES20.glGetAttribLocation(oesAdapterProgram, "aTexCoord");
        oesTransformLoc = GLES20.glGetUniformLocation(oesAdapterProgram, "uTexTransform");
        oesTextureLoc = GLES20.glGetUniformLocation(oesAdapterProgram, "uTexture");

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).flip();

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS).flip();

        surfaceTextureReady = true;
        hasValidTextureFrame = false;
        choreographer = Choreographer.getInstance();

        if (initLatch != null) initLatch.countDown();

        LimeLog.info("PostProcess: GL initialized, starting render loop at " + displayRefreshRate + " Hz");
        publishStatus();

        choreographer.postFrameCallback(renderFrameCallback);
    }

    private void onVsyncFrame(long frameTimeNanos) {
        if (!running || recoveryFailed || eglContext == null || !eglContext.isInitialized()) return;

        if (!eglContext.makeCurrent()) {
            LimeLog.warning("PostProcess: EGL context lost, stopping renderer");
            recoveryFailed = true;
            return;
        }

        int surfaceW = eglContext.getSurfaceWidth();
        int surfaceH = eglContext.getSurfaceHeight();
        if (surfaceW > 0 && surfaceH > 0 && (surfaceW != surfaceWidth || surfaceH != surfaceHeight)) {
            surfaceWidth = surfaceW;
            surfaceHeight = surfaceH;
            hdrUniforms.outputWidth = surfaceW;
            hdrUniforms.outputHeight = surfaceH;
            GLES20.glViewport(0, 0, surfaceW, surfaceH);
        }

        long nowNs = System.nanoTime();

        boolean isBlack = bfiScheduler.nextIsBlack();
        boolean consumedNewFrame = false;
        boolean frameStalled = hasValidTextureFrame
                && (nowNs - lastSuccessfulUpdateTexImageNs) > FRAME_STALL_TIMEOUT_NS;

        if (frameStalled) {
            LimeLog.warning("PostProcess: frame stall detected, clearing texture");
            hasValidTextureFrame = false;
        }

        if (frameAvailable && surfaceTextureReady) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(surfaceTransform);
            } catch (Exception e) {
                LimeLog.warning("PostProcess: updateTexImage failed: " + e.getMessage());
                recoveryFailed = true;
                return;
            }
            frameAvailable = false;
            hasValidTextureFrame = true;
            lastSuccessfulUpdateTexImageNs = nowNs;
            consumedNewFrame = true;
            int[] viewport = new int[4];
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
            renderOesToSourceTexture();
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }

        if (isBlack || frameStalled) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        } else if (hasValidTextureFrame) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture2d);
            GLES20.glUniform1i(uTextureLoc, 0);

            if (uMvpLoc >= 0) {
                GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, hdrUniforms.mvp, 0);
            }
            if (uSourceSizeLoc >= 0) {
                GLES20.glUniform4f(uSourceSizeLoc,
                        hdrUniforms.sourceWidth, hdrUniforms.sourceHeight,
                        1.0f / Math.max(hdrUniforms.sourceWidth, 1.0f),
                        1.0f / Math.max(hdrUniforms.sourceHeight, 1.0f));
            }
            if (uOutputSizeLoc >= 0) {
                GLES20.glUniform4f(uOutputSizeLoc,
                        hdrUniforms.outputWidth, hdrUniforms.outputHeight,
                        1.0f / Math.max(hdrUniforms.outputWidth, 1.0f),
                        1.0f / Math.max(hdrUniforms.outputHeight, 1.0f));
            }
            if (uBrightnessNitsLoc >= 0) GLES20.glUniform1f(uBrightnessNitsLoc, hdrUniforms.brightnessNits);
            if (uSubpixelLayoutLoc >= 0) GLES20.glUniform1i(uSubpixelLayoutLoc, hdrUniforms.subpixelLayout);
            if (uScanlinesLoc >= 0) GLES20.glUniform1f(uScanlinesLoc, hdrUniforms.scanlines);
            if (uExpandGamutLoc >= 0) GLES20.glUniform1i(uExpandGamutLoc, hdrUniforms.expandGamut);
            if (uInverseTonemapLoc >= 0) GLES20.glUniform1f(uInverseTonemapLoc, hdrUniforms.inverseTonemap);
            if (uHdr10Loc >= 0) GLES20.glUniform1f(uHdr10Loc, hdrUniforms.hdr10);
            if (uHdrModeLoc >= 0) GLES20.glUniform1i(uHdrModeLoc, hdrUniforms.hdrMode);

            GLES20.glEnableVertexAttribArray(aPositionLoc);
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoordLoc);
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPositionLoc);
            GLES20.glDisableVertexAttribArray(aTexCoordLoc);

            framesRendered++;
        } else {
            if (!bfiScheduler.isEnabled()) {
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
        }

        if (!recoveryFailed) {
            eglContext.swapBuffers();
        }

        if (statsFrameCount == 0 || statsFrameCount % 60 == 0) {
            publishStatus();
        }
        statsFrameCount++;

        if (running && !recoveryFailed) {
            choreographer.postFrameCallback(renderFrameCallback);
        }
    }

    private void resetStats() {
        lastSuccessfulUpdateTexImageNs = 0;
        framesRendered = 0;
        statsFrameCount = 0;
    }

    private void releaseGl() {
        if (eglContext != null) {
            if (!eglContext.makeCurrent()) {
                LimeLog.warning("PostProcess: makeCurrent failed during releaseGl");
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (oesAdapterProgram != 0) {
                GLES20.glDeleteProgram(oesAdapterProgram);
                oesAdapterProgram = 0;
            }
            if (sourceFramebuffer != 0) {
                GLES20.glDeleteFramebuffers(1, new int[]{sourceFramebuffer}, 0);
                sourceFramebuffer = 0;
            }
            if (sourceTexture2d != 0) {
                GLES20.glDeleteTextures(1, new int[]{sourceTexture2d}, 0);
                sourceTexture2d = 0;
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                textureId = 0;
            }
            if (surfaceTexture != null) {
                surfaceTexture.release();
                surfaceTexture = null;
            }
            if (codecSurface != null) {
                codecSurface.release();
                codecSurface = null;
            }
            eglContext.release();
            eglContext = null;
        }
        surfaceTextureReady = false;
        hasValidTextureFrame = false;
        recoveryFailed = false;
        applyLibretroHdrMode(LibretroHdrUniforms.HDR_MODE_OFF);
        LimeLog.info("PostProcess: GL released");
        publishStatus();
    }

    public void updateSettings() {
        Handler handler = renderHandler;
        if (handler != null) {
            handler.post(() -> refreshResolvedSettings(true));
        }
    }

    private void refreshResolvedSettings(boolean logChanges) {
        // Host HDR streams (those that arrived over HEVC Main10 with HDR10
        // metadata) bypass the client composite entirely; the upstream frame
        // is already PQ-encoded and the EGL surface is BT.2020 PQ.
        hdrUniforms.brightnessNits  = prefConfig.videoHdrPaperWhiteNits;
        hdrUniforms.expandGamut     = clampGamut(prefConfig.videoHdrExpandGamut);
        hdrUniforms.subpixelLayout  = clampSubpixel(prefConfig.videoHdrSubpixelLayout);
        hdrUniforms.scanlines       = prefConfig.videoHdrScanlines ? 1.0f : 0.0f;

        int darkFrames = Math.max(1, prefConfig.videoBfiDarkFrames);
        boolean bfiActive = prefConfig.videoBlackFrameInsertion
                && BfiScheduler.canEnable(streamFps, displayRefreshRate, darkFrames);
        bfiScheduler.configure(bfiActive, darkFrames);

        String reconnectMessage = null;
        if (logChanges && eglContext != null) {
            String desiredTargetMode = requestedTargetMode();
            if (!desiredTargetMode.equals(requestedEglMode)) {
                reconnectMessage = "HDR output mode changed. Reconnect stream to recreate EGL surface.";
                LimeLog.info("PostProcess: " + reconnectMessage + " requested=" + requestedEglMode
                        + " desired=" + desiredTargetMode);
            }
        }

        if (logChanges && BuildConfig.DEBUG) {
            // Two lines, libretro-side and Artemis-side, so the two layers
            // are unambiguous in the log.
            String hdrModeName;
            switch (hdrUniforms.hdrMode) {
                case LibretroHdrUniforms.HDR_MODE_HDR10: hdrModeName = "HDR10"; break;
                case LibretroHdrUniforms.HDR_MODE_SCRGB: hdrModeName = "scRGB"; break;
                case LibretroHdrUniforms.HDR_MODE_PQ_TO_SCRGB: hdrModeName = "PQ_to_scRGB"; break;
                default: hdrModeName = "OFF"; break;
            }
            LimeLog.info("Libretro HDR: mode=" + hdrModeName
                    + " brightness=" + (int) hdrUniforms.brightnessNits
                    + " gamut=" + hdrUniforms.expandGamut
                    + " scanlines=" + (hdrUniforms.scanlines > 0.0f)
                    + " subpixel=" + hdrUniforms.subpixelLayout
                    + " bfi=" + bfiScheduler.isEnabled()
                    + " darkFrames=" + bfiScheduler.getDarkFrames());
        }

        publishStatus();
        notifyHdrModeChanged();
        notifyHdrModeUnavailableIfNeeded();
        if (reconnectMessage != null && statusListener != null) {
            statusListener.onPostProcessStatusUpdate(reconnectMessage);
        }
    }



    private static int clampGamut(int v) {
        if (v < LibretroHdrUniforms.GAMUT_ACCURATE) return LibretroHdrUniforms.GAMUT_ACCURATE;
        if (v > LibretroHdrUniforms.GAMUT_SUPER) return LibretroHdrUniforms.GAMUT_SUPER;
        return v;
    }

    private static int clampSubpixel(int v) {
        if (v < LibretroHdrUniforms.SUBPIXEL_RGB) return LibretroHdrUniforms.SUBPIXEL_RGB;
        if (v > LibretroHdrUniforms.SUBPIXEL_BGR) return LibretroHdrUniforms.SUBPIXEL_BGR;
        return v;
    }

    static void applyLibretroHdrMode(LibretroHdrUniforms hdrUniforms, int mode) {
        hdrUniforms.hdrMode = mode;
        if (mode == LibretroHdrUniforms.HDR_MODE_HDR10) {
            hdrUniforms.inverseTonemap = 1.0f;
            hdrUniforms.hdr10 = 1.0f;
        } else {
            hdrUniforms.inverseTonemap = 0.0f;
            hdrUniforms.hdr10 = 0.0f;
        }
    }

    private void publishStatus() {
        if (statusListener == null || eglContext == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("PP: ").append(eglContext.getActualMode());
        String fbDesc = eglContext.getFramebufferFormatDescription();
        if (fbDesc != null) {
            sb.append(" | ").append(fbDesc);
        }
        sb.append(" | HDR=");
        switch (hdrUniforms.hdrMode) {
            case LibretroHdrUniforms.HDR_MODE_HDR10:
                sb.append("HDR10"); break;
            case LibretroHdrUniforms.HDR_MODE_SCRGB:
                sb.append("scRGB"); break;
            default:
                sb.append("OFF"); break;
        }
        sb.append(" ").append((int) hdrUniforms.brightnessNits).append("nits");
        if (bfiScheduler.isEnabled()) {
            sb.append(" | BFI=").append(bfiScheduler.getDarkFrames())
              .append(", cycle=").append(1 + bfiScheduler.getDarkFrames());
        } else {
            sb.append(" | BFI=off");
        }
        statusListener.onPostProcessStatusUpdate(sb.toString());
    }

    private String requestedTargetMode() {
        if (hostHdrStreamActive) {
            return "SDR";
        }

        switch (prefConfig.videoHdrMode) {
            case PreferenceConfiguration.VIDEO_HDR_HDR10:
                return "HDR10";
            case PreferenceConfiguration.VIDEO_HDR_SCRGB:
                return "scRGB";
            default:
                return "SDR";
        }
    }

    private void createSourceTexture(int width, int height) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        sourceTexture2d = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture2d);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // SDR adapter texture. Host HDR (10-bit/PQ) is intentionally not supported
        // here; the post-process renderer is disabled for host HDR streams.
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        sourceFramebuffer = fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sourceFramebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, sourceTexture2d, 0);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("OES adapter framebuffer incomplete: 0x" + Integer.toHexString(status));
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void renderOesToSourceTexture() {
        if (oesAdapterProgram == 0 || sourceFramebuffer == 0) {
            return;
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sourceFramebuffer);
        GLES20.glViewport(0, 0, prefConfig.width, prefConfig.height);

        GLES20.glUseProgram(oesAdapterProgram);

        GLES20.glUniformMatrix4fv(oesTransformLoc, 1, false, surfaceTransform, 0);

        GLES20.glUniform1i(oesTextureLoc, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);

        GLES20.glEnableVertexAttribArray(oesAPositionLoc);
        GLES20.glVertexAttribPointer(oesAPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
        GLES20.glEnableVertexAttribArray(oesATexCoordLoc);
        GLES20.glVertexAttribPointer(oesATexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(oesAPositionLoc);
        GLES20.glDisableVertexAttribArray(oesATexCoordLoc);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private String loadFragmentWithIncludes() {
        // GLES raw resources do not understand #include, so we manually
        // splice the common header in front of the composite body.
        String composite = readRawResource(com.limelight.R.raw.libretro_hdr_composite);
        String common   = readRawResource(com.limelight.R.raw.libretro_hdr_common);

        if (composite == null || common == null) {
            LimeLog.warning("PostProcess: missing libretro HDR shader source");
            return composite;
        }

        return composite.replace("#include \"libretro_hdr_common.glsl\"", common);
    }

    private String readRawResource(int resId) {
        try (InputStream is = context.getResources().openRawResource(resId);
             Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        } catch (Exception e) {
            LimeLog.warning("PostProcess: failed to read raw resource " + resId + ": " + e.getMessage());
            return null;
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShader, vertexSource);
        GLES20.glCompileShader(vertexShader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(vertexShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.warning("PostProcess: vertex shader compile failed: " + GLES20.glGetShaderInfoLog(vertexShader));
            GLES20.glDeleteShader(vertexShader);
            return 0;
        }

        int fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShader, fragmentSource);
        GLES20.glCompileShader(fragmentShader);

        GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.warning("PostProcess: fragment shader compile failed: " + GLES20.glGetShaderInfoLog(fragmentShader));
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            LimeLog.warning("PostProcess: program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }

    private void applyLibretroHdrMode(int mode) {
        applyLibretroHdrMode(hdrUniforms, mode);
    }

    private int hdrModeForActualEglMode() {
        if (eglContext == null) {
            return LibretroHdrUniforms.HDR_MODE_OFF;
        }
        return hdrModeForSurface(requestedEglMode, eglContext.getActualMode());
    }

    private int hdrModeForSurface(String requestedMode, String actualMode) {
        if ("HDR10".equals(requestedMode) && "HDR10".equals(actualMode)) {
            return LibretroHdrUniforms.HDR_MODE_HDR10;
        }
        if ("scRGB".equals(requestedMode) && "scRGB".equals(actualMode)) {
            return LibretroHdrUniforms.HDR_MODE_SCRGB;
        }
        return LibretroHdrUniforms.HDR_MODE_OFF;
    }

    private void updateHdrModeForCurrentSurface() {
        if (eglContext == null) {
            applyLibretroHdrMode(LibretroHdrUniforms.HDR_MODE_OFF);
            return;
        }
        applyLibretroHdrMode(hdrModeForActualEglMode());
    }

    private boolean isHdrModeActive() {
        return hdrUniforms.hdrMode != LibretroHdrUniforms.HDR_MODE_OFF;
    }

    private void notifyHdrModeUnavailableIfNeeded() {
        if (statusListener == null || eglContext == null || hostHdrStreamActive) {
            return;
        }
        if (prefConfig.videoHdrMode != LibretroHdrUniforms.HDR_MODE_OFF
                && hdrUniforms.hdrMode == LibretroHdrUniforms.HDR_MODE_OFF) {
            statusListener.onPostProcessStatusUpdate("HDR output unavailable; using SDR");
        }
    }

    private void notifyHdrModeChanged() {
        if (statusListener == null) {
            return;
        }
        boolean active = isHdrModeActive();
        if (active != lastHdrModeActive) {
            lastHdrModeActive = active;
            statusListener.onPostProcessHdrModeChanged(active);
        }
    }
}
