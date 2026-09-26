package com.falcon.robot.widget;

import android.opengl.Matrix;

import java.util.Arrays;

/**
 * What the robot is doing, as joint angles over time: the model acts out the command that was
 * sent, so pressing Forward walks it and Wave makes it wave.
 *
 * <p>An action is a table of terms, each driving one axis of one joint as
 * {@code offset + amplitude * sin(2 pi (rate * time + phase))}. A walk cycle or a wave is then one
 * readable block rather than trigonometry scattered through the renderer, and joints an action
 * leaves out keep their standing motion, so a waving robot still breathes and looks around.
 *
 * <p>Angles ease towards the action's rather than snapping to them, which is what makes one action
 * run into the next instead of the figure jumping between poses.
 */
final class RobotPose {

    // ---- actions ---------------------------------------------------------------------------

    static final int NONE = -1;
    static final int IDLE = 0, WALK = 1, WALK_BACK = 2, STEP = 3, STEP_BACK = 4, WALK_AWHILE = 5,
            TURN_LEFT = 6, TURN_RIGHT = 7, WAVE = 8, RAISE_LEFT = 9, RAISE_RIGHT = 10,
            NOD = 11, TWIST = 12, SIT = 13, T_POSE = 14, POWER_DOWN = 15;

    /** Axes a term drives: three rotations in degrees, then three shifts in model units. */
    private static final int RX = 0, RY = 1, RZ = 2, TX = 3, TY = 4, TZ = 5;
    private static final int AXES = 6;

    /** Term shape: a whole sine, or only its positive half, since a knee bends one way. */
    private static final float SINE = 0f;
    private static final float HALF = 1f;

    // the robot faces +z, so its own left limbs are the +x ones
    private static final int ROOT = RobotMesh.J_ROOT;
    private static final int SPINE = RobotMesh.J_SPINE;
    private static final int HEAD = RobotMesh.J_HEAD;
    private static final int SHOULDER_L = RobotMesh.J_SHOULDER_P;
    private static final int SHOULDER_R = RobotMesh.J_SHOULDER_N;
    private static final int ELBOW_L = RobotMesh.J_ELBOW_P;
    private static final int ELBOW_R = RobotMesh.J_ELBOW_N;
    private static final int HIP_L = RobotMesh.J_HIP_P;
    private static final int HIP_R = RobotMesh.J_HIP_N;
    private static final int KNEE_L = RobotMesh.J_KNEE_P;
    private static final int KNEE_R = RobotMesh.J_KNEE_N;
    private static final int ANKLE_L = RobotMesh.J_ANKLE_P;
    private static final int ANKLE_R = RobotMesh.J_ANKLE_N;

    /** Standing: breathing, a slow look around, and arms that are never quite still. */
    private static final float[][] IDLE_TERMS = {
            {ROOT, TY, 0f, 0.010f, 0.30f, 0f, SINE},
            {ROOT, RZ, 0f, 0.9f, 0.18f, 0.25f, SINE},
            {SPINE, RX, 1.2f, 1.0f, 0.30f, 0.5f, SINE},
            {HEAD, RY, 0f, 7f, 0.11f, 0f, SINE},
            {HEAD, RX, 1.5f, 1.2f, 0.30f, 0.1f, SINE},
            {SHOULDER_L, RZ, 4f, 1.4f, 0.30f, 0f, SINE},
            {SHOULDER_R, RZ, -4f, 1.4f, 0.30f, 0.5f, SINE},
            {ELBOW_L, RX, -9f, 2f, 0.30f, 0f, SINE},
            {ELBOW_R, RX, -9f, 2f, 0.30f, 0.5f, SINE},
    };

