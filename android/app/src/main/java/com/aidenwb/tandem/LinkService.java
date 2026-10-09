package com.aidenwb.tandem;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The always-on foreground service. It keeps the link with the computer up (Link, Features) and runs
 * audio sharing: it tells the computer once a second, while the headphones are on, whether they're the
 * phone's output ("hp") and connected at all ("hp_linked"), and plays the computer's audio into them
 * while the phone is the hub. Only then does it hold a wake lock; otherwise the phone sleeps as usual.
 */
public class LinkService extends Service {
    private static final String TAG = "Tandem";
    private static final String ACTION_PLAY = "com.aidenwb.tandem.PLAY";
    private static final String ACTION_SWITCH = "com.aidenwb.tandem.SWITCH";

    // Read by MainActivity and the features.
    static volatile boolean running;
    static volatile String headphones;
    static volatile BluetoothDevice hpDevice;
    static volatile String owner;
    static volatile long lastAckAt, lastAudioAt, lastLoudAt, packets;
    static volatile String error;
    static volatile String prefer; // the computer's hub setting, "phone" or "laptop" (from its acks)
    static volatile boolean hpLinked; // connected to the phone, even if its audio goes elsewhere
    private static volatile LinkService instance;

    private volatile boolean stop;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private String shownText;
    private PhoneMedia media;
    private String lastMediaLogged;
    private volatile boolean heartbeatNow; // a mixer command landed: report the new state right away
    private volatile boolean wakeNow; // the Bluetooth broadcast: look for the headphones now
    // A call (phone or app) is on: laptop audio stays off Bluetooth (hb `bta`), so the headphones' call audio
    // has the radio to itself. Sharing it with the laptop's stream made calls choppy.
    private volatile boolean inCall;
    private PlayLauncher launcher;
    private volatile BluetoothA2dp a2dp;
    private volatile String switchTo; // a hub switch to send with the next heartbeat
    private final Player player = new Player();
    private volatile AudioDeviceInfo hpOut;

    static void start(Context ctx) {
        send(ctx, new Intent(ctx, LinkService.class));
    }

    /** Asks the computer to make "laptop" or "phone" the hub ("toggle" flips it). */
    static void switchHub(Context ctx, String to) {
        send(ctx, new Intent(ctx, LinkService.class).setAction(ACTION_SWITCH).putExtra("to", to));
    }

    /** A headphone play press that reached the app's media button receiver. */
    static void play(Context ctx) {
        send(ctx, new Intent(ctx, LinkService.class).setAction(ACTION_PLAY));
    }

    /** Something the notification shows changed (link, the computer's battery). */
    static void refreshNotification(Context ctx) {
        LinkService s = instance;
        if (s != null) {
            s.shownText = null;
            s.wakeNow = true;
        }
    }

    private static void send(Context ctx, Intent intent) {
        try {
            ctx.startForegroundService(intent);
        } catch (RuntimeException e) { // a background-start refusal
            Log.w(TAG, "can't start: " + e);
            error = "can't start: " + e.getMessage();
        }
    }

    static void onAck(JSONObject o) {
        owner = o.isNull("owner") ? null : o.optString("owner");
        LinkService s = instance;
        if (o.has("prefer") && (s == null || s.switchTo == null)) prefer = o.optString("prefer");
        lastAckAt = SystemClock.elapsedRealtime();
        String hp = o.optString("headphones", "");
        if (!hp.isEmpty() && s != null) Prefs.get(s).edit().putString("shared_hp", hp).apply();
    }

