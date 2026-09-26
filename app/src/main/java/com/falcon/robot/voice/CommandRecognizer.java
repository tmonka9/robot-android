package com.falcon.robot.voice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Recognises the robot's own command words, rather than transcribing speech.
 *
 * <p>The robot answers to a short, fixed list of commands, and a model that only has to tell those
 * apart is a few hundred kilobytes rather than whisper's several hundred megabytes: it loads in
 * milliseconds, answers in a few more, and can be retrained on recordings of whoever will actually
 * be giving the orders. {@code tools/train_commands.py} builds one.
 *
 * <p>The model is {@code commands.tflite} with {@code commands.json} beside it, taken from the
 * models folder on the device if it is there, otherwise from the assets in the APK — so a
 * retrained model can be pushed with adb without building anything. The metadata names the labels
 * and the front-end settings, so a model retrained with different settings still runs.
 *
 * <p>Labels are {@link VoiceCommands} action names, plus {@code _silence} and {@code _unknown} for
 * everything the robot was not asked.
 */
public final class CommandRecognizer {

    private static final String TAG = "CommandRecognizer";

    public static final String MODEL = "commands.tflite";
    public static final String METADATA = "commands.json";

    /** Not one of the commands: background noise, or something else entirely. */
    public static final String SILENCE = "_silence";
    public static final String UNKNOWN = "_unknown";

    /** What the model made of an utterance. */
    public static final class Result {
        public final String label;
        public final float confidence;

        Result(String label, float confidence) {
            this.label = label;
            this.confidence = confidence;
        }

        /** True when this is a command the robot knows, said clearly enough to act on. */
        public boolean isCommand(float threshold) {
            return confidence >= threshold && !SILENCE.equals(label) && !UNKNOWN.equals(label);
        }
    }

    private final Interpreter interpreter;
    private final MelFeatures features;
    private final List<String> labels;
    private final float threshold;
    private final ByteBuffer input;
    private final float[][] output;

    private CommandRecognizer(Interpreter interpreter, MelFeatures features, List<String> labels,
                              float threshold) {
        this.interpreter = interpreter;
        this.features = features;
        this.labels = labels;
        this.threshold = threshold;
        this.input = ByteBuffer.allocateDirect(features.frames() * features.mels() * 4)
                .order(ByteOrder.nativeOrder());
        this.output = new float[1][labels.size()];
    }

    /** Loads the command model, or returns null when there is none to load. */
    public static CommandRecognizer load(Context context, int threads) {
        try {
            byte[] metadata = read(context, METADATA);
            if (metadata == null) return null;
            ByteBuffer model = asBuffer(read(context, MODEL));
            if (model == null) return null;

            JSONObject json = new JSONObject(new String(metadata, StandardCharsets.UTF_8));
            JSONArray names = json.getJSONArray("labels");
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < names.length(); i++) labels.add(names.getString(i));
            if (labels.isEmpty()) return null;

            MelFeatures features = new MelFeatures(
                    json.optInt("sample_rate", SpeechRecorder.SAMPLE_RATE),
                    json.optInt("clip_samples", 16000),
                    json.optInt("frame", 400),
                    json.optInt("hop", 160),
                    json.optInt("fft", 512),
                    json.optInt("mels", 40),
                    (float) json.optDouble("fmin", 20),
                    (float) json.optDouble("fmax", 7600));

            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(Math.max(1, threads));
            Interpreter interpreter = new Interpreter(model, options);
            CommandRecognizer recognizer = new CommandRecognizer(interpreter, features, labels,
                    (float) json.optDouble("threshold", 0.6));
            recognizer.checkShape();
            return recognizer;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not load the command model", e);
            return null;
        }
    }

    /**
     * True when there is a command model to load, without loading it — so the Voice page can tell
     * that the robot will understand its own orders before whisper has anything to say about it.
     */
    public static boolean exists(Context context) {
        if (new File(WhisperEngine.getModelDir(context), MODEL).exists()) return true;
        try (InputStream in = context.getAssets().open(MODEL)) {
            return in != null;
        } catch (IOException notBundled) {
            return false;
        }
    }

    /** Fails loudly here rather than quietly recognising nothing later. */
    private void checkShape() {
        int[] shape = interpreter.getInputTensor(0).shape();
        int expected = features.frames() * features.mels();
        int actual = 1;
        for (int dimension : shape) actual *= dimension;
        if (actual != expected) {
            throw new IllegalStateException("model wants " + actual + " inputs, the metadata's "
                    + "front-end makes " + expected + " (" + features.frames() + " frames x "
                    + features.mels() + " mels)");
        }
        int outputs = interpreter.getOutputTensor(0).shape()[1];
        if (outputs != labels.size()) {
            throw new IllegalStateException("model has " + outputs + " outputs, the metadata names "
                    + labels.size() + " labels");
        }
    }

    /** The command in one utterance of 16 kHz mono audio. Never null. */
    public Result classify(float[] audio) {
        float[] values = features.extract(audio);
        input.rewind();
        for (float value : values) input.putFloat(value);
        input.rewind();
        interpreter.run(input, output);

        int best = 0;
        for (int i = 1; i < output[0].length; i++) {
            if (output[0][i] > output[0][best]) best = i;
        }
        return new Result(labels.get(best), output[0][best]);
    }

    /** How sure the model has to be before the robot acts, as the training script set it. */
    public float threshold() {
        return threshold;
    }

    /** The commands this model knows, without the silence and unknown classes. */
    public List<String> commands() {
        List<String> known = new ArrayList<>();
        for (String label : labels) {
            if (!SILENCE.equals(label) && !UNKNOWN.equals(label)) known.add(label);
        }
        return known;
    }

    public void close() {
        interpreter.close();
    }

    /** From the device's models folder if it is there, otherwise from the APK; null when neither. */
    private static byte[] read(Context context, String name) throws IOException {
        File file = new File(WhisperEngine.getModelDir(context), name);
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                return readFully(in);
            }
        }
        try (InputStream in = context.getAssets().open(name)) {
            return readFully(in);
        } catch (IOException notBundled) {
            return null;
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int read;
        while ((read = in.read(chunk)) > 0) bytes.write(chunk, 0, read);
        return bytes.toByteArray();
    }

    private static ByteBuffer asBuffer(byte[] data) {
        if (data == null) return null;
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        buffer.put(data);
        buffer.rewind();
        return buffer;
    }
}
