package com.aidenwb.tandem;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/**
 * Frames, as in docs/PROTOCOL.md: a 4-byte big-endian header length, a JSON header (with "t", the type,
 * and "len", the payload size), then the payload.
 */
final class Proto {
    static final UUID SERVICE_UUID = UUID.fromString("7a6d3b40-6e1a-4d2a-9b7e-54616e64656d");
    static final int VERSION = 4;
    static final int DEFAULT_PORT = 47800;
    static final int HEADER_MAX = 64 * 1024;

    private Proto() {}

    static final class Frame {
        final JSONObject h;
        final byte[] payload;
        final File file; // a file a transfer brought in (type "file"), instead of the payload

        Frame(JSONObject h, byte[] payload) {
            this(h, payload, null);
        }

        Frame(JSONObject h, byte[] payload, File file) {
            this.h = h;
            this.payload = payload;
            this.file = file;
        }

        String type() {
            return h.optString("t");
        }
    }

    /** Payload limits per message type; the rest carry none. Anything bigger comes as a transfer (Xfer). */
    static int maxPayload(String type) {
        switch (type) {
            case "clip":
                return Xfer.INLINE_MAX;
            case "x":
                return Xfer.CHUNK_MAX;
            case "a":
                return 64 * 1024;
            default:
                return 0;
        }
    }

    static byte[] header(JSONObject h, long len) throws JSONException {
        byte[] b = h.put("len", len).toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + b.length];
        out[0] = (byte) (b.length >>> 24);
        out[1] = (byte) (b.length >>> 16);
        out[2] = (byte) (b.length >>> 8);
        out[3] = (byte) b.length;
        System.arraycopy(b, 0, out, 4, b.length);
        return out;
    }

    static void write(OutputStream out, JSONObject h, byte[] payload) throws IOException {
        try {
            out.write(header(h, payload == null ? 0 : payload.length));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (payload != null && payload.length > 0) out.write(payload);
        out.flush();
    }

    static JSONObject readHeader(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n <= 0 || n > HEADER_MAX) throw new IOException("header of " + n + " bytes");
        byte[] b = new byte[n];
        in.readFully(b);
        try {
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("bad header", e);
        }
    }

    static Frame read(DataInputStream in) throws IOException {
        JSONObject h = readHeader(in);
        long len = h.optLong("len", 0);
        if (len < 0 || len > maxPayload(h.optString("t"))) {
            throw new IOException(h.optString("t") + ": payload of " + len + " bytes");
        }
        byte[] p = new byte[(int) len];
        in.readFully(p);
        return new Frame(h, p);
    }

    static JSONObject msg(String type) {
        try {
            return new JSONObject().put("t", type);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    static void copy(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("closed early");
            out.write(buf, 0, r);
            n -= r;
        }
    }
}