    static void onMediaCommand(Context ctx, JSONObject o) {
        LinkService s = instance;
        if (s == null || s.media == null) return;
        try {
            s.media.apply(o);
        } catch (RuntimeException e) {
            Log.w(TAG, "cmd", e);
        }
        s.heartbeatNow = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Notifications.channels(this);
        Prefs.migrate(this);
        // Connected A2DP devices, for "linked but not the output". Public API, BLUETOOTH_CONNECT.
        try {
            getSystemService(BluetoothManager.class).getAdapter().getProfileProxy(this,
                    new BluetoothProfile.ServiceListener() {
                        @Override
                        public void onServiceConnected(int profile, BluetoothProfile proxy) {
                            a2dp = (BluetoothA2dp) proxy;
                        }

                        @Override
                        public void onServiceDisconnected(int profile) {
                            a2dp = null;
                        }
                    }, BluetoothProfile.A2DP);
        } catch (RuntimeException e) {
            Log.w(TAG, "a2dp proxy", e);
        }
        Link.get(this).start();
        Features.get(this).start();
        ClipSync.get(this).start();
    }

    /** The headphones if they're A2DP-connected to the phone (output or not), else null. */
    private BluetoothDevice linkedHeadphones() {
        BluetoothA2dp p = a2dp;
        if (p == null) return null;
        try {
            BluetoothDevice any = null;
            String shared = Prefs.get(this).getString("shared_hp", "");
            for (BluetoothDevice d : p.getConnectedDevices()) {
                if (d.getAddress().equalsIgnoreCase(shared)) return d; // the ones the computer shares
                if (any == null && Prefs.isHeadphones(this, d.getAddress(), d.getName())) any = d;
            }
            if (any != null) return any;
        } catch (SecurityException e) {
            error = "needs the Bluetooth permission";
        }
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(Notifications.ID_LINK, notification("Starting"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (RuntimeException e) {
            error = "foreground: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null) {
            stop = false;
            running = true;
            error = null;
            worker = new Thread(this::loop, "tandem");
            worker.start();
        }
        wakeNow = true;
        if (launcher == null) launcher = new PlayLauncher(this);
        launcher.start();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY.equals(action)) launcher.press();
        if (ACTION_SWITCH.equals(action)) {
            String to = intent.getStringExtra("to");
            if ("toggle".equals(to)) to = "laptop".equals(prefer) ? "phone" : "laptop";
            switchTo = to;
            prefer = to; // show it at once; the computer's ack confirms
            shownText = null;
            heartbeatNow = true;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        if (worker != null) worker.interrupt();
        if (launcher != null) launcher.stop();
        Features.get(this).stop();
        ClipSync.get(this).stop();
        Link link = Link.get(this);
        link.handler().post(() -> { // closing TLS sockets is network I/O: not on the main thread
            link.wantAudio(null);
            link.stop();
        });
        BluetoothA2dp p = a2dp;
        if (p != null) {
            try {
                getSystemService(BluetoothManager.class).getAdapter().closeProfileProxy(BluetoothProfile.A2DP, p);
            } catch (RuntimeException ignored) {
            }
        }
        running = false;
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Link.AudioSink sink = (codec, buf, off, len) -> {
        AudioDeviceInfo hp = hpOut;
        synchronized (player) {
            if (codec < 0 || hp == null) {
                if (player.isOpen()) player.release(); // nothing playing on the computer
                return;
            }
            packets++;
            lastAudioAt = SystemClock.elapsedRealtime();
            if (wifi != null && !wifi.isHeld()) wifi.acquire();
            player.feed(hp, codec, buf, off, len);
        }
    };

    private void loop() {
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tandem:link"); // only while the headphones are on
        wifi = getSystemService(WifiManager.class).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tandem");
        media = new PhoneMedia(this);
        Link link = Link.get(this);
        long lastHb = 0, lastHpCheck = 0, lastLinkCheck = 0;
        BluetoothDevice linked = null;
        boolean lastHp = false;
        AudioManager am = getSystemService(AudioManager.class);
        // Sound on this phone's radio (a call, music to the headphones or through the computer): chunks of a
        // big transfer over Bluetooth wait for it, so they don't make it cut out.
        link.xfer.radioBusy = () -> inCall || am.isMusicActive();
        try {
            while (!stop) {
                long now = SystemClock.elapsedRealtime();
                int mode = am.getMode();
                boolean call = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                        || mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING
                        || mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT;
                if (call != inCall) {
                    inCall = call;
                    heartbeatNow = true;
                    Log.i(TAG, call ? "call: laptop audio off Bluetooth" : "call over: laptop audio back on Bluetooth");
                }
                // A binder call. Without the headphones, check less often (the Bluetooth broadcast also
                // pokes the service when they connect).
                if (now - lastHpCheck >= (hpLinked ? 200 : 1000) || wakeNow) {
                    wakeNow = false;
                    lastHpCheck = now;
                    hpOut = Prefs.headphones(this);
                }
                if (now - lastLinkCheck >= 1000) {
                    lastLinkCheck = now;
                    linked = linkedHeadphones();
                }
                AudioDeviceInfo hp = hpOut;
                boolean hpOn = hp != null;
                boolean share = Settings.on(this, "audio_share");
                hpLinked = share && (hpOn || linked != null);
                hpDevice = linked;
                headphones = linked != null ? nameOf(linked) : hpOn ? String.valueOf(hp.getProductName()) : null;
                if (hpLinked && !wake.isHeld()) {
                    wake.acquire(); // the audio link must keep running with the screen off
                } else if (!hpLinked && wake.isHeld()) {
                    wake.release();
                }
                // Keep playing what comes over Bluetooth (and an audio connection that still works) while the
                // control connection reconnects.
                link.wantAudio(share && hpOn && (link.net != null || link.bt != null) ? sink : null);

                long every = hpLinked ? 1000 : 15_000;
                // Over Bluetooth too when the network is down: without heartbeats the computer can't tell the
                // headphones are on, so it neither hands them over nor takes them back.
                if (link.connected() && (now - lastHb >= every || hpOn != lastHp || heartbeatNow)) {
                    lastHb = now;
                    heartbeatNow = false;
                    lastHp = hpOn;
                    sendHeartbeat(link, share && hpOn, linked);
                    String to = switchTo;
                    if (to != null) {
                        switchTo = null;
                        try {
                            link.send(Proto.msg("switch").put("to", to), null);
                            Log.i(TAG, "asked the computer to make the " + to + " the hub");
                        } catch (JSONException ignored) {
                        }
                    }
                }
                synchronized (player) {
                    if (player.isOpen() && now - lastAudioAt > 3000) {
                        player.release(); // the computer went quiet: don't hold an idle track
                        if (wifi.isHeld()) wifi.release();
                    }
                }
                updateNotification(hpOn, hpLinked, now);
                try {
                    Thread.sleep(hpLinked ? 200 : 1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            // Stopped (app update, permission revoked): let the computer take the headphones back right away.
            if (hpLinked) for (int i = 0; i < 3; i++) sendHeartbeat(link, false, null);
        } finally {
            link.wantAudio(null);
            synchronized (player) {
                player.release();
            }
            if (wifi.isHeld()) wifi.release();
            if (wake.isHeld()) wake.release();
            owner = null;
            headphones = null;
            hpDevice = null;
            running = false;
            worker = null;
        }
    }

    private static String nameOf(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    private void sendHeartbeat(Link link, boolean hp, BluetoothDevice linked) {
        try {
            JSONObject hb = Proto.msg("hb").put("v", Proto.VERSION).put("hp", hp).put("hp_linked", hp || hpLinked)
                    .put("hpname", headphones == null ? "" : headphones)
                    .put("hpaddr", linked != null ? linked.getAddress() : hpOut != null ? hpOut.getAddress() : "")
                    .put("codec", Prefs.codec(this))
                    .put("bta", !inCall); // laptop audio may come over Bluetooth too (Link.audioFrame)
            if (Settings.on(this, "media_controls")) {
                try {
                    media.describe(hb);
                    for (JSONObject art : media.artPackets(hb)) {
                        byte[] jpeg = Base64.decode(art.optString("jpeg"), Base64.DEFAULT);
                        link.send(Proto.msg("art").put("key", art.optString("key")), jpeg);
                    }
                } catch (RuntimeException e) { // a player's session died mid-read; skip it this beat
                    Log.w(TAG, "media", e);
                }
                String m = String.valueOf(hb.opt("media")).replaceAll("\"pos\":\\d+,?", "");
                if (!m.equals(lastMediaLogged)) { // `adb logcat -s Tandem` shows what the mixer sees
                    lastMediaLogged = m;
                    Log.i(TAG, "media " + m);
                }
            }
            link.send(hb, null);
        } catch (JSONException | SecurityException e) {
            error = "send: " + e.getMessage();
        }
    }

    private void updateNotification(boolean hpOn, boolean linked, long now) {
        Link link = Link.get(this);
        String name = Pairing.name(this);
        String text;
        boolean acked = now - lastAckAt <= 5000;
        if (!Pairing.paired(this)) text = "Not paired · open Tandem to set it up";
        else if (!link.connected()) text = name + " not connected";
        else if (!linked) {
            String via = link.net != null && link.bt != null ? "Bluetooth + network" : link.net != null ? "network" : "Bluetooth";
            text = "Connected to " + name + " · " + via;
            int b = Status.computerBattery;
            if (b >= 0) text += " · " + b + "%" + (Status.computerCharging ? " ⚡" : "");
        } else if (!acked) text = name + " not answering · phone audio only";
        else if (!hpOn && "laptop".equals(owner)) text = name + " is the hub · phone audio plays through it";
        else if (!hpOn) text = "Phone audio isn't going to the headphones";
        else if (now - lastAudioAt < 2000) text = "Phone is the hub · playing " + name + "'s audio";
        else text = "Phone is the hub · " + name + "'s audio will play here";
        if (linked && link.bt != null && link.net == null) text += " · over Bluetooth, it may stutter";
        String sending = link.xfer.summary();
        if (sending != null) text += " · " + sending;
        String key = text + "|" + (acked ? prefer : null) + "|" + linked;
        if (key.equals(shownText)) return;
        shownText = key;
        getSystemService(NotificationManager.class).notify(Notifications.ID_LINK, notification(text));
    }

    private Notification notification(String text) {
        Notification.Builder b = new Notification.Builder(this, Notifications.CH_LINK)
                .setSmallIcon(headphones != null ? R.drawable.ic_headphones : R.drawable.ic_tandem)
                .setContentTitle(headphones != null ? headphones : "Tandem")
                .setContentText(text)
                .setContentIntent(Notifications.openApp(this))
                .setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (!Pairing.paired(this)) return b.build();
        int actions = 0;
        if (Settings.on(this, "clip_to_laptop")) {
            PendingIntent clip = PendingIntent.getActivity(this, 2,
                    ClipSync.grabIntent(this).putExtra(ClipGrabActivity.EXTRA_MANUAL, true),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Send clipboard", clip).build());
            actions++;
        }
        String p = prefer;
        if (p != null && hpLinked && SystemClock.elapsedRealtime() - lastAckAt <= 5000) {
            String to = "laptop".equals(p) ? "phone" : "laptop";
            PendingIntent sw = PendingIntent.getForegroundService(this, 1,
                    new Intent(this, LinkService.class).setAction(ACTION_SWITCH).putExtra("to", to),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null,
                    "laptop".equals(to) ? "Make " + Pairing.name(this) + " the hub" : "Make the phone the hub", sw).build());
            actions++;
        }
        if (actions < 3 && Settings.on(this, "find_phone")) {
            b.addAction(new Notification.Action.Builder(null, "Find " + Pairing.name(this),
                    Notifications.action(this, ActionReceiver.RING_COMPUTER, 3)).build());
        }
        return b.build();
    }
}
