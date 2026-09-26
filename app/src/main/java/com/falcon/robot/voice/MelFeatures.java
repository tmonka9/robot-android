package com.falcon.robot.voice;

/**
 * The log-mel spectrogram a command model is trained and run on.
 *
 * <p>This is the one place the app and the training script have to agree, down to the arithmetic:
 * features computed differently from the ones a model was trained on turn a good model into a bad
 * one, silently. {@code tools/train_commands.py} holds the same code in numpy, and the settings
 * travel with the model in its metadata file, so a retrained model that changes them keeps working.
 *
 * <p>Audio is 16 kHz mono in the range -1..1, as {@link SpeechRecorder} produces it.
 */
public final class MelFeatures {

    private final int clip;
    private final int frame;
    private final int hop;
    private final int fft;
    private final int mels;
    private final int bins;
    private final int frames;

    private final float[] window;
    private final float[][] filters;

    /** Scratch for one frame, reused: this runs on every utterance. */
    private final float[] re;
    private final float[] im;

    public MelFeatures(int sampleRate, int clipSamples, int frameSamples, int hopSamples,
                       int fftSize, int melCount, float fmin, float fmax) {
        this.clip = clipSamples;
        this.frame = frameSamples;
        this.hop = hopSamples;
        this.fft = fftSize;
        this.mels = melCount;
        this.bins = fftSize / 2 + 1;
        this.frames = 1 + (clipSamples - frameSamples) / hopSamples;
        this.re = new float[fftSize];
        this.im = new float[fftSize];

        window = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (frameSamples - 1)));
        }
        filters = melFilters(sampleRate, fmin, fmax);
    }

    public int frames() {
        return frames;
    }

    public int mels() {
        return mels;
    }

    /** Triangular filters on the spectrum's bin grid, on the HTK mel scale. */
    private float[][] melFilters(int sampleRate, float fmin, float fmax) {
        float[][] built = new float[mels][bins];
        double low = melFromHz(fmin);
        double high = melFromHz(fmax);
        double[] edges = new double[mels + 2];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = hzFromMel(low + (high - low) * i / (mels + 1));
        }
        for (int m = 0; m < mels; m++) {
            double left = edges[m];
            double centre = edges[m + 1];
            double right = edges[m + 2];
            for (int b = 0; b < bins; b++) {
                double hz = (double) b * sampleRate / fft;
                if (hz > left && hz < centre) {
                    built[m][b] = (float) ((hz - left) / (centre - left));
                } else if (hz >= centre && hz < right) {
                    built[m][b] = (float) ((right - hz) / (right - centre));
                }
            }
        }
        return built;
    }

    private static double melFromHz(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    private static double hzFromMel(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    /**
     * One utterance as {@code frames * mels} values, row by row, normalised to zero mean and unit
     * deviation so that how loudly it was said does not matter.
     */
    public float[] extract(float[] audio) {
        float[] clipped = oneClip(audio);
        float[] out = new float[frames * mels];
        for (int t = 0; t < frames; t++) {
            java.util.Arrays.fill(re, 0f);
            java.util.Arrays.fill(im, 0f);
            for (int i = 0; i < frame; i++) {
                re[i] = clipped[t * hop + i] * window[i];
            }
            fft(re, im);
            for (int m = 0; m < mels; m++) {
                float[] filter = filters[m];
                double total = 0;
                for (int b = 0; b < bins; b++) {
                    if (filter[b] != 0f) total += filter[b] * (re[b] * re[b] + im[b] * im[b]);
                }
                out[t * mels + m] = (float) Math.log(total + 1e-6);
            }
        }
        normalise(out);
        return out;
    }

    /** The loudest window of a long utterance, or a short one centred in silence. */
    private float[] oneClip(float[] audio) {
        float[] out = new float[clip];
        if (audio.length >= clip) {
            int best = 0;
            double loudest = -1;
            for (int start = 0; start + clip <= audio.length; start += hop) {
                double energy = 0;
                for (int i = start; i < start + clip; i++) energy += audio[i] * audio[i];
                if (energy > loudest) {
                    loudest = energy;
                    best = start;
                }
            }
            System.arraycopy(audio, best, out, 0, clip);
        } else {
            System.arraycopy(audio, 0, out, (clip - audio.length) / 2, audio.length);
        }
        return out;
    }

    private static void normalise(float[] values) {
        double mean = 0;
        for (float v : values) mean += v;
        mean /= values.length;
        double variance = 0;
        for (float v : values) variance += (v - mean) * (v - mean);
        double deviation = Math.sqrt(variance / values.length) + 1e-5;
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) ((values[i] - mean) / deviation);
        }
    }

    /** Iterative radix-2 Cooley-Tukey, in place; {@code re.length} is a power of two. */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j |= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2 * Math.PI / length;
            int step = length >> 1;
            for (int start = 0; start < n; start += length) {
                for (int k = 0; k < step; k++) {
                    float cos = (float) Math.cos(angle * k);
                    float sin = (float) Math.sin(angle * k);
                    int a = start + k;
                    int b = a + step;
                    float tr = re[b] * cos - im[b] * sin;
                    float ti = re[b] * sin + im[b] * cos;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}
