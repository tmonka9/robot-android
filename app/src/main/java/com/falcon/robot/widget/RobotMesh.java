package com.falcon.robot.widget;

import android.content.Context;
import android.opengl.GLES20;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The geometry {@link Robot3DView} draws: the Xiao Ao robot and the floor it stands on, built in
 * code, or a model file if one is supplied.
 *
 * <p>The figure follows the design artwork ({@code design/.../Components/robot.png}): white armour
 * over a black underlayer, a visored helmet, and blue lights at the ears, chest, hips and knees.
 * Shapes are lofted through superellipse rings rather than stacked from boxes, so the armour has
 * the rounded, panelled silhouette of the artwork instead of a blocky one.
 *
 * <p>Drop an OBJ export of the real robot into {@code app/src/main/assets} (positions, normals and
 * triangular or quad faces; materials are ignored) and pass its name to {@link #fromAsset}; without
 * it the built-in figure is used, so the page works with no assets at all.
 *
 * <p>Vertices are position, normal, colour and material interleaved, ten floats each.
 */
final class RobotMesh {

    static final int FLOATS_PER_VERTEX = 10;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** The last vertex float, telling the shader how to light it. */
    private static final float MATTE = 0f;
    private static final float GLOSS = 1f;    // clear-coated armour: takes a highlight
    private static final float EMISSIVE = 2f; // lights and floor: drawn at full brightness

    // palette read off the artwork
    private static final float[] SHELL = {0.90f, 0.93f, 0.96f};    // white armour
    private static final float[] UNDER = {0.055f, 0.070f, 0.105f}; // black underlayer
    private static final float[] TRIM = {0.14f, 0.18f, 0.26f};     // joint collars and seams
    private static final float[] GLOW = {0.16f, 0.64f, 1.00f};     // the blue lights
    private static final float[] GRID = {0.10f, 0.36f, 0.55f};     // floor squares
    private static final float[] RING = {0.16f, 0.62f, 0.95f};     // floor circles

    // ---- the skeleton --------------------------------------------------------------------------

    /**
     * The joints the figure is built in. Every part of the mesh belongs to one and turns about its
     * pivot, which is what lets {@link RobotPose} walk it, wave it or sit it down. {@code _N} is
     * the limb on the -x side and {@code _P} the one on +x; the robot faces +z, so its own left arm
     * is the {@code _P} one. Parents come before their children, which the matrix chain relies on.
     */
    static final int J_ROOT = 0, J_SPINE = 1, J_HEAD = 2,
            J_SHOULDER_N = 3, J_SHOULDER_P = 4, J_ELBOW_N = 5, J_ELBOW_P = 6,
            J_HIP_N = 7, J_HIP_P = 8, J_KNEE_N = 9, J_KNEE_P = 10,
            J_ANKLE_N = 11, J_ANKLE_P = 12;
    static final int JOINTS = 13;

    /** The joint each one hangs off, or -1 for the root. */
    static final int[] JOINT_PARENT = {
            -1, J_ROOT, J_SPINE,
            J_SPINE, J_SPINE, J_SHOULDER_N, J_SHOULDER_P,
            J_ROOT, J_ROOT, J_HIP_N, J_HIP_P,
            J_KNEE_N, J_KNEE_P,
    };

    /** Where each joint turns, in the rest pose. */
    static final float[][] JOINT_PIVOT = {
            {0f, -0.020f, 0f},                                      // root, at the hips
            {0f, 0.060f, 0f},                                       // spine, at the waist
            {0f, 0.660f, 0f},                                       // neck
            {-0.300f, 0.440f, 0f}, {0.300f, 0.440f, 0f},            // shoulders
            {-0.314f, 0.100f, 0f}, {0.314f, 0.100f, 0f},            // elbows
            {-0.188f, -0.115f, 0f}, {0.188f, -0.115f, 0f},          // hips
            {-0.155f, -0.545f, 0.006f}, {0.155f, -0.545f, 0.006f},  // knees
            {-0.155f, -0.928f, 0.006f}, {0.155f, -0.928f, 0.006f},  // ankles
    };

    /** 0 for the -x limb and 1 for the +x one, so a joint is {@code J_HIP_N + sideIndex(side)}. */
    static int sideIndex(int side) {
        return side < 0 ? 0 : 1;
    }

    /** How far the floor reaches before it has faded into the panel. */
    private static final float FLOOR_RADIUS = 3.15f;

    /** Ring resolution for the lofted shells, and for the ellipsoids. */
    private static final int SLICES = 22;
    private static final int STACKS = 10;
    private static final int PATCH_SLICES = 18;

    private float[] data = new float[8192];
    private int size;
    private FloatBuffer buffer;
    private int vertexCount;
    private int drawMode = GLES20.GL_TRIANGLES;

    /** One {joint, first vertex, vertex count} per run of the mesh that moves together. */
    private final List<int[]> parts = new ArrayList<>();
    private int partJoint = J_ROOT;
    private int partStart;

    /** The vertex buffer object holding this mesh, or 0 before it has been uploaded. */
    int vbo;

    static int strideBytes() {
        return STRIDE_BYTES;
    }

    int drawMode() {
        return drawMode;
    }

    /** Everything built from here on belongs to this joint. */
    private void part(int joint) {
        closePart();
        partJoint = joint;
    }

    private void closePart() {
        if (vertexCount > partStart) {
            parts.add(new int[] {partJoint, partStart, vertexCount - partStart});
        }
        partStart = vertexCount;
    }

    /** Zero for a mesh with no skeleton, such as a model loaded from the assets. */
    int partCount() {
        return parts.size();
    }

    int partJoint(int index) {
        return parts.get(index)[0];
    }

    int partFirst(int index) {
        return parts.get(index)[1];
    }

    int partVertices(int index) {
        return parts.get(index)[2];
    }

    // ---- the figure ---------------------------------------------------------------------------

    /** The built-in humanoid: about two units tall, standing on y = -1.05. */
    static RobotMesh humanoid() {
        RobotMesh mesh = new RobotMesh();
        mesh.head();
        mesh.torso();
        for (int side = -1; side <= 1; side += 2) {
            mesh.arm(side);
            mesh.leg(side);
        }
        mesh.closePart();
        return mesh;
    }

    private void head() {
        part(J_HEAD);
        float y = 0.848f;
        ellipsoid(0f, y, 0f, 0.178f, 0.188f, 0.188f, SHELL, GLOSS);
        // the visor wraps the front of the helmet, just proud of the shell
        patch(0f, y, 0f, 0.182f, 0.192f, 0.192f, -32f, 34f, 40f, 140f, UNDER, GLOSS);
        patch(0f, y, 0f, 0.186f, 0.196f, 0.196f, 2f, 13f, 56f, 124f, GLOW, EMISSIVE);
        // dark crown and jaw, so the white shell reads as a helmet
        patch(0f, y, 0f, 0.180f, 0.190f, 0.190f, 52f, 90f, 0f, 360f, TRIM, GLOSS);
        patch(0f, y, 0f, 0.180f, 0.190f, 0.190f, -90f, -52f, 0f, 360f, UNDER, GLOSS);
        for (int side = -1; side <= 1; side += 2) {
            // a boss on the side of the helmet; the blue lens is a cap on the boss rather than a
            // flat disc, so it stays on the surface however the head is turned
            ellipsoid(0.165f * side, y + 0.013f, 0.005f, 0.050f, 0.070f, 0.070f, TRIM, GLOSS);
            patch(0.165f * side, y + 0.013f, 0.005f, 0.0515f, 0.0721f, 0.0721f,
                    -42f, 42f, 48f - 90f * side, 132f - 90f * side, GLOW, EMISSIVE);
        }
        part(J_SPINE); // the neck belongs to the body, so the head turns on top of it
        loft(0f, 0f, 0.9f, UNDER, GLOSS, new float[][] {
                {0.56f, 0.080f, 0.075f, 0f, 0f},
                {0.66f, 0.085f, 0.080f, 0f, 0f},
                {0.72f, 0.078f, 0.075f, 0f, 0f},
        });
    }

    private void torso() {
        part(J_SPINE);
        // chest: widest at the shoulders, tapering into the waist
        loft(0f, 0f, 0.58f, SHELL, GLOSS, new float[][] {
                {0.24f, 0.215f, 0.145f, 0f, 0f},
                {0.34f, 0.270f, 0.168f, 0f, 0f},
                {0.46f, 0.276f, 0.174f, 0f, 0f},
                {0.54f, 0.235f, 0.157f, 0f, 0f},
                {0.60f, 0.150f, 0.114f, 0f, 0f},
        });
        // the dark midsection showing between the plates
        loft(0f, 0f, 0.72f, UNDER, GLOSS, new float[][] {
                {0.06f, 0.163f, 0.118f, 0f, 0f},
                {0.16f, 0.176f, 0.126f, 0f, 0f},
                {0.26f, 0.196f, 0.137f, 0f, 0f},
        });
        part(J_ROOT);
        // pelvis
        loft(0f, 0f, 0.60f, SHELL, GLOSS, new float[][] {
                {-0.15f, 0.144f, 0.118f, 0f, 0f},
                {-0.09f, 0.196f, 0.138f, 0f, 0f},
                {0.00f, 0.212f, 0.147f, 0f, 0f},
                {0.08f, 0.190f, 0.132f, 0f, 0f},
        });

        part(J_SPINE);
        box(0f, 0.47f, 0.170f, 0.080f, 0.052f, 0.014f, GLOW, EMISSIVE);   // sternum light
        box(0f, 0.395f, 0.166f, 0.150f, 0.012f, 0.010f, TRIM, GLOSS);     // plate seams
        box(0f, 0.300f, 0.155f, 0.120f, 0.012f, 0.010f, TRIM, GLOSS);
        box(0f, 0.105f, 0.122f, 0.100f, 0.014f, 0.010f, GLOW, EMISSIVE);  // belt light
        box(0f, 0.55f, -0.140f, 0.240f, 0.090f, 0.020f, UNDER, GLOSS);    // collar, from behind
        for (int side = -1; side <= 1; side += 2) {
            // the dark panels the white chest plates sit on
            box(0.135f * side, 0.495f, 0.160f, 0.075f, 0.105f, 0.014f, UNDER, GLOSS);
        }
    }

    private void arm(int side) {
        float x = 0.300f * side;
        part(J_SHOULDER_N + sideIndex(side));
        ellipsoid(0.305f * side, 0.475f, 0f, 0.140f, 0.132f, 0.142f, SHELL, GLOSS); // pauldron
        patch(0.305f * side, 0.475f, 0f, 0.143f, 0.135f, 0.145f, -90f, -38f, 0f, 360f, UNDER, GLOSS);
        ellipsoid(0.292f * side, 0.395f, 0f, 0.088f, 0.086f, 0.088f, UNDER, GLOSS); // shoulder joint
        loft(x, 0f, 0.75f, SHELL, GLOSS, new float[][] {
                {0.14f, 0.070f, 0.072f, 0.012f * side, 0f},
                {0.26f, 0.082f, 0.082f, 0.006f * side, 0f},
                {0.38f, 0.088f, 0.088f, 0f, 0f},
        });
        box(x + 0.012f * side, 0.270f, -0.078f, 0.090f, 0.150f, 0.016f, UNDER, GLOSS);  // upper arm panel

        part(J_ELBOW_N + sideIndex(side));
        ellipsoid(x + 0.014f * side, 0.100f, 0f, 0.074f, 0.074f, 0.074f, UNDER, GLOSS); // elbow
        loft(x, 0f, 0.75f, SHELL, GLOSS, new float[][] {
                {-0.19f, 0.052f, 0.058f, 0.022f * side, 0f},
                {-0.05f, 0.066f, 0.070f, 0.018f * side, 0f},
                {0.07f, 0.074f, 0.076f, 0.014f * side, 0f},
        });
        box(x + 0.018f * side, -0.030f, 0.072f, 0.024f, 0.100f, 0.012f, GLOW, EMISSIVE); // forearm strip
        ellipsoid(x + 0.023f * side, -0.215f, 0f, 0.050f, 0.046f, 0.052f, UNDER, GLOSS); // wrist

        float hand = x + 0.024f * side;
        loft(hand, 0f, 0.45f, UNDER, GLOSS, new float[][] {
                {-0.330f, 0.046f, 0.026f, 0f, 0f},
                {-0.240f, 0.054f, 0.032f, 0f, 0f},
        });
        for (int finger = -1; finger <= 1; finger++) {
            box(hand + finger * 0.028f, -0.372f, 0f, 0.020f, 0.080f, 0.026f, UNDER, GLOSS);
        }
        box(hand - 0.048f * side, -0.325f, 0.018f, 0.020f, 0.054f, 0.022f, UNDER, GLOSS); // thumb
    }

    private void leg(int side) {
        float x = 0.155f * side;
        part(J_ROOT);
        ellipsoid(0.188f * side, -0.115f, 0f, 0.108f, 0.100f, 0.108f, UNDER, GLOSS); // hip joint
        patch(0.188f * side, -0.115f, 0f, 0.111f, 0.103f, 0.111f,
                -32f, 32f, 58f - 90f * side, 122f - 90f * side, GLOW, EMISSIVE);     // hip light

        part(J_HIP_N + sideIndex(side));
        box(0.070f * side, -0.310f, 0f, 0.070f, 0.380f, 0.180f, UNDER, GLOSS);       // inner thigh
        loft(x, 0f, 0.65f, SHELL, GLOSS, new float[][] {
                {-0.500f, 0.094f, 0.100f, 0f, 0.002f},
                {-0.360f, 0.110f, 0.118f, 0.006f * side, 0.006f},
                {-0.220f, 0.124f, 0.130f, 0.018f * side, 0.006f},
                {-0.070f, 0.130f, 0.134f, 0.030f * side, 0.002f},
        });
        part(J_KNEE_N + sideIndex(side));
        ellipsoid(x, -0.545f, 0.006f, 0.098f, 0.092f, 0.100f, UNDER, GLOSS); // knee
        patch(x, -0.545f, 0.006f, 0.101f, 0.095f, 0.103f, -26f, 26f, 64f, 116f, GLOW, EMISSIVE);

        loft(x, 0f, 0.65f, SHELL, GLOSS, new float[][] {
                {-0.900f, 0.076f, 0.082f, 0f, 0.006f},
                {-0.760f, 0.086f, 0.096f, 0f, 0.010f},
                {-0.610f, 0.096f, 0.106f, 0f, 0.004f},
        });
        box(x, -0.750f, -0.082f, 0.100f, 0.230f, 0.022f, UNDER, GLOSS); // calf plate

        part(J_ANKLE_N + sideIndex(side));
        ellipsoid(x, -0.928f, 0.006f, 0.064f, 0.056f, 0.068f, UNDER, GLOSS); // ankle

        loft(x, 0f, 0.45f, SHELL, GLOSS, new float[][] {
                {-1.032f, 0.092f, 0.172f, 0f, 0.056f},
                {-0.986f, 0.099f, 0.164f, 0f, 0.046f},
                {-0.936f, 0.086f, 0.106f, 0f, 0.012f},
        });
        box(x, -1.042f, 0.056f, 0.190f, 0.016f, 0.342f, UNDER, MATTE); // sole
        light(x + 0.104f * side, -0.985f, 0.040f, side, 0f, 0f, 0.028f, GLOW, EMISSIVE);
    }

    // ---- the floor ----------------------------------------------------------------------------

    /**
     * The lit platform under the robot: a square grid with concentric rings, the way the artwork
     * has it. Drawn as lines, and faded towards the edge so it dissolves into the panel instead of
     * stopping at a hard border.
     */
    static RobotMesh floor() {
        RobotMesh mesh = new RobotMesh();
        mesh.drawMode = GLES20.GL_LINES;
        final float y = -1.05f;
        final float step = 0.4f;
        final int cells = 8;
        for (int i = -cells; i <= cells; i++) {
            float a = i * step;
            for (int j = -cells; j < cells; j++) {
                // a segment per cell, so the fade follows the distance along the line
                float from = j * step;
                float to = from + step;
                mesh.segment(a, y, from, a, y, to, GRID);
                mesh.segment(from, y, a, to, y, a, GRID);
            }
        }
        for (float radius : new float[] {0.62f, 1.25f, 1.95f, 2.70f}) {
            mesh.ring(y + 0.003f, radius, RING);
        }
        return mesh;
    }

    private void ring(float y, float radius, float[] colour) {
        final int steps = 72;
        for (int i = 0; i < steps; i++) {
            double a = 2 * Math.PI * i / steps;
            double b = 2 * Math.PI * (i + 1) / steps;
            segment((float) (radius * Math.cos(a)), y, (float) (radius * Math.sin(a)),
                    (float) (radius * Math.cos(b)), y, (float) (radius * Math.sin(b)), colour);
        }
    }

    private void segment(float x1, float y1, float z1, float x2, float y2, float z2, float[] colour) {
        // the platform is round, the way the artwork has it, so the square grid stops at the rim
        float mx = (x1 + x2) / 2f;
        float mz = (z1 + z2) / 2f;
        if (Math.sqrt(mx * mx + mz * mz) > FLOOR_RADIUS) return;
        floorVertex(x1, y1, z1, colour);
        floorVertex(x2, y2, z2, colour);
    }

    private void floorVertex(float x, float y, float z, float[] colour) {
        double distance = Math.sqrt(x * x + z * z);
        float fade = Math.max(0f, 1f - (float) Math.pow(distance / (FLOOR_RADIUS + 0.1f), 1.6));
        vertex(x, y, z, 0f, 1f, 0f,
                colour[0] * fade, colour[1] * fade, colour[2] * fade, EMISSIVE);
    }

    // ---- a model from the assets ----------------------------------------------------------------

    /**
     * Reads an OBJ from the assets, scaled and centred to the same size as the built-in figure.
     * Returns null when the file is missing or has no usable faces, so the caller can fall back.
     */
    static RobotMesh fromAsset(Context context, String assetName) {
        List<float[]> positions = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>(); // vertex index, normal index, per corner
        try (InputStream in = context.getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("v ")) positions.add(parseFloats(line));
                else if (line.startsWith("vn ")) normals.add(parseFloats(line));
                else if (line.startsWith("f ")) addFace(line, faces);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (positions.isEmpty() || faces.isEmpty()) return null;

        // fit the model into the same box as the built-in one
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] p : positions) {
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], p[i]);
                max[i] = Math.max(max[i], p[i]);
            }
        }
        float height = Math.max(0.0001f, max[1] - min[1]);
        float scale = 2.08f / height;
        float[] centre = {(min[0] + max[0]) / 2f, (min[1] + max[1]) / 2f, (min[2] + max[2]) / 2f};

        RobotMesh mesh = new RobotMesh();
        for (int[] corner : faces) {
            float[] p = positions.get(corner[0]);
            float[] n = corner[1] >= 0 && corner[1] < normals.size()
                    ? normals.get(corner[1]) : new float[] {0f, 1f, 0f};
            mesh.vertex((p[0] - centre[0]) * scale, (p[1] - centre[1]) * scale,
                    (p[2] - centre[2]) * scale, n[0], n[1], n[2],
                    SHELL[0], SHELL[1], SHELL[2], GLOSS);
        }
        return mesh;
    }

    private static float[] parseFloats(String line) {
        String[] parts = line.trim().split("\\s+");
        return new float[] {Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                Float.parseFloat(parts[3])};
    }

    /** One face as a triangle fan, so quads and n-gons work as well as triangles. */
    private static void addFace(String line, List<int[]> faces) {
        String[] parts = line.trim().split("\\s+");
        int corners = parts.length - 1;
        if (corners < 3) return;
        int[][] indices = new int[corners][];
        for (int i = 0; i < corners; i++) {
            String[] fields = parts[i + 1].split("/");
            int position = Integer.parseInt(fields[0]) - 1;
            int normal = fields.length >= 3 && !fields[2].isEmpty()
                    ? Integer.parseInt(fields[2]) - 1 : -1;
            indices[i] = new int[] {position, normal};
        }
        for (int i = 1; i + 1 < corners; i++) {
            faces.add(indices[0]);
            faces.add(indices[i]);
            faces.add(indices[i + 1]);
        }
    }

    // ---- primitives -------------------------------------------------------------------------

    /**
     * A shell lofted through a stack of rings, bottom to top. Each row is
     * {@code {y, half width, half depth, x offset, z offset}}, and {@code exponent} shapes the ring:
     * 1 is an ellipse, and lower values square it off into the rounded rectangle armour plates are
     * cut from. Both ends are closed, so nothing shows through where limbs meet.
     */
    private void loft(float cx, float cz, float exponent, float[] colour, float material,
                      float[][] rows) {
        int count = rows.length;
        float[][][] points = new float[count][SLICES][];
        for (int i = 0; i < count; i++) {
            float[] row = rows[i];
            for (int s = 0; s < SLICES; s++) {
                double t = 2 * Math.PI * s / SLICES;
                points[i][s] = new float[] {
                        cx + row[3] + row[1] * signedPow(Math.cos(t), exponent),
                        row[0],
                        cz + row[4] + row[2] * signedPow(Math.sin(t), exponent),
                };
            }
        }
        for (int i = 0; i + 1 < count; i++) {
            for (int s = 0; s < SLICES; s++) {
                int next = (s + 1) % SLICES;
                float[] a = points[i][s];
                float[] b = points[i][next];
                float[] c = points[i + 1][next];
                float[] d = points[i + 1][s];
                float[] na = loftNormal(points, i, s);
                float[] nb = loftNormal(points, i, next);
                float[] nc = loftNormal(points, i + 1, next);
                float[] nd = loftNormal(points, i + 1, s);
                vertex(a, na, colour, material);
                vertex(c, nc, colour, material);
                vertex(b, nb, colour, material);
                vertex(a, na, colour, material);
                vertex(d, nd, colour, material);
                vertex(c, nc, colour, material);
            }
        }
        cap(points[0], false, colour, material);
        cap(points[count - 1], true, colour, material);
    }

    /** The surface normal at one lofted point, from the slope to its neighbours. */
    private static float[] loftNormal(float[][][] points, int row, int slice) {
        float[] up = points[Math.min(row + 1, points.length - 1)][slice];
        float[] down = points[Math.max(row - 1, 0)][slice];
        float[] next = points[row][(slice + 1) % SLICES];
        float[] previous = points[row][(slice + SLICES - 1) % SLICES];
        return normalize(cross(subtract(up, down), subtract(next, previous)));
    }

    private void cap(float[][] ring, boolean upwards, float[] colour, float material) {
        float[] centre = new float[3];
        for (float[] point : ring) {
            for (int i = 0; i < 3; i++) centre[i] += point[i] / ring.length;
        }
        float[] normal = {0f, upwards ? 1f : -1f, 0f};
        for (int s = 0; s < ring.length; s++) {
            float[] a = ring[s];
            float[] b = ring[(s + 1) % ring.length];
            vertex(centre, normal, colour, material);
            vertex(upwards ? b : a, normal, colour, material);
            vertex(upwards ? a : b, normal, colour, material);
        }
    }

    private void ellipsoid(float cx, float cy, float cz, float rx, float ry, float rz,
                           float[] colour, float material) {
        patch(cx, cy, cz, rx, ry, rz, -90f, 90f, 0f, 360f, colour, material);
    }

    /**
     * Part of an ellipsoid, between two latitudes and two longitudes in degrees (longitude measured
     * from the right, increasing towards the front). Used for the helmet's visor and for the
     * collars around the joints.
     */
    private void patch(float cx, float cy, float cz, float rx, float ry, float rz,
                       float latitudeFrom, float latitudeTo, float longitudeFrom, float longitudeTo,
                       float[] colour, float material) {
        float[][][] points = new float[STACKS + 1][PATCH_SLICES + 1][];
        float[][][] normals = new float[STACKS + 1][PATCH_SLICES + 1][];
        for (int i = 0; i <= STACKS; i++) {
            double phi = Math.toRadians(latitudeFrom + (latitudeTo - latitudeFrom) * i / STACKS);
            for (int s = 0; s <= PATCH_SLICES; s++) {
                double theta = Math.toRadians(
                        longitudeFrom + (longitudeTo - longitudeFrom) * s / PATCH_SLICES);
                float ux = (float) (Math.cos(phi) * Math.cos(theta));
                float uy = (float) Math.sin(phi);
                float uz = (float) (Math.cos(phi) * Math.sin(theta));
                points[i][s] = new float[] {cx + rx * ux, cy + ry * uy, cz + rz * uz};
                normals[i][s] = normalize(new float[] {ux / rx, uy / ry, uz / rz});
            }
        }
        for (int i = 0; i < STACKS; i++) {
            for (int s = 0; s < PATCH_SLICES; s++) {
                vertex(points[i][s], normals[i][s], colour, material);
                vertex(points[i + 1][s + 1], normals[i + 1][s + 1], colour, material);
                vertex(points[i][s + 1], normals[i][s + 1], colour, material);
                vertex(points[i][s], normals[i][s], colour, material);
                vertex(points[i + 1][s], normals[i + 1][s], colour, material);
                vertex(points[i + 1][s + 1], normals[i + 1][s + 1], colour, material);
            }
        }
    }

    /** A flat disc facing the given direction: the blue lights, and the dark rings behind them. */
    private void light(float cx, float cy, float cz, float nx, float ny, float nz, float radius,
                       float[] colour, float material) {
        float[] normal = normalize(new float[] {nx, ny, nz});
        float[] guide = Math.abs(normal[1]) > 0.9f
                ? new float[] {0f, 0f, 1f} : new float[] {0f, 1f, 0f};
        float[] u = normalize(cross(guide, normal));
        float[] v = cross(normal, u);
        float[] centre = {cx, cy, cz};
        final int steps = 24;
        for (int i = 0; i < steps; i++) {
            float[] a = rim(centre, u, v, radius, 2 * Math.PI * i / steps);
            float[] b = rim(centre, u, v, radius, 2 * Math.PI * (i + 1) / steps);
            vertex(centre, normal, colour, material);
            vertex(a, normal, colour, material);
            vertex(b, normal, colour, material);
        }
    }

    private static float[] rim(float[] centre, float[] u, float[] v, float radius, double angle) {
        float cos = (float) (radius * Math.cos(angle));
        float sin = (float) (radius * Math.sin(angle));
        return new float[] {
                centre[0] + u[0] * cos + v[0] * sin,
                centre[1] + u[1] * cos + v[1] * sin,
                centre[2] + u[2] * cos + v[2] * sin,
        };
    }

    private void box(float cx, float cy, float cz, float w, float h, float d,
                     float[] colour, float material) {
        float x = w / 2, y = h / 2, z = d / 2;
        // front, back, left, right, top, bottom
        quad(cx - x, cy - y, cz + z, cx + x, cy - y, cz + z, cx + x, cy + y, cz + z, cx - x, cy + y, cz + z, 0, 0, 1, colour, material);
        quad(cx + x, cy - y, cz - z, cx - x, cy - y, cz - z, cx - x, cy + y, cz - z, cx + x, cy + y, cz - z, 0, 0, -1, colour, material);
        quad(cx - x, cy - y, cz - z, cx - x, cy - y, cz + z, cx - x, cy + y, cz + z, cx - x, cy + y, cz - z, -1, 0, 0, colour, material);
        quad(cx + x, cy - y, cz + z, cx + x, cy - y, cz - z, cx + x, cy + y, cz - z, cx + x, cy + y, cz + z, 1, 0, 0, colour, material);
        quad(cx - x, cy + y, cz + z, cx + x, cy + y, cz + z, cx + x, cy + y, cz - z, cx - x, cy + y, cz - z, 0, 1, 0, colour, material);
        quad(cx - x, cy - y, cz - z, cx + x, cy - y, cz - z, cx + x, cy - y, cz + z, cx - x, cy - y, cz + z, 0, -1, 0, colour, material);
    }

    private void quad(float x1, float y1, float z1, float x2, float y2, float z2,
                      float x3, float y3, float z3, float x4, float y4, float z4,
                      float nx, float ny, float nz, float[] colour, float material) {
        vertex(x1, y1, z1, nx, ny, nz, colour[0], colour[1], colour[2], material);
        vertex(x2, y2, z2, nx, ny, nz, colour[0], colour[1], colour[2], material);
        vertex(x3, y3, z3, nx, ny, nz, colour[0], colour[1], colour[2], material);
        vertex(x1, y1, z1, nx, ny, nz, colour[0], colour[1], colour[2], material);
        vertex(x3, y3, z3, nx, ny, nz, colour[0], colour[1], colour[2], material);
        vertex(x4, y4, z4, nx, ny, nz, colour[0], colour[1], colour[2], material);
    }

    // ---- vector helpers ---------------------------------------------------------------------

    /** Raising the ring's cosine to a power below one squares the circle off into a plate. */
    private static float signedPow(double value, float exponent) {
        float magnitude = (float) Math.pow(Math.abs(value), exponent);
        return value < 0 ? -magnitude : magnitude;
    }

    private static float[] subtract(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
        };
    }

    /** Unit length, or straight up where the vector is degenerate (a pole, or a flat ring). */
    private static float[] normalize(float[] v) {
        float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length < 1e-6f) return new float[] {0f, 1f, 0f};
        return new float[] {v[0] / length, v[1] / length, v[2] / length};
    }

    // ---- vertex storage ------------------------------------------------------------------------

    private void vertex(float[] position, float[] normal, float[] colour, float material) {
        vertex(position[0], position[1], position[2], normal[0], normal[1], normal[2],
                colour[0], colour[1], colour[2], material);
    }

    private void vertex(float x, float y, float z, float nx, float ny, float nz,
                        float red, float green, float blue, float material) {
        if (size + FLOATS_PER_VERTEX > data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = x;
        data[size++] = y;
        data[size++] = z;
        data[size++] = nx;
        data[size++] = ny;
        data[size++] = nz;
        data[size++] = red;
        data[size++] = green;
        data[size++] = blue;
        data[size++] = material;
        vertexCount++;
    }

    /** The vertices, ready to upload. */
    FloatBuffer vertices() {
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(data, 0, size);
            buffer.position(0);
        }
        return buffer;
    }

    int vertexCount() {
        return vertexCount;
    }
}
