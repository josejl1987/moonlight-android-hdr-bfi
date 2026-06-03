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

    private BfiScheduler bfiScheduler;
    private HdrCompositeSettings hdrSettings;
    private Choreographer choreographer;
    private final Choreographer.FrameCallback renderFrameCallback = this::onVsyncFrame;

    private CountDownLatch initLatch;

    private int uTextureLoc;
    private int uPaperWhiteNitsLoc;
    private int uPeakNitsLoc;
    private int uInverseTonemapStrengthLoc;
    private int uOutputModeLoc;

    private int outputModeUniform;

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

    private static final int OUTPUT_MODE_SDR = 0;
    private static final int OUTPUT_MODE_SCRGB = 1;

    public PostProcessVideoRenderer(
            Context context,
            Surface outputSurface,
            PreferenceConfiguration prefs,
            float streamFps,
            float displayRefreshRate,
            boolean hostHdrStreamActive,
            Window window,
            Display display
    ) {
        this.context = context;
        this.outputSurface = outputSurface;
        this.prefConfig = prefs;
        this.streamFps = streamFps;
        this.displayRefreshRate = displayRefreshRate;
        this.hostHdrStreamActive = hostHdrStreamActive;
        this.window = window;
        this.display = display;

        this.bfiScheduler = new BfiScheduler();
        this.hdrSettings = HdrCompositeSettings.fromPrefs(
                prefs.clientHdrMode,
                prefs.clientHdrPaperWhiteNits,
                prefs.clientHdrPeakNits,
                prefs.clientHdrExpandGamut,
                prefs.clientBfi,
                prefs.clientBfiCompensationMode
        );

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

        if (hostHdrStreamActive) {
            LimeLog.info("PostProcess: host HDR active, disabling SDR->HDR boost");
            hdrSettings.hdrMode = HdrCompositeSettings.HDR_MODE_OFF;
        }

        if (bfiScheduler.canEnable(streamFps, displayRefreshRate)) {
            bfiScheduler.setEnabled(prefConfig.clientBfi);
            if (bfiScheduler.isEnabled()) {
                LimeLog.info("PostProcess: BFI 1:1 enabled at " + streamFps + " FPS / " + displayRefreshRate + " Hz");
            }
        } else {
            bfiScheduler.setEnabled(false);
            if (prefConfig.clientBfi) {
                LimeLog.info("PostProcess: BFI disabled - display " + displayRefreshRate
                        + " Hz does not match 2x stream " + streamFps + " FPS");
            }
        }

        LimeLog.info("PostProcess: displayHz=" + displayRefreshRate + " streamFps=" + streamFps);
        LimeLog.info("PostProcess: HDR boost paperWhite=" + hdrSettings.paperWhiteNits
                + " peak=" + hdrSettings.effectiveVisiblePeakNits
                + " gamut=" + hdrSettings.expandGamut
                + " strength=" + hdrSettings.inverseTonemapStrength);

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

        boolean bfiUseful = prefs.clientBfi && Math.abs(displayRefreshRate - prefs.fps * 2.0f) <= 3.0f;
        boolean hdrUseful = prefs.clientHdrMode != HdrCompositeSettings.HDR_MODE_OFF && !hostHdrStreamActive;

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

        String actualMode = eglContext.getActualMode();
        outputModeUniform = actualMode.equals("scRGB") ? OUTPUT_MODE_SCRGB : OUTPUT_MODE_SDR;

        LimeLog.info("PostProcess: target=" + targetMode + " actual=" + actualMode
                + " outputUniform=" + outputModeUniform);

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

        String vertexSource = readRawResource(com.limelight.R.raw.postprocess_video);
        if (vertexSource == null) {
            vertexSource =
                    "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}\n";
        }

        String fragmentSource = readRawResource(com.limelight.R.raw.postprocess_hdr_composite);
        if (fragmentSource == null) {
            // Keep this fallback in sync with res/raw/postprocess_hdr_composite.frag.
            fragmentSource =
                    "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "precision highp samplerExternalOES;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "uniform float uPaperWhiteNits;\n" +
                    "uniform float uPeakNits;\n" +
                    "uniform float uInverseTonemapStrength;\n" +
                    "uniform int uOutputMode;\n" +
                    "vec3 srgbToLinear(vec3 c) {\n" +
                    "    bvec3 cutoff = lessThanEqual(c, vec3(0.04045));\n" +
                    "    vec3 lo = c / 12.92;\n" +
                    "    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));\n" +
                    "    return mix(hi, lo, vec3(cutoff));\n" +
                    "}\n" +
                    "vec3 linearToSrgb(vec3 c) {\n" +
                    "    bvec3 cutoff = lessThanEqual(c, vec3(0.0031308));\n" +
                    "    vec3 lo = c * 12.92;\n" +
                    "    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;\n" +
                    "    return mix(hi, lo, vec3(cutoff));\n" +
                    "}\n" +
                    "vec3 inverseTonemap(vec3 sdrLinear, float peakNits, float paperWhiteNits, float strength) {\n" +
                    "    float inputVal = max(max(sdrLinear.r, sdrLinear.g), sdrLinear.b);\n" +
                    "    if (inputVal < 0.0001) return sdrLinear;\n" +
                    "    float peakRatio = max(peakNits / paperWhiteNits, 1.0);\n" +
                    "    float denominator = 1.0 - inputVal * (1.0 - (1.0 / peakRatio));\n" +
                    "    float mapped = inputVal / max(denominator, 0.0001);\n" +
                    "    vec3 boosted = sdrLinear * (mapped / inputVal);\n" +
                    "    return mix(sdrLinear, boosted, clamp(strength, 0.0, 1.0));\n" +
                    "}\n" +
                    "void main() {\n" +
                    "    vec4 sampled = texture2D(uTexture, vTexCoord);\n" +
                    "    vec3 linear709 = srgbToLinear(sampled.rgb);\n" +
                    "    if (uOutputMode == 0) {\n" +
                    "        gl_FragColor = vec4(linearToSrgb(linear709), 1.0);\n" +
                    "        return;\n" +
                    "    }\n" +
                    "    vec3 boosted709 = inverseTonemap(linear709, uPeakNits, uPaperWhiteNits, uInverseTonemapStrength);\n" +
                    "    gl_FragColor = vec4(boosted709 * (uPaperWhiteNits / 80.0), 1.0);\n" +
                    "}\n";
        }

        program = createProgram(vertexSource, fragmentSource);
        if (program == 0) {
            LimeLog.warning("PostProcess: shader compile failed");
            running = false;
            recoveryFailed = true;
            if (initLatch != null) initLatch.countDown();
            return;
        }

        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture");
        uPaperWhiteNitsLoc = GLES20.glGetUniformLocation(program, "uPaperWhiteNits");
        uPeakNitsLoc = GLES20.glGetUniformLocation(program, "uPeakNits");
        uInverseTonemapStrengthLoc = GLES20.glGetUniformLocation(program, "uInverseTonemapStrength");
        uOutputModeLoc = GLES20.glGetUniformLocation(program, "uOutputMode");

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

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTextureLoc, 0);

            float peakForShader = bfiScheduler.isEnabled()
                    ? hdrSettings.effectiveVisiblePeakNits
                    : hdrSettings.peakNits;

            GLES20.glUniform1f(uPaperWhiteNitsLoc, hdrSettings.paperWhiteNits);
            GLES20.glUniform1f(uPeakNitsLoc, peakForShader);
            GLES20.glUniform1f(uInverseTonemapStrengthLoc,
                    hdrSettings.hdrMode != HdrCompositeSettings.HDR_MODE_OFF
                            ? hdrSettings.inverseTonemapStrength : 0.0f);
            GLES20.glUniform1i(uOutputModeLoc, outputModeUniform);

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
    }

    private String determineTargetMode(PostProcessCapabilities caps) {
        if (!caps.supportsPostProcess) {
            return "SDR";
        }

        int userMode = prefConfig.clientHdrMode;

        switch (userMode) {
            case HdrCompositeSettings.HDR_MODE_SCRGB:
            case HdrCompositeSettings.HDR_MODE_HDR10:
                if (caps.supportsScRgb) return "scRGB";
                if (caps.supportsWideColor) return "Display P3";
                return "SDR";
            case HdrCompositeSettings.HDR_MODE_AUTO:
                return caps.selectedOutputMode;
            default:
                return "SDR";
        }
    }

    private String readRawResource(int resId) {
        try (InputStream is = context.getResources().openRawResource(resId);
             Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
            String result = s.hasNext() ? s.next() : "";
            return result;
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
