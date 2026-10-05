package com.aidenwb.tandem;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings.Global;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This phone's identity and the one computer it's paired with: {id, name, fp (its TLS certificate's
 * SHA-256), token (what we show it on every network connection), bt (its Bluetooth address), addrs, port}.
 */
final class Pairing {
    private Pairing() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("pairing", Context.MODE_PRIVATE);
    }

    static synchronized String myId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("my_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("my_id", id).apply();
        }
        return id;
    }

    static String myName(Context c) {
        String n = Global.getString(c.getContentResolver(), Global.DEVICE_NAME);
        return n != null && !n.isEmpty() ? n : Build.MODEL;
    }

    static boolean paired(Context c) {
        return !prefs(c).getString("id", "").isEmpty() && !prefs(c).getString("token", "").isEmpty();
    }

    static String id(Context c) { return prefs(c).getString("id", ""); }
    static String name(Context c) { return prefs(c).getString("name", "your computer"); }
    static String fp(Context c) { return prefs(c).getString("fp", ""); }
    static String token(Context c) { return prefs(c).getString("token", ""); }
    static String bt(Context c) { return prefs(c).getString("bt", ""); }
    static int port(Context c) { return prefs(c).getInt("port", Proto.DEFAULT_PORT); }
    static String lastAddr(Context c) { return prefs(c).getString("last_addr", ""); }

    static List<String> addrs(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("addrs", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void save(Context c, String id, String name, String fp, String token, String bt) {
        prefs(c).edit().putString("id", id).putString("name", name).putString("fp", fp).putString("token", token)
                .putString("bt", bt).apply();
    }

    static void setToken(Context c, String token) {
        prefs(c).edit().putString("token", token).apply();
    }

    static void setName(Context c, String name) {
        if (name != null && !name.isEmpty()) prefs(c).edit().putString("name", name).apply();
    }

    static void setAddrs(Context c, JSONArray addrs, int port) {
        if (addrs == null) return;
        prefs(c).edit().putString("addrs", addrs.toString()).putInt("port", port > 0 ? port : Proto.DEFAULT_PORT).apply();
    }

    static void setLastAddr(Context c, String addr) {
        prefs(c).edit().putString("last_addr", addr).apply();
    }

    static void forget(Context c) {
        String me = myId(c);
        prefs(c).edit().clear().putString("my_id", me).apply();
    }
}
