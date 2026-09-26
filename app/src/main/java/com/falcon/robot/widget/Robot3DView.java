package com.falcon.robot.widget;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The robot as a 3D model the operator can turn, standing on a lit floor grid.
 *
 * <p>Drag to rotate, and it keeps spinning for a moment when you let go; pinch — or roll a mouse
 * wheel — to zoom; double tap to put it back as it was. Left alone it turns slowly by itself, so
 * the panel is never static. The floor turns with it, like a turntable.
 *
 * <p>The figure is built from primitives ({@link RobotMesh}), so nothing has to ship with the app.
 * Call {@link #setModelAsset} to draw a real model instead: put an OBJ export in
 * {@code app/src/main/assets} and name it here.
 */
public class Robot3DView extends GLSurfaceView {

    private static final String TAG = "Robot3DView";

    /** Degrees per second while nobody is touching it. */
    private static final float IDLE_SPIN = 12f;
    /** How long after a touch the idle spin starts again. */
    private static final long IDLE_DELAY_MS = 2500;
    private static final float MIN_DISTANCE = 2.2f;
    private static final float MAX_DISTANCE = 9f;
    private static final float DEFAULT_DISTANCE = 3.6f;
    private static final float DEFAULT_YAW = 24f;
    /** Looking slightly down on the robot, so the floor grid reads as a floor. */
    private static final float DEFAULT_PITCH = 13f;
    private static final float MAX_PITCH = 60f;
    /** One wheel notch, as a fraction of the viewing distance. */
    private static final double WHEEL_STEP = 0.88;

    /**
     * Framing for the camera overlay: the robot's upper body seen from directly behind, so it
     * faces the same way the camera does and the feed reads as what is in front of it. The camera
     * looks level, and the body is cropped by the bottom of the view, the way an over the shoulder
     * camera frames it.
     */
    private static final float OVERLAY_EYE_Y = 0.675f;
    private static final float OVERLAY_DISTANCE = 1.2f;
    private static final float OVERLAY_YAW = 180f;
    private static final float OVERLAY_PITCH = 6f;

    private final Renderer renderer = new Renderer();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    /** Draws the upper body alone, on nothing, to lay over the camera feed. */
    private final boolean overlay;

    private float lastX;
    private float lastY;
    private long lastTouchTime;

    public Robot3DView(Context context) {
        this(context, null);
    }

    public Robot3DView(Context context, AttributeSet attrs) {
        this(context, attrs, false);
    }

    /**
     * The robot's head and torso on their own, to lay over the camera feed: transparent, not
     * interactive, and seen from behind so it faces the way the camera looks. It acts out the same
     * commands as the full figure, so it turns and nods along with it.
     */
    public static Robot3DView cameraOverlay(Context context) {
        return new Robot3DView(context, null, true);
    }

    private Robot3DView(Context context, AttributeSet attrs, boolean overlay) {
        super(context, attrs);
        this.overlay = overlay;
        setEGLContextClientVersion(2);
        if (overlay) {
            // an alpha channel, and above the feed it sits on: a surface is behind the window
            // otherwise, and the camera image would hide it
            setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            getHolder().setFormat(PixelFormat.TRANSLUCENT);
            setZOrderOnTop(true);
            renderer.yaw = OVERLAY_YAW;
            renderer.pitch = OVERLAY_PITCH;
            renderer.distance = OVERLAY_DISTANCE;
        }
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom(1f / detector.getScaleFactor());
                return true;
            }
        });
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent event) {
                renderer.yaw = DEFAULT_YAW;
                renderer.pitch = DEFAULT_PITCH;
                renderer.spin = 0f;
                renderer.distance = DEFAULT_DISTANCE;
                return true;
            }
        });
    }

    /**
     * Acts out a robot command: the figure walks for a move, waves for a greeting, raises the arm
     * that was selected. Commands that are not movement (camera, speed) leave it as it is.
     */
    public void perform(String command) {
        play(RobotPose.actionFor(command));
    }

    /** Plays one of {@link RobotPose}'s actions, such as {@code RobotPose.WAVE}. */
    public void play(final int action) {
        if (action == RobotPose.NONE) return;
        queueEvent(() -> renderer.pose.play(action));
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
        if (overlay) return false; // an overlay: the panel underneath keeps its touches
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

    /** A mouse wheel or a trackpad two-finger scroll zooms, the same as a pinch. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!overlay && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float notches = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (notches != 0f) {
                zoom((float) Math.pow(WHEEL_STEP, notches));
                lastTouchTime = SystemClock.elapsedRealtime(); // hold the idle spin off
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private void zoom(float factor) {
        renderer.distance = clamp(renderer.distance * factor, MIN_DISTANCE, MAX_DISTANCE);
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
                        + "attribute float aMaterial;\n"
                        + "varying vec3 vNormal;\n"
                        + "varying vec3 vColour;\n"
                        + "varying vec3 vWorld;\n"
                        + "varying float vMaterial;\n"
                        + "void main() {\n"
                        + "  vNormal = mat3(uModel) * aNormal;\n"
                        + "  vWorld = (uModel * vec4(aPosition, 1.0)).xyz;\n"
                        + "  vColour = aColour;\n"
                        + "  vMaterial = aMaterial;\n"
                        + "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
                        + "}\n";

        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n"
                        + "varying vec3 vNormal;\n"
                        + "varying vec3 vColour;\n"
                        + "varying vec3 vWorld;\n"
                        + "varying float vMaterial;\n"
                        + "uniform vec3 uLight;\n"
                        + "uniform vec3 uEye;\n"
                        + "void main() {\n"
                        + "  vec3 n = normalize(vNormal);\n"
                        + "  vec3 toEye = normalize(uEye - vWorld);\n"
                        + "  vec3 toLight = normalize(uLight);\n"
                        + "  float key = max(dot(n, toLight), 0.0);\n"
                        + "  float fill = max(dot(n, normalize(vec3(-0.7, 0.25, 0.45))), 0.0) * 0.30;\n"
                        // a cyan rim picks the silhouette out of the dark panel
                        + "  float rim = pow(1.0 - max(dot(n, toEye), 0.0), 3.0);\n"
                        // 0 matte, 1 clear-coated armour, 2 self-lit
                        + "  float emissive = step(1.5, vMaterial);\n"
                        + "  float gloss = step(0.5, vMaterial) * (1.0 - emissive);\n"
                        + "  float spec = pow(max(dot(n, normalize(toLight + toEye)), 0.0), 42.0) * gloss;\n"
                        + "  vec3 lit = vColour * (0.22 + 0.86 * key + fill)\n"
                        + "      + vec3(0.10, 0.42, 0.62) * rim * 0.55\n"
                        + "      + vec3(0.85, 0.93, 1.00) * spec * 0.55;\n"
                        + "  vec3 glow = vColour * (1.05 + 0.35 * rim);\n"
                        + "  gl_FragColor = vec4(mix(lit, glow, emissive), 1.0);\n"
                        + "}\n";

        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] model = new float[16];
        private final float[] viewProjection = new float[16];
        private final float[] partModel = new float[16];
        private final float[] mvp = new float[16];

        /** What the robot is doing, and the matrix it puts each joint at. */
        private final RobotPose pose = new RobotPose();
        private final float[] joints = new float[RobotMesh.JOINTS * 16];

        // both built on the GL thread the first time the surface comes up, not during inflation
        private volatile RobotMesh mesh;
        private RobotMesh floor;
        private int program;
        private int positionHandle;
        private int normalHandle;
        private int colourHandle;
        private int materialHandle;
        private int mvpHandle;
        private int modelHandle;
        private int lightHandle;
        private int eyeHandle;

        volatile float yaw = DEFAULT_YAW;
        volatile float pitch = DEFAULT_PITCH;
        volatile float spin;
        volatile float distance = DEFAULT_DISTANCE;
        private long lastFrame;

        /** Called on the GL thread, so the buffer the old mesh held can go back here. */
        void replaceMesh(RobotMesh replacement) {
            RobotMesh previous = mesh;
            mesh = replacement;
            if (previous != null && previous.vbo != 0) {
                GLES20.glDeleteBuffers(1, new int[] {previous.vbo}, 0);
                previous.vbo = 0;
            }
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            // the overlay clears to nothing, so the camera feed shows through around the robot
            if (overlay) {
                GLES20.glClearColor(0f, 0f, 0f, 0f);
            } else {
                GLES20.glClearColor(0.043f, 0.082f, 0.149f, 1f); // the panel's navy
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_CULL_FACE);
            GLES20.glLineWidth(2f);

            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER));
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
            GLES20.glLinkProgram(program);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(program));

            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            normalHandle = GLES20.glGetAttribLocation(program, "aNormal");
            colourHandle = GLES20.glGetAttribLocation(program, "aColour");
            materialHandle = GLES20.glGetAttribLocation(program, "aMaterial");
            mvpHandle = GLES20.glGetUniformLocation(program, "uMvp");
            modelHandle = GLES20.glGetUniformLocation(program, "uModel");
            lightHandle = GLES20.glGetUniformLocation(program, "uLight");
            eyeHandle = GLES20.glGetUniformLocation(program, "uEye");

            // the built-in figure is skipped when an asset model has already taken its place
            if (mesh == null) mesh = RobotMesh.humanoid();
            if (floor == null && !overlay) floor = RobotMesh.floor();
            // the context is new, so whatever the meshes were uploaded into has gone with it
            mesh.vbo = 0;
            if (floor != null) floor.vbo = 0;
            lastFrame = SystemClock.elapsedRealtime();
        }

        private int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "shader failed: " + GLES20.glGetShaderInfoLog(shader));
            }
            return shader;
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            float aspect = height == 0 ? 1f : (float) width / height;
            Matrix.perspectiveM(projection, 0, 42f, aspect, 0.4f, 40f);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            long now = SystemClock.elapsedRealtime();
            float seconds = Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            advance(seconds);
            pose.advance(seconds);
            pose.matrices(joints);

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glUseProgram(program);

            // the overlay looks level, so its framing does not shift; the figure is seen from
            // slightly above, looking at the middle of it
            float eyeY = overlay ? OVERLAY_EYE_Y : 0.1f;
            float targetY = overlay ? OVERLAY_EYE_Y : 0f;
            Matrix.setLookAtM(view, 0, 0f, eyeY, distance, 0f, targetY, 0f, 0f, 1f, 0f);
            Matrix.setIdentityM(model, 0);
            Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f);
            Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f);
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0);

            GLES20.glUniform3f(lightHandle, 0.4f, 0.8f, 0.7f);
            GLES20.glUniform3f(eyeHandle, 0f, eyeY, distance);

            if (!overlay) draw(floor);
            draw(mesh);
        }

        /** Puts what is drawn next where the given matrix says. */
        private void place(float[] matrix) {
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, matrix, 0);
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(modelHandle, 1, false, matrix, 0);
        }

        /** Uploads the mesh the first time it is seen, then draws it out of the card's memory. */
        private void draw(RobotMesh drawable) {
            if (drawable.vbo == 0) {
                int[] ids = new int[1];
                GLES20.glGenBuffers(1, ids, 0);
                drawable.vbo = ids[0];
                FloatBuffer data = drawable.vertices();
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, drawable.vbo);
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                        drawable.vertexCount() * RobotMesh.strideBytes(), data,
                        GLES20.GL_STATIC_DRAW);
            } else {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, drawable.vbo);
            }

            int stride = RobotMesh.strideBytes();
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, stride, 0);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, stride, 12);
            GLES20.glEnableVertexAttribArray(normalHandle);
            GLES20.glVertexAttribPointer(colourHandle, 3, GLES20.GL_FLOAT, false, stride, 24);
            GLES20.glEnableVertexAttribArray(colourHandle);
            GLES20.glVertexAttribPointer(materialHandle, 1, GLES20.GL_FLOAT, false, stride, 36);
            GLES20.glEnableVertexAttribArray(materialHandle);

            if (drawable.partCount() == 0) {
                // the floor, and any model loaded from the assets: one piece, no skeleton
                place(model);
                GLES20.glDrawArrays(drawable.drawMode(), 0, drawable.vertexCount());
            } else {
                for (int i = 0; i < drawable.partCount(); i++) {
                    if (overlay && !inOverlay(drawable.partJoint(i))) continue;
                    // each part turns about its own joint, which hangs off the one before it
                    Matrix.multiplyMM(partModel, 0, model, 0, joints, drawable.partJoint(i) * 16);
                    place(partModel);
                    GLES20.glDrawArrays(drawable.drawMode(), drawable.partFirst(i),
                            drawable.partVertices(i));
                }
            }

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(normalHandle);
            GLES20.glDisableVertexAttribArray(colourHandle);
            GLES20.glDisableVertexAttribArray(materialHandle);
        }

        /** Head, torso and arms: what an over the shoulder view of the robot shows. */
        private boolean inOverlay(int joint) {
            return joint == RobotMesh.J_HEAD || joint == RobotMesh.J_SPINE
                    || joint == RobotMesh.J_SHOULDER_N || joint == RobotMesh.J_SHOULDER_P
                    || joint == RobotMesh.J_ELBOW_N || joint == RobotMesh.J_ELBOW_P;
        }

        /** Momentum after a drag, then the slow idle turn once the finger has been gone a while. */
        private void advance(float seconds) {
            if (overlay) return; // the overlay is framed on the robot and stays there
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
