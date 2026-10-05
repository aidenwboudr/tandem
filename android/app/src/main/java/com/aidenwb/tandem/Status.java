package com.aidenwb.tandem;

import android.app.NotificationManager;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Battery (the phone's and the headphones'), Do Not Disturb, and the computer's status in return.
 * Sent when something changes and when the link comes up.
 */
final class Status {
    private static final String TAG = "Tandem";
    // Hidden but stable since Android 8; the public alternative needs a system app.
    private static final String ACTION_HP_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_HP_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL";

    private final Context ctx;
    private boolean started;
    private int level = -1;
    private boolean charging;
    private int hpLevel = -1;
    private Boolean dnd;
    private Boolean dndApplied; // what we set from the computer, so we don't echo it back
    private String lastSent;

    // The computer's, for the notification and the app.
    static volatile int computerBattery = -1;
    static volatile boolean computerCharging;

    Status(Context ctx) {
        this.ctx = ctx;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                level = l >= 0 && s > 0 ? l * 100 / s : -1;
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            } else if (ACTION_HP_BATTERY.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                BluetoothDevice hp = LinkService.hpDevice;
                if (d != null && hp != null && d.getAddress().equals(hp.getAddress())) {
                    hpLevel = i.getIntExtra(EXTRA_HP_BATTERY, -1);
                }
            } else if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(a)) {
                onLocalDnd();
            }
            Link.get(ctx).handler().post(() -> send(false));
        }
    };

    void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(ACTION_HP_BATTERY);
        f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        dnd = dndOn();
    }

    void stop() {
        if (!started) return;
        started = false;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private boolean dndOn() {
        int f = ctx.getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }

    private void onLocalDnd() {
        boolean now = dndOn();
        if (dnd != null && dnd == now) return;
        dnd = now;
        if (dndApplied != null && dndApplied == now) {
            dndApplied = null; // our own change
            return;
        }
        if (Settings.on(ctx, "dnd_sync")) {
            try {
                Link.get(ctx).send(Proto.msg("dnd").put("on", now), null);
            } catch (JSONException ignored) {
            }
        }
    }

    void onDnd(JSONObject h) {
        if (!Settings.on(ctx, "dnd_sync")) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) {
            Log.i(TAG, "dnd: no Do Not Disturb access; can't follow the computer");
            return;
        }
        boolean on = h.optBoolean("on");
        if (dnd != null && dnd == on) return;
        dndApplied = on;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
    }

    void onComputerStatus(JSONObject h) {
        JSONObject b = h.optJSONObject("battery");
        computerBattery = b != null ? b.optInt("level", -1) : -1;
        computerCharging = b != null && b.optBoolean("charging");
        LinkService.refreshNotification(ctx);
    }

    /** The headphones' battery, if Android knows it (most headphones report it over HFP or BLE). */
    int headphoneBattery() {
        BluetoothDevice hp = LinkService.hpDevice;
        if (hp == null) return -1;
        if (hpLevel >= 0) return hpLevel;
        try {
            Object v = BluetoothDevice.class.getMethod("getBatteryLevel").invoke(hp);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (ReflectiveOperationException | SecurityException e) {
            return -1;
        }
    }

    void sendNow() {
        lastSent = null;
        send(true);
    }

    private long lastSendAt;

    void send(boolean force) {
        if (!Settings.on(ctx, "battery") && !Settings.on(ctx, "dnd_sync")) return;
        try {
            JSONObject m = Proto.msg("status");
            if (Settings.on(ctx, "battery") && level >= 0) {
                m.put("battery", new JSONObject().put("level", level).put("charging", charging));
                int hb = headphoneBattery();
                if (hb >= 0) m.put("hp_battery", hb).put("hp_name", LinkService.headphones);
            }
            if (Settings.on(ctx, "dnd_sync") && dnd != null) m.put("dnd", dnd);
            String key = m.toString();
            long now = SystemClock.elapsedRealtime();
            if (!force && (key.equals(lastSent) || now - lastSendAt < 10_000)) return;
            if (Link.get(ctx).send(m, null)) {
                lastSent = key;
                lastSendAt = now;
            }
        } catch (JSONException ignored) {
        }
    }
}
