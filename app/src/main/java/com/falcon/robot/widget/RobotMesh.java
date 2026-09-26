package com.falcon.robot.widget;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The geometry {@link Robot3DView} draws: a humanoid robot built from boxes and spheres, or a
 * model file if one is supplied.
 *
 * <p>Drop an OBJ export of the real robot into {@code app/src/main/assets} (positions, normals and
 * triangular or quad faces; materials are ignored) and pass its name to
 * {@link #fromAsset}; without it the built-in figure is used, so the page works with no assets at
 * all. Vertices are position, normal and colour interleaved, nine floats each.
 */
final class RobotMesh {

    static final int FLOATS_PER_VERTEX = 9;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    private static final float[] PANEL = {0.92f, 0.95f, 1.00f}; // white shell
    private static final float[] JOINT = {0.09f, 0.14f, 0.22f}; // dark navy joints
    private static final float[] GLOW = {0.13f, 0.83f, 0.93f};  // cyan eyes and lights

    private final List<Float> data = new ArrayList<>();
    private FloatBuffer buffer;
    private int vertexCount;

    static int strideBytes() {
        return STRIDE_BYTES;
    }

    /** The built-in humanoid: roughly two units tall, standing on the origin. */
    static RobotMesh humanoid() {
        RobotMesh mesh = new RobotMesh();
        // head
        mesh.box(0f, 0.72f, 0f, 0.42f, 0.38f, 0.38f, PANEL);
        mesh.box(0f, 0.74f, 0.20f, 0.34f, 0.16f, 0.03f, GLOW);       // visor
        mesh.box(-0.23f, 0.72f, 0f, 0.06f, 0.14f, 0.14f, GLOW);      // ear pods
        mesh.box(0.23f, 0.72f, 0f, 0.06f, 0.14f, 0.14f, GLOW);
        mesh.box(0f, 0.50f, 0f, 0.14f, 0.10f, 0.14f, JOINT);         // neck

        // body
        mesh.box(0f, 0.24f, 0f, 0.52f, 0.46f, 0.30f, PANEL);
        mesh.box(0f, 0.28f, 0.16f, 0.16f, 0.16f, 0.02f, GLOW);       // chest light
        mesh.box(0f, -0.02f, 0f, 0.34f, 0.12f, 0.24f, JOINT);        // waist

        // arms
        for (int side = -1; side <= 1; side += 2) {
            float x = 0.33f * side;
            mesh.sphere(x, 0.40f, 0f, 0.11f, JOINT);
            mesh.box(x, 0.22f, 0f, 0.14f, 0.30f, 0.14f, PANEL);
            mesh.sphere(x, 0.05f, 0f, 0.08f, JOINT);
            mesh.box(x, -0.12f, 0f, 0.12f, 0.28f, 0.12f, PANEL);
            mesh.box(x, -0.32f, 0f, 0.12f, 0.12f, 0.12f, JOINT);     // hand
        }

        // legs
        for (int side = -1; side <= 1; side += 2) {
            float x = 0.15f * side;
            mesh.sphere(x, -0.16f, 0f, 0.10f, JOINT);
            mesh.box(x, -0.38f, 0f, 0.18f, 0.36f, 0.18f, PANEL);
            mesh.sphere(x, -0.58f, 0f, 0.09f, JOINT);
            mesh.box(x, -0.78f, 0f, 0.16f, 0.34f, 0.16f, PANEL);
            mesh.box(x, -0.98f, 0.04f, 0.20f, 0.10f, 0.30f, JOINT);  // foot
        }
        return mesh;
    }

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
        float scale = 2f / height;
        float[] centre = {(min[0] + max[0]) / 2f, (min[1] + max[1]) / 2f, (min[2] + max[2]) / 2f};

        RobotMesh mesh = new RobotMesh();
        for (int[] corner : faces) {
            float[] p = positions.get(corner[0]);
            float[] n = corner[1] >= 0 && corner[1] < normals.size()
                    ? normals.get(corner[1]) : new float[] {0f, 1f, 0f};
            mesh.vertex((p[0] - centre[0]) * scale, (p[1] - centre[1]) * scale,
                    (p[2] - centre[2]) * scale, n[0], n[1], n[2], PANEL);
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

    private void box(float cx, float cy, float cz, float w, float h, float d, float[] colour) {
        float x = w / 2, y = h / 2, z = d / 2;
        // front, back, left, right, top, bottom
        quad(cx - x, cy - y, cz + z, cx + x, cy - y, cz + z, cx + x, cy + y, cz + z, cx - x, cy + y, cz + z, 0, 0, 1, colour);
        quad(cx + x, cy - y, cz - z, cx - x, cy - y, cz - z, cx - x, cy + y, cz - z, cx + x, cy + y, cz - z, 0, 0, -1, colour);
        quad(cx - x, cy - y, cz - z, cx - x, cy - y, cz + z, cx - x, cy + y, cz + z, cx - x, cy + y, cz - z, -1, 0, 0, colour);
        quad(cx + x, cy - y, cz + z, cx + x, cy - y, cz - z, cx + x, cy + y, cz - z, cx + x, cy + y, cz + z, 1, 0, 0, colour);
        quad(cx - x, cy + y, cz + z, cx + x, cy + y, cz + z, cx + x, cy + y, cz - z, cx - x, cy + y, cz - z, 0, 1, 0, colour);
        quad(cx - x, cy - y, cz - z, cx + x, cy - y, cz - z, cx + x, cy - y, cz + z, cx - x, cy - y, cz + z, 0, -1, 0, colour);
    }

    private void quad(float x1, float y1, float z1, float x2, float y2, float z2,
                      float x3, float y3, float z3, float x4, float y4, float z4,
                      float nx, float ny, float nz, float[] colour) {
        vertex(x1, y1, z1, nx, ny, nz, colour);
        vertex(x2, y2, z2, nx, ny, nz, colour);
        vertex(x3, y3, z3, nx, ny, nz, colour);
        vertex(x1, y1, z1, nx, ny, nz, colour);
        vertex(x3, y3, z3, nx, ny, nz, colour);
        vertex(x4, y4, z4, nx, ny, nz, colour);
    }

    private void sphere(float cx, float cy, float cz, float radius, float[] colour) {
        final int stacks = 10;
        final int slices = 14;
        for (int i = 0; i < stacks; i++) {
            double phi1 = Math.PI * i / stacks - Math.PI / 2;
            double phi2 = Math.PI * (i + 1) / stacks - Math.PI / 2;
            for (int j = 0; j < slices; j++) {
                double theta1 = 2 * Math.PI * j / slices;
                double theta2 = 2 * Math.PI * (j + 1) / slices;
                float[] a = point(phi1, theta1);
                float[] b = point(phi1, theta2);
                float[] c = point(phi2, theta2);
                float[] d = point(phi2, theta1);
                sphereVertex(cx, cy, cz, radius, a, colour);
                sphereVertex(cx, cy, cz, radius, b, colour);
                sphereVertex(cx, cy, cz, radius, c, colour);
                sphereVertex(cx, cy, cz, radius, a, colour);
                sphereVertex(cx, cy, cz, radius, c, colour);
                sphereVertex(cx, cy, cz, radius, d, colour);
            }
        }
    }

    private static float[] point(double phi, double theta) {
        return new float[] {
                (float) (Math.cos(phi) * Math.cos(theta)),
                (float) Math.sin(phi),
                (float) (Math.cos(phi) * Math.sin(theta)),
        };
    }

    private void sphereVertex(float cx, float cy, float cz, float radius, float[] n, float[] colour) {
        vertex(cx + n[0] * radius, cy + n[1] * radius, cz + n[2] * radius, n[0], n[1], n[2], colour);
    }

    private void vertex(float x, float y, float z, float nx, float ny, float nz, float[] colour) {
        data.add(x);
        data.add(y);
        data.add(z);
        data.add(nx);
        data.add(ny);
        data.add(nz);
        data.add(colour[0]);
        data.add(colour[1]);
        data.add(colour[2]);
        vertexCount++;
    }

    /** The vertices, ready for {@code glVertexAttribPointer}. */
    FloatBuffer vertices() {
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(data.size() * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (float value : data) buffer.put(value);
            buffer.position(0);
        }
        return buffer;
    }

    int vertexCount() {
        return vertexCount;
    }
}