    /**
     * One stride a second: the legs swing, the knee bends only while its foot is off the floor,
     * the arms swing against the legs, and the body rises twice a cycle.
     */
    private static final float[][] WALK_TERMS = {
            {ROOT, TY, -0.015f, 0.016f, 2f, 0.75f, SINE},
            {ROOT, RZ, 0f, 2.2f, 1f, 0f, SINE},
            {ROOT, RY, 0f, 4f, 1f, 0.25f, SINE},
            {SPINE, RX, -3.5f, 0f, 0f, 0f, SINE},
            {HEAD, RY, 0f, 0f, 0f, 0f, SINE},
            {HIP_R, RX, 0f, 24f, 1f, 0f, SINE},
            {HIP_L, RX, 0f, 24f, 1f, 0.5f, SINE},
            {KNEE_R, RX, 4f, 34f, 1f, 0.85f, HALF},
            {KNEE_L, RX, 4f, 34f, 1f, 0.35f, HALF},
            {ANKLE_R, RX, -4f, 7f, 1f, 0.5f, SINE},
            {ANKLE_L, RX, -4f, 7f, 1f, 0f, SINE},
            {SHOULDER_R, RX, 0f, 13f, 1f, 0.5f, SINE},
            {SHOULDER_L, RX, 0f, 13f, 1f, 0f, SINE},
            {SHOULDER_R, RZ, -5f, 0f, 0f, 0f, SINE},
            {SHOULDER_L, RZ, 5f, 0f, 0f, 0f, SINE},
            {ELBOW_R, RX, -20f, 8f, 1f, 0.5f, SINE},
            {ELBOW_L, RX, -20f, 8f, 1f, 0f, SINE},
    };

    /** Stepping on the spot, for turning: the same cycle, shorter and quicker. */
    private static final float[][] MARCH_TERMS = {
            {ROOT, TY, -0.010f, 0.010f, 2.4f, 0.75f, SINE},
            {HIP_R, RX, 0f, 12f, 1.2f, 0f, SINE},
            {HIP_L, RX, 0f, 12f, 1.2f, 0.5f, SINE},
            {KNEE_R, RX, 3f, 22f, 1.2f, 0.85f, HALF},
            {KNEE_L, RX, 3f, 22f, 1.2f, 0.35f, HALF},
            {SHOULDER_R, RX, 0f, 7f, 1.2f, 0.5f, SINE},
            {SHOULDER_L, RX, 0f, 7f, 1.2f, 0f, SINE},
            {ELBOW_R, RX, -16f, 0f, 0f, 0f, SINE},
            {ELBOW_L, RX, -16f, 0f, 0f, 0f, SINE},
    };

    /** Greeting: the right arm goes up and the forearm swings, and the head turns to look. */
    private static final float[][] WAVE_TERMS = {
            // the elbow angle undoes the shoulder's, which is what stands the forearm upright;
            // the swing is then about that, so the hand rocks over the shoulder
            {SHOULDER_R, RZ, -132f, 0f, 0f, 0f, SINE},
            {SHOULDER_R, RX, -14f, 0f, 0f, 0f, SINE},
            {ELBOW_R, RZ, -48f, 22f, 2.1f, 0f, SINE},
            {ELBOW_R, RX, -10f, 0f, 0f, 0f, SINE},
            {SHOULDER_L, RZ, 6f, 0f, 0f, 0f, SINE},
            {SPINE, RZ, 3f, 0f, 0f, 0f, SINE},
            {HEAD, RY, -10f, 0f, 0f, 0f, SINE},
            {HEAD, RZ, -5f, 0f, 0f, 0f, SINE},
    };

    /**
     * Raising an arm and holding it there. It goes out to the side rather than straight forward,
     * which is the same gesture but one you can read from the front instead of end on.
     */
    private static final float[][] RAISE_L_TERMS = {
            {SHOULDER_L, RZ, 102f, 0f, 0f, 0f, SINE},
            {SHOULDER_L, RX, -18f, 0f, 0f, 0f, SINE},
            {ELBOW_L, RX, -8f, 0f, 0f, 0f, SINE},
            {ELBOW_L, RZ, -6f, 0f, 0f, 0f, SINE},
            {HEAD, RY, 12f, 0f, 0f, 0f, SINE},
    };

