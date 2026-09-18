package com.falcon.robot.detect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YOLO detection and segmentation on TFLite: boxes, class scores and instance masks for one frame.
 *
 * <p>Exports differ, so the shapes are read from the model instead of assumed. Handled are:
 * predictions in one tensor, {@code [1, 4+classes+coefficients, anchors]} or its transpose, and
 * predictions split into a tensor each ({@code [1, anchors, 4]} boxes, {@code [1, anchors,
 * classes]} scores, {@code [1, anchors, 32]} coefficients); the mask prototypes as
 * {@code [1, h, w, 32]} or {@code [1, 32, h, w]}; NHWC and NCHW inputs; float32 and quantized
 * uint8/int8 tensors;
 * boxes in input pixels or normalised, as a centre and size or as corners; and the finished
 * detections that NMS-free models such as YOLO26 and YOLOv10 return ({@code [1, 300, 6]}:
 * x1, y1, x2, y2, score, class). A plain detection model such as yolov8n works too — without
 * prototypes it simply has no masks.
 *
 * <p>The frame is letterboxed into the model's input (aspect ratio kept, grey padding) and the
 * boxes are mapped back to frame pixels. One instance belongs to one thread.
 */
public final class YoloSegmenter implements Closeable {

    private static final String TAG = "YoloSegmenter";

    /** Bundled model, if there is one; any {@code *.tflite} in assets or the models folder works. */
    public static final String DEFAULT_MODEL = "yolov8n-seg.tflite";
    /** MobileFaceNet lives in the same folder but is not a detector. */
    private static final String FACE_MODEL = "mobilefacenet.tflite";

    private static final int PADDING_GREY = 0xFF727272; // 114,114,114, as in the YOLO letterbox
    private static final int MAX_DETECTIONS = 50;
    /** Above this many rows an output is raw anchors, not a finished detection list. */
    private static final int MAX_FINAL_DETECTIONS = 1024;
    /** Mask probability above which a pixel belongs to the instance. */
    private static final float MASK_THRESHOLD = 0.5f;

    /** One detected instance, in frame pixels. */
    public static final class Detection {
        public final RectF box;
        public final int classId;
        public final float score;
        /** Instance mask, or null when the model has no masks. */
        public final Bitmap mask;
        /**
         * The frame rectangle {@link #mask} covers. It is the box grown to whole mask pixels, so
         * the mask has to be drawn into this rather than into {@link #box} to stay aligned.
         */
        public final RectF maskBox;

        Detection(RectF box, int classId, float score, Bitmap mask, RectF maskBox) {
            this.box = box;
            this.classId = classId;
            this.score = score;
            this.mask = mask;
            this.maskBox = maskBox;
        }
    }

    /** A model output: random access to its values, dequantized when needed. */
    private static final class Output {
        final ByteBuffer buffer;
        final FloatBuffer floats; // fast path for float32 tensors
        final DataType type;
        final float scale;
        final int zeroPoint;

        Output(Tensor tensor) {
            buffer = ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder());
            type = tensor.dataType();
            scale = tensor.quantizationParams().getScale();
            zeroPoint = tensor.quantizationParams().getZeroPoint();
            floats = type == DataType.FLOAT32 ? buffer.asFloatBuffer() : null;
        }

