package com.falcon.robot;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * App display language.
 *
 * <p>The chosen language is stored on the device and applied in
 * {@link BaseActivity#attachBaseContext}, so it works on every Android version. The default is
 * {@link #SYSTEM}, which leaves the system language in charge — including the per-app language
 * Android 13 and newer offer in the system settings (see {@code res/xml/locales_config.xml}).
 */
public final class LocaleHelper {

    /** Follow the system language. */
    public static final String SYSTEM = "";

    /** Supported languages, in the order the settings dialog lists them. */
    public static final String[] LANGUAGES = {SYSTEM, "en", "zh", "ja"};

    private static final String PREFS = "settings";
    private static final String KEY_LANGUAGE = "language";

    private LocaleHelper() {
    }

    public static String getLanguage(Context context) {
        return prefs(context).getString(KEY_LANGUAGE, SYSTEM);
    }

    public static void setLanguage(Context context, String language) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply();
    }

    /** Label for the language picker; the languages name themselves, the default is translated. */
    public static int labelOf(String language) {
        switch (language) {
            case "en":
                return R.string.language_english;
            case "zh":
                return R.string.language_chinese;
            case "ja":
                return R.string.language_japanese;
            default:
                return R.string.language_system;
        }
    }

    /** Index of the saved language in {@link #LANGUAGES}, for the picker. */
    public static int indexOf(String language) {
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(language)) return i;
        }
        return 0;
    }

    /**
     * The language actually in use ("en", "zh", "ja", …): the chosen one, or the system language
     * when the app follows the system.
     */
    public static String effectiveLanguage(Context context) {
        String language = getLanguage(context);
        if (!SYSTEM.equals(language)) return language;
        Configuration configuration = context.getResources().getConfiguration();
        Locale locale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? configuration.getLocales().get(0) : configuration.locale;
        return locale == null ? "en" : locale.getLanguage();
    }

    /** The context an activity should run with: the base context in the chosen language. */
    public static Context wrap(Context base) {
        String language = getLanguage(base);
        if (SYSTEM.equals(language)) return base;
        Locale locale = new Locale(language);
        Locale.setDefault(locale); // so dates and numbers formatted in code follow too
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
        } else {
            configuration.locale = locale;
        }
        return base.createConfigurationContext(configuration);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
