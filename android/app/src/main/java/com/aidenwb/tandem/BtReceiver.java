package com.aidenwb.tandem;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * Wakes the app when the headphones connect; the service handles everything after that. Also
 * gets the headphones' play presses that Android sends to the last app that held the media buttons
 * (see PlayLauncher).
 */
public class BtReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                LinkService.start(ctx);
                return;
            case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return;
                // fall through
            case BluetoothDevice.ACTION_ACL_CONNECTED:
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                String name = null;
                try {
                    if (dev != null) name = dev.getName();
                } catch (SecurityException ignored) {
                }
                if (dev != null && Prefs.isHeadphones(ctx, dev.getAddress(), name)) LinkService.start(ctx);
                return;
            case Intent.ACTION_MEDIA_BUTTON:
                KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
                if (ke == null || ke.getAction() != KeyEvent.ACTION_DOWN || ke.getRepeatCount() > 0) return;
                int k = ke.getKeyCode();
                boolean play = k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || k == KeyEvent.KEYCODE_HEADSETHOOK;
                if (play && Settings.on(ctx, "play_opens_app") && Prefs.headphones(ctx) != null) LinkService.play(ctx);
                return;
            default:
        }
    }
}
