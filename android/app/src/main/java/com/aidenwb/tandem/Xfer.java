package com.aidenwb.tandem;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Big messages over whichever link is up: files, clipboard images, app icons. The same as the computer's
 * laptop/tandemd/xfer.py (the why is there) and docs/PROTOCOL.md: numbered chunks (`x`) on the network when
 * it's up, else on Bluetooth; the receiver says how much it has (`x-ack`), and after a change of link the
 * sender carries on from there. On Bluetooth, chunks wait while either device's radio carries audio.
 */
final class Xfer {
    private static final String TAG = "Tandem";
    static final int INLINE_MAX = 64 * 1024, CHUNK_MAX = 64 * 1024;
    /** What send() says when the other side wouldn't take it (no use sending it again). */
    static final String REFUSED = "refused: ";
    private static final long GIVE_UP_MS = 60_000, STALL_MS = 15_000, REACK_MS = 5_000, PARTIAL_TTL_MS = 3600_000;

    private static int chunk(String via) {
        return "net".equals(via) ? 64 * 1024 : 16 * 1024;
    }

    /** Bytes in flight before an ack. Bluetooth's is small: frames queued behind it (heartbeats, audio) wait. */
    private static long window(String via) {
        return "net".equals(via) ? 1 << 20 : 64 * 1024;
    }

    private static long maxSize(String type) {
        switch (type) {
            case "clip":
                return 64L << 20;
            case "art":
            case "notif-icon":
                return 1L << 20;
            case "file":
                return 1L << 40;
            default:
                return -1;
        }
    }

    /** What a transfer sends: bytes in memory, or a file read as it goes. */
    interface Source {
        long size();

        byte[] read(long off, int n) throws IOException;

        default void close() {
        }
    }

    static Source of(byte[] data) {
        return new Source() {
            public long size() {
                return data.length;
            }

            public byte[] read(long off, int n) {
                return Arrays.copyOfRange(data, (int) off, (int) off + n);
            }
        };
    }

