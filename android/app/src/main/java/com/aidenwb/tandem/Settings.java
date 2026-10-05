package com.aidenwb.tandem;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The feature settings, shared with the computer (it has the same list in laptop/tandemd/config.py).
 * Whichever side changed them last wins ("rev", ms since epoch). Changing one here sends them over.
 */
final class Settings {
    static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("clip_to_laptop", true);
        DEFAULTS.put("clip_from_laptop", true);
        DEFAULTS.put("clip_secrets", false);
        DEFAULTS.put("audio_share", true);
        DEFAULTS.put("play_opens_app", false);
        DEFAULTS.put("play_app", "com.spotify.music");
        DEFAULTS.put("media_controls", true);
        DEFAULTS.put("notif_mirror", false);
        DEFAULTS.put("notif_excluded", "");
        DEFAULTS.put("notif_dismiss_sync", true);
        DEFAULTS.put("notif_reply", true);
        DEFAULTS.put("otp_copy", false);
        DEFAULTS.put("files", true);
        DEFAULTS.put("open_links", true);
        DEFAULTS.put("calls", false);
        DEFAULTS.put("call_pause_media", true);
        DEFAULTS.put("battery", true);
        DEFAULTS.put("battery_low", 15);
        DEFAULTS.put("find_phone", true);
        DEFAULTS.put("screenshots", false);
        DEFAULTS.put("screenshot_clipboard", true);
        DEFAULTS.put("remote_input", false);
        DEFAULTS.put("lock_on_leave", false);
        DEFAULTS.put("lock_delay", 30);
        DEFAULTS.put("dnd_sync", false);
        DEFAULTS.put("screen_mirror", false);
    }

    interface Listener {
        void onSettingsChanged(List<String> keys);
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Settings() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("features", Context.MODE_PRIVATE);
    }

    static boolean on(Context c, String key) {
        Object d = DEFAULTS.get(key);
        return prefs(c).getBoolean(key, d instanceof Boolean && (Boolean) d);
    }

    static int num(Context c, String key) {
        Object d = DEFAULTS.get(key);
        return prefs(c).getInt(key, d instanceof Integer ? (Integer) d : 0);
    }

    static String str(Context c, String key) {
        Object d = DEFAULTS.get(key);
        return prefs(c).getString(key, d instanceof String ? (String) d : "");
    }

    static long rev(Context c) {
        return prefs(c).getLong("_rev", 0);
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Changes one setting here and tells the computer. */
    static void set(Context c, String key, Object value) {
        SharedPreferences.Editor e = prefs(c).edit();
        put(e, key, value);
        e.putLong("_rev", Math.max(System.currentTimeMillis(), rev(c) + 1)).apply(); // a clock behind still wins
        Link.get(c).send(message(c), null);
        fire(java.util.Collections.singletonList(key));
    }

    private static void put(SharedPreferences.Editor e, String key, Object value) {
        Object d = DEFAULTS.get(key);
        if (d instanceof Boolean) e.putBoolean(key, Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value)));
        else if (d instanceof Integer) {
            try {
                e.putInt(key, value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
            }
        } else e.putString(key, value == null ? "" : String.valueOf(value));
    }

    static JSONObject message(Context c) {
        JSONObject values = new JSONObject();
        try {
            for (Map.Entry<String, Object> en : DEFAULTS.entrySet()) {
                String k = en.getKey();
                Object d = en.getValue();
                values.put(k, d instanceof Boolean ? on(c, k) : d instanceof Integer ? num(c, k) : str(c, k));
            }
            return Proto.msg("settings").put("values", values).put("rev", rev(c));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The computer's settings: taken if they're newer than ours. */
    static void merge(Context c, JSONObject msg) {
        long rev = msg.optLong("rev", 0);
        JSONObject values = msg.optJSONObject("values");
        if (values == null || rev <= rev(c)) return;
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        List<String> changed = new ArrayList<>();
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
            String k = it.next();
            Object d = DEFAULTS.get(k);
            if (d == null) continue;
            Object v = values.opt(k);
            Object cur = d instanceof Boolean ? (Object) on(c, k) : d instanceof Integer ? (Object) num(c, k) : str(c, k);
            if (!String.valueOf(cur).equals(String.valueOf(v))) {
                put(e, k, v);
                changed.add(k);
            }
        }
        e.putLong("_rev", rev).apply();
        if (!changed.isEmpty()) {
            android.util.Log.i("Tandem", "settings from the computer: " + changed);
            fire(changed);
        }
    }

    private static void fire(List<String> keys) {
        for (Listener l : listeners) {
            try {
                l.onSettingsChanged(keys);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
