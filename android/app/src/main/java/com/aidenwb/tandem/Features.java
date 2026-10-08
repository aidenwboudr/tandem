package com.aidenwb.tandem;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;

/**
 * Routes the computer's messages to the features, and keeps each feature's watchers in step with its
 * setting. Runs on the link's thread.
 */
final class Features implements Link.Listener, Settings.Listener {
    private static final String TAG = "Tandem";
    private static Features instance;

    static synchronized Features get(Context ctx) {
        if (instance == null) instance = new Features(ctx.getApplicationContext());
        return instance;
    }

    final Context ctx;
    final Status status;
    final Calls calls;
    final FindPhone ring;
    final Files files;
    final Screenshots screenshots;
    private boolean started;

    private Features(Context ctx) {
        this.ctx = ctx;
        status = new Status(ctx);
        calls = new Calls(ctx);
        ring = new FindPhone(ctx);
        files = new Files(ctx);
        screenshots = new Screenshots(ctx);
    }

    /** From LinkService.onCreate: start the watchers the settings ask for. */
    void start() {
        if (started) return;
        started = true;
        Link.get(ctx).addListener(this);
        Settings.addListener(this);
        apply();
    }

    void stop() {
        started = false;
        Link.get(ctx).removeListener(this);
        Settings.removeListener(this);
        status.stop();
        calls.stop();
        screenshots.stop();
    }

    private void apply() {
        status.start();
        if (Settings.on(ctx, "calls") || Settings.on(ctx, "call_pause_media")) calls.start();
        else calls.stop();
        if (Settings.on(ctx, "screenshots")) screenshots.start();
        else screenshots.stop();
        ClipSync.get(ctx).startWatch();
    }

    @Override
    public void onSettingsChanged(List<String> keys) {
        Link.get(ctx).handler().post(this::apply);
    }

    @Override
    public void onLinkChanged() {
        Link link = Link.get(ctx);
        if (link.connected()) {
            status.sendNow();
            ClipSync.get(ctx).retryNow();
            files.retryNow();
        }
        LinkService.refreshNotification(ctx);
    }

    void onMessage(String via, Proto.Frame f) {
        JSONObject h = f.h;
        try {
            switch (f.type()) {
                case "settings":
                    Settings.merge(ctx, h);
                    break;
                case "ack":
                    LinkService.onAck(h);
                    break;
                case "switch":
                    break;
                case "cmd":
                    if (Settings.on(ctx, "media_controls")) LinkService.onMediaCommand(ctx, h);
                    break;
                case "clip":
                    ClipSync.get(ctx).onClip(h, f.payload);
                    break;
                case "status":
                    status.onComputerStatus(h);
                    break;
                case "notif-dismiss":
                case "notif-reply":
                case "notif-action":
                    MediaListener.onCommand(ctx, h);
                    break;
                case "call-cmd":
                    calls.onCommand(h);
                    break;
                case "ring":
                    ring.onRing(h);
                    break;
                case "open":
                    files.onOpen(h);
                    break;
                case "file":
                    if (f.file != null) files.onFile(h, f.file);
                    break;
                case "file-no":
                    files.onRefused(h);
                    break;
                case "key":
                    if (Settings.on(ctx, "remote_input")) Keyboard.onKey(h);
                    break;
                case "dnd":
                    status.onDnd(h);
                    break;
                default:
            }
        } catch (RuntimeException e) {
            Log.w(TAG, f.type(), e);
        }
    }
}
