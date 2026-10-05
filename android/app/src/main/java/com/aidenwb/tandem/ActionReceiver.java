package com.aidenwb.tandem;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Buttons on the app's notifications. */
public class ActionReceiver extends BroadcastReceiver {
    static final String PAIR_ALLOW = "com.aidenwb.tandem.PAIR_ALLOW";
    static final String PAIR_DENY = "com.aidenwb.tandem.PAIR_DENY";
    static final String RING_STOP = "com.aidenwb.tandem.RING_STOP";
    static final String RING_COMPUTER = "com.aidenwb.tandem.RING_COMPUTER";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        switch (a) {
            case PAIR_ALLOW:
                Link.get(ctx).answerPairing(true);
                break;
            case PAIR_DENY:
                Link.get(ctx).answerPairing(false);
                break;
            case RING_STOP:
                Features.get(ctx).ring.stop();
                break;
            case RING_COMPUTER:
                FindPhone.ringComputer(ctx);
                break;
            default:
        }
    }
}
