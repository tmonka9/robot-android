package com.falcon.robot.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registered faces, stored on the device: {@code files/faces/faces.json} (records with their
 * embeddings) and {@code files/faces/<id>.jpg} (face crops). Thread-safe.
 */
public final class FaceDatabase {

    private static final String TAG = "FaceDatabase";

    public static final class Record {
        public final String id;
        public final String name;
        public final String department;
        public final String position;
        public final float[] embedding;
        public final long createdAt;
        public volatile boolean active;

        Record(String id, String name, String department, String position, float[] embedding,
               long createdAt, boolean active) {
            this.id = id;
            this.name = name;
            this.department = department;
            this.position = position;
            this.embedding = embedding;
            this.createdAt = createdAt;
            this.active = active;
        }
    }

    public static final class Match {
        public final Record record;
        public final float similarity; // percent, see FaceMath#toPercent

        Match(Record record, float similarity) {
            this.record = record;
            this.similarity = similarity;
        }
    }

    private final File dir;
    private final File file;
    private final List<Record> records = new ArrayList<>();

    public FaceDatabase(Context context) {
        dir = new File(context.getFilesDir(), "faces");
        file = new File(dir, "faces.json");
        load();
    }

    public synchronized List<Record> getAll() {
        return new ArrayList<>(records);
    }

    public synchronized int size() {
        return records.size();
    }

    /** Best match among active records (null if the database has no usable entry). */
    public synchronized Match findBest(float[] embedding) {
        Record best = null;
        float bestCosine = -2f;
        for (Record r : records) {
            if (!r.active || r.embedding.length != embedding.length) continue;
            float c = FaceMath.cosine(embedding, r.embedding);
            if (c > bestCosine) {
                bestCosine = c;
                best = r;
            }
        }
        return best == null ? null : new Match(best, FaceMath.toPercent(bestCosine));
    }

    public synchronized Record add(String name, String department, String position, float[] embedding, Bitmap photo) {
        String id = nextId();
        Record record = new Record(id, name, department, position, embedding, System.currentTimeMillis(), true);
        records.add(record);
        if (photo != null) savePhoto(id, photo);
        save();
        return record;
    }

    public synchronized void remove(Record record) {
        records.remove(record);
        //noinspection ResultOfMethodCallIgnored
        photoFile(record.id).delete();
        save();
    }

    public synchronized void setActive(Record record, boolean active) {
        record.active = active;
        save();
    }

    public Bitmap loadPhoto(Record record) {
        File f = photoFile(record.id);
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    private String nextId() {
        int max = 0;
        for (Record r : records) {
            try {
                max = Math.max(max, Integer.parseInt(r.id));
            } catch (NumberFormatException ignored) {
                // non-numeric ids are skipped
            }
        }
        return String.format(Locale.US, "%05d", max + 1);
    }

    private File photoFile(String id) {
        return new File(dir, id + ".jpg");
    }

    private void savePhoto(String id, Bitmap photo) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (OutputStream out = new FileOutputStream(photoFile(id))) {
            photo.compress(Bitmap.CompressFormat.JPEG, 92, out);
        } catch (IOException e) {
            Log.w(TAG, "Could not save photo for " + id, e);
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONArray array = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                JSONArray e = o.getJSONArray("embedding");
                float[] embedding = new float[e.length()];
                for (int j = 0; j < embedding.length; j++) embedding[j] = (float) e.getDouble(j);
                records.add(new Record(o.getString("id"), o.getString("name"), o.optString("department"),
                        o.optString("position"), embedding, o.optLong("createdAt"), o.optBoolean("active", true)));
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read " + file, e);
        }
    }

    private void save() {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try {
            JSONArray array = new JSONArray();
            for (Record r : records) {
                JSONArray e = new JSONArray();
                for (float v : r.embedding) e.put((double) v);
                array.put(new JSONObject()
                        .put("id", r.id)
                        .put("name", r.name)
                        .put("department", r.department)
                        .put("position", r.position)
                        .put("createdAt", r.createdAt)
                        .put("active", r.active)
                        .put("embedding", e));
            }
            File tmp = new File(dir, "faces.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not write " + file, e);
        }
    }
}
