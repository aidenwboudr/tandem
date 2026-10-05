package com.aidenwb.tandem;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

/**
 * An invisible activity that holds focus just long enough to read the clipboard (Android lets only the
 * focused app read it), queues the clip for the laptop, and closes. Started by ClipSync when a copy is
 * seen, by the notification's "Send clipboard" button, and as the Share -> Tandem target.
 */
public class ClipGrabActivity extends Activity {
    private static final String TAG = "Tandem";
    static final String EXTRA_MANUAL = "manual";
    private static final long GIVE_UP_MS = 1500;

    static volatile boolean active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean done;

    /** From the background: needs "Display over other apps", like opening Spotify does. */
    static void grab(Context ctx) {
        try {
            ctx.startActivity(ClipSync.grabIntent(ctx));
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard: can't open the reader", e);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        active = true;
        Intent in = getIntent();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            share(in);
            return;
        }
        main.postDelayed(this::finishNow, GIVE_UP_MS); // never got focus (e.g. the screen locked)
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || done || Intent.ACTION_SEND.equals(getIntent().getAction())) return;
        ClipSync.get(this).offerCurrent(getIntent().getBooleanExtra(EXTRA_MANUAL, false));
        if (getIntent().getBooleanExtra(EXTRA_MANUAL, false)) toast("Clipboard sent to " + Pairing.name(this));
        finishNow();
    }

    private void share(Intent in) {
        CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        Uri uri = in.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        String type = in.getType();
        ClipSync sync = ClipSync.get(this);
        if (uri != null && type != null && type.startsWith("image/")) {
            // Read it before finishing: the share's permission on the URI ends with this activity.
            new Thread(() -> {
                sync.offerUri(uri, getContentResolver().getType(uri) != null ? getContentResolver().getType(uri) : type, true);
                main.post(() -> {
                    toast("Image sent to " + Pairing.name(this) + "'s clipboard");
                    finishNow();
                });
            }, "tandem-share").start();
            return;
        }
        if (text != null && text.length() > 0) {
            sync.offer("text/plain", text.toString().getBytes(StandardCharsets.UTF_8), true);
            toast("Sent to " + Pairing.name(this) + "'s clipboard");
        } else {
            toast("Tandem can send text and images");
        }
        finishNow();
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    private void finishNow() {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        finish();
        if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        else overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        active = false;
        super.onDestroy();
    }
}