    private static final float[][] RAISE_R_TERMS = {
            {SHOULDER_R, RZ, -102f, 0f, 0f, 0f, SINE},
            {SHOULDER_R, RX, -18f, 0f, 0f, 0f, SINE},
            {ELBOW_R, RX, -8f, 0f, 0f, 0f, SINE},
            {ELBOW_R, RZ, 6f, 0f, 0f, 0f, SINE},
            {HEAD, RY, -12f, 0f, 0f, 0f, SINE},
    };

    private static final float[][] NOD_TERMS = {
            {HEAD, RX, -6f, 12f, 1.4f, 0f, SINE},
            {HEAD, RY, 0f, 0f, 0f, 0f, SINE},
    };

    private static final float[][] TWIST_TERMS = {
            {SPINE, RY, 0f, 26f, 0.55f, 0f, SINE},
            {HEAD, RY, 0f, 10f, 0.55f, 0.08f, SINE},
    };

    private static final float[][] SIT_TERMS = {
            {ROOT, TY, -0.30f, 0f, 0f, 0f, SINE},
            {ROOT, RZ, 0f, 0f, 0f, 0f, SINE},
            {SPINE, RX, 5f, 0f, 0f, 0f, SINE},
            {HIP_R, RX, -76f, 0f, 0f, 0f, SINE},
            {HIP_L, RX, -76f, 0f, 0f, 0f, SINE},
            {KNEE_R, RX, 80f, 0f, 0f, 0f, SINE},
            {KNEE_L, RX, 80f, 0f, 0f, 0f, SINE},
            {ANKLE_R, RX, -8f, 0f, 0f, 0f, SINE},
            {ANKLE_L, RX, -8f, 0f, 0f, 0f, SINE},
            {SHOULDER_R, RX, -14f, 0f, 0f, 0f, SINE},
            {SHOULDER_L, RX, -14f, 0f, 0f, 0f, SINE},
            {ELBOW_R, RX, -26f, 0f, 0f, 0f, SINE},
            {ELBOW_L, RX, -26f, 0f, 0f, 0f, SINE},
    };

    private static final float[][] T_POSE_TERMS = {
            {SHOULDER_L, RZ, 92f, 0f, 0f, 0f, SINE},
            {SHOULDER_R, RZ, -92f, 0f, 0f, 0f, SINE},
            {SHOULDER_L, RX, 0f, 0f, 0f, 0f, SINE},
            {SHOULDER_R, RX, 0f, 0f, 0f, 0f, SINE},
            {ELBOW_L, RX, 0f, 0f, 0f, 0f, SINE},
            {ELBOW_R, RX, 0f, 0f, 0f, 0f, SINE},
            {HEAD, RY, 0f, 0f, 0f, 0f, SINE},
    };

    /** Shutting down: the head drops, the body folds a little and the knees give. */
    private static final float[][] POWER_DOWN_TERMS = {
            {ROOT, TY, -0.09f, 0f, 0f, 0f, SINE},
            {SPINE, RX, -13f, 0f, 0f, 0f, SINE},
            {HEAD, RX, -24f, 0f, 0f, 0f, SINE},
            {HEAD, RY, 0f, 0f, 0f, 0f, SINE},
            {SHOULDER_R, RX, 7f, 0f, 0f, 0f, SINE},
            {SHOULDER_L, RX, 7f, 0f, 0f, 0f, SINE},
            {SHOULDER_R, RZ, -2f, 0f, 0f, 0f, SINE},
            {SHOULDER_L, RZ, 2f, 0f, 0f, 0f, SINE},
            {ELBOW_R, RX, -14f, 0f, 0f, 0f, SINE},
            {ELBOW_L, RX, -14f, 0f, 0f, 0f, SINE},
            {HIP_R, RX, -9f, 0f, 0f, 0f, SINE},
            {HIP_L, RX, -9f, 0f, 0f, 0f, SINE},
            {KNEE_R, RX, 15f, 0f, 0f, 0f, SINE},
            {KNEE_L, RX, 15f, 0f, 0f, 0f, SINE},
    };

