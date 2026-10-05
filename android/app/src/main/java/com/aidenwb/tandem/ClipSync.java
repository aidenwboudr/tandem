package com.aidenwb.tandem;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * The phone's half of the shared clipboard (the computer's half is laptop/tandemd/clipboard.py).
 *
 * Computer -> phone: a "clip" message over the link goes straight onto the clipboard. Writing the
 * clipboard is allowed from the background.
 *
 * Phone -> laptop: Android only lets the focused app (or the keyboard) read the clipboard. So:
 *  - automatic: Android logs "Denying clipboard access to <us>" every time something is copied, because
 *    this registers a clipboard listener it isn't allowed to call. With READ_LOGS (granted once over adb)
 *    and the "device logs" prompt allowed (it only shows while the app is open), a logcat reader sees
 *    that line, and {@link ClipGrabActivity} takes focus for a moment to read the clip.
 *  - by hand: the notification's "Send clipboard" button, or Share -> Computer clipboard.
 */
final class ClipSync {
    private static final String TAG = "Tandem";
    static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final long RETRY_MS = 30_000, TTL_MS = 15 * 60_000;
    /** After writing a clip ourselves, the listener denial it causes isn't a new copy. */
    private static final long OWN_WRITE_QUIET_MS = 2500;
    private static final String DENIED = "Denying clipboard access to ";

    private static ClipSync instance;

    // Read by MainActivity and the notification.
    static volatile long lastToLaptopAt, lastFromLaptopAt, watchSeenAt;
    static volatile int toLaptop, fromLaptop;
    static volatile String lastError;
    static volatile boolean watching; // a logcat reader is running...
    static volatile boolean approved; // ...and sees the system's lines, so "device logs" access was allowed
    private long watchStartedAt;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private volatile boolean stop;
    private volatile String lastHash;
    private volatile long ownWriteAt;
    private Pending pending;
    private Thread sender;
    private Process logcat;
    private final ClipboardManager.OnPrimaryClipChangedListener listener = this::onClipChanged;

    private static final class Pending {
        final String mime;
        final byte[] data;
        final String hash;
        final long since = SystemClock.elapsedRealtime();
        boolean failed;

        final boolean manual;

        Pending(String mime, byte[] data, String hash, boolean manual) {
            this.manual = manual;
            this.mime = mime;
            this.data = data;
            this.hash = hash;
        }
    }

