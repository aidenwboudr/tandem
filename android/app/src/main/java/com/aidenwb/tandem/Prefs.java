package com.aidenwb.tandem;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.Locale;

/** This phone's own settings (not shared with the computer; those are in {@link Settings}). */
final class Prefs {
    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("tandem", Context.MODE_PRIVATE);
    }

    /** Which Bluetooth audio devices count as "the headphones": a name filter, empty = any. */
    static String match(Context c) { return get(c).getString("match", ""); }
    static String codec(Context c) { return get(c).getString("codec", "opus"); }

    /** Settings from before 3.0 (when they were all local) move to the shared ones once. */
    static void migrate(Context c) {
        SharedPreferences p = get(c);
        if (p.getBoolean("migrated3", false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean("migrated3", true);
        if (p.contains("play_opens_spotify")) Settings.set(c, "play_opens_app", p.getBoolean("play_opens_spotify", false));
        if (p.contains("clip_to_laptop")) Settings.set(c, "clip_to_laptop", p.getBoolean("clip_to_laptop", true));
        if (p.contains("clip_from_laptop")) Settings.set(c, "clip_from_laptop", p.getBoolean("clip_from_laptop", true));
        e.remove("laptop").remove("port").remove("play_opens_spotify").remove("clip_to_laptop").remove("clip_from_laptop");
        e.apply();
    }

    static boolean matches(Context c, CharSequence name) {
        String m = match(c).trim().toLowerCase(Locale.ROOT);
        return name != null && (m.isEmpty() || name.toString().toLowerCase(Locale.ROOT).contains(m));
    }

    /** Whether a Bluetooth audio device counts as the headphones. The paired computer never does (while
     *  it's the hub, the phone's audio goes to it as a Bluetooth speaker). */
    static boolean isHeadphones(Context c, String address, CharSequence name) {
        return matches(c, name) && (address == null || !address.equalsIgnoreCase(Pairing.bt(c)));
    }

    /** The headphones, if they're connected to this phone as an audio output right now. */
    static AudioDeviceInfo headphones(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        String shared = get(c).getString("shared_hp", ""); // the ones the computer shares, if it said
        AudioDeviceInfo any = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            boolean bt = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || t == AudioDeviceInfo.TYPE_BLE_SPEAKER;
            if (!bt) continue;
            if (d.getAddress().equalsIgnoreCase(shared)) return d;
            if (any == null && isHeadphones(c, d.getAddress(), d.getProductName())) any = d;
        }
        return any;
    }
}
