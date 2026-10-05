package com.aidenwb.tandem;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tells the computer when a call rings, is answered, and ends (so it can show it and pause its media),
 * and takes "silence" and "decline" from it. The caller's name comes from the dialer's own notification
 * (MediaListener), so no contacts permission is needed.
 */
final class Calls {
    private static final String TAG = "Tandem";
    private final Context ctx;
    private TelephonyCallback callback;
    private boolean muted;
    static volatile String callerName; // set by MediaListener from the dialer's notification

    Calls(Context ctx) {
        this.ctx = ctx;
    }

    static boolean canWatch(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canReject(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
    }

    private final class Cb extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            String s = state == TelephonyManager.CALL_STATE_RINGING ? "ringing"
                    : state == TelephonyManager.CALL_STATE_OFFHOOK ? "offhook" : "idle";
            Link.get(ctx).handler().postDelayed(() -> report(s), "ringing".equals(s) ? 600 : 0); // let the name arrive
        }
    }

    void start() {
        if (callback != null || !canWatch(ctx)) return;
        try {
            callback = new Cb();
            ctx.getSystemService(TelephonyManager.class).registerTelephonyCallback(ctx.getMainExecutor(), callback);
        } catch (SecurityException e) {
            callback = null;
        }
    }

    void stop() {
        if (callback == null) return;
        ctx.getSystemService(TelephonyManager.class).unregisterTelephonyCallback(callback);
        callback = null;
    }

    private void report(String state) {
        if ("idle".equals(state) && muted) {
            ctx.getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0);
            muted = false;
        }
        if (!Settings.on(ctx, "calls") && !Settings.on(ctx, "call_pause_media")) return;
        try {
            JSONObject m = Proto.msg("call").put("state", state).put("can_reject", canReject(ctx));
            if (Settings.on(ctx, "calls")) m.put("name", callerName != null ? callerName : "");
            Link.get(ctx).send(m, null);
        } catch (JSONException ignored) {
        }
        if (!"ringing".equals(state)) callerName = null;
    }

    void onCommand(JSONObject h) {
        String op = h.optString("op");
        if ("mute".equals(op)) {
            ctx.getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0);
            muted = true;
        } else if ("reject".equals(op) && canReject(ctx)) {
            try {
                //noinspection deprecation (still the only way for a non-dialer app)
                ctx.getSystemService(TelecomManager.class).endCall();
            } catch (SecurityException e) {
                Log.w(TAG, "calls: can't decline", e);
            }
        }
    }
}
