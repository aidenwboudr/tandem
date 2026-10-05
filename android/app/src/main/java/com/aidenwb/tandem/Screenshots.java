package com.aidenwb.tandem;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** New screenshots go to the computer (its Pictures/Phone folder, and its clipboard if that's on). */
final class Screenshots {
    private static final String TAG = "Tandem";
    private final Context ctx;
    private ContentObserver observer;
    private long since;
    private final Set<Long> done = new LinkedHashSet<>();

    Screenshots(Context ctx) {
        this.ctx = ctx;
    }

    static boolean canWatch(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (observer != null || !canWatch(ctx)) return;
        since = System.currentTimeMillis() / 1000 - 5;
        observer = new ContentObserver(Link.get(ctx).handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                check();
            }
        };
        ctx.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
    }

    void stop() {
        if (observer == null) return;
        ctx.getContentResolver().unregisterContentObserver(observer);
        observer = null;
    }

    private void check() {
        if (!Settings.on(ctx, "screenshots")) return;
        Bundle q = new Bundle();
        q.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Images.Media.DATE_ADDED + " >= ? AND " + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?");
        q.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{String.valueOf(since), "%Screenshots%"});
        q.putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{MediaStore.Images.Media.DATE_ADDED});
        q.putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        q.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 3);
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, q, null)) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                if (done.contains(id)) continue;
                done.add(id);
                if (done.size() > 50) done.remove(done.iterator().next());
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                try {
                    Features.get(ctx).files.queue(uri, "screenshot");
                    Log.i(TAG, "screenshots: sending a new one");
                } catch (IOException e) {
                    done.remove(id); // still being written: try on the next change
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "screenshots", e);
        }
    }
}
