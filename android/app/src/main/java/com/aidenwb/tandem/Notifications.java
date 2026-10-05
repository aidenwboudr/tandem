package com.aidenwb.tandem;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** The app's own notification channels and the one-off notifications features post. */
final class Notifications {
    static final String CH_LINK = "link", CH_PAIR = "pair", CH_EVENTS = "events", CH_RING = "ring";
    static final int ID_LINK = 1, ID_PAIR = 2, ID_RING = 3, ID_EVENTS = 100;
    private static int nextId = ID_EVENTS;

    private Notifications() {}

    static void channels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        // MIN: the link is always there, so keep it out of the status bar; it still sits in the shade.
        NotificationChannel link = new NotificationChannel(CH_LINK, "Link with the computer", NotificationManager.IMPORTANCE_MIN);
        link.setShowBadge(false);
        nm.createNotificationChannel(link);
        nm.createNotificationChannel(new NotificationChannel(CH_PAIR, "Pairing requests", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_EVENTS, "Files and links from the computer",
                NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel ring = new NotificationChannel(CH_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH);
        ring.setSound(null, null); // FindPhone plays its own sound on the alarm stream
        ring.setBypassDnd(true);
        nm.createNotificationChannel(ring);
    }

    static PendingIntent action(Context ctx, String action, int code) {
        return PendingIntent.getBroadcast(ctx, code, new Intent(ctx, ActionReceiver.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openApp(Context ctx) {
        return PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }

    static void pairRequest(Context ctx, String name) {
        channels(ctx);
        Notification n = new Notification.Builder(ctx, CH_PAIR)
                .setSmallIcon(R.drawable.ic_tandem)
                .setContentTitle("Pair with " + name + "?")
                .setContentText("It wants to use Tandem with this phone. Only allow it if it's your computer.")
                .setContentIntent(openApp(ctx))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Allow", action(ctx, ActionReceiver.PAIR_ALLOW, 10))
                        .setAuthenticationRequired(true).build()) // not from the lock screen
                .addAction(new Notification.Action.Builder(null, "Deny", action(ctx, ActionReceiver.PAIR_DENY, 11)).build())
                .build();
        ctx.getSystemService(NotificationManager.class).notify(ID_PAIR, n);
    }

    static void cancelPairRequest(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(ID_PAIR);
    }

    /** A plain notification ("Received photo.jpg"), opening `tap` when tapped. */
    static void event(Context ctx, String title, String text, PendingIntent tap) {
        channels(ctx);
        Notification.Builder b = new Notification.Builder(ctx, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_tandem)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(tap != null ? tap : openApp(ctx));
        synchronized (Notifications.class) {
            nextId = nextId >= ID_EVENTS + 50 ? ID_EVENTS : nextId + 1;
            ctx.getSystemService(NotificationManager.class).notify(nextId, b.build());
        }
    }
}
