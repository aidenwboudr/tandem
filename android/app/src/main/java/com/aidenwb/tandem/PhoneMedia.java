package com.aidenwb.tandem;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone half of the laptop's mixer: lists the phone's media sessions and applies the
 * laptop's play/pause/volume commands. Android has no per-app volume without root, so local
 * players share one media volume; only remote (cast) sessions have their own.
 */
final class PhoneMedia {
    private static final int MAX_SESSIONS = 6;
    private final Context ctx;
    /** Album art the laptop can't fetch itself (no http URI): key -> base64 JPEG, sent on change. */
    private final Map<String, String> art = new HashMap<>();
    private final Map<String, Long> artSentAt = new HashMap<>();

    PhoneMedia(Context ctx) {
        this.ctx = ctx;
    }

    /** The phone's active media sessions, or null without notification-listener access. */
    static List<MediaController> controllers(Context ctx) {
        try {
            MediaSessionManager m = ctx.getSystemService(MediaSessionManager.class);
            return m.getActiveSessions(new ComponentName(ctx, MediaListener.class));
        } catch (SecurityException e) { // notification-listener access not granted
            return null;
        }
    }

    /** Adds "media" (sessions) and "volume" (phone media volume, 0..1) to a heartbeat. */
    void describe(JSONObject hb) throws JSONException {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        hb.put("volume", max > 0 ? (double) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0);
        List<MediaController> list = controllers(ctx);
        if (list == null) {
            hb.put("media_access", false);
            return;
        }
        JSONArray out = new JSONArray();
        for (MediaController c : list) {
            String pkg = c.getPackageName();
            if (pkg.equals(ctx.getPackageName())) continue;
            PlaybackState ps = c.getPlaybackState();
            MediaMetadata md = c.getMetadata();
            JSONObject o = new JSONObject()
                    .put("id", pkg)
                    .put("app", label(pkg))
                    .put("title", clip(md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE)))
                    .put("artist", clip(md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST)))
                    .put("playing", ps != null && ps.getState() == PlaybackState.STATE_PLAYING);
            if (md != null) {
                long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (dur > 0) o.put("dur", dur);
                artInfo(pkg, md, o);
            }
            if (ps != null && ps.getPosition() >= 0) {
                long pos = ps.getPosition();
                if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
                o.put("pos", pos);
            }
            MediaController.PlaybackInfo pi = c.getPlaybackInfo();
            if (pi != null && pi.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
                    && pi.getMaxVolume() > 0) {
                o.put("volume", (double) pi.getCurrentVolume() / pi.getMaxVolume());
            }
            out.put(o);
            if (out.length() >= MAX_SESSIONS) break;
        }
        hb.put("media", out);
    }

    /** {"t":"cmd","op":"play"|"pause"|"toggle"|"next"|"previous"|"volume", "id":pkg?, "value":0..1?} */
    void apply(JSONObject cmd) {
        String op = cmd.optString("op");
        String id = cmd.optString("id", "");
        if (op.equals("volume") && id.isEmpty()) {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) Math.round(clamp(cmd.optDouble("value", 0)) * max), 0);
            return;
        }
        List<MediaController> list = controllers(ctx);
        for (MediaController c : list == null ? Collections.<MediaController>emptyList() : list) {
            if (!c.getPackageName().equals(id)) continue;
            MediaController.TransportControls tc = c.getTransportControls();
            PlaybackState ps = c.getPlaybackState();
            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            switch (op) {
                case "toggle":
                    if (playing) tc.pause(); else tc.play();
                    break;
                case "play": tc.play(); break;
                case "pause": tc.pause(); break;
                case "next": tc.skipToNext(); break;
                case "previous": tc.skipToPrevious(); break;
                case "volume":
                    MediaController.PlaybackInfo pi = c.getPlaybackInfo();
                    if (pi != null) c.setVolumeTo((int) Math.round(clamp(cmd.optDouble("value", 0)) * pi.getMaxVolume()), 0);
                    break;
                default:
            }
            return;
        }
    }

    /** "art": an http(s) URI the laptop downloads, else "art_key" naming a JPEG sent by artPackets(). */
    private void artInfo(String pkg, MediaMetadata md, JSONObject o) throws JSONException {
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String u = md.getString(k);
            if (u != null && (u.startsWith("https://") || u.startsWith("http://"))) {
                o.put("art", u);
                return;
            }
        }
        String key = pkg + "|" + md.getString(MediaMetadata.METADATA_KEY_ALBUM) + "|"
                + md.getString(MediaMetadata.METADATA_KEY_TITLE);
        key = Integer.toHexString(key.hashCode());
        if (!art.containsKey(key)) {
            Bitmap bm = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bm == null) bm = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bm == null) bm = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (bm == null) return;
            Bitmap small = Bitmap.createScaledBitmap(bm, 96, 96, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 82, out);
            if (art.size() > 8) art.clear();
            art.put(key, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
        }
        o.put("art_key", key);
    }

    /** Art packets to send now: new art at once, then a refresh every 15 s (UDP may drop one). */
    List<JSONObject> artPackets(JSONObject hb) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        JSONArray media = hb.optJSONArray("media");
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; media != null && i < media.length(); i++) {
            String key = media.getJSONObject(i).optString("art_key", "");
            String b64 = art.get(key);
            if (b64 == null) continue;
            Long last = artSentAt.get(key);
            if (last != null && now - last < 15_000) continue;
            artSentAt.put(key, now);
            out.add(new JSONObject().put("t", "art").put("key", key).put("jpeg", b64));
        }
        return out;
    }

    private String label(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 59) + "…" : s;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
