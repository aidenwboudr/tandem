package com.aidenwb.tandem;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The link with the computer (docs/PROTOCOL.md). Bluetooth: this phone serves an RFCOMM socket and the
 * computer connects to it; that's where pairing happens, and small messages go that way when there's no
 * network. Network: this phone connects to the computer's TLS server (its certificate pinned at pairing)
 * for a control connection, an audio connection while laptop audio plays here, and one per file.
 * Laptop audio also comes over Bluetooth: each frame plays from whichever link brings it first.
 *
 * Incoming messages are handed to {@link Features} on one thread ("tandem-link"), in order.
 */
final class Link {
    private static final String TAG = "Tandem";
    private static final long PING_MS = 25_000, NET_IDLE_MS = 90_000;
    private static final long NET_RETRY_MIN = 15_000, NET_RETRY_MAX = 120_000;
    private static final long PAIR_WAIT_MS = 110_000;

    interface Listener {
        void onLinkChanged();
    }

    interface AudioSink {
        void onAudio(int codec, byte[] buf, int off, int len);
    }

    /** A pairing request waiting for the user (shown as a notification and in the app). */
    static final class PendingPair {
        final String name, address;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean allowed;

        PendingPair(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    static final class Conn {
        final String via, addr;
        final Closeable sock;
        final DataInputStream in;
        final OutputStream out;
        final long openedAt = SystemClock.elapsedRealtime();
        volatile long sentAt;
        volatile boolean closed;

        Conn(String via, String addr, Closeable sock, InputStream in, OutputStream out) {
            this.via = via;
            this.addr = addr;
            this.sock = sock;
            this.in = new DataInputStream(in);
            this.out = new BufferedOutputStream(out, 64 * 1024);
        }

        synchronized void send(JSONObject h, byte[] payload) throws IOException {
            Proto.write(out, h, payload);
            sentAt = SystemClock.elapsedRealtime();
        }

        void close() {
            closed = true;
            try {
                sock.close();
            } catch (IOException | RuntimeException ignored) { // TLS close_notify on the main thread
            }
        }
    }

    private static Link instance;

    static synchronized Link get(Context ctx) {
        if (instance == null) instance = new Link(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler handler;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object netKick = new Object();
    private volatile boolean stop = true, kicked;
    private volatile int gen; // bumped by start(): loops from an earlier start() exit
    private Thread btThread, netThread;
    private volatile BluetoothServerSocket btServer;
    private ConnectivityManager.NetworkCallback netCallback;

    // Read by the UI and the notification.
    volatile Conn bt, net, audio;
    volatile PendingPair pending;
    volatile String error;
    volatile long lastNetTryAt;
    private volatile AudioSink audioSink;
    private volatile boolean audioConnecting;
    // The newest audio frame played (both links carry every frame): its stream, sequence number and when.
    private int audioId, audioSeq;
    private volatile long audioAt;
    private volatile long audioRetryAt; // when a fresh audio connection was last started over a stalled one

    private Link(Context ctx) {
        this.ctx = ctx;
        HandlerThread t = new HandlerThread("tandem-link");
        t.start();
        handler = new Handler(t.getLooper());
    }

    Handler handler() {
        return handler;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        handler.post(() -> {
            for (Listener l : listeners) l.onLinkChanged();
        });
    }

    boolean connected() {
        return bt != null || net != null;
    }

    synchronized void start() {
        if (!stop) return;
        stop = false;
        int g = ++gen;
        btThread = new Thread(() -> btLoop(g), "tandem-bt");
        btThread.start();
        netThread = new Thread(() -> netLoop(g), "tandem-net");
        netThread.start();
        handler.postDelayed(ping, PING_MS);
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    kick();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    kick();
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "network callback", e);
        }
    }

    synchronized void stop() {
        if (stop) return;
        stop = true;
        handler.removeCallbacks(ping);
        try {
            if (netCallback != null) ctx.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
        }
        netCallback = null;
        closeServer();
        for (Conn c : new Conn[]{bt, net, audio}) if (c != null) c.close();
        if (btThread != null) btThread.interrupt();
        kick();
    }

    /** Look for the computer on the network again now (network changed, new addresses, just paired). */
    void kick() {
        synchronized (netKick) {
            kicked = true;
            netKick.notifyAll();
        }
    }

    /** Sends over the network if it's up, else over Bluetooth (small payloads only). False if neither could.
     *  From the main thread (where Android forbids network I/O) it's queued, and the answer is a guess. */
    boolean send(JSONObject h, byte[] payload) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handler.post(() -> send(h, payload));
            return payload == null || payload.length <= Proto.BT_MAX ? connected() : net != null;
        }
        int len = payload == null ? 0 : payload.length;
        for (Conn c : new Conn[]{net, len <= Proto.BT_MAX ? bt : null}) {
            if (c == null) continue;
            try {
                c.send(h, payload);
                return true;
            } catch (IOException e) {
                Log.w(TAG, "link: " + c.via + " send failed: " + e.getMessage());
                drop(c);
            }
        }
        return false;
    }

