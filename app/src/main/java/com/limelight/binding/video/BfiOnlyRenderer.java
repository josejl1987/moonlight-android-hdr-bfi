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

/**
 * Slim BFI-only renderer for the host-HDR + BFI fast path.
 *
 * <p>Mirrors {@link PostProcessVideoRenderer}'s lifecycle (init GL on caller
 * thread, {@link #startBlocking()} for the render thread) but strips out the
 * SDR adapter FBO, the 2D RGBA8 intermediate texture, the libretro composite
 * program, and the tonemap pipeline. It owns exactly:</p>
 * <ul>
 *   <li>one EGL context (delegated to {@link EglPostProcessContext} with
 *       {@code requestMode="HDR10"} so the system compositor presents the
 *       surface as BT.2020 PQ);</li>
 *   <li>one OES adapter program (the existing
 *       {@code oes_adapter_vert/frag} shaders);</li>
 *   <li>one {@code GL_TEXTURE_EXTERNAL_OES} bound to a {@link SurfaceTexture}
 *       whose {@link Surface} is exposed to the decoder;</li>
 *   <li>one {@link BfiScheduler} instance for the cadence.</li>
 * </ul>
 *
 * <p>On a "show" refresh the renderer binds the OES adapter program and draws
 * a fullscreen quad sampling the OES texture with the OES transform matrix.
 * On a "black" refresh it issues a single {@code glClear} (zero RGB → PQ(0) =
 * 0 cd/m², true black via the panel EOTF on a BT.2020 PQ surface).</p>
 *
 * <p>Failure modes:</p>
 * <ul>
 *   <li>EGL init failure or EGL HDR10 silent fallback to SDR →
 *       {@link #startBlocking()} returns {@code false}; the caller is expected
 *       to fall back to the direct surface path.</li>
 *   <li>Shader compile/link failure, OES texture init failure → same.</li>
 * </ul>
 */
