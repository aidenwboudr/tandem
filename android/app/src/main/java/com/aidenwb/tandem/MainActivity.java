package com.aidenwb.tandem;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Setup (pairing with a computer) and every feature as a switch, each with what it needs to work.
 * The switches are shared with the computer: changing one here changes it there too.
 */
public class MainActivity extends Activity implements Settings.Listener, Link.Listener {
    static final String REPO = "https://github.com/aidenwboudr/tandem";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Runnable> refreshers = new ArrayList<>();
    private LinearLayout col;
    private int accent;

    /** Something a feature needs that the user has to grant. */
    private abstract class Need {
        final String label;

        Need(String label) {
            this.label = label;
        }

        abstract boolean met();

        abstract void grant();
    }

    private Need perm(String label, String... perms) {
        return new Need(label) {
            boolean met() {
                for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
                return true;
            }

            void grant() {
                requestPermissions(perms, 1);
            }
        };
    }

    private final Need notifAccess = new Need("Allow notification access") {
        boolean met() {
            return getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                    new ComponentName(MainActivity.this, MediaListener.class));
        }

        void grant() {
            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(MainActivity.this, MediaListener.class).flattenToString()));
        }
    };

    private final Need overlay = new Need("Allow Display over other apps") {
        boolean met() {
            return android.provider.Settings.canDrawOverlays(MainActivity.this);
        }

        void grant() {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
    };

    private final Need dndAccess = new Need("Allow Do Not Disturb access") {
        boolean met() {
            return getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
        }

        void grant() {
            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        }
    };

    private final Need keyboard = new Need("Turn on the Tandem keyboard") {
        boolean met() {
            for (InputMethodInfo i : getSystemService(InputMethodManager.class).getEnabledInputMethodList()) {
                if (i.getPackageName().equals(getPackageName())) return true;
            }
            return false;
        }

        void grant() {
            startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS));
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true);
        accent = tv.data;
        build();
    }

    private void build() {
        refreshers.clear();
        int pad = dp(20);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad * 2);

        TextView title = text("Tandem", 28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(title);
        TextView tagline = text("Your phone and your computer, working as one.", 14);
        tagline.setAlpha(0.7f);
        tagline.setPadding(0, dp(2), 0, dp(16));
        col.addView(tagline);

        statusCard();
        setupCard();
        basics();

        section("Clipboard");
        feature("clip_to_laptop", "Send my copies to the computer",
                "Copy on the phone, paste on the computer.", null);
        note(this::clipNote);
        feature("clip_from_laptop", "Get the computer's copies",
                "Copy on the computer, paste here. Images too.", null);
        feature("clip_secrets", "Include password manager copies",
                "Off: copies a password manager marks as secret stay on the computer.", null);
        feature("otp_copy", "Copy sign-in codes to the computer",
                "When a text or notification has a one-time code, it lands on the computer's clipboard.", null,
                notifAccess);

        section("Notifications");
        LinearLayout notif = feature("notif_mirror", "Show phone notifications on the computer",
                "Messages, mail, reminders… Media and ongoing notifications stay here.", null, notifAccess);
        sub(notif, "notif_reply", "Reply from the computer", "For messaging apps that offer a reply.");
        sub(notif, "notif_dismiss_sync", "Dismiss on one, gone on both", null);
        notif.addView(button("Choose apps not to show…", v -> pickExcluded()));

        section("Calls");
        feature("calls", "Show calls on the computer",
                "With buttons to silence the ringer or decline.", null,
                perm("Allow phone access", Manifest.permission.READ_PHONE_STATE),
                optional(perm("Allow declining calls", Manifest.permission.ANSWER_PHONE_CALLS)));
        feature("call_pause_media", "Pause the computer's music during calls",
                "And play it again when the call ends.", null,
                perm("Allow phone access", Manifest.permission.READ_PHONE_STATE));

        section("Files and links");
        feature("files", "Send and receive files",
                "Share → Tandem sends to the computer's Downloads. `tandem send` on the computer puts files in "
                        + "Downloads/Tandem here.", null);
        feature("open_links", "Open shared links on the other device",
                "Share a link to Tandem and it opens in the computer's browser, and the other way round.", null,
                optional(overlay));
        LinearLayout shots = feature("screenshots", "Send new screenshots to the computer",
                "They go to its Pictures/Phone folder.", null,
                perm("Allow access to photos", Manifest.permission.READ_MEDIA_IMAGES));
        sub(shots, "screenshot_clipboard", "Also put them on its clipboard", null);

        section("Audio");
        LinearLayout audio = feature("audio_share", "Share audio through one pair of headphones",
                "With Bluetooth headphones on, they stay on one link and you hear both devices: whichever is "
                        + "the hub plays the other's audio. Needs both on the same network.", null,
                perm("Allow Bluetooth", Manifest.permission.BLUETOOTH_CONNECT));
        audio.addView(label("Headphone name contains (empty = any Bluetooth headphones)"));
        EditText match = field(Prefs.match(this), InputType.TYPE_CLASS_TEXT);
        audio.addView(match);
        Switch opus = new Switch(this);
        opus.setText("Compress the computer's audio (off = raw, ~1.5 Mbit/s)");
        opus.setChecked("opus".equals(Prefs.codec(this)));
        opus.setPadding(0, dp(8), 0, dp(8));
        audio.addView(opus);
        audio.addView(button("Save", v -> {
            Prefs.get(this).edit().putString("match", match.getText().toString().trim())
                    .putString("codec", opus.isChecked() ? "opus" : "pcm").apply();
            v.post(() -> ((Button) v).setText("Saved"));
        }));
        Button hub = button("", v -> LinkService.switchHub(this, "toggle"));
        audio.addView(hub);
        refreshers.add(() -> {
            long now = SystemClock.elapsedRealtime();
            boolean can = LinkService.hpLinked && LinkService.prefer != null && now - LinkService.lastAckAt <= 5000;
            hub.setVisibility(can ? View.VISIBLE : View.GONE);
            if (can) hub.setText("laptop".equals(LinkService.prefer)
                    ? "Hub: " + Pairing.name(this) + " · make the phone the hub"
                    : "Hub: phone · make " + Pairing.name(this) + " the hub");
        });
        feature("media_controls", "Control phone media from the computer",
                "Play/pause and volume from its bar, and what's playing.", null, notifAccess);
        LinearLayout play = feature("play_opens_app", "Headphone play button opens my music app",
                "If the app is closed, pressing play opens it and starts playing.", null, overlay, notifAccess);
        Button pickApp = button("", v -> pickPlayApp());
        play.addView(pickApp);
        refreshers.add(() -> pickApp.setText("App: " + appLabel(Settings.str(this, "play_app")) + " · change"));

        section("Phone status");
        LinearLayout bat = feature("battery", "Battery on the computer",
                "Shows the phone's (and headphones') battery there, and warns when it's low.", null);
        bat.addView(label("Warn below (%)"));
        EditText low = field(String.valueOf(Settings.num(this, "battery_low")), InputType.TYPE_CLASS_NUMBER);
        low.setOnFocusChangeListener((v, has) -> {
            if (!has) Settings.set(this, "battery_low", low.getText().toString().trim());
        });
        bat.addView(low);
        LinearLayout find = feature("find_phone", "Find my phone",
                "The computer can make this phone ring at full volume, even on silent (`tandem ring`).", null);
        find.addView(button("Find my computer", v -> FindPhone.ringComputer(this)));
        feature("dnd_sync", "Sync Do Not Disturb",
                "Turn it on or off on one and the other follows (GNOME, mako, swaync and dunst on the computer).",
                null, dndAccess);

        section("Remote control");
        feature("remote_input", "Type on the phone from the computer",
                "Run `tandem type` on the computer, then pick the Tandem keyboard here.", null, keyboard);
        feature("screen_mirror", "Mirror this screen on the computer",
                "`tandem screen` shows and controls the phone (needs scrcpy on the computer, and Wireless "
                        + "debugging on here: Settings → System → Developer options).", null);

        section("Security");
        LinearLayout lock = feature("lock_on_leave", "Lock the computer when I walk away",
                "When this phone leaves Bluetooth range, the computer locks its screen.", null);
        lock.addView(label("After (seconds)"));
        EditText delay = field(String.valueOf(Settings.num(this, "lock_delay")), InputType.TYPE_CLASS_NUMBER);
        delay.setOnFocusChangeListener((v, has) -> {
            if (!has) Settings.set(this, "lock_delay", delay.getText().toString().trim());
        });
        lock.addView(delay);

        footer();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
        refresh();
    }

    // ------------------------------------------------------------ cards

    private void statusCard() {
        LinearLayout card = card();
        TextView line = text("", 17);
        TextView detail = text("", 14);
        detail.setAlpha(0.75f);
        detail.setPadding(0, dp(4), 0, 0);
        card.addView(line);
        card.addView(detail);
        LinearLayout row = new LinearLayout(this);
        Button sendClip = button("Send clipboard", v -> startActivity(ClipSync.grabIntent(this)
                .putExtra(ClipGrabActivity.EXTRA_MANUAL, true)));
        Button ring = button("Find computer", v -> FindPhone.ringComputer(this));
        row.addView(sendClip);
        row.addView(ring);
        card.addView(row);
        col.addView(card);
        refreshers.add(() -> {
            Link link = Link.get(this);
            boolean paired = Pairing.paired(this);
            card.setVisibility(paired ? View.VISIBLE : View.GONE);
            row.setVisibility(link.connected() ? View.VISIBLE : View.GONE);
            ring.setVisibility(Settings.on(this, "find_phone") ? View.VISIBLE : View.GONE);
            sendClip.setVisibility(Settings.on(this, "clip_to_laptop") ? View.VISIBLE : View.GONE);
            if (!paired) return;
            String name = Pairing.name(this);
            if (!link.connected()) {
                line.setText("○  " + name + " isn't connected");
                detail.setText("It connects over Bluetooth when it's nearby with Tandem running, or over the "
                        + "network when you're on the same Wi-Fi (or Tailscale)."
                        + (link.error != null ? "\n" + link.error : ""));
                return;
            }
            line.setText("●  Connected to " + name);
            StringBuilder d = new StringBuilder();
            d.append(link.bt != null ? "Bluetooth ✓" : "Bluetooth ✗").append("   ")
                    .append(link.net != null ? "Network ✓ (" + link.net.addr + ")" : "Network ✗ (big files and audio wait)");
            if (Status.computerBattery >= 0) {
                d.append("\nBattery ").append(Status.computerBattery).append("%").append(Status.computerCharging ? " · charging" : "");
            }
            if (LinkService.headphones != null) d.append("\nHeadphones: ").append(LinkService.headphones);
            if (LinkService.error != null) d.append("\n").append(LinkService.error);
            detail.setText(d);
        });
    }

    private void setupCard() {
        LinearLayout card = card();
        TextView head = text("Set up", 18);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(head);
        TextView steps = text("1.  Install Tandem on your Linux computer (see " + REPO + ").\n"
                + "2.  Pair this phone with the computer in Bluetooth settings, like any device.\n"
                + "3.  Keep this screen open. The computer finds this phone and asks to pair; say yes here.", 14);
        steps.setPadding(0, dp(6), 0, dp(6));
        card.addView(steps);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Bluetooth settings", v -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))));
        row.addView(button("Instructions", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO)))));
        card.addView(row);

        LinearLayout ask = new LinearLayout(this);
        ask.setOrientation(LinearLayout.VERTICAL);
        ask.setPadding(0, dp(12), 0, 0);
        TextView q = text("", 16);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        ask.addView(q);
        TextView warn = text("Only allow it if it's your computer. It will be able to use the features you turn on below.", 13);
        warn.setAlpha(0.75f);
        ask.addView(warn);
        LinearLayout yn = new LinearLayout(this);
        yn.addView(button("Allow", v -> Link.get(this).answerPairing(true)));
        yn.addView(button("Deny", v -> Link.get(this).answerPairing(false)));
        ask.addView(yn);
        card.addView(ask);
        col.addView(card);
        refreshers.add(() -> {
            Link.PendingPair p = Link.get(this).pending;
            card.setVisibility(!Pairing.paired(this) || p != null ? View.VISIBLE : View.GONE);
            ask.setVisibility(p != null ? View.VISIBLE : View.GONE);
            if (p != null) q.setText("Pair with " + p.name + "?");
        });
    }

    private void basics() {
        Button perms = button("Allow Bluetooth + notifications (needed)", v -> requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS}, 1));
        Button battery = button("Let Tandem run in the background (recommended)", v -> startActivity(new Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        col.addView(perms);
        col.addView(battery);
        refreshers.add(() -> {
            boolean ok = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            perms.setVisibility(ok ? View.GONE : View.VISIBLE);
            battery.setVisibility(getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())
                    ? View.GONE : View.VISIBLE);
        });
    }

    private void footer() {
        section("About");
        TextView about = text("", 13);
        about.setAlpha(0.7f);
        col.addView(about);
        Button unpair = button("Unpair from this computer", v -> new AlertDialog.Builder(this)
                .setTitle("Unpair from " + Pairing.name(this) + "?")
                .setMessage("Tandem stops working with it until you pair again.")
                .setPositiveButton("Unpair", (d, w) -> Link.get(this).unpair(true))
                .setNegativeButton("Cancel", null).show());
        col.addView(unpair);
        col.addView(button("Source code and help", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO)))));
        refreshers.add(() -> {
            unpair.setVisibility(Pairing.paired(this) ? View.VISIBLE : View.GONE);
            String v;
            try {
                v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                v = "?";
            }
            about.setText("Tandem " + v + " · free software (GPL-3.0)\nThis phone: " + Pairing.myName(this));
        });
    }

    // ------------------------------------------------------------ feature rows

    private Need optional(Need n) {
        return new Need(n.label + " (optional)") {
            boolean met() {
                return n.met();
            }

            void grant() {
                n.grant();
            }

            @Override
            public String toString() {
                return "optional";
            }
        };
    }

    /** A feature's switch, its description, buttons for what it still needs, and room for its options. */
    private LinearLayout feature(String key, String title, String summary, Runnable onChange, Need... needs) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(6));
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(Settings.on(this, key));
        sw.setOnCheckedChangeListener((b, on) -> {
            if (on == Settings.on(this, key)) return;
            Settings.set(this, key, on);
            if (on) for (Need n : needs) if (!n.met() && !"optional".equals(n.toString())) {
                n.grant();
                break;
            }
            if (onChange != null) onChange.run();
            refresh();
        });
        row.addView(sw);
        TextView s = text(summary, 13);
        s.setAlpha(0.7f);
        row.addView(s);
        List<Button> grants = new ArrayList<>();
        for (Need n : needs) {
            Button g = button(n.label, v -> n.grant());
            g.setTextColor(accent);
            row.addView(g);
            grants.add(g);
        }
        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setPadding(dp(16), 0, 0, 0);
        row.addView(opts);
        col.addView(row);
        refreshers.add(() -> {
            boolean on = Settings.on(this, key);
            if (sw.isChecked() != on) sw.setChecked(on);
            for (int i = 0; i < needs.length; i++) grants.get(i).setVisibility(on && !needs[i].met() ? View.VISIBLE : View.GONE);
            opts.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        return opts;
    }

    private void sub(LinearLayout parent, String key, String title, String summary) {
        Switch sw = new Switch(this);
        sw.setText(summary == null ? title : title + "\n" + summary);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setPadding(0, dp(6), 0, dp(6));
        sw.setChecked(Settings.on(this, key));
        sw.setOnCheckedChangeListener((b, on) -> {
            if (on != Settings.on(this, key)) Settings.set(this, key, on);
        });
        parent.addView(sw);
        refreshers.add(() -> {
            if (sw.isChecked() != Settings.on(this, key)) sw.setChecked(Settings.on(this, key));
        });
    }

    private void note(java.util.function.Supplier<String> text) {
        TextView t = text("", 13);
        t.setPadding(0, 0, 0, dp(4));
        col.addView(t);
        refreshers.add(() -> {
            String s = Settings.on(this, "clip_to_laptop") ? text.get() : "";
            t.setText(s);
            t.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private String clipNote() {
        long now = SystemClock.elapsedRealtime();
        StringBuilder s = new StringBuilder();
        s.append("↑ ").append(ClipSync.toLaptop).append(" sent").append(ago(now, ClipSync.lastToLaptopAt))
                .append("   ↓ ").append(ClipSync.fromLaptop).append(" received").append(ago(now, ClipSync.lastFromLaptopAt))
                .append("\n");
        if (!ClipSync.canWatch(this)) {
            s.append("Android only lets the app on screen read the clipboard, so copies go over when you tap Send "
                    + "clipboard (in the notification) or share text to Tandem. To send every copy automatically, "
                    + "run this once from a computer with adb:\nadb shell pm grant " + getPackageName()
                    + " android.permission.READ_LOGS");
        } else if (!android.provider.Settings.canDrawOverlays(this)) {
            s.append("Every copy can go over automatically once Display over other apps is allowed.");
        } else if (ClipSync.approved) {
            s.append("Every copy goes over automatically.");
        } else if (ClipSync.watching) {
            s.append("Almost: allow Tandem access to device logs when Android asks (it only looks for \"something "
                    + "was copied\"). Switch away and back if the prompt is gone.");
        } else {
            s.append("Not watching for copies. Close and reopen Tandem, and allow access to device logs.");
        }
        if (ClipSync.lastError != null) s.append("\n").append(ClipSync.lastError);
        return s.toString();
    }

    private void pickPlayApp() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC);
        List<ResolveInfo> apps = new ArrayList<>(pm.queryIntentActivities(main, 0));
        if (apps.isEmpty()) apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        String[] labels = new String[apps.size()];
        String[] pkgs = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).loadLabel(pm).toString();
            pkgs[i] = apps.get(i).activityInfo.packageName;
        }
        new AlertDialog.Builder(this).setTitle("Music app")
                .setItems(labels, (d, w) -> {
                    Settings.set(this, "play_app", pkgs[w]);
                    refresh();
                }).show();
    }

    private void pickExcluded() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> all = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            all.add(r.activityInfo.applicationInfo);
        }
        Set<String> seen = new HashSet<>();
        List<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo a : all) if (seen.add(a.packageName) && !a.packageName.equals(getPackageName())) apps.add(a);
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        Set<String> excluded = new HashSet<>(Arrays.asList(Settings.str(this, "notif_excluded").split(",")));
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).loadLabel(pm).toString();
            checked[i] = excluded.contains(apps.get(i).packageName);
        }
        new AlertDialog.Builder(this).setTitle("Don't show these on the computer")
                .setMultiChoiceItems(labels, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("Save", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < apps.size(); i++) {
                        if (checked[i]) sb.append(sb.length() > 0 ? "," : "").append(apps.get(i).packageName);
                    }
                    Settings.set(this, "notif_excluded", sb.toString());
                })
                .setNegativeButton("Cancel", null).show();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg + " (not installed)";
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (!LinkService.running) LinkService.start(this);
        // Now, while the app is on screen: Android only asks to allow "all device logs" then.
        ClipSync.get(this).startWatch();
        Settings.addListener(this);
        Link.get(this).addListener(this);
        tick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Settings.removeListener(this);
        Link.get(this).removeListener(this);
        ui.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (!LinkService.running) LinkService.start(this);
        Features.get(this).onSettingsChanged(Collections.emptyList());
        refresh();
    }

    @Override
    public void onSettingsChanged(List<String> keys) {
        ui.post(this::refresh);
    }

    @Override
    public void onLinkChanged() {
        ui.post(this::refresh);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    // ------------------------------------------------------------ views

    private void section(String s) {
        TextView t = text(s.toUpperCase(Locale.ROOT), 12);
        t.setTextColor(accent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(26), 0, dp(2));
        col.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor((accent & 0x00FFFFFF) | 0x1A000000);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private EditText field(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(s);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        return b;
    }

    private static String ago(long now, long at) {
        if (at == 0) return "";
        long sec = (now - at) / 1000;
        return sec < 60 ? " (" + sec + " s ago)" : sec < 3600 ? " (" + sec / 60 + " min ago)" : " (" + sec / 3600 + " h ago)";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

}
