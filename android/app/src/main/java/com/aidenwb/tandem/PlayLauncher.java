package com.aidenwb.tandem;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * Makes the headphones' play button work while your music app (Spotify by default; the play_app
 * setting) is closed. With its session gone, Android hands a play press to the app's media button
 * receiver, which usually does nothing. So while the headphones are on this phone and no other app could
 * take the press, this holds a paused media session and becomes the app the buttons go to. A press then
 * chimes in the headphones, opens the music app, and presses play in it once its session shows up. It
 * lets go of the buttons as soon as another player appears. Main thread only.
 */
final class PlayLauncher {
    private static final String TAG = "Tandem";
    private static final long TICK_MS = 1000, POLL_MS = 250, RETRY_MS = 1500, OPEN_TIMEOUT_MS = 20_000;
    private static final int RATE = 48000;
    private static final short[] CHIME = notes(0.22, 659.25, 90, 987.77, 170); // rising E-B: on it
    private static final short[] WAIT = notes(0.10, 1318.5, 40); // soft tick while the app opens
    private static final short[] FAIL = notes(0.22, 659.25, 120, 440, 240); // falling: didn't work
    private static final short[] SILENCE = new short[RATE / 5];

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private MediaSession session;
    private long openingSince; // 0 = not opening Spotify
    private long lastPlayAt, lastWaitAt;

    PlayLauncher(Context ctx) {
        this.ctx = ctx;
    }

    void start() {
        if (started) return;
        started = true;
        main.post(tick);
    }

    void stop() {
        started = false;
        openingSince = 0;
        main.removeCallbacks(tick);
        release();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            long now = SystemClock.elapsedRealtime();
            AudioDeviceInfo hp = Prefs.headphones(ctx);
            if (openingSince > 0) whileOpening(hp, now);
            boolean want = hp != null && Settings.on(ctx, "play_opens_app") && (openingSince > 0 || !otherPlayer());
            if (want && session == null) capture(hp);
            else if (!want && session != null) release();
            main.postDelayed(this, openingSince > 0 ? POLL_MS : TICK_MS);
        }
    };

    /** A headphone play press, from the session or from the app's media button receiver. */
    void press() {
        long now = SystemClock.elapsedRealtime();
        if (openingSince > 0) return; // already on it
        if (now - LinkService.lastLoudAt < 1500) return; // laptop audio is playing: the press was meant for that
        MediaController sp = spotify();
        if (sp != null) { // open after all (we got the press through the receiver): just play
            sp.getTransportControls().play();
            return;
        }
        AudioDeviceInfo hp = Prefs.headphones(ctx);
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(app());
        if (open == null) {
            LinkService.error = app() + " isn't installed";
            tone(hp, FAIL);
            return;
        }
        tone(hp, CHIME);
        Log.i(TAG, "play pressed with " + app() + " closed: opening it");
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            // Android blocks opening another app from the background without this permission.
            LinkService.error = "Allow \"Display over other apps\" so the play button can open your music app";
        }
        try {
            ctx.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "open " + app(), e);
        }
        openingSince = now;
        lastPlayAt = 0;
        lastWaitAt = now;
        main.removeCallbacks(tick);
        main.postDelayed(tick, POLL_MS);
    }

    /** The music app is starting up: press play once its session is there, until it's actually playing. */
    private void whileOpening(AudioDeviceInfo hp, long now) {
        MediaController sp = spotify();
        PlaybackState ps = sp == null ? null : sp.getPlaybackState();
        if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
            Log.i(TAG, app() + " is playing after " + (now - openingSince) + " ms");
            openingSince = 0; // the music is the confirmation
            return;
        }
        if (now - openingSince > OPEN_TIMEOUT_MS) {
            Log.w(TAG, app() + " didn't start playing" + (sp == null ? " (it never opened)" : ""));
            if (sp == null && LinkService.error == null) LinkService.error = "Your music app didn't open";
            openingSince = 0;
            tone(hp, FAIL);
            return;
        }
        if (sp != null && now - lastPlayAt >= RETRY_MS) { // early presses can land before it has loaded a track
            lastPlayAt = now;
            sp.getTransportControls().play();
        }
        if (now - lastWaitAt >= RETRY_MS) {
            lastWaitAt = now;
            tone(hp, WAIT);
        }
    }

    private void capture(AudioDeviceInfo hp) {
        session = new MediaSession(ctx, "tandem-play");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                press();
            }
        }, main);
        // Android remembers this receiver after we let go, so a later press can still reach us.
        session.setMediaButtonBroadcastReceiver(new ComponentName(ctx, BtReceiver.class));
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0, 1f)
                .build());
        session.setActive(true);
        // The buttons go to the app that most recently played audio and has a session, so play a
        // moment of silence to become that app.
        tone(hp, SILENCE);
        Log.i(TAG, "holding the play button (no other player)");
    }

    private void release() {
        if (session == null) return;
        session.release();
        session = null;
        Log.i(TAG, "let go of the play button");
    }

    /** Another app's player that a play press belongs to (playing, paused, buffering...). */
    private boolean otherPlayer() {
        List<MediaController> list = PhoneMedia.controllers(ctx);
        if (list == null) return true; // no media access: can't tell, so stay out of the way
        for (MediaController c : list) {
            if (c.getPackageName().equals(ctx.getPackageName())) continue;
            PlaybackState ps = c.getPlaybackState();
            if (ps == null) continue;
            int st = ps.getState();
            if (st != PlaybackState.STATE_NONE && st != PlaybackState.STATE_STOPPED && st != PlaybackState.STATE_ERROR) {
                return true;
            }
        }
        return false;
    }

    private String app() {
        String a = Settings.str(ctx, "play_app").trim();
        return a.isEmpty() ? "com.spotify.music" : a;
    }

    private MediaController spotify() {
        List<MediaController> list = PhoneMedia.controllers(ctx);
        String app = app();
        if (list != null) for (MediaController c : list) if (c.getPackageName().equals(app)) return c;
        return null;
    }

    /** Plays into the headphones only, never the phone's speaker (it's in a pocket). */
    private void tone(AudioDeviceInfo hp, short[] pcm) {
        if (hp == null) return;
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            t.write(pcm, 0, pcm.length);
            t.setPreferredDevice(hp);
            t.play();
            main.postDelayed(t::release, pcm.length * 1000L / RATE + 500);
        } catch (RuntimeException e) {
            Log.w(TAG, "tone", e);
        }
    }

    /** Sine notes back to back (Hz, ms, Hz, ms...), each with a quick attack and a soft decay. */
    private static short[] notes(double vol, double... hzMs) {
        int lead = RATE * 120 / 1000; // a waking A2DP link can clip the first moment of sound
        int n = lead;
        for (int i = 1; i < hzMs.length; i += 2) n += (int) (RATE * hzMs[i] / 1000);
        short[] out = new short[n];
        int at = lead;
        for (int i = 0; i < hzMs.length; i += 2) {
            int len = (int) (RATE * hzMs[i + 1] / 1000);
            for (int k = 0; k < len; k++) {
                double env = Math.min(1, k / (RATE * 0.004)) * Math.exp(-5.0 * k / len);
                out[at + k] = (short) (Math.sin(2 * Math.PI * hzMs[i] * k / RATE) * env * vol * 32767);
            }
            at += len;
        }
        return out;
    }
}