    private ClipSync(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static synchronized ClipSync get(Context ctx) {
        if (instance == null) instance = new ClipSync(ctx);
        return instance;
    }

    synchronized void start() {
        if (sender != null) return;
        stop = false;
        // Never called while we're in the background; it's here so Android logs a denial for each copy.
        ctx.getSystemService(ClipboardManager.class).addPrimaryClipChangedListener(listener);
        sender = new Thread(this::sendLoop, "tandem-clip-sender");
        sender.start();
    }

    synchronized void stop() {
        stop = true;
        ctx.getSystemService(ClipboardManager.class).removePrimaryClipChangedListener(listener);
        if (sender != null) sender.interrupt();
        sender = null;
        stopWatch();
    }

    /** The link came up: hand over a copy that was waiting. */
    void retryNow() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    static boolean canWatch(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------ phone -> laptop: noticing copies

    /**
     * Starts the logcat reader. Call it while the app is in the foreground: Android asks "allow access to
     * all device logs?" only then, and silently limits a reader started from the background to the app's
     * own lines (which don't include the denial; system_server logs it).
     */
    synchronized void startWatch() {
        if (!canWatch(ctx) || !Settings.on(ctx, "clip_to_laptop")) return;
        long now = SystemClock.elapsedRealtime();
        // A reader that hasn't proved it can see system lines was probably refused (the prompt was dismissed,
        // or it started in the background). Ask again, but not on every resume while the prompt is up.
        if (logcat != null && logcat.isAlive() && (approved || now - watchStartedAt < 10_000)) return;
        stopWatch();
        try {
            // ActivityTaskManager logs every activity start; seeing one proves the system's lines reach us.
            logcat = new ProcessBuilder("logcat", "-v", "brief", "-T", "1",
                    "ClipboardService:E", "ActivityTaskManager:I", "*:S").redirectErrorStream(true).start();
        } catch (IOException e) {
            lastError = "logcat: " + e.getMessage();
            return;
        }
        watching = true;
        approved = false;
        watchStartedAt = now;
        Process p = logcat;
        Thread t = new Thread(() -> readLogcat(p), "tandem-clip-watch");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "clipboard: watching for copies");
    }

    private synchronized void stopWatch() {
        if (logcat != null) logcat.destroy();
        logcat = null;
        watching = false;
        approved = false;
    }

    private void readLogcat(Process p) {
        long started = SystemClock.elapsedRealtime();
        String needle = DENIED + ctx.getPackageName() + ",";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                long now = SystemClock.elapsedRealtime();
                if (now - started < 1500) continue; // -T 1 replays an old line
                if (!approved && line.contains("ActivityTaskManager")) {
                    approved = true;
                    Log.i(TAG, "clipboard: device logs allowed; every copy goes to the laptop");
                }
                if (!line.contains(needle)) continue;
                approved = true;
                if (now - watchSeenAt < 1000) continue; // one copy can log more than one denial
                watchSeenAt = now;
                if (now - ownWriteAt < OWN_WRITE_QUIET_MS || !Settings.on(ctx, "clip_to_laptop")) continue;
                main.post(() -> ClipGrabActivity.grab(ctx));
            }
        } catch (IOException ignored) {
        }
        synchronized (this) {
            if (logcat == p) {
                watching = false;
                approved = false;
                Log.w(TAG, "clipboard: logcat ended; open Tandem to watch again");
            }
        }
    }

    /** Only fires while one of our windows has focus (ClipGrabActivity, MainActivity). */
    private void onClipChanged() {
        if (SystemClock.elapsedRealtime() - ownWriteAt < OWN_WRITE_QUIET_MS) return;
        if (ClipGrabActivity.active) return; // it reads the clip itself
        if (Settings.on(ctx, "clip_to_laptop")) offerCurrent(false);
    }

    /** Reads the clipboard (the caller has focus) and queues it for the laptop. */
    /** manual: sent by hand (button, share), so it goes even with automatic copying off. */
    void offerCurrent(boolean manual) {
        ClipData clip;
        try {
            clip = ctx.getSystemService(ClipboardManager.class).getPrimaryClip();
        } catch (SecurityException e) {
            return;
        }
        if (clip == null || clip.getItemCount() == 0) return;
        ClipDescription desc = clip.getDescription();
        PersistableBundle extras = desc.getExtras();
        if (extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) {
            Log.i(TAG, "clipboard: a sensitive copy (password?) stays on the phone");
            return;
        }
        ClipData.Item item = clip.getItemAt(0);
        Uri uri = item.getUri();
        String type = uri != null ? ctx.getContentResolver().getType(uri) : null;
        if (uri != null && type != null && type.startsWith("image/")) {
            // Reading the image can take a moment; the clipboard already granted us the URI.
            new Thread(() -> offerUri(uri, type, manual), "tandem-clip-read").start();
            return;
        }
        CharSequence text = item.coerceToText(ctx);
        if (text != null && text.length() > 0) offer("text/plain", text.toString().getBytes(StandardCharsets.UTF_8), manual);
    }

    void offerUri(Uri uri, String type, boolean manual) {
        ContentResolver cr = ctx.getContentResolver();
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    lastError = "image is over 25 MB; not sent";
                    return;
                }
            }
            offer(type, out.toByteArray(), manual);
        } catch (IOException | SecurityException e) {
            lastError = "can't read the image: " + e.getMessage();
        }
    }

    /** Queues a clip for the laptop (the newest one wins), unless it's the one the laptop just sent. */
    void offer(String mime, byte[] data, boolean manual) {
        if (data.length == 0 || data.length > MAX_BYTES) return;
        String h = hash(mime, data);
        if (h.equals(lastHash)) return;
        lastHash = h;
        synchronized (lock) {
            pending = new Pending(mime, data, h, manual);
            lock.notifyAll();
        }
    }

    private void sendLoop() {
        PowerManager.WakeLock wake = ctx.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tandem:clip-send");
        while (!stop) {
            Pending p;
            synchronized (lock) {
                try {
                    if (pending == null) lock.wait();
                } catch (InterruptedException e) {
                    return;
                }
                p = pending;
            }
            if (p == null) continue;
            if (SystemClock.elapsedRealtime() - p.since > TTL_MS) {
                drop(p);
                continue;
            }
            wake.acquire(30_000);
            String err;
            try {
                err = send(p);
            } finally {
                if (wake.isHeld()) wake.release();
            }
            if (err == null) {
                drop(p);
                toLaptop++;
                lastToLaptopAt = SystemClock.elapsedRealtime();
                lastError = null;
                Log.i(TAG, "clipboard -> computer: " + p.mime + ", " + p.data.length + " bytes");
                continue;
            }
            if (!p.failed) {
                p.failed = true;
                Log.w(TAG, "clipboard: " + err + "; retrying");
            }
            lastError = err;
            synchronized (lock) {
                try {
                    if (pending == p) lock.wait(RETRY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void drop(Pending p) {
        synchronized (lock) {
            if (pending == p) pending = null;
        }
    }

    /** Null on success, else why not. */
    private String send(Pending p) {
        try {
            JSONObject h = Proto.msg("clip").put("mime", p.mime).put("hash", p.hash).put("manual", p.manual);
            return Link.get(ctx).send(h, p.data) ? null : "not connected to " + Pairing.name(ctx);
        } catch (JSONException e) {
            return String.valueOf(e.getMessage());
        }
    }

    // ------------------------------------------------------------ computer -> phone

    void onClip(JSONObject h, byte[] data) {
        String mime = h.optString("mime");
        if (data.length == 0 || !(Settings.on(ctx, "clip_from_laptop") || h.optBoolean("manual"))) return;
        if (!mime.startsWith("text/") && !mime.startsWith("image/")) return;
        String err = setLocal(mime, data);
        if (err != null) {
            lastError = err;
            return;
        }
        fromLaptop++;
        lastFromLaptopAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "clipboard <- computer: " + mime + ", " + data.length + " bytes");
    }

    /** Puts a clip from the laptop on the phone's clipboard. Null on success. */
    private String setLocal(String mime, byte[] data) {
        ClipData clip;
        if (mime.startsWith("text/")) {
            clip = ClipData.newPlainText("Computer", new String(data, StandardCharsets.UTF_8));
        } else {
            File f;
            try {
                f = ClipFiles.save(ctx, data, mime);
            } catch (IOException e) {
                return "can't save the image: " + e.getMessage();
            }
            clip = ClipData.newUri(ctx.getContentResolver(), "Computer image", ClipFiles.uri(f));
        }
        PersistableBundle extras = new PersistableBundle();
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            extras.putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true); // keeps the copy preview small
        }
        clip.getDescription().setExtras(extras);
        lastHash = hash(mime, data);
        ownWriteAt = SystemClock.elapsedRealtime();
        try {
            ctx.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        } catch (RuntimeException e) {
            return "can't set the clipboard: " + e.getMessage();
        }
        return null;
    }

    // ------------------------------------------------------------ helpers

    static String hash(String mime, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((mime.startsWith("text/") ? "text" : mime).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(Arrays.hashCode(data));
        }
    }

    /** Images from the laptop, served to whichever app pastes them (see ClipFiles provider). */
    static final class ClipFiles {
        static final String AUTHORITY = "com.aidenwb.tandem.clips";
        private static final int KEEP = 5;

        static File dir(Context ctx) {
            File d = new File(ctx.getCacheDir(), "clips");
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            return d;
        }

        static File save(Context ctx, byte[] data, String mime) throws IOException {
            String ext = mime.contains("/") ? mime.substring(mime.indexOf('/') + 1).replaceAll("[^a-z0-9]", "") : "bin";
            File f = new File(dir(ctx), "laptop-" + System.currentTimeMillis() + "." + (ext.isEmpty() ? "bin" : ext));
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            File[] all = dir(ctx).listFiles();
            if (all != null && all.length > KEEP) {
                Arrays.sort(all, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < all.length - KEEP; i++) //noinspection ResultOfMethodCallIgnored
                    all[i].delete();
            }
            return f;
        }

        static Uri uri(File f) {
            return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)
                    .appendPath(f.getName()).build();
        }
    }

    /** For "Send clipboard" in the notification. */
    static Intent grabIntent(Context ctx) {
        return new Intent(ctx, ClipGrabActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
}
