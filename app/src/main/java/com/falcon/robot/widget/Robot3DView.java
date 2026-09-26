package com.falcon.robot.widget;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The robot as a 3D model the operator can turn with a finger.
 *
 * <p>Drag to rotate, and it keeps spinning for a moment when you let go; pinch to zoom; double tap
 * to put it back as it was. Left alone it turns slowly by itself, so the panel is never static.
 *
 * <p>The figure is built from primitives ({@link RobotMesh}), so nothing has to ship with the app.
 * Call {@link #setModelAsset} to draw a real model instead: put an OBJ export in
 * {@code app/src/main/assets} and name it here.
 */
public class Robot3DView extends GLSurfaceView {

    /** Degrees per second while nobody is touching it. */
    private static final float IDLE_SPIN = 12f;
    /** How long after a touch the idle spin starts again. */
    private static final long IDLE_DELAY_MS = 2500;
    private static final float MIN_DISTANCE = 3.2f;
    private static final float MAX_DISTANCE = 9f;
    private static final float DEFAULT_DISTANCE = 5.2f;
    private static final float MAX_PITCH = 55f;

    private final Renderer renderer = new Renderer();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;

    private float lastX;
    private float lastY;
    private long lastTouchTime;

    public Robot3DView(Context context) {
        this(context, null);
    }

    public Robot3DView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                renderer.distance = clamp(renderer.distance / detector.getScaleFactor(),
                        MIN_DISTANCE, MAX_DISTANCE);
                return true;
            }
        });
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent event) {
                renderer.yaw = 24f;
                renderer.pitch = 8f;
                renderer.spin = 0f;
                renderer.distance = DEFAULT_DISTANCE;
                return true;
            }
        });
    }

    /** Draws an OBJ from the assets instead of the built-in figure, if it is there. */
    public void setModelAsset(final String assetName) {
        final Context context = getContext().getApplicationContext();
        queueEvent(() -> {
            RobotMesh model = RobotMesh.fromAsset(context, assetName);
            if (model != null) renderer.replaceMesh(model);
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        lastTouchTime = SystemClock.elapsedRealtime();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                renderer.spin = 0f; // the finger takes over from the idle turn
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    renderer.yaw += dx * 0.4f;
                    renderer.pitch = clamp(renderer.pitch + dy * 0.25f, -MAX_PITCH, MAX_PITCH);
                    renderer.spin = dx * 0.4f * 20f; // carried on as momentum when the finger lifts
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    private long idleSince() {
        return SystemClock.elapsedRealtime() - lastTouchTime;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- rendering ---------------------------------------------------------------------------

    private final class Renderer implements GLSurfaceView.Renderer {

        private static final String VERTEX_SHADER =
                "uniform mat4 uMvp;\n"
                        + "uniform mat4 uModel;\n"
                        + "attribute vec3 aPosition;\n"
                        + "attribute vec3 aNormal;\n"
                        + "attribute vec3 aColour;\n"
                        + "varying vec3 vNormal;\n"
                        + "varying vec3 vColour;\n"
                        + "void main() {\n"
                        + "  vNormal = mat3(uModel) * aNormal;\n"
                        + "  vColour = aColour;\n"
                        + "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
                        + "}\n";

        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n"
                        + "varying vec3 vNormal;\n"
                        + "varying vec3 vColour;\n"
                        + "uniform vec3 uLight;\n"
                        + "void main() {\n"
                        + "  vec3 n = normalize(vNormal);\n"
                        + "  float key = max(dot(n, normalize(uLight)), 0.0);\n"
                        + "  float fill = max(dot(n, vec3(-0.4, 0.2, 0.6)), 0.0) * 0.25;\n"
                        // a cyan rim picks the silhouette out of the dark panel
                        + "  float rim = pow(1.0 - max(n.z, 0.0), 3.0) * 0.5;\n"
                        + "  vec3 colour = vColour * (0.32 + 0.78 * key + fill)\n"
                        + "      + vec3(0.10, 0.45, 0.60) * rim;\n"
                        + "  gl_FragColor = vec4(colour, 1.0);\n"
                        + "}\n";

        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] model = new float[16];
        private final float[] temp = new float[16];
        private final float[] mvp = new float[16];

        private volatile RobotMesh mesh = RobotMesh.humanoid();
        private int program;
        private int positionHandle;
        private int normalHandle;
        private int colourHandle;
        private int mvpHandle;
        private int modelHandle;
        private int lightHandle;

        float yaw = 24f;
        float pitch = 8f;
        float spin;
        float distance = DEFAULT_DISTANCE;
        private long lastFrame;

        void replaceMesh(RobotMesh replacement) {
            mesh = replacement;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            GLES20.glClearColor(0.043f, 0.082f, 0.149f, 1f); // the panel's navy
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_CULL_FACE);

            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER));
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
            GLES20.glLinkProgram(program);

            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            normalHandle = GLES20.glGetAttribLocation(program, "aNormal");
            colourHandle = GLES20.glGetAttribLocation(program, "aColour");
            mvpHandle = GLES20.glGetUniformLocation(program, "uMvp");
            modelHandle = GLES20.glGetUniformLocation(program, "uModel");
            lightHandle = GLES20.glGetUniformLocation(program, "uLight");
            lastFrame = SystemClock.elapsedRealtime();
        }

        private int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            float aspect = height == 0 ? 1f : (float) width / height;
            Matrix.perspectiveM(projection, 0, 42f, aspect, 1f, 40f);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            long now = SystemClock.elapsedRealtime();
            float seconds = Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            advance(seconds);

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glUseProgram(program);

            Matrix.setLookAtM(view, 0, 0f, 0.1f, distance, 0f, 0f, 0f, 0f, 1f, 0f);
            Matrix.setIdentityM(model, 0);
            Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f);
            Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f);
            Matrix.multiplyMM(temp, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0);

            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0);
            GLES20.glUniform3f(lightHandle, 0.4f, 0.8f, 0.7f);

            RobotMesh current = mesh;
            java.nio.FloatBuffer vertices = current.vertices();
            int stride = RobotMesh.strideBytes();

            vertices.position(0);
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, stride, vertices);
            GLES20.glEnableVertexAttribArray(positionHandle);
            vertices.position(3);
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, stride, vertices);
            GLES20.glEnableVertexAttribArray(normalHandle);
            vertices.position(6);
            GLES20.glVertexAttribPointer(colourHandle, 3, GLES20.GL_FLOAT, false, stride, vertices);
            GLES20.glEnableVertexAttribArray(colourHandle);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, current.vertexCount());

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(normalHandle);
            GLES20.glDisableVertexAttribArray(colourHandle);
        }

        /** Momentum after a drag, then the slow idle turn once the finger has been gone a while. */
        private void advance(float seconds) {
            if (Math.abs(spin) > 0.5f) {
                yaw += spin * seconds;
                spin *= Math.max(0f, 1f - 2.2f * seconds); // friction
            } else if (idleSince() > IDLE_DELAY_MS) {
                yaw += IDLE_SPIN * seconds;
            }
            if (yaw > 360f) yaw -= 360f;
            if (yaw < -360f) yaw += 360f;
        }
    }
}
