package com.limelight.binding.video;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class PostProcessVideoRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

    private final Context context;
    private final Surface outputSurface;
    private final PreferenceConfiguration prefConfig;
    private final float streamFps;
    private final float displayRefreshRate;
    private final boolean hostHdrStreamActive;

    private SurfaceTexture surfaceTexture;
    private Surface codecSurface;
    private EglPostProcessContext eglContext;
    private HandlerThread renderThread;
    private Handler renderHandler;

    private int program;
    private int textureId;
    private FloatBuffer quadVertexBuffer;
    private FloatBuffer texCoordBuffer;

    private volatile boolean running;
    private volatile boolean frameAvailable;
    private volatile boolean surfaceTextureReady;

    private BfiScheduler bfiScheduler;
    private HdrCompositeSettings hdrSettings;

    private long framePeriodNs;
    private final Runnable renderTick = this::onRenderTick;

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
            boolean hostHdrStreamActive
    ) {
        this.context = context;
        this.outputSurface = outputSurface;
        this.prefConfig = prefs;
        this.streamFps = streamFps;
        this.displayRefreshRate = displayRefreshRate;
        this.hostHdrStreamActive = hostHdrStreamActive;

        this.bfiScheduler = new BfiScheduler();
        this.hdrSettings = HdrCompositeSettings.fromPrefs(
                prefs.clientHdrMode,
                prefs.clientHdrPaperWhiteNits,
                prefs.clientHdrPeakNits,
                prefs.clientHdrExpandGamut,
                prefs.clientBfi,
                prefs.clientBfiCompensationMode
        );

        this.framePeriodNs = (long)(1_000_000_000.0 / displayRefreshRate);
    }

    public Surface getCodecSurface() {
        return codecSurface;
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

        running = true;

        renderThread = new HandlerThread("PostProcessGL");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        renderHandler.post(this::initGl);
    }

    public void stop() {
        running = false;
        if (renderHandler != null) {
            renderHandler.removeCallbacks(renderTick);
            renderHandler.post(this::releaseGl);
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
        frameAvailable = true;
    }

    private void initGl() {
        eglContext = new EglPostProcessContext(outputSurface);
        if (!eglContext.initialize()) {
            LimeLog.warning("PostProcess: EGL init failed, falling back");
            running = false;
            return;
        }

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

        program = createProgram();
        if (program == 0) {
            LimeLog.warning("PostProcess: shader compile failed");
            running = false;
            return;
        }

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).flip();

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS).flip();

        surfaceTextureReady = true;
        LimeLog.info("PostProcess: GL initialized, starting render loop at " + displayRefreshRate + " Hz");

        renderHandler.post(renderTick);
    }

    private void onRenderTick() {
        if (!running || !eglContext.isInitialized()) return;

        if (!eglContext.makeCurrent()) return;

        boolean isBlack = bfiScheduler.nextIsBlack();

        if (!isBlack) {
            if (surfaceTextureReady && frameAvailable) {
                surfaceTexture.updateTexImage();
                frameAvailable = false;
            }

            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);

            int positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            int texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
            int textureHandle = GLES20.glGetUniformLocation(program, "uTexture");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureHandle, 0);

            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        eglContext.swapBuffers();

        if (running) {
            renderHandler.postDelayed(renderTick, framePeriodNs / 1000000L);
        }
    }

    private void releaseGl() {
        if (eglContext != null) {
            eglContext.makeCurrent();
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
        LimeLog.info("PostProcess: GL released");
    }

    private int createProgram() {
        String vertexSource =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = aPosition;\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}\n";

        String fragmentSource =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n";

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
