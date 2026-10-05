package com.aidenwb.tandem;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Share -> "Send to computer": files go to its Downloads folder, a link opens in its browser, and other
 * text goes onto its clipboard. Invisible; it copies what it needs and closes.
 */
public class ShareActivity extends Activity {
    private static final String TAG = "Tandem";
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        if (!Pairing.paired(this)) {
            toast("Pair Tandem with your computer first");
            finish();
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (list != null) uris.addAll(list);
        } else {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (u != null) uris.add(u);
        }
        String name = Pairing.name(this);
        if (uris.isEmpty()) {
            CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text == null || text.length() == 0) {
                toast("Nothing to send");
            } else if (Files.isLink(text) && Settings.on(this, "open_links")) {
                toast(Files.sendLink(this, text.toString()) ? "Opening on " + name : "Not connected to " + name);
            } else {
                ClipSync.get(this).offer("text/plain", text.toString().getBytes(StandardCharsets.UTF_8), true);
                toast("Copied to " + name + "'s clipboard");
            }
            finish();
            return;
        }
        if (!Settings.on(this, "files")) {
            toast("Turn on Files in Tandem to send files");
            finish();
            return;
        }
        // Copy before finishing: the share's permission on the URIs ends with this activity.
        new Thread(() -> {
            int ok = 0;
            for (Uri u : uris) {
                try {
                    Features.get(this).files.queue(u, "file");
                    ok++;
                } catch (IOException | SecurityException e) {
                    Log.w(TAG, "share", e);
                }
            }
            int n = ok;
            main.post(() -> {
                boolean up = Link.get(this).connected();
                toast(n == 0 ? "Couldn't read that" : (n == 1 ? "Sending to " : "Sending " + n + " files to ") + name
                        + (up ? "" : " when it's connected"));
                finish();
            });
        }, "tandem-share").start();
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
