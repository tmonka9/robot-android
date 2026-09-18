package com.falcon.robot.voice;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records 16 kHz mono audio and cuts it into utterances with a simple energy gate: recording
 * starts when the level rises above the measured noise floor and ends after a short silence.
 *
 * <p>Utterances are delivered as float samples in [-1, 1], which is what whisper.cpp expects.
 * Callbacks run on the main thread.
 */
public final class SpeechRecorder {

    private static final String TAG = "SpeechRecorder";
    public static final int SAMPLE_RATE = 16000;

    private static final int CHUNK_SAMPLES = SAMPLE_RATE / 50;     // 20 ms
    private static final float SILENCE_TAIL_SEC = 0.8f;
    private static final float MIN_SPEECH_SEC = 0.4f;
    private static final float MAX_SPEECH_SEC = 15f;
    private static final float NOISE_CALIBRATION_SEC = 0.6f;
    /** Speech must be this much louder than the noise floor. */
    private static final float SPEECH_FACTOR = 2.5f;
    private static final float MIN_SPEECH_LEVEL = 0.012f;

    public interface Listener {
        /** Current microphone level, 0..1, about every 20 ms (for the waveform). */
        void onLevel(float level, boolean speaking);

        void onUtterance(float[] samples);

        void onError(String message);
    }

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean noiseSuppression = true;
    private Thread thread;

    public SpeechRecorder(Listener listener) {
        this.listener = listener;
    }

    /** Enables the platform noise suppressor / echo canceller when the device offers them. */
    public void setNoiseSuppression(boolean enabled) {
        noiseSuppression = enabled;
    }

    public boolean isRunning() {
        return running.get();
    }

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO first
    public void start() {
        if (running.getAndSet(true)) return;
        thread = new Thread(this::record, "speech-recorder");
        thread.start();
    }

    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }

    private android.media.audiofx.AudioEffect[] effects;

    private void record() {
        AudioRecord recorder = null;
        try {
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) minBuffer = SAMPLE_RATE;
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                post(() -> listener.onError("Microphone unavailable"));
                return;
            }
            NoiseSuppressor suppressor = null;
            AcousticEchoCanceler canceler = null;
            if (noiseSuppression) {
                if (NoiseSuppressor.isAvailable()) {
                    suppressor = NoiseSuppressor.create(recorder.getAudioSessionId());
                    if (suppressor != null) suppressor.setEnabled(true);
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    canceler = AcousticEchoCanceler.create(recorder.getAudioSessionId());
                    if (canceler != null) canceler.setEnabled(true);
                }
            }
            effects = new android.media.audiofx.AudioEffect[] {suppressor, canceler};
            recorder.startRecording();

            short[] chunk = new short[CHUNK_SAMPLES];
            float[] speech = new float[(int) (MAX_SPEECH_SEC * SAMPLE_RATE)];
            int speechLength = 0;
            float noiseFloor = 0f;
            int calibrationChunks = 0;
            float silenceSec = 0f;
            boolean speaking = false;

            while (running.get()) {
                int read = recorder.read(chunk, 0, chunk.length);
                if (read <= 0) continue;

                double sum = 0;
                for (int i = 0; i < read; i++) {
                    float v = chunk[i] / 32768f;
                    sum += v * v;
                }
                float level = (float) Math.sqrt(sum / read);

                // first frames measure the room noise
                if (calibrationChunks < NOISE_CALIBRATION_SEC * 50) {
                    noiseFloor = calibrationChunks == 0 ? level : (noiseFloor * 0.9f + level * 0.1f);
                    calibrationChunks++;
                    post(level, false);
                    continue;
                }

                boolean loud = level > Math.max(MIN_SPEECH_LEVEL, noiseFloor * SPEECH_FACTOR);
                if (!loud && !speaking) {
                    noiseFloor = noiseFloor * 0.98f + level * 0.02f; // follow slow changes
                }
                post(level, speaking || loud);

                if (loud) {
                    speaking = true;
                    silenceSec = 0f;
                } else if (speaking) {
                    silenceSec += CHUNK_SAMPLES / (float) SAMPLE_RATE;
                }

                if (speaking) {
                    int copy = Math.min(read, speech.length - speechLength);
                    for (int i = 0; i < copy; i++) speech[speechLength + i] = chunk[i] / 32768f;
                    speechLength += copy;

                    boolean tooLong = speechLength >= speech.length;
                    if (tooLong || silenceSec >= SILENCE_TAIL_SEC) {
                        if (speechLength >= MIN_SPEECH_SEC * SAMPLE_RATE) {
                            final float[] utterance = new float[speechLength];
                            System.arraycopy(speech, 0, utterance, 0, speechLength);
                            post(() -> listener.onUtterance(utterance));
                        }
                        speechLength = 0;
                        speaking = false;
                        silenceSec = 0f;
                    }
                }
            }
        } catch (IllegalStateException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Recording failed", e);
            post(() -> listener.onError(e.getMessage() != null ? e.getMessage() : "Recording failed"));
        } finally {
            if (effects != null) {
                for (android.media.audiofx.AudioEffect effect : effects) {
                    if (effect != null) effect.release();
                }
                effects = null;
            }
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (IllegalStateException ignored) {
                    // already stopped
                }
                recorder.release();
            }
            running.set(false);
        }
    }

    private void post(float level, boolean speaking) {
        post(() -> listener.onLevel(Math.min(1f, level * 6f), speaking));
    }

    private void post(Runnable runnable) {
        main.post(runnable);
    }
}
