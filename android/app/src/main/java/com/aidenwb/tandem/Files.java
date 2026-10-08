package com.aidenwb.tandem;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Files both ways, and links. Outgoing files are copied into an outbox first (a share's permission ends
 * with the share), then sent as a transfer over whichever link is up ({@link Xfer}). Incoming ones land in
 * Downloads/Tandem.
 */
final class Files {
    private static final String TAG = "Tandem";
    private static final Pattern LINK = Pattern.compile("^(https?|mailto|tel|geo):\\S+$", Pattern.CASE_INSENSITIVE);
    private static final long OUTBOX_TTL_MS = 24 * 3600_000L;
    private final Context ctx;
    private final Object sending = new Object();

    Files(Context ctx) {
        this.ctx = ctx;
    }

    static boolean isLink(CharSequence s) {
        return s != null && LINK.matcher(s.toString().trim()).matches();
    }

    // ------------------------------------------------------------ links

    void onOpen(JSONObject h) {
        String url = h.optString("url").trim();
        if (!isLink(url)) return;
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Settings.on(ctx, "open_links") && android.provider.Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(view);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "open", e);
            }
        }
        Notifications.event(ctx, "Link from " + Pairing.name(ctx), url,
                PendingIntent.getActivity(ctx, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE));
    }

    static boolean sendLink(Context ctx, String url) {
        try {
            return Link.get(ctx).send(Proto.msg("open").put("url", url.trim()), null);
        } catch (JSONException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ phone -> computer

    private File outbox() {
        File d = new File(ctx.getCacheDir(), "outbox");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Copies a shared item into the outbox (call it before the share ends) and sends it when it can. */
    void queue(Uri uri, String kind) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String name = displayName(cr, uri);
        String mime = cr.getType(uri);
        if (mime == null) mime = guessMime(name);
        File dir = new File(outbox(), UUID.randomUUID().toString());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new IOException("can't read " + name);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        writeMeta(dir, mime, kind);
        retryNow();
    }

    void queueFile(File src, String mime, String kind) throws IOException {
        File dir = new File(outbox(), UUID.randomUUID().toString());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, src.getName());
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        writeMeta(dir, mime, kind);
        retryNow();
    }

    private static void writeMeta(File dir, String mime, String kind) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(dir, ".meta"))) {
            out.write((mime + "\n" + kind + "\n").getBytes());
        }
    }

    /** Sends whatever is waiting in the outbox (on its own thread). */
    void retryNow() {
        new Thread(this::drain, "tandem-files-out").start();
    }

    private void drain() {
        synchronized (sending) {
            File[] items = outbox().listFiles();
            if (items == null) return;
            for (File dir : items) {
                if (System.currentTimeMillis() - dir.lastModified() > OUTBOX_TTL_MS) {
                    deleteDir(dir);
                    continue;
                }
                File[] parts = dir.listFiles((d, n) -> !n.equals(".meta"));
                if (parts == null || parts.length == 0) {
                    deleteDir(dir);
                    continue;
                }
                String mime = "application/octet-stream", kind = "file";
                try {
                    String[] meta = new String(java.nio.file.Files.readAllBytes(new File(dir, ".meta").toPath())).split("\n");
                    mime = meta[0];
                    kind = meta.length > 1 ? meta[1] : kind;
                } catch (IOException ignored) {
                }
                File f = parts[0];
                String err = send(f, dir.getName(), mime, kind); // the same id each try: it carries on
                if (err == null) {
                    deleteDir(dir);
                    Log.i(TAG, "files: sent " + f.getName());
                    if (!"screenshot".equals(kind)) Notifications.event(ctx, "Sent to " + Pairing.name(ctx), f.getName(), null);
                } else if (err.startsWith(Xfer.REFUSED)) {
                    deleteDir(dir);
                    Log.i(TAG, "files: " + f.getName() + ": " + err);
                    Notifications.event(ctx, "Not sent to " + Pairing.name(ctx), f.getName() + ": " + err.substring(Xfer.REFUSED.length()), null);
                } else {
                    Log.i(TAG, "files: " + f.getName() + " waits: " + err);
                    break; // not connected: the rest wait too, for the next connection
                }
            }
        }
    }

    /** Null on success, else why it didn't go. */
    private String send(File f, String id, String mime, String kind) {
        try {
            JSONObject h = Proto.msg("file").put("id", id).put("name", f.getName()).put("mime", mime).put("kind", kind);
            return Link.get(ctx).transfer(h, Xfer.of(f), null);
        } catch (IOException | JSONException e) {
            return e.getMessage();
        }
    }

    /** The computer didn't take a file (turned off there). */
    void onRefused(JSONObject h) {
        Notifications.event(ctx, "Not taken by " + Pairing.name(ctx),
                h.optString("name", "A file") + ": " + h.optString("error", "refused"), null);
    }

    // ------------------------------------------------------------ computer -> phone

    /** A file from the computer, where its transfer left it. */
    void onFile(JSONObject h, File file) {
        String name = h.optString("name");
        if (!Settings.on(ctx, "files")) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            try {
                Link.get(ctx).send(Proto.msg("file-no").put("id", h.optString("id")).put("name", name)
                        .put("error", "turned off on the phone"), null);
            } catch (JSONException ignored) {
            }
            return;
        }
        new Thread(() -> {
            try (InputStream in = new FileInputStream(file)) {
                received(name, h.optString("mime"), save(name, h.optString("mime"), in, file.length()));
            } catch (IOException e) {
                Log.w(TAG, "files: saving failed", e);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }, "tandem-files-in").start();
    }

    private Uri save(String name, String mime, InputStream in, long len) throws IOException {
        name = safeName(name);
        if (mime == null || mime.isEmpty()) mime = guessMime(name);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Tandem");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("can't create " + name + " in Downloads");
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("can't write " + name);
            Proto.copy(in, out, len);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    private void received(String name, String mime, Uri uri) {
        Log.i(TAG, "files: received " + name);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Notifications.event(ctx, "From " + Pairing.name(ctx), safeName(name) + " · in Downloads/Tandem",
                PendingIntent.getActivity(ctx, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE));
    }

    // ------------------------------------------------------------ helpers

    static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return safeName(c.getString(0));
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return safeName(last != null ? last : "file");
    }

    static String safeName(String name) {
        if (name == null) name = "";
        name = name.replaceAll("[\\x00-\\x1f/\\\\]", "_").replaceAll("^\\.+", "").trim();
        if (name.length() > 200) name = name.substring(name.length() - 200);
        return name.isEmpty() ? "file" : name;
    }

    static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        String m = dot >= 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
        return m != null ? m : "application/octet-stream";
    }

    private static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

}