    private static final float[][][] TERMS = {
            IDLE_TERMS, WALK_TERMS, WALK_TERMS, WALK_TERMS, WALK_TERMS, WALK_TERMS,
            MARCH_TERMS, MARCH_TERMS, WAVE_TERMS, RAISE_L_TERMS, RAISE_R_TERMS,
            NOD_TERMS, TWIST_TERMS, SIT_TERMS, T_POSE_TERMS, POWER_DOWN_TERMS,
    };

    /** Seconds before the action gives way to standing again, or 0 to hold it. */
    private static final float[] DURATION = {
            0f, 0f, 0f, 1.5f, 1.5f, 3.2f, 1.1f, 1.1f, 3.2f, 2.6f, 2.6f, 1.8f, 3.4f, 0f, 0f, 0f,
    };

    /** -1 runs the cycle backwards, which is how walking backwards is walking. */
    private static final float[] TIME_SCALE = {
            1f, 1f, -1f, 1f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f,
    };

    /** Degrees a second the robot turns itself on the spot while the action runs. */
    private static final float[] TURN_RATE = {
            0f, 0f, 0f, 0f, 0f, 0f, 62f, -62f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
    };

    /** How quickly the figure catches up with a new action: higher is snappier. */
    private static final float BLEND_RATE = 9f;

    // ---- state -----------------------------------------------------------------------------

    private final float[] angles = new float[RobotMesh.JOINTS * AXES];
    private final float[] target = new float[RobotMesh.JOINTS * AXES];
    private final boolean[] cleared = new boolean[RobotMesh.JOINTS * AXES];
    private final float[] local = new float[16];

    private int action = IDLE;
    /** The action's own clock, which runs backwards for the reversed cycles. */
    private float time;
    /** How long it has been running, which does not. */
    private float elapsed;
    /** How far the robot has turned itself, kept across actions so a turn stays turned. */
    private float facing;

    /** Starts an action, or restarts it when it is one of the ones that plays once. */
    void play(int next) {
        if (next == NONE) return;
        if (next == action) {
            if (DURATION[action] > 0f) {
                time = 0f;
                elapsed = 0f;
            }
            return;
        }
        action = next;
        time = 0f;
        elapsed = 0f;
    }

    void advance(float seconds) {
        time += seconds * TIME_SCALE[action];
        elapsed += seconds;
        facing += TURN_RATE[action] * seconds;
        if (DURATION[action] > 0f && elapsed >= DURATION[action]) play(IDLE);

        evaluate(IDLE_TERMS, true);
        if (action != IDLE) evaluate(TERMS[action], false);

        float blend = 1f - (float) Math.exp(-BLEND_RATE * seconds);
        for (int i = 0; i < angles.length; i++) {
            angles[i] += (target[i] - angles[i]) * blend;
        }
    }

    /**
     * Adds a table's terms into the target pose. The standing table lays down the base; an action
     * on top of it first clears the axes it has something to say about, so that it replaces the
     * standing motion there and leaves the rest of the body alone.
     */
    private void evaluate(float[][] terms, boolean base) {
        if (base) {
            Arrays.fill(target, 0f);
        } else {
            Arrays.fill(cleared, false);
            for (float[] term : terms) {
                int slot = (int) term[0] * AXES + (int) term[1];
                if (!cleared[slot]) {
                    target[slot] = 0f;
                    cleared[slot] = true;
                }
            }
        }
        for (float[] term : terms) {
            int slot = (int) term[0] * AXES + (int) term[1];
            float wave = (float) Math.sin(2 * Math.PI * (term[4] * time + term[5]));
            if (term[6] > 0.5f) wave = Math.max(0f, wave);
            target[slot] += term[2] + term[3] * wave;
        }
    }