    boolean sendNet(JSONObject h, byte[] payload) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handler.post(() -> sendNet(h, payload));
            return net != null;
        }
        Conn c = net;
        if (c == null) return false;
        try {
            c.send(h, payload);
            return true;
        } catch (IOException e) {
            drop(c);
            return false;
        }
    }

    private void drop(Conn c) {
        c.close();
        boolean was = false;
        synchronized (this) {
            if (c == bt) {
                bt = null;
                was = true;
            } else if (c == net) {
                net = null;
                was = true;
            } else if (c == audio) {
                audio = null;
            }
        }
        if (was) {
            Log.i(TAG, "link: " + c.via + " down");
            changed();
        }
    }

    void unpair(boolean tell) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handler.post(() -> unpair(tell));
            return;
        }
        if (tell) send(Proto.msg("unpair"), null);
        Pairing.forget(ctx);
        for (Conn c : new Conn[]{bt, net, audio}) if (c != null) drop(c);
        changed();
    }

    private final Runnable ping = new Runnable() {
        @Override
        public void run() {
            Conn c = net;
            if (c != null) {
                try {
                    c.send(Proto.msg("ping"), null);
                } catch (IOException e) {
                    drop(c);
                }
            }
            if (!stop) handler.postDelayed(this, PING_MS);
        }
    };

    // ------------------------------------------------------------ Bluetooth (we serve)

    private boolean hasBt() {
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void btLoop(int g) {
        while (!stop && g == gen) {
            BluetoothAdapter ad = ctx.getSystemService(BluetoothManager.class).getAdapter();
            if (!hasBt() || ad == null || !ad.isEnabled()) {
                if (!sleep(5000)) return;
                continue;
            }
            try (BluetoothServerSocket ss = ad.listenUsingRfcommWithServiceRecord("Tandem", Proto.SERVICE_UUID)) {
                btServer = ss;
                while (!stop) {
                    BluetoothSocket s = ss.accept();
                    new Thread(() -> handleBt(s), "tandem-bt-conn").start();
                }
            } catch (IOException | SecurityException e) {
                if (stop) return;
                Log.i(TAG, "link: bluetooth server: " + e.getMessage());
                if (!sleep(5000)) return;
            } finally {
                btServer = null;
            }
        }
    }

    private void closeServer() {
        BluetoothServerSocket s = btServer;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleBt(BluetoothSocket s) {
        Conn c;
        String addr;
        try {
            addr = s.getRemoteDevice().getAddress();
            c = new Conn("bt", addr, s, s.getInputStream(), s.getOutputStream());
        } catch (IOException | SecurityException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            return;
        }
        // Bluetooth sockets have no read timeout: close it if the hello doesn't come.
        Runnable watchdog = c::close;
        handler.postDelayed(watchdog, 20_000);
        try {
            Proto.Frame hello = Proto.read(c.in);
            handler.removeCallbacks(watchdog);
            JSONObject h = hello.h;
            String id = h.optString("id"), name = h.optString("name", "Computer"), fp = h.optString("fp");
            if (!"hello".equals(hello.type()) || !"computer".equals(h.optString("kind")) || id.isEmpty() || fp.isEmpty()) {
                c.close();
                return;
            }
            String state;
            // Paired = the same computer (id + certificate) on the same Bluetooth bond. Its id and fingerprint
            // aren't secret, so the address (backed by the bond's link key) is what proves it.
            if (Pairing.paired(ctx) && id.equals(Pairing.id(ctx)) && fp.equals(Pairing.fp(ctx))
                    && addr.equalsIgnoreCase(Pairing.bt(ctx))) state = "paired";
            else if (Pairing.paired(ctx)) state = "busy";
            else state = "asking";
            c.send(Proto.msg("hello").put("v", Proto.VERSION).put("id", Pairing.myId(ctx))
                    .put("name", Pairing.myName(ctx)).put("kind", "phone").put("state", state), null);
            if ("busy".equals(state)) {
                Log.i(TAG, "link: " + name + " wants to pair, but we're paired with " + Pairing.name(ctx));
                c.close();
                return;
            }
            if ("asking".equals(state)) {
                if (!askUser(c, name, addr)) {
                    c.close();
                    return;
                }
                handler.postDelayed(watchdog, 20_000);
                Proto.Frame keys = Proto.read(c.in);
                handler.removeCallbacks(watchdog);
                String token = keys.h.optString("token");
                if (!"keys".equals(keys.type()) || token.isEmpty()) {
                    c.close();
                    return;
                }
                Pairing.save(ctx, id, name, fp, token, addr);
                Log.i(TAG, "link: paired with " + name);
            } else {
                Pairing.setName(ctx, name);
            }
            Pairing.setAddrs(ctx, h.optJSONArray("addrs"), h.optInt("port", Proto.DEFAULT_PORT));
            serve(c);
        } catch (IOException | JSONException e) {
            Log.i(TAG, "link: bluetooth: " + e.getMessage());
            handler.removeCallbacks(watchdog);
            c.close();
        }
    }

    private boolean askUser(Conn c, String name, String addr) throws IOException, JSONException {
        PendingPair p = new PendingPair(name, addr);
        pending = p;
        Notifications.pairRequest(ctx, name);
        changed();
        boolean answered;
        try {
            answered = p.done.await(PAIR_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            answered = false;
        }
        boolean ok = answered && p.allowed;
        if (pending == p) pending = null;
        Notifications.cancelPairRequest(ctx);
        changed();
        // A missed prompt asks again soon; a "Deny" keeps the computer away for a long while.
        c.send(Proto.msg("pair").put("ok", ok).put("reason", ok ? "" : answered ? "denied" : "timeout"), null);
        return ok;
    }

    /** From the notification or the app: the answer to the pending pairing request. */
    void answerPairing(boolean allow) {
        PendingPair p = pending;
        if (p == null) return;
        p.allowed = allow;
        p.done.countDown();
    }

    // ------------------------------------------------------------ network (we connect)

    private void netLoop(int g) {
        long delay = NET_RETRY_MIN;
        while (!stop && g == gen) {
            boolean worked = false;
            if (Pairing.paired(ctx) && net == null) {
                lastNetTryAt = SystemClock.elapsedRealtime();
                Conn c = connectNet("control", null);
                if (c != null) {
                    worked = true;
                    error = null;
                    serve(c); // until it drops
                }
            }
            // A connection that worked and dropped is mostly a blip: try again right away. Waiting the usual
            // 15 s left laptop audio off that long (it needs this connection).
            long wait;
            if (worked) {
                delay = NET_RETRY_MIN;
                wait = 1000;
            } else {
                wait = delay;
                delay = Math.min(delay * 2, NET_RETRY_MAX);
            }
            synchronized (netKick) {
                if (!kicked) {
                    try {
                        netKick.wait(Pairing.paired(ctx) ? wait : 60_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (kicked) delay = NET_RETRY_MIN;
                kicked = false;
            }
        }
    }

    /** Opens an authenticated TLS connection to the computer, trying each address it has. */
    Conn connectNet(String role, String bulk) {
        if (!Pairing.paired(ctx)) return null;
        List<String> cands = candidates();
        for (String a : cands) {
            Conn c = tryConnect(a, role, bulk);
            if (c != null) return c;
        }
        if ("control".equals(role)) {
            String found = discover();
            if (found != null && !cands.contains(found)) {
                Conn c = tryConnect(found, role, bulk);
                if (c != null) return c;
            }
            if (!cands.isEmpty()) error = "Computer not reachable on the network";
        }
        return null;
    }

    private List<String> candidates() {
        List<String> out = new ArrayList<>();
        String last = Pairing.lastAddr(ctx);
        if (!last.isEmpty()) out.add(last);
        List<String> v4 = new ArrayList<>(), v6 = new ArrayList<>();
        for (String a : Pairing.addrs(ctx)) {
            if (out.contains(a)) continue;
            (a.contains(":") ? v6 : v4).add(a);
        }
        // Same LAN first (a quick answer), then the rest (Tailscale and other routes).
        List<String> mine = myPrefixes();
        for (String a : v4) if (sameNet(a, mine)) out.add(a);
        for (String a : v4) if (!sameNet(a, mine)) out.add(a);
        out.addAll(v6);
        return out;
    }

    private static List<String> myPrefixes() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        String s = ia.getAddress().getHostAddress();
                        out.add(s.substring(0, s.lastIndexOf('.') + 1));
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static boolean sameNet(String ip, List<String> prefixes) {
        for (String p : prefixes) if (ip.startsWith(p)) return true;
        return false;
    }

    private Conn tryConnect(String addr, String role, String bulk) {
        SSLSocket s = null;
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{new Pinned(Pairing.fp(ctx))}, null);
            s = (SSLSocket) tls.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(InetAddress.getByName(addr), Pairing.port(ctx)), 2500);
            s.setSoTimeout(8000);
            s.setTcpNoDelay(true);
            s.startHandshake();
            Conn c = new Conn("net", addr, s, s.getInputStream(), s.getOutputStream());
            JSONObject auth = Proto.msg("auth").put("id", Pairing.myId(ctx)).put("token", Pairing.token(ctx))
                    .put("role", role);
            if (bulk != null) auth.put("bulk", bulk);
            c.send(auth, null);
            Proto.Frame r = Proto.read(c.in);
            if (!"auth".equals(r.type()) || !r.h.optBoolean("ok")) {
                error = "The computer didn't accept this phone. Unpair and pair again.";
                c.close();
                return null;
            }
            s.setSoTimeout("control".equals(role) ? (int) NET_IDLE_MS : "audio".equals(role) ? 1000 : 60_000);
            if ("control".equals(role)) {
                Pairing.setLastAddr(ctx, addr);
                Pairing.setName(ctx, r.h.optString("name"));
            }
            return c;
        } catch (Exception e) { // IO, TLS (wrong certificate), JSON
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            Log.d(TAG, "link: " + addr + ": " + e.getMessage());
            return null;
        }
    }

    /** Asks the LAN where the paired computer is (UDP broadcast); its address, or null. */
    private String discover() {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            ds.setSoTimeout(1500);
            byte[] q = Proto.msg("where").put("id", Pairing.id(ctx)).toString().getBytes(StandardCharsets.UTF_8);
            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
            }
            for (InetAddress t : targets) {
                try {
                    ds.send(new DatagramPacket(q, q.length, t, Pairing.port(ctx)));
                } catch (IOException ignored) {
                }
            }
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            long until = SystemClock.elapsedRealtime() + 1500;
            while (SystemClock.elapsedRealtime() < until) {
                ds.receive(p);
                JSONObject r = new JSONObject(new String(buf, 0, p.getLength(), StandardCharsets.UTF_8));
                if ("here".equals(r.optString("t")) && Pairing.id(ctx).equals(r.optString("id"))) {
                    return p.getAddress().getHostAddress();
                }
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException | JSONException e) {
            Log.d(TAG, "link: discovery: " + e.getMessage());
        }
        return null;
    }

    /** Pins the computer's certificate by its SHA-256 (no CA involved). */
    private static final class Pinned implements X509TrustManager {
        private final String fp;

        Pinned(String fp) {
            this.fp = fp;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0 || fp.isEmpty() || !fp.equals(Proto.sha256(chain[0].getEncoded()))) {
                throw new CertificateException("not the paired computer");
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates aren't used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ------------------------------------------------------------ serving a control connection

    private void serve(Conn c) {
        Conn old;
        synchronized (this) {
            if ("bt".equals(c.via)) {
                old = bt;
                bt = c;
            } else {
                old = net;
                net = c;
            }
        }
        if (old != null) old.close();
        Log.i(TAG, "link: " + c.via + " up (" + c.addr + ")");
        changed();
        try {
            c.send(Settings.message(ctx), null);
            if ("bt".equals(c.via)) kick(); // maybe the network works too
            while (!stop && !c.closed) {
                Proto.Frame f = Proto.read(c.in);
                switch (f.type()) {
                    case "ping":
                        c.send(Proto.msg("pong"), null);
                        break;
                    case "pong":
                        break;
                    case "keys": // the computer lost our record and made new keys
                        if ("bt".equals(c.via) && !f.h.optString("token").isEmpty()) {
                            Pairing.setToken(ctx, f.h.optString("token"));
                            kick();
                        }
                        break;
                    case "addrs":
                        Pairing.setAddrs(ctx, f.h.optJSONArray("addrs"), f.h.optInt("port", Proto.DEFAULT_PORT));
                        if (net == null) kick();
                        break;
                    case "unpair":
                        Log.i(TAG, "link: the computer unpaired");
                        handler.post(() -> unpair(false));
                        return;
                    case "a":
                        audioFrame(f, c.via);
                        // Android puts this link into sniff mode 7 s after this phone last sent anything on it
                        // (what it receives doesn't count), and in sniff it can't keep up with audio: the sound
                        // stopped for seconds. Saying something now and then keeps the link active.
                        if ("bt".equals(c.via) && SystemClock.elapsedRealtime() - c.sentAt > 2000) {
                            c.send(Proto.msg("pong"), null);
                        }
                        break;
                    default:
                        String via = c.via;
                        handler.post(() -> Features.get(ctx).onMessage(via, f));
                }
            }
        } catch (IOException e) {
            if (!c.closed) Log.i(TAG, "link: " + c.via + ": " + e.getMessage());
        } finally {
            drop(c);
        }
    }

    // ------------------------------------------------------------ audio (laptop -> phone)

    /** Opens (or closes) the audio connection; frames go to the sink on its own thread. */
    void wantAudio(AudioSink sink) {
        audioSink = sink;
        if (sink == null) {
            Conn a = audio;
            if (a != null) drop(a);
            return;
        }
        if (audio != null || audioConnecting || net == null) return;
        openAudio();
    }

    private void openAudio() {
        audioConnecting = true;
        new Thread(() -> {
            Conn a = connectNet("audio", null);
            audioConnecting = false;
            if (a == null) return;
            Conn old = audio;
            audio = a;
            if (old != null) old.close(); // a stalled one this replaces (the computer drops it too)
            readAudio(a);
        }, "tandem-audio").start();
    }

    private void readAudio(Conn a) {
        try {
            while (!a.closed && audioSink != null) {
                Proto.Frame f;
                try {
                    f = Proto.read(a.in);
                } catch (SocketTimeoutException e) {
                    long now = SystemClock.elapsedRealtime(), quiet = now - audioAt;
                    AudioSink s = audioSink;
                    // Nothing from either link for 5 s: nothing is playing on the laptop. Let the player rest.
                    if (s != null && quiet > 5000) s.onAudio(-1, null, 0, 0);
                    // The stream stopped mid-play: maybe the laptop paused, maybe the network stalled (Tailscale
                    // moving between paths stops traffic for seconds). After a stall, TCP waits out its retry
                    // backoff, seconds more of silence; a fresh connection plays as soon as the network is back.
                    // A pause costs a few needless connections.
                    if (s != null && quiet > 1500 && quiet < 30_000 && !audioConnecting && net != null
                            && now - audioRetryAt > Math.max(2000, quiet / 3)) {
                        audioRetryAt = now;
                        Log.d(TAG, "audio: nothing for " + quiet + " ms, opening a fresh connection");
                        openAudio();
                    }
                    continue;
                }
                if ("a".equals(f.type())) audioFrame(f, a.via);
            }
        } catch (IOException e) {
            Log.d(TAG, "audio: " + e.getMessage());
        }
        drop(a);
    }

    /** Plays a frame unless the other link already brought it (or a newer one). Frames from a laptop that
     *  doesn't number its streams (`id`) come over one link only, and all play. */
    private void audioFrame(Proto.Frame f, String via) {
        AudioSink s = audioSink;
        if (s == null) return;
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            if (f.h.has("id")) {
                int id = f.h.optInt("id"), seq = (int) f.h.optLong("s");
                if (id == audioId && seq - audioSeq <= 0 && now - audioAt < 30_000) return; // seen it
                if (now - audioAt > 500 && audioAt > 0) {
                    Log.d(TAG, "audio: " + (now - audioAt) + " ms gap, resumed via " + via);
                }
                audioId = id;
                audioSeq = seq;
            }
            audioAt = now;
            s.onAudio(f.h.optInt("c"), f.payload, 0, f.payload.length); // in order, whichever link it came on
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