public final class BfiOnlyRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final long INIT_TIMEOUT_MS = 2000;
    // Throttle status publishing to avoid spamming the UI thread. Mirrors
    // PostProcessVideoRenderer.java:598-600 (~every 60 frames ≈ 0.5s at
    // 120Hz).
    private static final int STATUS_PUBLISH_INTERVAL_FRAMES = 60;
    // Stall threshold for the public isFrameStalled() hook used by the BFI
    // toggle toast. 2 vsync intervals ≈ 16.6 ms at 120 Hz. Mirrors
    // PostProcessVideoRenderer.STALL_HOOK_TIMEOUT_NS.
    private static final long STALL_HOOK_TIMEOUT_NS = 2L * 16_666_667L;

    private final Context context;
    private final Surface outputSurface;
    private final PreferenceConfiguration prefConfig;
    private final float streamFps;
    private final float displayRefreshRate;
    private final Window window;
    private final Display display;
    private final PostProcessStatusListener statusListener;

    private EglPostProcessContext eglContext;
    private SurfaceTexture surfaceTexture;
    private Surface codecSurface;

    private final BfiScheduler bfiScheduler = new BfiScheduler();
    private int oesProgram;
    private int oesTextureId;
    private int aPosLoc;
    private int aTexCoordLoc;
    private int uTextureLoc;
    private int uTexTransformLoc;
    private final float[] oesTransform = new float[16];
    private FloatBuffer quadVertexBuffer;
    private FloatBuffer texCoordBuffer;

    private volatile boolean running;
    private volatile boolean recoveryFailed;
    private volatile boolean surfaceTextureReady;
    private volatile boolean frameAvailable;
    private volatile boolean released;
    private int statsFrameCount;

    private HandlerThread renderThread;
    private Handler renderHandler;
    private Choreographer choreographer;
    private final Choreographer.FrameCallback renderFrameCallback = this::onVsyncFrame;

    private CountDownLatch initLatch;

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

    public BfiOnlyRenderer(
            Context context,
            Surface outputSurface,
            PreferenceConfiguration prefs,
            float streamFps,
            float displayRefreshRate,
            Window window,
            Display display,
            PostProcessStatusListener statusListener
    ) {
        this.context = context;
        this.outputSurface = outputSurface;
        this.prefConfig = prefs;
        this.streamFps = streamFps;
        this.displayRefreshRate = displayRefreshRate;
        this.window = window;
        this.display = display;
        this.statusListener = statusListener;
    }

    public Surface getCodecSurface() {
        return codecSurface;
    }

    /**
     * Returns {@code true} when no new decoder frame has arrived for at
     * least two vsync intervals, as tracked by the embedded
     * {@link BfiScheduler}. Safe to call from any thread (backed by a
     * volatile read).
     */
    public boolean isFrameStalled() {
        return bfiScheduler.isFrameStalled();
    }

    public boolean startBlocking() {
        if (running) return true;

        initLatch = new CountDownLatch(1);
        start();

        try {
            if (!initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LimeLog.warning("BfiOnly: init timed out after " + INIT_TIMEOUT_MS + "ms");
                recoveryFailed = true;
                stop();
                return false;
            }
            if (recoveryFailed || codecSurface == null || !surfaceTextureReady) {
                LimeLog.warning("BfiOnly: init failed (codecSurface=" + codecSurface
                        + " ready=" + surfaceTextureReady + " failed=" + recoveryFailed + ")");
                return false;
            }
            running = true;
            return true;
        } catch (InterruptedException e) {
            LimeLog.warning("BfiOnly: init interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void start() {
        if (renderThread != null) return;

        renderThread = new HandlerThread("BfiOnlyGL");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        renderHandler.post(this::initGl);
    }

    private void stop() {
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
                } finally {
                    done.countDown();
                }
            });
            renderHandler = null;
            try {
                if (!done.await(2000, TimeUnit.MILLISECONDS)) {
                    LimeLog.warning("BfiOnly: stop timed out waiting for releaseGl");
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
        // Idempotent: a second release() call is a no-op. This matters for
        // the Game.java release path (onStop + onDestroy) which can fire
        // twice in a row if the activity is re-bound.
        if (released) return;
        released = true;
        stop();
    }

    public boolean isBfiActive() {
        return running && !recoveryFailed;
    }

    public String getDebugStatus() {
        return "BFI=" + bfiScheduler.getDarkFrames()
                + ", cycle=" + (1 + bfiScheduler.getDarkFrames());
    }

    /**
     * Re-read the BFI-related preferences and push them to the
     * {@link BfiScheduler} on the render thread. Mirrors
     * {@code PostProcessVideoRenderer.updateSettings()} (line ~716-721) so the
     * caller can swap which renderer is active without changing the call
     * site. Returns silently when the renderer has been stopped (no live
     * render handler to post to).
     */
    public void updateSettings() {
        Handler handler = renderHandler;
        if (handler == null) {
            return;
        }
        handler.post(() -> {
            int darkFrames = Math.max(1, prefConfig.videoBfiDarkFrames);
            boolean bfiActive = prefConfig.videoBlackFrameInsertion
                    && bfiScheduler.canEnable(streamFps, displayRefreshRate, darkFrames);
            bfiScheduler.configure(bfiActive, darkFrames);
        });
    }

    private void publishStatus() {
        if (statusListener == null) return;
        // The render thread is the caller for in-loop publishes; the
        // PostProcessStatusListener implementations in this codebase marshal
        // to the UI thread themselves (see Game#onPostProcessStatusUpdate),
        // so a direct call is safe.
        statusListener.onPostProcessStatusUpdate(getDebugStatus());
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // Listener fires on the render thread (we passed renderHandler to
        // setOnFrameAvailableListener). The Choreographer loop consumes the
        // flag on each vsync — see onVsyncFrame.
        frameAvailable = true;
    }

    private void initGl() {
        eglContext = new EglPostProcessContext(outputSurface, "HDR10");
        if (!eglContext.initialize()) {
            LimeLog.warning("BfiOnly: EGL init failed");
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        // Critical: EglPostProcessContext.fallbackChain("HDR10") returns
        // ["HDR10", "SDR"]. If the device silently fell back to SDR,
        // glClear(0,0,0,1) writes sRGB(0) instead of PQ(0) — defeating BFI.
        // Spec requires a true PQ surface; release and fail fast.
        if (!"HDR10".equals(eglContext.getActualMode())) {
            LimeLog.warning("BfiOnly: EGL HDR10 fallback to " + eglContext.getActualMode()
                    + " — refusing fast path");
            eglContext.release();
            eglContext = null;
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        if (!eglContext.makeCurrent()) {
            LimeLog.warning("BfiOnly: EGL makeCurrent failed after init");
            eglContext.release();
            eglContext = null;
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        String vertexSource = readRawResource(com.limelight.R.raw.oes_adapter_vert);
        String fragmentSource = readRawResource(com.limelight.R.raw.oes_adapter_frag);
        oesProgram = createProgram(vertexSource, fragmentSource);
        if (oesProgram == 0) {
            LimeLog.warning("BfiOnly: OES adapter shader compile/link failed");
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        aPosLoc = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(oesProgram, "aTexCoord");
        uTextureLoc = GLES20.glGetUniformLocation(oesProgram, "uTexture");
        uTexTransformLoc = GLES20.glGetUniformLocation(oesProgram, "uTexTransform");

        if (uTexTransformLoc < 0) {
            LimeLog.warning("BfiOnly: uTexTransform uniform not found in OES adapter program");
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            LimeLog.warning("BfiOnly: glGenTextures failed");
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }
        oesTextureId = textures[0];

        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(oesTextureId);
        surfaceTexture.setDefaultBufferSize(prefConfig.width, prefConfig.height);
        surfaceTexture.setOnFrameAvailableListener(this, renderHandler);
        codecSurface = new Surface(surfaceTexture);

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).flip();

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS).flip();

        int darkFrames = Math.max(1, prefConfig.videoBfiDarkFrames);
        boolean bfiActive = prefConfig.videoBlackFrameInsertion
                && bfiScheduler.canEnable(streamFps, displayRefreshRate, darkFrames);
        bfiScheduler.configure(bfiActive, darkFrames);

        surfaceTextureReady = true;
        choreographer = Choreographer.getInstance();

        LimeLog.info("BfiOnly: GL initialized, target=HDR10 actual=" + eglContext.getActualMode()
                + " oesProgram=" + oesProgram + " oesTexture=" + oesTextureId
                + " bfi=" + bfiActive + " darkFrames=" + darkFrames);

        publishStatus();

        if (initLatch != null) initLatch.countDown();

        // Kick off the Choreographer-driven render loop on this render thread.
        choreographer.postFrameCallback(renderFrameCallback);
    }

    private void releaseGl() {
        if (eglContext != null) {
            if (!eglContext.makeCurrent()) {
                LimeLog.warning("BfiOnly: makeCurrent failed during releaseGl");
            }
            if (oesProgram != 0) {
                GLES20.glDeleteProgram(oesProgram);
                oesProgram = 0;
            }
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{oesTextureId}, 0);
                oesTextureId = 0;
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
        recoveryFailed = false;
        statsFrameCount = 0;
        LimeLog.info("BfiOnly: GL released");
        // Publish "BFI=off" so the overlay reflects the inactive state.
        publishStatus();
    }

    private void onVsyncFrame(long frameTimeNanos) {
        if (!running || recoveryFailed || eglContext == null || !eglContext.isInitialized()) {
            return;
        }

        if (!eglContext.makeCurrent()) {
            LimeLog.warning("BfiOnly: EGL context lost, stopping renderer");
            recoveryFailed = true;
            return;
        }

        int surfaceW = eglContext.getSurfaceWidth();
        int surfaceH = eglContext.getSurfaceHeight();
        if (surfaceW > 0 && surfaceH > 0) {
            GLES20.glViewport(0, 0, surfaceW, surfaceH);
        }

        long nowNs = System.nanoTime();
        // Drive the public isFrameStalled() hook on every vsync so the
        // value reflects the latest gap, even when no new frame arrived.
        bfiScheduler.evaluateStall(nowNs, STALL_HOOK_TIMEOUT_NS);

        boolean isBlack = bfiScheduler.nextIsBlack();

        if (isBlack) {
            // Dark phase: write PQ(0) via glClear. On a BT.2020 PQ EGL
            // surface the framebuffer is registered as PQ-encoded to the
            // compositor, so zero RGB writes PQ(0) = 0 cd/m² — true black
            // after the panel EOTF.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        } else if (frameAvailable && surfaceTextureReady) {
            // Show phase: consume the latest OES frame, draw it through the
            // OES adapter program into the EGL window surface.
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(oesTransform);
            } catch (Exception e) {
                LimeLog.warning("BfiOnly: updateTexImage failed: " + e.getMessage());
                recoveryFailed = true;
                return;
            }
            frameAvailable = false;
            bfiScheduler.recordFrameArrival(nowNs);

            // Always clear to black first so any partial draw is bounded by
            // PQ(0). The OES adapter program overwrites the entire viewport
            // with the decoded frame.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(oesProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniform1i(uTextureLoc, 0);
            GLES20.glUniformMatrix4fv(uTexTransformLoc, 1, false, oesTransform, 0);

            GLES20.glEnableVertexAttribArray(aPosLoc);
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoordLoc);
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPosLoc);
            GLES20.glDisableVertexAttribArray(aTexCoordLoc);
        } else {
            // Show phase but no new frame from the decoder (stall). Per spec:
            // do not repeat the last frame (would cause motion judder); write
            // PQ(0) instead. Once frames flow again, the next vsync will draw.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        if (!recoveryFailed) {
            eglContext.swapBuffers();
        }

        // Surface any GL errors from this frame. We check after swapBuffers
        // so the loop self-recovers via recoveryFailed on the next vsync if
        // the EGL context was lost (e.g. backgrounded activity).
        int glError = GLES20.glGetError();
        if (glError != GLES20.GL_NO_ERROR) {
            LimeLog.warning("BfiOnly: glGetError after frame: 0x" + Integer.toHexString(glError));
            recoveryFailed = true;
        }

        statsFrameCount++;
        if (statsFrameCount == 1 || statsFrameCount % STATUS_PUBLISH_INTERVAL_FRAMES == 0) {
            publishStatus();
        }

        if (running && !recoveryFailed && choreographer != null) {
            choreographer.postFrameCallback(renderFrameCallback);
        }
    }

    private String readRawResource(int resId) {
        try (InputStream is = context.getResources().openRawResource(resId);
             Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        } catch (Exception e) {
            LimeLog.warning("BfiOnly: failed to read raw resource " + resId + ": " + e.getMessage());
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
            LimeLog.warning("BfiOnly: vertex shader compile failed: " + GLES20.glGetShaderInfoLog(vertexShader));
            GLES20.glDeleteShader(vertexShader);
            return 0;
        }

        int fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShader, fragmentSource);
        GLES20.glCompileShader(fragmentShader);

        GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.warning("BfiOnly: fragment shader compile failed: " + GLES20.glGetShaderInfoLog(fragmentShader));
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
            LimeLog.warning("BfiOnly: program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }
}
