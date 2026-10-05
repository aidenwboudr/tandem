package com.aidenwb.tandem;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notification access. Being an enabled listener lets the app read and control the phone's media sessions
 * (for the computer's mixer), and it's how phone notifications reach the computer (notif_mirror), how
 * one-time codes go to its clipboard (otp_copy), and how a caller's name is known (calls).
 */
public class MediaListener extends NotificationListenerService {
    private static final String TAG = "Tandem";
    private static volatile MediaListener active;
    private static final Set<String> iconsSent = new HashSet<>();
    private static final Map<String, String> otpSent = new ConcurrentHashMap<>();
    // "123456 is your code", "Your verification code: 4821-77", "G-123456"...
    private static final Pattern OTP_CONTEXT = Pattern.compile(
            "(code|otp|passcode|password|verification|verify|login|log in|sign in|2fa|pin|token|security|"
                    + "código|codigo|code de|bestätigung|verifica)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTP = Pattern.compile("(?<![\\d\\w])(?:G-)?(\\d{3}[- ]?\\d{3}|\\d{4,8})(?![\\d\\w])");

    @Override
    public void onListenerConnected() {
        active = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (active == this) active = null;
    }

    static boolean connected() {
        return active != null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Context ctx = getApplicationContext();
        Notification n = sbn.getNotification();
        if (sbn.getPackageName().equals(getPackageName())) return;
        Bundle ex = n.extras;
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence text = big != null ? big : ex.getCharSequence(Notification.EXTRA_TEXT);
        if (Notification.CATEGORY_CALL.equals(n.category) && title != null) Calls.callerName = title.toString();
        if (Settings.on(ctx, "otp_copy")) otp(ctx, sbn, title, text);
        if (!Settings.on(ctx, "notif_mirror")) return;
        if (!worthMirroring(sbn)) {
            Log.d(TAG, "notif: not mirroring " + sbn.getPackageName());
            return;
        }
        Link.get(ctx).handler().post(() -> mirror(ctx, sbn, title, text));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        Context ctx = getApplicationContext();
        if (!Settings.on(ctx, "notif_mirror") || !Settings.on(ctx, "notif_dismiss_sync")) return;
        if (sbn.getPackageName().equals(getPackageName())) return;
        try {
            Link.get(ctx).send(Proto.msg("notif-gone").put("key", sbn.getKey()), null);
        } catch (JSONException ignored) {
        }
    }

    private boolean worthMirroring(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        // Android 14+ drops FLAG_ONGOING_EVENT from foreground-service notifications (users can swipe them),
        // so "app is running" status notifications only show up as FGS / not clearable.
        if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0 || !sbn.isClearable()) return false;
        if (Notification.CATEGORY_CALL.equals(n.category) || Notification.CATEGORY_TRANSPORT.equals(n.category)
                || Notification.CATEGORY_PROGRESS.equals(n.category) || Notification.CATEGORY_SERVICE.equals(n.category)) {
            return false;
        }
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false;
        Ranking r = new Ranking();
        if (getCurrentRanking() != null && getCurrentRanking().getRanking(sbn.getKey(), r)
                && r.getImportance() <= android.app.NotificationManager.IMPORTANCE_MIN) {
            return false;
        }
        String excluded = "," + Settings.str(getApplicationContext(), "notif_excluded").replace(" ", "") + ",";
        return !excluded.contains("," + sbn.getPackageName() + ",");
    }

    private void mirror(Context ctx, StatusBarNotification sbn, CharSequence title, CharSequence text) {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        try {
            if (!iconsSent.contains(pkg)) {
                byte[] png = icon(ctx, pkg);
                if (png != null && Link.get(ctx).send(Proto.msg("notif-icon").put("pkg", pkg), png)) iconsSent.add(pkg);
            }
            JSONArray actions = new JSONArray();
            boolean reply = false;
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) reply = true;
                    else if (a.title != null && actions.length() < 3) actions.put(a.title.toString());
                }
            }
            JSONObject m = Proto.msg("notif").put("key", sbn.getKey()).put("pkg", pkg).put("app", label(ctx, pkg))
                    .put("title", str(title)).put("text", str(text)).put("when", sbn.getPostTime())
                    .put("reply", reply).put("actions", actions);
            boolean sent = Link.get(ctx).send(m, null);
            Log.i(TAG, "notif: " + pkg + (sent ? " -> computer" : " (not connected)"));
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "notif", e);
        }
    }

    private static void otp(Context ctx, StatusBarNotification sbn, CharSequence title, CharSequence text) {
        String all = str(title) + "\n" + str(text);
        if (!OTP_CONTEXT.matcher(all).find()) return;
        Matcher m = OTP.matcher(all);
        if (!m.find()) return;
        String code = m.group(1).replaceAll("[- ]", "");
        if (code.equals(otpSent.get(sbn.getKey()))) return; // the same notification updated
        otpSent.put(sbn.getKey(), code);
        try {
            byte[] data = code.getBytes(StandardCharsets.UTF_8);
            Link.get(ctx).send(Proto.msg("clip").put("mime", "text/plain").put("otp", true)
                    .put("hash", ClipSync.hash("text/plain", data)), data);
            Log.i(TAG, "otp: sent a code from " + sbn.getPackageName());
        } catch (JSONException ignored) {
        }
    }

    /** notif-dismiss / notif-reply / notif-action from the computer. */
    static void onCommand(Context ctx, JSONObject h) {
        MediaListener l = active;
        if (l == null) return;
        String key = h.optString("key");
        StatusBarNotification sbn = null;
        try {
            for (StatusBarNotification s : l.getActiveNotifications()) if (s.getKey().equals(key)) sbn = s;
        } catch (RuntimeException e) {
            return;
        }
        if (sbn == null) return;
        switch (h.optString("t")) {
            case "notif-dismiss":
                l.cancelNotification(key);
                break;
            case "notif-reply":
                reply(ctx, sbn, h.optString("text"));
                break;
            case "notif-action":
                int want = h.optInt("index", -1), i = 0;
                Notification.Action[] as = sbn.getNotification().actions;
                if (as == null) return;
                for (Notification.Action a : as) {
                    if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                    if (i++ == want) {
                        try {
                            a.actionIntent.send();
                        } catch (PendingIntent.CanceledException ignored) {
                        }
                        return;
                    }
                }
                break;
            default:
        }
    }

    private static void reply(Context ctx, StatusBarNotification sbn, String text) {
        if (!Settings.on(ctx, "notif_reply") || text.isEmpty()) return;
        Notification.Action[] as = sbn.getNotification().actions;
        if (as == null) return;
        for (Notification.Action a : as) {
            RemoteInput[] ris = a.getRemoteInputs();
            if (ris == null || ris.length == 0) continue;
            Intent fill = new Intent();
            Bundle b = new Bundle();
            for (RemoteInput ri : ris) b.putCharSequence(ri.getResultKey(), text);
            RemoteInput.addResultsToIntent(ris, fill, b);
            try {
                a.actionIntent.send(ctx, 0, fill);
                Log.i(TAG, "notif: replied from the computer");
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "notif: reply failed", e);
            }
            return;
        }
    }

    private static byte[] icon(Context ctx, String pkg) {
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String label(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString();
    }
}
