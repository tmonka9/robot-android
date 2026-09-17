package com.falcon.robot.face;

/** Embedding helpers. */
public final class FaceMath {

    private FaceMath() {
    }

    public static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += x * x;
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }

    /** Cosine similarity of two L2-normalised vectors (-1..1). */
    public static float cosine(float[] a, float[] b) {
        if (a.length != b.length) return -1f;
        float dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    /**
     * Similarity shown to users, 0..100 %: cosine mapped linearly from [-1, 1].
     * Typical MobileFaceNet values: same person ≈ 85–95 %, different people ≈ 55–75 %.
     */
    public static float toPercent(float cosine) {
        return Math.max(0f, Math.min(100f, (cosine + 1f) * 50f));
    }

    /** Mean of several embeddings, re-normalised. */
    public static float[] average(java.util.List<float[]> embeddings) {
        float[] mean = new float[embeddings.get(0).length];
        for (float[] e : embeddings) for (int i = 0; i < mean.length; i++) mean[i] += e[i];
        return normalize(mean);
    }
}