        float get(int index) {
            if (floats != null) return floats.get(index);
            switch (type) {
                case UINT8:
                    return ((buffer.get(index) & 0xFF) - zeroPoint) * scale;
                case INT8:
                    return (buffer.get(index) - zeroPoint) * scale;
                default:
                    throw new IllegalStateException("Unsupported output type " + type);
            }
        }
    }

    /** A candidate before coordinate conversion and NMS. */
    private static final class Candidate {
        float cx, cy, w, h, score;
        int classId;
        int anchor;
    }

    private final Interpreter interpreter;
    private final String name;

    private final int inputWidth;
    private final int inputHeight;
    /** Some exports want [1, 3, H, W] instead of [1, H, W, 3]. */
    private final boolean inputChannelsFirst;
    private final DataType inputType;
    private final float inputScale;
    private final int inputZeroPoint;
    private final ByteBuffer inputBuffer;
    private final Bitmap inputImage;
    private final Canvas inputCanvas;
    private final Paint inputPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix letterbox = new Matrix();
    private final int[] pixels;

    /**
     * One prediction output, {@code [1, channels, anchors]} or {@code [1, anchors, channels]}.
     * Exports either pack boxes, class scores and mask coefficients into a single head or emit
     * one head each, so the three are addressed through a head plus an offset.
     */
    private static final class Head {
        final int index;
        final Output data;
        final int anchors;
        final int channels;
        final boolean channelsFirst;

        Head(int index, Tensor tensor) {
            int[] shape = tensor.shape();
            this.index = index;
            this.data = new Output(tensor);
            this.channelsFirst = shape[1] < shape[2];
            this.channels = channelsFirst ? shape[1] : shape[2];
            this.anchors = channelsFirst ? shape[2] : shape[1];
        }

        float get(int channel, int anchor) {
            return data.get(channelsFirst ? channel * anchors + anchor : anchor * channels + channel);
        }
    }

    private final List<Head> heads = new ArrayList<>();
    private final Head boxHead;
    private final Head scoreHead;
    private final Head coefficientHead; // null without masks
    private final int boxOffset;
    private final int scoreOffset;
    private final int coefficientOffset;
    private final int anchors;
    private final int numClasses;
    private final int coefficients;
    /** Split exports differ on the box format; decided per frame in {@link #isCornerFormat}. */
    private final boolean detectBoxFormat;
    /** NMS-free models (YOLO26, YOLOv10) return finished detections instead of raw anchors. */
    private final boolean endToEnd;

    private final int protoIndex;
    private final Output proto;
    private final boolean protoChannelsFirst;
    private final int protoWidth;
    private final int protoHeight;

    private final Map<Integer, Object> outputs = new HashMap<>();
    private final float[] coefficientBuffer;

    public YoloSegmenter(Context context, String modelName, int threads) throws IOException {
        name = modelName;
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(1, threads));
        interpreter = new Interpreter(loadModel(context, modelName), options);

        Tensor input = interpreter.getInputTensor(0);
        int[] inShape = input.shape(); // [1, H, W, 3] or [1, 3, H, W]
        if (inShape.length != 4 || (inShape[3] != 3 && inShape[1] != 3)) {
            throw new IOException("Expected an RGB input with three channels, got "
                    + Arrays.toString(inShape));
        }
        inputChannelsFirst = inShape[1] == 3 && inShape[3] != 3;
        inputHeight = inputChannelsFirst ? inShape[2] : inShape[1];
        inputWidth = inputChannelsFirst ? inShape[3] : inShape[2];
        inputType = input.dataType();
        inputScale = input.quantizationParams().getScale();
        inputZeroPoint = input.quantizationParams().getZeroPoint();
        inputBuffer = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder());
        inputImage = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        inputCanvas = new Canvas(inputImage);
        pixels = new int[inputWidth * inputHeight];

        // 3-dimensional outputs are predictions, a 4-dimensional one holds the mask prototypes
        int protoTensor = -1;
        for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
            Tensor tensor = interpreter.getOutputTensor(i);
            int rank = tensor.shape().length;
            if (rank == 3) heads.add(new Head(i, tensor));
            else if (rank == 4 && protoTensor < 0) protoTensor = i;
        }
        if (heads.isEmpty()) throw new IOException("No YOLO prediction output in " + modelName + outputShapes());

        protoIndex = protoTensor;
        if (protoTensor >= 0) {
            Tensor prototypes = interpreter.getOutputTensor(protoTensor);
            int[] shape = prototypes.shape(); // [1, h, w, 32] or [1, 32, h, w]
            protoChannelsFirst = shape[1] < shape[3];
            coefficients = protoChannelsFirst ? shape[1] : shape[3];
            protoHeight = protoChannelsFirst ? shape[2] : shape[1];
            protoWidth = protoChannelsFirst ? shape[3] : shape[2];
            proto = new Output(prototypes);
        } else {
            protoChannelsFirst = false;
            coefficients = 0;
            protoHeight = 0;
            protoWidth = 0;
            proto = null;
        }

        anchors = heads.get(0).anchors;
        for (Head head : heads) {
            if (head.anchors != anchors) {
                throw new IOException("Prediction outputs disagree on the number of boxes"
                        + outputShapes());
            }
        }

        if (heads.size() == 1 && proto == null && heads.get(0).channels == 6
                && heads.get(0).anchors <= MAX_FINAL_DETECTIONS) {
            // YOLO26 and the other NMS-free exports hand back finished detections, one row per
            // object: x1, y1, x2, y2, score, class. A raw two-class model would also have six
            // channels, but it would have thousands of rows rather than a few hundred.
            Head head = heads.get(0);
            boxHead = scoreHead = head;
            coefficientHead = null;
            boxOffset = 0;
            scoreOffset = 4;
            coefficientOffset = 0;
            numClasses = 0; // the class comes as a number in the row, not as a score per class
            endToEnd = true;
            detectBoxFormat = false;
        } else if (heads.size() == 1) {
            // one head: 4 box values, then the class scores, then the mask coefficients
            Head head = heads.get(0);
            boxHead = scoreHead = head;
            coefficientHead = coefficients > 0 ? head : null;
            boxOffset = 0;
            scoreOffset = 4;
            coefficientOffset = 4 + (head.channels - 4 - coefficients);
            numClasses = head.channels - 4 - coefficients;
            endToEnd = false;
            detectBoxFormat = false; // this layout is always centre/size
        } else {
            // separate heads: boxes have 4 channels, coefficients as many as the prototypes,
            // and whatever is left is the class scores
            Head boxes = null;
            Head coeffs = null;
            Head scores = null;
            for (Head head : heads) {
                if (boxes == null && head.channels == 4) boxes = head;
                else if (coeffs == null && coefficients > 0 && head.channels == coefficients) coeffs = head;
                else if (scores == null) scores = head;
            }
            if (boxes == null || scores == null) {
                throw new IOException("Cannot tell the YOLO outputs apart" + outputShapes());
            }
            boxHead = boxes;
            scoreHead = scores;
            coefficientHead = coeffs;
            boxOffset = 0;
            scoreOffset = 0;
            coefficientOffset = 0;
            numClasses = scores.channels;
            endToEnd = false;
            detectBoxFormat = true;
        }
        if (!endToEnd && numClasses <= 0) {
            throw new IOException("Unexpected output shape" + outputShapes());
        }
        coefficientBuffer = new float[Math.max(1, coefficients)];
        Log.i(TAG, "Loaded " + modelName + ": " + describe() + outputShapes());
    }

    /** Every output shape, for the log and for error messages. */
    private String outputShapes() {
        StringBuilder text = new StringBuilder(" (outputs:");
        for (int i = 0; i < interpreter.getOutputTensorCount(); i++) {
            text.append(' ').append(Arrays.toString(interpreter.getOutputTensor(i).shape()));
        }
        return text.append(')').toString();
    }

    public String getName() {
        return name;
    }

    public boolean hasMasks() {
        return proto != null && coefficientHead != null;
    }

    public String describe() {
        return inputWidth + "×" + inputHeight + (inputChannelsFirst ? " NCHW" : "")
                + (endToEnd ? " · end-to-end" : " · " + numClasses + " classes")
                + (hasMasks() ? " · masks" : "");
    }

    /**
     * Detects objects in an upright frame.
     *
     * @param confidence minimum class score
     * @param iou        IoU above which overlapping boxes of one class are merged
     * @param withMasks  false skips mask decoding, which is the expensive part
     */
    public List<Detection> detect(Bitmap frame, float confidence, float iou, boolean withMasks) {
        float scale = Math.min(inputWidth / (float) frame.getWidth(),
                inputHeight / (float) frame.getHeight());
        float padX = (inputWidth - frame.getWidth() * scale) / 2f;
        float padY = (inputHeight - frame.getHeight() * scale) / 2f;

        inputCanvas.drawColor(PADDING_GREY);
        letterbox.setScale(scale, scale);
        letterbox.postTranslate(padX, padY);
        inputCanvas.drawBitmap(frame, letterbox, inputPaint);
        writeInput();

        outputs.clear();
        for (Head head : heads) {
            head.data.buffer.rewind();
            outputs.put(head.index, head.data.buffer);
        }
        if (proto != null) {
            proto.buffer.rewind();
            outputs.put(protoIndex, proto.buffer);
        }
        inputBuffer.rewind();
        interpreter.runForMultipleInputsOutputs(new Object[] {inputBuffer}, outputs);

        List<Candidate> candidates = collectCandidates(confidence);
        if (candidates.isEmpty()) return new ArrayList<>();

        // ultralytics exports boxes either in input pixels or normalised; tell them apart by size
        float largest = 0f;
        for (Candidate c : candidates) largest = Math.max(largest, Math.max(c.cx, c.cy));
        float boxScaleX = largest > 2f ? 1f : inputWidth;
        float boxScaleY = largest > 2f ? 1f : inputHeight;

        // some exports give the corners instead of the centre and size
        boolean corners = endToEnd || (detectBoxFormat && isCornerFormat(candidates, boxScaleX, boxScaleY));
        if (corners) {
            for (Candidate c : candidates) toCentreSize(c);
        }

        // an NMS-free model has already merged its boxes
        List<Candidate> kept = endToEnd ? candidates
                : suppressOverlaps(candidates, iou, boxScaleX, boxScaleY);

        List<Detection> result = new ArrayList<>(kept.size());
        for (Candidate c : kept) {
            float cx = c.cx * boxScaleX;
            float cy = c.cy * boxScaleY;
            float w = c.w * boxScaleX;
            float h = c.h * boxScaleY;
            RectF inInput = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            RectF box = new RectF(
                    clamp((inInput.left - padX) / scale, 0, frame.getWidth()),
                    clamp((inInput.top - padY) / scale, 0, frame.getHeight()),
                    clamp((inInput.right - padX) / scale, 0, frame.getWidth()),
                    clamp((inInput.bottom - padY) / scale, 0, frame.getHeight()));
            if (box.width() < 1f || box.height() < 1f) continue;
            RectF maskBox = new RectF();
            Bitmap mask = withMasks && hasMasks() ? buildMask(c, inInput, maskBox) : null;
            if (mask != null) {
                // the mask rectangle is in input pixels: move it into frame pixels like the box
                maskBox.set((maskBox.left - padX) / scale, (maskBox.top - padY) / scale,
                        (maskBox.right - padX) / scale, (maskBox.bottom - padY) / scale);
            }
            result.add(new Detection(box, c.classId, c.score, mask, mask == null ? null : maskBox));
        }
        return result;
    }

    private void writeInput() {
        inputImage.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        inputBuffer.rewind();
        if (inputChannelsFirst) {
            // planar [1, 3, H, W]: every red, then every green, then every blue
            for (int channel = 0; channel < 3; channel++) {
                int shift = 16 - channel * 8;
                for (int pixel : pixels) writeChannel(((pixel >> shift) & 0xFF) / 255f);
            }
        } else {
            for (int pixel : pixels) {
                writeChannel(((pixel >> 16) & 0xFF) / 255f);
                writeChannel(((pixel >> 8) & 0xFF) / 255f);
                writeChannel((pixel & 0xFF) / 255f);
            }
        }
    }

    private void writeChannel(float value) {
        switch (inputType) {
            case FLOAT32:
                inputBuffer.putFloat(value);
                break;
            case UINT8:
                inputBuffer.put((byte) clampInt(Math.round(value / inputScale) + inputZeroPoint, 0, 255));
                break;
            case INT8:
                inputBuffer.put((byte) clampInt(Math.round(value / inputScale) + inputZeroPoint, -128, 127));
                break;
            default:
                throw new IllegalStateException("Unsupported input type " + inputType);
        }
    }

    /**
     * Whether the box head gives corners (x1, y1, x2, y2) rather than a centre and a size. Both
     * are four numbers, so the only way to tell them apart is which reading yields boxes that fit
     * the image; decided on the candidates of the current frame.
     */
    private boolean isCornerFormat(List<Candidate> candidates, float scaleX, float scaleY) {
        final float margin = 8f;
        int centre = 0;
        int corner = 0;
        for (Candidate c : candidates) {
            float a = c.cx * scaleX;
            float b = c.cy * scaleY;
            float x = c.w * scaleX;
            float y = c.h * scaleY;
            if (x > 0 && y > 0 && a - x / 2 >= -margin && b - y / 2 >= -margin
                    && a + x / 2 <= inputWidth + margin && b + y / 2 <= inputHeight + margin) {
                centre++;
            }
            if (x > a && y > b && a >= -margin && b >= -margin
                    && x <= inputWidth + margin && y <= inputHeight + margin) {
                corner++;
            }
        }
        return corner > centre;
    }

    /** Rewrites a corner box (x1, y1, x2, y2) as centre and size. */
    private static void toCentreSize(Candidate c) {
        float left = c.cx;
        float top = c.cy;
        float right = c.w;
        float bottom = c.h;
        c.cx = (left + right) / 2f;
        c.cy = (top + bottom) / 2f;
        c.w = right - left;
        c.h = bottom - top;
    }

    /** Anchors whose best class beats the threshold. */
    private List<Candidate> collectCandidates(float confidence) {
        List<Candidate> candidates = new ArrayList<>();
        if (endToEnd) return collectFinalDetections(confidence, candidates);
        for (int a = 0; a < anchors; a++) {
            int bestClass = -1;
            float bestScore = confidence;
            for (int c = 0; c < numClasses; c++) {
                float score = scoreHead.get(scoreOffset + c, a);
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0) continue;
            Candidate candidate = new Candidate();
            candidate.cx = boxHead.get(boxOffset, a);
            candidate.cy = boxHead.get(boxOffset + 1, a);
            candidate.w = boxHead.get(boxOffset + 2, a);
            candidate.h = boxHead.get(boxOffset + 3, a);
            candidate.score = bestScore;
            candidate.classId = bestClass;
            candidate.anchor = a;
            candidates.add(candidate);
        }
        return candidates;
    }

    /**
     * Rows of an NMS-free model: x1, y1, x2, y2, score, class. They come out sorted by score and
     * padded with zero-score rows, so the first row below the threshold ends the list.
     */
    private List<Candidate> collectFinalDetections(float confidence, List<Candidate> candidates) {
        for (int a = 0; a < anchors && candidates.size() < MAX_DETECTIONS; a++) {
            float score = boxHead.get(scoreOffset, a);
            if (score < confidence) break;
            Candidate candidate = new Candidate();
            candidate.cx = boxHead.get(boxOffset, a);       // x1, converted below
            candidate.cy = boxHead.get(boxOffset + 1, a);   // y1
            candidate.w = boxHead.get(boxOffset + 2, a);    // x2
            candidate.h = boxHead.get(boxOffset + 3, a);    // y2
            candidate.score = score;
            candidate.classId = Math.round(boxHead.get(scoreOffset + 1, a));
            candidate.anchor = a;
            candidates.add(candidate);
        }
        return candidates;
    }

    /** Per-class non-maximum suppression, strongest detection first. */
    private static List<Candidate> suppressOverlaps(List<Candidate> candidates, float iouThreshold,
                                                    float scaleX, float scaleY) {
        Collections.sort(candidates, (a, b) -> Float.compare(b.score, a.score));
        List<Candidate> kept = new ArrayList<>();
        boolean[] removed = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size() && kept.size() < MAX_DETECTIONS; i++) {
            if (removed[i]) continue;
            Candidate a = candidates.get(i);
            kept.add(a);
            for (int j = i + 1; j < candidates.size(); j++) {
                Candidate b = candidates.get(j);
                if (removed[j] || b.classId != a.classId) continue;
                if (iou(a, b, scaleX, scaleY) > iouThreshold) removed[j] = true;
            }
        }
        return kept;
    }

    private static float iou(Candidate a, Candidate b, float scaleX, float scaleY) {
        float aw = a.w * scaleX / 2, ah = a.h * scaleY / 2;
        float bw = b.w * scaleX / 2, bh = b.h * scaleY / 2;
        float ax = a.cx * scaleX, ay = a.cy * scaleY;
        float bx = b.cx * scaleX, by = b.cy * scaleY;
        float left = Math.max(ax - aw, bx - bw);
        float top = Math.max(ay - ah, by - bh);
        float right = Math.min(ax + aw, bx + bw);
        float bottom = Math.min(ay + ah, by + bh);
        if (right <= left || bottom <= top) return 0f;
        float intersection = (right - left) * (bottom - top);
        float union = 4 * aw * ah + 4 * bw * bh - intersection;
        return union <= 0 ? 0f : intersection / union;
    }

    /**
     * Instance mask: the prototype planes combined with this detection's coefficients, cropped to
     * the box. Returned at prototype resolution (a quarter of the input), which is what the
     * overlay stretches over the box.
     */
    private Bitmap buildMask(Candidate candidate, RectF boxInInput, RectF maskBoxInInput) {
        for (int k = 0; k < coefficients; k++) {
            coefficientBuffer[k] = coefficientHead.get(coefficientOffset + k, candidate.anchor);
        }
        float toProtoX = protoWidth / (float) inputWidth;
        float toProtoY = protoHeight / (float) inputHeight;
        int left = clampInt((int) Math.floor(boxInInput.left * toProtoX), 0, protoWidth - 1);
        int top = clampInt((int) Math.floor(boxInInput.top * toProtoY), 0, protoHeight - 1);
        int right = clampInt((int) Math.ceil(boxInInput.right * toProtoX), left + 1, protoWidth);
        int bottom = clampInt((int) Math.ceil(boxInInput.bottom * toProtoY), top + 1, protoHeight);

        maskBoxInInput.set(left / toProtoX, top / toProtoY, right / toProtoX, bottom / toProtoY);

        int width = right - left;
        int height = bottom - top;
        Bitmap mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        int stride = mask.getRowBytes();
        ByteBuffer alpha = ByteBuffer.allocate(mask.getByteCount());
        for (int y = top; y < bottom; y++) {
            alpha.position((y - top) * stride);
            for (int x = left; x < right; x++) {
                float sum = 0f;
                for (int k = 0; k < coefficients; k++) sum += coefficientBuffer[k] * prototype(y, x, k);
                float value = 1f / (1f + (float) Math.exp(-sum));
                // a short ramp above the threshold keeps the mask edge from looking jagged
                float opacity = clamp((value - MASK_THRESHOLD) * 8f, 0f, 1f);
                alpha.put((byte) Math.round(opacity * 255f));
            }
        }
        alpha.rewind();
        mask.copyPixelsFromBuffer(alpha);
        return mask;
    }

    private float prototype(int y, int x, int k) {
        return proto.get(protoChannelsFirst ? (k * protoHeight + y) * protoWidth + x
                : (y * protoWidth + x) * coefficients + k);
    }

    /** Detector models available: {@code *.tflite} in assets and in the models folder. */
    public static List<String> listModels(Context context) {
        List<String> names = new ArrayList<>();
        addAssetModels(context, "", names);       // models sitting in assets/
        addAssetModels(context, "models", names); // or tidied into assets/models/
        File[] files = getModelDir(context).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".tflite") && !names.contains(file.getName())) {
                    names.add(file.getName());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Adds the detector models in one assets folder. {@code list} also returns the folders the
     * build tools put there ("images", "webkit"), which simply do not match, and it throws on
     * some devices when the folder is not there at all.
     */
    private static void addAssetModels(Context context, String folder, List<String> names) {
        try {
            String[] assets = context.getAssets().list(folder);
            if (assets == null) return;
            for (String asset : assets) {
                if (asset == null || !asset.endsWith(".tflite") || asset.equals(FACE_MODEL)) continue;
                names.add(folder.isEmpty() ? asset : folder + "/" + asset);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not list the assets in '" + folder + "'", e);
        }
    }

    /** Same folder the speech models use, so everything can be pushed to one place. */
    public static File getModelDir(Context context) {
        File external = context.getExternalFilesDir(null);
        File dir = new File(external != null ? external : context.getFilesDir(), "models");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Reads the model from the models folder if it is there, otherwise from assets. */
    private static ByteBuffer loadModel(Context context, String modelName) throws IOException {
        File file = new File(getModelDir(context), modelName);
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                return readFully(in);
            }
        }
        try (InputStream in = context.getAssets().open(modelName)) {
            return readFully(in);
        }
    }

    private static ByteBuffer readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int read;
        while ((read = in.read(chunk)) > 0) bytes.write(chunk, 0, read);
        byte[] data = bytes.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        buffer.put(data);
        buffer.rewind();
        return buffer;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        interpreter.close();
        inputImage.recycle();
    }
}
