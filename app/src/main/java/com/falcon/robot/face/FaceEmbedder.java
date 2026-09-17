package com.falcon.robot.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Runs MobileFaceNet ({@code assets/mobilefacenet.tflite}) on a face crop and returns an
 * L2-normalised embedding.
 *
 * <p>The input/output shapes are read from the model, so common MobileFaceNet exports work:
 * NHWC or NCHW input (usually 112x112 RGB), batch size 1 or 2 (a batch of 2 is fed the face and
 * its mirror image and the outputs are averaged), float32 or quantized uint8/int8 tensors, and
 * 128- or 192-dimensional outputs.
 */
public final class FaceEmbedder implements Closeable {

    public static final String MODEL_ASSET = "mobilefacenet.tflite";

    /** Pixel normalisation used by MobileFaceNet training: (value - 127.5) / 128. */
    private static final float PIXEL_MEAN = 127.5f;
    private static final float PIXEL_STD = 128f;

    private final Interpreter interpreter;
    private final int batch;
    private final int inputHeight;
    private final int inputWidth;
    private final boolean channelsFirst;
    private final DataType inputType;
    private final float inputScale;
    private final int inputZeroPoint;
    private final DataType outputType;
    private final float outputScale;
    private final int outputZeroPoint;
    private final int embeddingSize;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private final int[] pixels;

    public FaceEmbedder(Context context, int threads) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(1, threads));
        interpreter = new Interpreter(loadModel(context), options);

        Tensor input = interpreter.getInputTensor(0);
        int[] shape = input.shape(); // [N, H, W, 3] or [N, 3, H, W]
        if (shape.length != 4) throw new IOException("Unexpected input shape: " + java.util.Arrays.toString(shape));
        batch = shape[0];
        channelsFirst = shape[1] == 3 && shape[3] != 3;
        inputHeight = channelsFirst ? shape[2] : shape[1];
        inputWidth = channelsFirst ? shape[3] : shape[2];
        inputType = input.dataType();
        inputScale = input.quantizationParams().getScale();
        inputZeroPoint = input.quantizationParams().getZeroPoint();

        Tensor output = interpreter.getOutputTensor(0);
        int[] outShape = output.shape(); // [N, D]
        embeddingSize = outShape[outShape.length - 1];
        outputType = output.dataType();
        outputScale = output.quantizationParams().getScale();
        outputZeroPoint = output.quantizationParams().getZeroPoint();

        inputBuffer = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder());
        outputBuffer = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder());
        pixels = new int[inputWidth * inputHeight];
    }

    public int getEmbeddingSize() {
        return embeddingSize;
    }

    public String describe() {
        return inputWidth + "x" + inputHeight + " → " + embeddingSize + "-d (" + inputType + ")";
    }

    /** Embedding of an (already cropped) face bitmap. Not thread-safe: call from one thread. */
    public float[] embed(Bitmap face) {
        Bitmap scaled = Bitmap.createScaledBitmap(face, inputWidth, inputHeight, true);
        inputBuffer.rewind();
        writeImage(scaled, false);
        for (int i = 1; i < batch; i++) writeImage(scaled, i % 2 == 1); // mirrored copies for batch > 1
        if (scaled != face) scaled.recycle();

        outputBuffer.rewind();
        interpreter.run(inputBuffer, outputBuffer);
        outputBuffer.rewind();

        float[] embedding = new float[embeddingSize];
        int rows = batch;
        for (int r = 0; r < rows; r++) {
            for (int i = 0; i < embeddingSize; i++) embedding[i] += readOutput();
        }
        return FaceMath.normalize(embedding);
    }

    private void writeImage(Bitmap bitmap, boolean mirror) {
        Bitmap source = bitmap;
        if (mirror) {
            Bitmap flipped = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
            Matrix m = new Matrix();
            m.setScale(-1, 1, inputWidth / 2f, 0);
            new Canvas(flipped).drawBitmap(bitmap, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            source = flipped;
        }
        source.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        if (channelsFirst) {
            for (int c = 0; c < 3; c++) {
                for (int p : pixels) writeChannel(channel(p, c));
            }
        } else {
            for (int p : pixels) {
                writeChannel(channel(p, 0));
                writeChannel(channel(p, 1));
                writeChannel(channel(p, 2));
            }
        }
        if (source != bitmap) source.recycle();
    }

    private static int channel(int argb, int c) {
        return c == 0 ? (argb >> 16) & 0xFF : c == 1 ? (argb >> 8) & 0xFF : argb & 0xFF;
    }

    private void writeChannel(int value) {
        float normalized = (value - PIXEL_MEAN) / PIXEL_STD;
        switch (inputType) {
            case FLOAT32:
                inputBuffer.putFloat(normalized);
                break;
            case UINT8:
                inputBuffer.put((byte) (inputScale > 0 ? clamp(Math.round(normalized / inputScale) + inputZeroPoint, 0, 255) : value));
                break;
            case INT8:
                inputBuffer.put((byte) (inputScale > 0 ? clamp(Math.round(normalized / inputScale) + inputZeroPoint, -128, 127) : value - 128));
                break;
            default:
                throw new IllegalStateException("Unsupported input type " + inputType);
        }
    }

    private float readOutput() {
        switch (outputType) {
            case FLOAT32:
                return outputBuffer.getFloat();
            case UINT8:
                return ((outputBuffer.get() & 0xFF) - outputZeroPoint) * outputScale;
            case INT8:
                return (outputBuffer.get() - outputZeroPoint) * outputScale;
            default:
                throw new IllegalStateException("Unsupported output type " + outputType);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static ByteBuffer loadModel(Context context) throws IOException {
        try (InputStream in = context.getAssets().open(MODEL_ASSET)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) bytes.write(chunk, 0, n);
            byte[] data = bytes.toByteArray();
            ByteBuffer buffer = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
            buffer.put(data);
            buffer.rewind();
            return buffer;
        }
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
