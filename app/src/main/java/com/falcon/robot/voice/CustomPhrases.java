package com.falcon.robot.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extra spoken phrases the user adds, mapped to robot actions, stored in SharedPreferences.
 * They are checked before the built-in rules of {@link VoiceCommands}.
 */
public final class CustomPhrases {

    private static final String TAG = "CustomPhrases";
    private static final String PREFS = "voice_phrases";
    private static final String KEY = "phrases";

    public static final class Phrase {
        public final String text;
        public final VoiceCommands.Action action;

        Phrase(String text, VoiceCommands.Action action) {
            this.text = text;
            this.action = action;
        }
    }

    private final SharedPreferences prefs;
    private final List<Phrase> phrases = new ArrayList<>();

    public CustomPhrases(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public List<Phrase> getAll() {
        return new ArrayList<>(phrases);
    }

    public void add(String text, VoiceCommands.Action action) {
        phrases.add(new Phrase(text, action));
        save();
    }

    public void remove(Phrase phrase) {
        phrases.remove(phrase);
        save();
    }

    /** Action for a transcript that contains one of the phrases, or null. */
    public VoiceCommands.Action match(String transcript) {
        String text = transcript.toLowerCase(Locale.US);
        for (Phrase phrase : phrases) {
            if (text.contains(phrase.text.toLowerCase(Locale.US))) return phrase.action;
        }
        return null;
    }

    private void load() {
        String raw = prefs.getString(KEY, null);
        if (raw == null) return;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                VoiceCommands.Action action = VoiceCommands.actionByName(o.getString("action"));
                if (action != null) phrases.add(new Phrase(o.getString("text"), action));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not read custom phrases", e);
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (Phrase p : phrases) {
                array.put(new JSONObject().put("text", p.text).put("action", p.action.name));
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Could not save custom phrases", e);
        }
    }
}
