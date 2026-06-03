package com.limelight.binding.video;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;
import android.view.Window;

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
    private static final int STATS_INTERVAL_FRAMES = 120;
    private static final long FRAME_STALL_TIMEOUT_NS = 3_000_000_000L;
    private static final long INIT_TIMEOUT_MS = 2000;

    private final Context context;
    private final Surface outputSurface;
    private final PreferenceConfiguration prefConfig;
    private final float streamFps;
    private final float displayRefreshRate;
    private final boolean hostHdrStreamActive;
    private final Window window;
    private final Display display;
    private final PostProcessStatusListener statusListener;

    private SurfaceTexture surfaceTexture;
    private Surface codecSurface;
    private EglPostProcessContext eglContext;
    private volatile boolean recoveryFailed;
    private HandlerThread renderThread;
    private Handler renderHandler;

    private int program;
    private int textureId;
    private FloatBuffer quadVertexBuffer;
    private FloatBuffer texCoordBuffer;

    private volatile boolean running;
    private volatile boolean frameAvailable;
    private volatile boolean surfaceTextureReady;
    private boolean hasValidTextureFrame;

    private final BfiScheduler bfiScheduler = new BfiScheduler();
    private final LibretroHdrUniforms hdrUniforms = new LibretroHdrUniforms();
    private Choreographer choreographer;
    private final Choreographer.FrameCallback renderFrameCallback = this::onVsyncFrame;

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

    private long lastFrameArrivalNs;
    private long lastFrameDrawStartNs;
    private int totalRenderCalls;
    private int framesRendered;
    private int framesSkippedNoInput;
    private long accumulatedLatencyNs;
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
            Window window,
            Display display,
            PostProcessStatusListener statusListener
    ) {
        this.context = context;
        this.outputSurface = outputSurface;
        this.prefConfig = prefs;
        this.streamFps = streamFps;
        this.displayRefreshRate = displayRefreshRate;
        this.hostHdrStreamActive = hostHdrStreamActive;
        this.window = window;
        this.display = display;
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
            handler.post(() -> {
                if (choreographer != null) {
                    choreographer.removeFrameCallback(renderFrameCallback);
                }
                releaseGl();
            });
            renderHandler = null;
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
            Context context,
            Surface outputSurface,
            PreferenceConfiguration prefs,
            float displayRefreshRate,
            boolean hostHdrStreamActive
    ) {
        if (prefs.postProcessRendererMode == PreferenceConfiguration.POST_PROCESS_OFF) {
            return false;
        }

        if (prefs.postProcessRendererMode == PreferenceConfiguration.POST_PROCESS_FORCE) {
            return true;
        }

        if (displayRefreshRate <= 0) {
            return false;
        }

        // Auto mode: enable the post-process renderer whenever either BFI or
        // HDR is useful on this display.
        int darkFrames = prefs.videoBfiDarkFrames;
        float required = prefs.fps * (1.0f + Math.max(1, darkFrames));
        boolean bfiUseful = prefs.videoBlackFrameInsertion
                && Math.abs(displayRefreshRate - required) <= 3.0f;
        boolean hdrUseful = prefs.videoHdrMode != PreferenceConfiguration.VIDEO_HDR_OFF && !hostHdrStreamActive;

        return bfiUseful || hdrUseful;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        lastFrameArrivalNs = System.nanoTime();
        frameAvailable = true;
    }

    private void initGl() {
        PostProcessCapabilities caps = PostProcessCapabilities.probe(context, window, display);
        String targetMode = determineTargetMode(caps);

        eglContext = new EglPostProcessContext(outputSurface, targetMode);
        if (!eglContext.initialize()) {
            LimeLog.warning("PostProcess: EGL init failed, falling back");
            running = false;
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        refreshResolvedSettings(false);

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
        surfaceTexture.setOnFrameAvailableListener(this);
        codecSurface = new Surface(surfaceTexture);

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
        uTextureLoc        = GLES20.glGetUniformLocation(program, "SourceOES");

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
        dispatchStatus(false);

        choreographer.postFrameCallback(renderFrameCallback);
    }

    private void onVsyncFrame(long frameTimeNanos) {
        if (!running || recoveryFailed || eglContext == null || !eglContext.isInitialized()) return;

        if (!eglContext.makeCurrent()) {
            LimeLog.warning("PostProcess: EGL context lost, stopping renderer");
            recoveryFailed = true;
            return;
        }

        long nowNs = System.nanoTime();

        boolean isBlack = bfiScheduler.nextIsBlack();
        boolean consumedNewFrame = false;
        boolean frameStalled = frameAvailable && (nowNs - lastFrameArrivalNs) > FRAME_STALL_TIMEOUT_NS;

        if (frameStalled) {
            LimeLog.warning("PostProcess: frame stall detected");
            frameAvailable = false;
        }

        if (frameAvailable && surfaceTextureReady) {
            try {
                surfaceTexture.updateTexImage();
            } catch (Exception e) {
                LimeLog.warning("PostProcess: updateTexImage failed: " + e.getMessage());
                recoveryFailed = true;
                return;
            }
            frameAvailable = false;
            hasValidTextureFrame = true;
            lastFrameDrawStartNs = nowNs;
            consumedNewFrame = true;
        }

        totalRenderCalls++;

        if (isBlack || frameStalled) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        } else if (hasValidTextureFrame) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);

            // Effective BrightnessNits: libretro shader math stays untouched.
            // The only Artemis deviation is multiplying BrightnessNits by
            // (1 + darkFrames) on visible frames when BFI compensation is on,
            // so the user still sees the same paper white despite the dark
            // refreshes.
            float baseBrightnessNits = hdrUniforms.brightnessNits;
            if (bfiScheduler.isEnabled() && prefConfig.videoBfiBrightnessCompensation) {
                baseBrightnessNits *= (1.0f + bfiScheduler.getDarkFrames());
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
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
            if (uBrightnessNitsLoc >= 0) GLES20.glUniform1f(uBrightnessNitsLoc, baseBrightnessNits);
            if (uSubpixelLayoutLoc >= 0) GLES20.glUniform1i(uSubpixelLayoutLoc, hdrUniforms.subpixelLayout);
            if (uScanlinesLoc >= 0) GLES20.glUniform1f(uScanlinesLoc, hdrUniforms.scanlines);
            if (uExpandGamutLoc >= 0) GLES20.glUniform1i(uExpandGamutLoc, hdrUniforms.expandGamut);
            if (uInverseTonemapLoc >= 0) GLES20.glUniform1f(uInverseTonemapLoc, hdrUniforms.inverseTonemap);
            if (uHdr10Loc >= 0) GLES20.glUniform1f(uHdr10Loc, hdrUniforms.hdr10);
            if (uHdrModeLoc >= 0) GLES20.glUniform1i(uHdrModeLoc, hdrUniforms.hdrMode);

            int positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            int texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");

            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);

            if (consumedNewFrame) {
                accumulatedLatencyNs += (nowNs - lastFrameArrivalNs);
            }
            framesRendered++;
        } else {
            if (!bfiScheduler.isEnabled()) {
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
            framesSkippedNoInput++;
        }

        if (!recoveryFailed) {
            eglContext.swapBuffers();
        }

        statsFrameCount++;
        if (statsFrameCount == 1 || statsFrameCount % 30 == 0) {
            dispatchStatus(isBlack);
        }
        if (statsFrameCount >= STATS_INTERVAL_FRAMES) {
            logStats();
            statsFrameCount = 0;
        }

        if (running && !recoveryFailed) {
            choreographer.postFrameCallback(renderFrameCallback);
        }
    }

    private void resetStats() {
        lastFrameArrivalNs = 0;
        lastFrameDrawStartNs = 0;
        totalRenderCalls = 0;
        framesRendered = 0;
        framesSkippedNoInput = 0;
        accumulatedLatencyNs = 0;
        statsFrameCount = 0;
    }

    private void logStats() {
        if (totalRenderCalls == 0) return;

        float avgLatencyMs = framesRendered > 0
                ? (accumulatedLatencyNs / (float) framesRendered) / 1_000_000f
                : 0f;

        float renderRateHz = (float) totalRenderCalls * displayRefreshRate / (float) statsFrameCount;
        float actualFps = (float) framesRendered * displayRefreshRate / (float) statsFrameCount;

        LimeLog.info("PostProcess: stats render=" + totalRenderCalls
                + " displayed=" + framesRendered
                + " skipped=" + framesSkippedNoInput
                + " renderHz=" + String.format("%.1f", renderRateHz)
                + " fps=" + String.format("%.1f", actualFps)
                + " avgLatency=" + String.format("%.2f", avgLatencyMs) + "ms");
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
        LimeLog.info("PostProcess: GL released");
        dispatchStatus(false);
    }

    public void updateSettings(PreferenceConfiguration prefs) {
        refreshResolvedSettings(true);
    }

    private void refreshResolvedSettings(boolean logChanges) {
        // Host HDR streams (those that arrived over HEVC Main10 with HDR10
        // metadata) bypass the client composite entirely; the upstream frame
        // is already PQ-encoded and the EGL surface is BT.2020 PQ.
        int requestedHdrMode = hostHdrStreamActive
                ? LibretroHdrUniforms.HDR_MODE_OFF
                : prefConfig.videoHdrMode;
        // The libretro spec only defines modes 0..2 on the user-facing path;
        // mode 3 (PQ->scRGB) is reserved for shader-emitted PQ, not user
        // preference. Clamp to the user range here.
        hdrUniforms.hdrMode = Math.max(LibretroHdrUniforms.HDR_MODE_OFF,
                Math.min(LibretroHdrUniforms.HDR_MODE_SCRGB, requestedHdrMode));

        hdrUniforms.brightnessNits  = Math.max(80, prefConfig.videoHdrPaperWhiteNits);
        hdrUniforms.expandGamut     = clampGamut(prefConfig.videoHdrExpandGamut);
        hdrUniforms.subpixelLayout  = clampSubpixel(prefConfig.videoHdrSubpixelLayout);
        hdrUniforms.scanlines       = prefConfig.videoHdrScanlines ? 1.0f : 0.0f;
        hdrUniforms.inverseTonemap  = (prefConfig.videoHdrMode == LibretroHdrUniforms.HDR_MODE_SCRGB) ? 1.0f : 0.0f;
        hdrUniforms.hdr10           = (prefConfig.videoHdrMode == LibretroHdrUniforms.HDR_MODE_HDR10
                                    || prefConfig.videoHdrMode == LibretroHdrUniforms.HDR_MODE_SCRGB) ? 1.0f : 0.0f;

        bfiScheduler.configure(
                prefConfig.videoBlackFrameInsertion,
                Math.max(1, prefConfig.videoBfiDarkFrames));
        boolean bfiActive = bfiScheduler.isEnabled()
                && bfiScheduler.canEnable(streamFps, displayRefreshRate);

        if (logChanges) {
            LimeLog.info("PostProcess: HDR mode=" + hdrUniforms.hdrMode
                    + " brightnessNits=" + (int) hdrUniforms.brightnessNits
                    + " paperWhiteNits=" + prefConfig.videoHdrPaperWhiteNits
                    + " gamut=" + hdrUniforms.expandGamut
                    + " scanlines=" + (hdrUniforms.scanlines > 0.0f)
                    + " subpixel=" + hdrUniforms.subpixelLayout
                    + " bfiEnabled=" + bfiScheduler.isEnabled()
                    + " bfiActive=" + bfiActive
                    + " darkFrames=" + bfiScheduler.getDarkFrames()
                    + " bfiCompensation=" + prefConfig.videoBfiBrightnessCompensation);
        }

        dispatchStatus(bfiActive);
    }

    private static int clampGamut(int v) {
        if (v < LibretroHdrUniforms.GAMUT_ACCURATE) return LibretroHdrUniforms.GAMUT_ACCURATE;
        if (v > LibretroHdrUniforms.GAMUT_EXPANDED) return LibretroHdrUniforms.GAMUT_EXPANDED;
        return v;
    }

    private static int clampSubpixel(int v) {
        if (v < LibretroHdrUniforms.SUBPIXEL_RGB) return LibretroHdrUniforms.SUBPIXEL_RGB;
        if (v > LibretroHdrUniforms.SUBPIXEL_BGR) return LibretroHdrUniforms.SUBPIXEL_BGR;
        return v;
    }

    private void dispatchStatus(boolean blackFrameActive) {
        if (statusListener == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PP: ");

        switch (hdrUniforms.hdrMode) {
            case LibretroHdrUniforms.HDR_MODE_HDR10:
                sb.append("HDR10 (PQ)");
                break;
            case LibretroHdrUniforms.HDR_MODE_SCRGB:
                sb.append("scRGB");
                break;
            default:
                sb.append("SDR");
                break;
        }

        if (hdrUniforms.hdrMode != LibretroHdrUniforms.HDR_MODE_OFF) {
            sb.append(" | ").append((int) hdrUniforms.brightnessNits).append(" nits");
        }

        if (bfiScheduler.isEnabled()) {
            sb.append(" | BFI ").append(bfiScheduler.getDarkFrames()).append(" dark");
            sb.append(" | cycle ").append(1 + bfiScheduler.getDarkFrames()).append(" refreshes");
            sb.append(" | phase ").append(blackFrameActive ? "black" : "visible");
            if (prefConfig.videoBfiBrightnessCompensation) {
                float factor = 1.0f + bfiScheduler.getDarkFrames();
                sb.append(" | boost ").append(String.format(java.util.Locale.US, "%.2fx", factor));
            }
        } else {
            sb.append(" | BFI off");
        }

        if (hostHdrStreamActive) {
            sb.append(" | host HDR");
        }

        statusListener.onPostProcessStatusUpdate(sb.toString());
    }

    private String determineTargetMode(PostProcessCapabilities caps) {
        if (!caps.supportsPostProcess) {
            return "SDR";
        }
        return resolveHdrMode(caps) == LibretroHdrUniforms.HDR_MODE_SCRGB ? "scRGB" : "SDR";
    }

    private int resolveHdrMode(PostProcessCapabilities caps) {
        if (hostHdrStreamActive) {
            return LibretroHdrUniforms.HDR_MODE_OFF;
        }

        switch (prefConfig.videoHdrMode) {
            case LibretroHdrUniforms.HDR_MODE_SCRGB:
            case LibretroHdrUniforms.HDR_MODE_HDR10:
                return caps.supportsScRgb ? LibretroHdrUniforms.HDR_MODE_SCRGB : LibretroHdrUniforms.HDR_MODE_OFF;
            default:
                return LibretroHdrUniforms.HDR_MODE_OFF;
        }
    }

    private String loadFragmentWithIncludes() {
        // GLES raw resources do not understand #include, so we manually
        // splice the common header in front of the composite body. The
        // composite file references "libretro_hdr_common.glsl" and
        // "libretro_hdr_tonemap.frag" verbatim; we replace those includes
        // with the actual file contents.
        String composite = readRawResource(com.limelight.R.raw.libretro_hdr_composite);
        String common   = readRawResource(com.limelight.R.raw.libretro_hdr_common);
        String tonemap  = readRawResource(com.limelight.R.raw.libretro_hdr_tonemap);

        if (composite == null || common == null || tonemap == null) {
            LimeLog.warning("PostProcess: missing libretro HDR shader source");
            return composite;
        }

        // The Vulkan libretro shaders use "#include <filename>". The
        // Android raw resources cannot resolve them, so do the splice
        // here. Order matters: include the common header (defines the
        // UBO and matrices) before the tonemap file.
        return composite
                .replace("#include \"libretro_hdr_common.glsl\"", common)
                .replace("#include \"libretro_hdr_tonemap.frag\"", tonemap);
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
}
