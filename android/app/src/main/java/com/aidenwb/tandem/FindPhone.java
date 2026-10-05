package com.aidenwb.tandem;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Find my phone: the computer says "ring" and this plays the alarm sound at full alarm volume (alarms
 * still sound on silent and, by default, through Do Not Disturb) and vibrates, for up to a minute or
 * until stopped from the notification, the app, or the computer.
 */
final class FindPhone {
    private static final String TAG = "Tandem";
    private static final long MAX_MS = 60_000;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private int savedVolume = -1;

    FindPhone(Context ctx) {
        this.ctx = ctx;
    }

    void onRing(JSONObject h) {
        if (h.optBoolean("on", true)) {
            if (Settings.on(ctx, "find_phone")) main.post(this::start);
        } else {
            stop();
        }
    }

    boolean ringing() {
        return player != null;
    }

    private void start() {
        if (player != null) return;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(ctx, sound);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.w(TAG, "ring", e);
        }
        Vibrator v = ctx.getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 400}, 0));
        Notifications.channels(ctx);
        Notification n = new Notification.Builder(ctx, Notifications.CH_RING)
                .setSmallIcon(R.drawable.ic_tandem)
                .setContentTitle("Found it?")
                .setContentText(Pairing.name(ctx) + " is ringing this phone")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(Notifications.action(ctx, ActionReceiver.RING_STOP, 20))
                .setDeleteIntent(Notifications.action(ctx, ActionReceiver.RING_STOP, 21))
                .addAction(new Notification.Action.Builder(null, "Stop", Notifications.action(ctx, ActionReceiver.RING_STOP, 22)).build())
                .build();
        ctx.getSystemService(NotificationManager.class).notify(Notifications.ID_RING, n);
        main.postDelayed(this::stop, MAX_MS);
    }

    void stop() {
        main.post(() -> {
            main.removeCallbacksAndMessages(null);
            if (player != null) {
                try {
                    player.stop();
                } catch (IllegalStateException ignored) {
                }
                player.release();
                player = null;
                if (savedVolume >= 0) {
                    ctx.getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
                }
            }
            Vibrator v = ctx.getSystemService(Vibrator.class);
            if (v != null) v.cancel();
            ctx.getSystemService(NotificationManager.class).cancel(Notifications.ID_RING);
        });
    }

    /** "Find my computer": makes the computer ring. */
    static void ringComputer(Context ctx) {
        boolean ok;
        try {
            ok = Link.get(ctx).send(Proto.msg("ring").put("on", true), null);
        } catch (JSONException e) {
            ok = false;
        }
        String text = ok ? "Ringing " + Pairing.name(ctx) : "Not connected to " + Pairing.name(ctx);
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
    }
}