    /** Fills {@code out} with one 16-float matrix per joint, each already chained to its parent. */
    void matrices(float[] out) {
        for (int j = 0; j < RobotMesh.JOINTS; j++) {
            float[] pivot = RobotMesh.JOINT_PIVOT[j];
            int base = j * AXES;
            float turn = angles[base + RY] + (j == ROOT ? facing : 0f);

            Matrix.setIdentityM(local, 0);
            Matrix.translateM(local, 0, pivot[0] + angles[base + TX],
                    pivot[1] + angles[base + TY], pivot[2] + angles[base + TZ]);
            Matrix.rotateM(local, 0, angles[base + RZ], 0f, 0f, 1f);
            Matrix.rotateM(local, 0, angles[base + RX], 1f, 0f, 0f);
            Matrix.rotateM(local, 0, turn, 0f, 1f, 0f);
            Matrix.translateM(local, 0, -pivot[0], -pivot[1], -pivot[2]);

            int parent = RobotMesh.JOINT_PARENT[j];
            if (parent < 0) {
                System.arraycopy(local, 0, out, j * 16, 16);
            } else {
                Matrix.multiplyMM(out, j * 16, out, parent * 16, local, 0);
            }
        }
    }

    // ---- commands --------------------------------------------------------------------------

    /**
     * The action a robot command calls for, or {@link #NONE} for the ones that are not movement
     * (camera, speed, odometry) and should leave the figure as it is.
     */
    static int actionFor(String command) {
        if (command == null) return NONE;
        if (command.equals("STOP") || command.equals("MOVE STOP")) return IDLE;
        if (command.equals("MOVE FORWARD")) return STEP;
        if (command.equals("MOVE BACKWARD")) return STEP_BACK;
        if (command.equals("TURN LEFT")) return TURN_LEFT;
        if (command.equals("TURN RIGHT")) return TURN_RIGHT;
        if (command.startsWith("MOVE ")) return joystick(command.substring(5));

        if (command.equals("POSE STAND")) return IDLE;
        if (command.equals("POSE SIT")) return SIT;
        if (command.equals("POSE WAVE")) return WAVE;
        if (command.equals("POSE T_POSE")) return T_POSE;

        if (command.equals("ARM SELECT LEFT_ARM")) return RAISE_LEFT;
        if (command.equals("ARM SELECT RIGHT_ARM")) return RAISE_RIGHT;
        if (command.equals("ARM SELECT HEAD")) return NOD;
        if (command.equals("ARM SELECT WAIST")) return TWIST;

        if (command.equals("GO_HOME")) return WALK_AWHILE;
        if (command.equals("PATROL START") || command.equals("FOLLOW START")) return WALK;
        if (command.equals("PATROL STOP") || command.equals("FOLLOW STOP")) return IDLE;
        if (command.equals("SHUTDOWN")) return POWER_DOWN;

        // the four custom buttons are demonstrations, so give each one something to show
        if (command.startsWith("CUSTOM_ACTION ")) {
            switch (command.substring("CUSTOM_ACTION ".length()).trim()) {
                case "1": return WAVE;
                case "2": return NOD;
                case "3": return RAISE_RIGHT;
                case "4": return TWIST;
                default: return NONE;
            }
        }
        return NONE;
    }

    /** "MOVE x y" from the stick: forward wins over turning, and centred means stand still. */
    private static int joystick(String arguments) {
        String[] fields = arguments.trim().split("\\s+");
        if (fields.length != 2) return NONE;
        try {
            float x = Float.parseFloat(fields[0]);
            float y = Float.parseFloat(fields[1]);
            if (Math.abs(y) >= 0.25f) return y > 0 ? WALK : WALK_BACK;
            if (Math.abs(x) >= 0.25f) return x > 0 ? TURN_RIGHT : TURN_LEFT;
            return IDLE;
        } catch (NumberFormatException notANumber) {
            return NONE;
        }
    }
}