    static Source of(File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        long len = raf.length();
        return new Source() {
            public long size() {
                return len;
            }

            public byte[] read(long off, int n) throws IOException {
                byte[] b = new byte[n];
                raf.seek(off);
                raf.readFully(b);
                return b;
            }

            public void close() {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        };
    }

    private static final class Out {
        final String xid, sha;
        final JSONObject header;
        final long size;
        final Source src;
        final BooleanSupplier wanted;
        long acked, next; // the receiver has everything before `acked`; `next` is the next byte to send
        boolean sentFirst, wait; // chunk 0 went on the current link; the receiver asked us to hold off
        Link.Conn conn; // the link the last chunk went on
        String result; // "" once the receiver has it all, else why not
        long progressAt = SystemClock.elapsedRealtime();
        volatile String state = "sending";

        Out(String xid, JSONObject header, long size, String sha, Source src, BooleanSupplier wanted) {
            this.xid = xid;
            this.header = header;
            this.size = size;
            this.sha = sha;
            this.src = src;
            this.wanted = wanted;
        }
    }

    private static final class In {
        final String xid, sha;
        final JSONObject header;
        final long size;
        final File file;
        final RandomAccessFile raf;
        final TreeMap<Long, Long> ahead = new TreeMap<>(); // chunks past `have` (after a change of link)
        long have, seen, ackedAt;
        boolean wait;

        In(String xid, JSONObject header, long size, String sha, File file) throws IOException {
            this.xid = xid;
            this.header = header;
            this.size = size;
            this.sha = sha;
            this.file = file;
            raf = new RandomAccessFile(file, "rw");
            raf.setLength(0);
            seen = ackedAt = SystemClock.elapsedRealtime();
        }
    }

    private final Link link;
    private final File folder;
    /** Is this phone's radio carrying audio (set by LinkService)? Bluetooth chunks wait for it. */
    volatile BooleanSupplier radioBusy = () -> false;
    private final Map<String, Out> out = new ConcurrentHashMap<>();
    private final Map<String, In> inc = new HashMap<>(); // guarded by this
    // What came in, so a resent chunk isn't taken twice: xid -> {when, size, finished (0/1)}. Guarded by this.
    private final Map<String, long[]> done = new HashMap<>();

    Xfer(Link link, File folder) {
        this.link = link;
        this.folder = folder;
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        File[] old = folder.listFiles(); // partials don't outlive the app
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        Thread t = new Thread(this::housekeeping, "tandem-xfer");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ sending

    /** Sends one message with a big payload and waits until the computer has all of it (minutes, over
     *  Bluetooth: not on the main thread). Null once it has, else why not: not connected for a minute,
     *  REFUSED..., or no longer `wanted`. */
    String send(JSONObject header, Source src, BooleanSupplier wanted) {
        String name = header.optString("name", header.optString("t"));
        try {
            long size = src.size();
            String sha = sha256(src);
            // The same message gets the same id, so sending it again carries on where the last try stopped.
            String xid = Proto.sha256((header.toString() + sha).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
            Out o = new Out(xid, header, size, sha, src, wanted);
            if (out.putIfAbsent(xid, o) != null) return "already on its way";
            String r;
            try {
                r = run(o);
            } finally {
                out.remove(xid);
            }
            Log.i(TAG, r.isEmpty() ? "xfer: sent " + name + " (" + size + " bytes)" : "xfer: " + name + " didn't go: " + r);
            return r.isEmpty() ? null : r;
        } catch (IOException | JSONException e) {
            return String.valueOf(e.getMessage());
        } finally {
            src.close();
        }
    }

    private String run(Out o) throws IOException, JSONException {
        long lostAt = 0;
        while (true) {
            synchronized (o) {
                if (o.result != null) return o.result;
            }
            if (o.wanted != null && !o.wanted.getAsBoolean()) {
                link.send(Proto.msg("x-no").put("x", o.xid).put("error", "cancelled"), null);
                return "cancelled";
            }
            Link.Conn net = link.net, bt = link.bt;
            Link.Conn c = net != null ? net : bt;
            String via = net != null ? "net" : "bt";
            long now = SystemClock.elapsedRealtime();
            if (c == null) {
                o.state = "waits for the connection";
                if (lostAt == 0) lostAt = now;
                if (now - lostAt > GIVE_UP_MS) return "not connected";
                nap(o);
                continue;
            }
            lostAt = 0;
            boolean hold;
            synchronized (o) {
                if (c != o.conn) { // what went on the old link may never arrive: from what it has, here
                    o.conn = c;
                    o.next = o.acked;
                    o.sentFirst = o.acked > 0;
                    o.progressAt = now;
                }
                hold = o.wait;
            }
            if ("bt".equals(via) && (hold || radioBusy.getAsBoolean())) {
                o.state = "waits for the audio to stop";
                synchronized (o) {
                    o.progressAt = now;
                }
                nap(o);
                continue;
            }
            o.state = "sending";
            long off = -1;
            int n = 0;
            synchronized (o) {
                boolean more = o.next < o.size || !o.sentFirst;
                if (more && o.next - o.acked < window(via)) {
                    off = o.next;
                    n = (int) Math.min(chunk(via), o.size - o.next);
                } else if (now - o.progressAt > STALL_MS) { // nothing back for a while: again from what it has
                    o.next = o.acked;
                    o.sentFirst = o.acked > 0;
                    o.progressAt = now;
                    continue;
                }
            }
            if (off < 0) {
                nap(o);
                continue;
            }
            JSONObject h = Proto.msg("x").put("x", o.xid).put("i", off);
            if (off == 0) h.put("n", o.size).put("sha", o.sha).put("h", o.header);
            byte[] chunk = o.src.read(off, n);
            try {
                c.send(h, chunk);
            } catch (IOException e) {
                Log.w(TAG, "xfer: " + via + " send failed: " + e.getMessage());
                link.drop(c);
                continue;
            }
            synchronized (o) {
                if (o.next == off) {
                    o.next = off + n;
                    o.sentFirst = true;
                }
            }
        }
    }

    private static void nap(Out o) {
        synchronized (o) {
            if (o.result != null) return;
            try {
                o.wait(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /** `x-ack` or `x-no`, on the thread reading that link. */
    void onAck(JSONObject h) {
        String xid = h.optString("x");
        Out o = out.get(xid);
        if ("x-no".equals(h.optString("t"))) {
            if (o != null) {
                synchronized (o) {
                    o.result = REFUSED + h.optString("error", "refused");
                    o.notifyAll();
                }
            }
            In p;
            synchronized (this) {
                p = inc.remove(xid);
            }
            if (p != null) discard(p); // the sender gave up on it
            return;
        }
        if (o == null) return;
        synchronized (o) {
            long have = Math.min(Math.max(h.optLong("have"), 0), o.size);
            if (have < o.acked) { // it lost what it had: from there again
                o.next = have;
                o.sentFirst = have > 0;
            }
            o.acked = have;
            o.next = Math.max(o.next, have);
            o.wait = h.optBoolean("wait");
            o.progressAt = SystemClock.elapsedRealtime();
            if (h.optBoolean("done")) o.result = "";
            o.notifyAll();
        }
    }

    /** The first file or clipboard copy on its way, for the notification ("photo.jpg 40%"), or null. */
    String summary() {
        for (Out o : out.values()) {
            String t = o.header.optString("t");
            String name = "file".equals(t) ? o.header.optString("name") : "clip".equals(t) ? "the clipboard" : null;
            if (name == null) continue;
            if (!"sending".equals(o.state)) return name + " " + o.state;
            long pct = o.size > 0 ? o.acked * 10 / o.size * 10 : 0;
            return "sending " + name + " " + pct + "%";
        }
        return null;
    }

    // ------------------------------------------------------------ receiving

    /** A chunk, on the thread reading that link. */
    void onChunk(String via, Proto.Frame f) {
        JSONObject h = f.h;
        String xid = h.optString("x");
        long i = h.optLong("i");
        boolean busy = "bt".equals(via) && radioBusy.getAsBoolean();
        JSONObject reply = null;
        In finished = null;
        try {
            synchronized (this) {
                In p = inc.get(xid);
                if (p == null) {
                    long[] d = done.get(xid);
                    JSONObject meta = h.optJSONObject("h");
                    long size = h.optLong("n", -1);
                    if (d != null) {
                        if (d[2] == 1) reply = Proto.msg("x-ack").put("x", xid).put("have", d[1]).put("done", true);
                    } else if (i != 0 || !h.has("n")) {
                        reply = ack(xid, 0, false); // we don't have it (any more): from the start
                    } else if (meta == null || h.optString("sha").isEmpty() || size < 0
                            || size > maxSize(meta.optString("t"))) {
                        reply = Proto.msg("x-no").put("x", xid).put("error", String.format(Locale.ROOT,
                                "won't take %d bytes of %s", size, meta == null ? "?" : meta.optString("t")));
                    } else {
                        p = new In(xid, meta, size, h.optString("sha"), new File(folder, xid));
                        inc.put(xid, p);
                    }
                }
                if (p != null) {
                    long end = i + f.payload.length;
                    if (i >= 0 && end <= p.size) {
                        if (f.payload.length > 0) {
                            p.raf.seek(i);
                            p.raf.write(f.payload);
                        }
                        if (i <= p.have) p.have = Math.max(p.have, end);
                        else p.ahead.merge(i, end, Math::max);
                        while (!p.ahead.isEmpty() && p.ahead.firstKey() <= p.have) {
                            p.have = Math.max(p.have, p.ahead.pollFirstEntry().getValue());
                        }
                    }
                    p.seen = SystemClock.elapsedRealtime();
                    if (p.have >= p.size) {
                        finished = inc.remove(xid);
                        done.put(xid, new long[]{p.seen, p.size, 0});
                    } else {
                        p.wait = busy;
                        p.ackedAt = p.seen;
                        reply = ack(xid, p.have, busy);
                    }
                }
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "xfer: " + e.getMessage());
        }
        if (reply != null) link.send(reply, null);
        if (finished != null) finish(via, finished);
    }

    private void finish(String via, In p) {
        try {
            p.raf.close();
            if (!sha256(p.file).equals(p.sha)) {
                Log.w(TAG, "xfer: " + p.header.optString("name", p.header.optString("t")) + " came in damaged; asking again");
                //noinspection ResultOfMethodCallIgnored
                p.file.delete();
                synchronized (this) {
                    done.remove(p.xid);
                }
                link.send(ack(p.xid, 0, false), null);
                return;
            }
            synchronized (this) {
                done.put(p.xid, new long[]{SystemClock.elapsedRealtime(), p.size, 1});
            }
            link.send(Proto.msg("x-ack").put("x", p.xid).put("have", p.size).put("done", true), null);
            if ("file".equals(p.header.optString("t"))) {
                link.deliver(via, p.header, new byte[0], p.file); // the feature moves it where it goes
            } else {
                byte[] data = java.nio.file.Files.readAllBytes(p.file.toPath());
                //noinspection ResultOfMethodCallIgnored
                p.file.delete();
                link.deliver(via, p.header, data, null);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "xfer: " + e.getMessage());
        }
    }

    private static void discard(In p) {
        try {
            p.raf.close();
        } catch (IOException ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        p.file.delete();
    }

    private static JSONObject ack(String xid, long have, boolean wait) throws JSONException {
        return Proto.msg("x-ack").put("x", xid).put("have", have).put("wait", wait);
    }

    private void housekeeping() {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            List<In> parts;
            synchronized (this) {
                parts = new ArrayList<>(inc.values());
                done.values().removeIf(d -> now - d[0] > PARTIAL_TTL_MS);
            }
            if (parts.isEmpty()) continue;
            boolean anyWait = false;
            for (In p : parts) anyWait |= p.wait;
            boolean busy = anyWait && radioBusy.getAsBoolean();
            for (In p : parts) {
                if (now - p.seen > PARTIAL_TTL_MS) {
                    synchronized (this) {
                        inc.remove(p.xid);
                    }
                    discard(p);
                    continue;
                }
                long have;
                boolean wait;
                synchronized (this) {
                    if (!(p.wait && !busy) && now - p.ackedAt <= REACK_MS) continue;
                    p.wait = p.wait && busy; // and an idle one says where it is, in case an ack was lost
                    p.ackedAt = now;
                    have = p.have;
                    wait = p.wait;
                }
                try {
                    link.send(ack(p.xid, have, wait), null);
                } catch (JSONException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private static String sha256(Source src) throws IOException {
        MessageDigest md = digest();
        long size = src.size();
        for (long off = 0; off < size; off += 1 << 20) md.update(src.read(off, (int) Math.min(1 << 20, size - off)));
        return Proto.hex(md.digest());
    }

    private static String sha256(File f) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return Proto.hex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
