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
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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
 * Setup (pairing with a computer), the link, and every feature as a switch, each with what it needs to
 * work. The switches are shared with the computer: changing one here changes it there too. The look
 * (Look, LinkView) follows docs/BRAND.md.
 */
public class MainActivity extends Activity implements Settings.Listener, Link.Listener {
    static final String REPO = "https://github.com/aidenwboudr/tandem";
    static final String SITE = "https://tandem.aidenwb.com";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Runnable> refreshers = new ArrayList<>();
    private LinearLayout col;

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
        build();
    }

    private void build() {
        refreshers.clear();
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        col.setPadding(pad, dp(12), pad, dp(32));

        header();
        basics();
        setupCard();
        linkPanel();

        section("Clipboard");
        LinearLayout clip = group();
        LinearLayout toPc = feature(clip, "clip_to_laptop", Look.Dir.TO_PC, "Send my copies to the computer",
                "Copy on the phone, paste on the computer.");
        clipNote(toPc);
        feature(clip, "clip_from_laptop", Look.Dir.TO_PHONE, "Get the computer's copies",
                "Copy on the computer, paste here. Images too.");
        feature(clip, "clip_secrets", Look.Dir.TO_PHONE, "Include password manager copies",
                "Off: copies a password manager marks as secret stay on the computer.");
        feature(clip, "otp_copy", Look.Dir.TO_PC, "Copy sign-in codes to the computer",
                "When a text or notification has a one-time code, it lands on the computer's clipboard.",
                notifAccess);

        section("Notifications");
        LinearLayout nGroup = group();
        LinearLayout notif = feature(nGroup, "notif_mirror", Look.Dir.TO_PC, "Show phone notifications on the computer",
                "Messages, mail, reminders… Media and ongoing notifications stay here.", notifAccess);
        sub(notif, "notif_reply", "Reply from the computer", "For messaging apps that offer a reply.");
        sub(notif, "notif_dismiss_sync", "Dismiss on one, gone on both", null);
        notif.addView(Look.button(this, "Choose apps not to show…", Look.Kind.TEXT, v -> pickExcluded()));

        section("Calls");
        LinearLayout calls = group();
        feature(calls, "calls", Look.Dir.TO_PC, "Show calls on the computer",
                "With buttons to silence the ringer or decline.",
                perm("Allow phone access", Manifest.permission.READ_PHONE_STATE),
                optional(perm("Allow declining calls", Manifest.permission.ANSWER_PHONE_CALLS)));
        feature(calls, "call_pause_media", Look.Dir.TO_PC, "Pause the computer's music during calls",
                "And play it again when the call ends.",
                perm("Allow phone access", Manifest.permission.READ_PHONE_STATE));

        section("Files and links");
        LinearLayout files = group();
        feature(files, "files", Look.Dir.BOTH, "Send and receive files",
                "Share → Tandem sends to the computer's Downloads. `tandem send` on the computer puts files in "
                        + "Downloads/Tandem here.");
        feature(files, "open_links", Look.Dir.BOTH, "Open shared links on the other device",
                "Share a link to Tandem and it opens in the computer's browser, and the other way round.",
                optional(overlay));
        LinearLayout shots = feature(files, "screenshots", Look.Dir.TO_PC, "Send new screenshots to the computer",
                "They go to its Pictures/Phone folder.",
                perm("Allow access to photos", Manifest.permission.READ_MEDIA_IMAGES));
        sub(shots, "screenshot_clipboard", "Also put them on its clipboard", null);

        section("Audio");
        LinearLayout aGroup = group();
        LinearLayout audio = feature(aGroup, "audio_share", Look.Dir.BOTH, "Share audio through one pair of headphones",
                "With Bluetooth headphones on, they stay on one link and you hear both devices: whichever is "
                        + "the hub plays the other's audio. Needs both on the same network.",
                perm("Allow Bluetooth", Manifest.permission.BLUETOOTH_CONNECT));
        audio.addView(fieldLabel("Headphone name contains"));
        EditText match = Look.field(this, Prefs.match(this), InputType.TYPE_CLASS_TEXT);
        match.setHint("Empty: any headphones");
        audio.addView(match);
        LinearLayout opusRow = Look.row(this);
        opusRow.setPadding(0, dp(10), 0, 0);
        TextView opusText = Look.body(this, "Compress the computer's audio (off = raw, ~1.5 Mbit/s)", 14);
        opusText.setTextColor(Look.color(this, R.color.ink));
        Switch opus = Look.toggle(this);
        opus.setChecked("opus".equals(Prefs.codec(this)));
        opusRow.addView(opusText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        opusRow.addView(opus);
        opusRow.setOnClickListener(v -> opus.toggle());
        audio.addView(opusRow);
        Button save = Look.button(this, "Save", Look.Kind.LINE, null);
        save.setOnClickListener(v -> {
            Prefs.get(this).edit().putString("match", match.getText().toString().trim())
                    .putString("codec", opus.isChecked() ? "opus" : "pcm").apply();
            save.setText("Saved");
        });
        audio.addView(Look.buttons(this, save));
        feature(aGroup, "media_controls", Look.Dir.TO_PHONE, "Control phone media from the computer",
                "Play/pause and volume from its bar, and what's playing.", notifAccess);
        LinearLayout play = feature(aGroup, "play_opens_app", Look.Dir.LOCAL, "Headphone play button opens my music app",
                "If the app is closed, pressing play opens it and starts playing.", overlay, notifAccess);
        Button pickApp = Look.button(this, "", Look.Kind.TEXT, v -> pickPlayApp());
        play.addView(pickApp);
        refreshers.add(() -> pickApp.setText("App: " + appLabel(Settings.str(this, "play_app")) + " · change"));

        section("Phone status");
        LinearLayout sGroup = group();
        LinearLayout bat = feature(sGroup, "battery", Look.Dir.TO_PC, "Battery on the computer",
                "Shows the phone's (and headphones') battery there, and warns when it's low.");
        bat.addView(fieldLabel("Warn below this much (%)"));
        EditText low = numberField(String.valueOf(Settings.num(this, "battery_low")));
        low.setOnFocusChangeListener((v, has) -> {
            if (!has) Settings.set(this, "battery_low", low.getText().toString().trim());
        });
        bat.addView(low);
        LinearLayout find = feature(sGroup, "find_phone", Look.Dir.BOTH, "Find my phone",
                "The computer can make this phone ring at full volume, even on silent (`tandem ring`).");
        find.addView(Look.button(this, "Find my computer", Look.Kind.TEXT, v -> FindPhone.ringComputer(this)));
        feature(sGroup, "dnd_sync", Look.Dir.BOTH, "Sync Do Not Disturb",
                "Turn it on or off on one and the other follows (GNOME, mako, swaync and dunst on the computer).",
                dndAccess);

        section("Remote control");
        LinearLayout rGroup = group();
        feature(rGroup, "remote_input", Look.Dir.TO_PHONE, "Type on the phone from the computer",
                "Run `tandem type` on the computer, then pick the Tandem keyboard here.", keyboard);
        feature(rGroup, "screen_mirror", Look.Dir.TO_PC, "Mirror this screen on the computer",
                "`tandem screen` shows and controls the phone (needs scrcpy on the computer, and Wireless "
                        + "debugging on here: Settings → System → Developer options).");

        section("Security");
        LinearLayout secGroup = group();
        LinearLayout lock = feature(secGroup, "lock_on_leave", Look.Dir.TO_PC, "Lock the computer when I walk away",
                "When this phone leaves Bluetooth range, the computer locks its screen.");
        lock.addView(fieldLabel("After this many seconds"));
        EditText delay = numberField(String.valueOf(Settings.num(this, "lock_delay")));
        delay.setOnFocusChangeListener((v, has) -> {
            if (!has) Settings.set(this, "lock_delay", delay.getText().toString().trim());
        });
        lock.addView(delay);

        footer();

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(col);
        // Android 15 draws apps edge to edge: keep the content clear of the status and navigation bars.
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
        refresh();
    }

    // ------------------------------------------------------------ the top

    private void header() {
        LinearLayout row = Look.row(this);
        row.setPadding(0, dp(8), 0, dp(18));
        Look.Mark mark = new Look.Mark(this);
        row.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(20)));
        TextView name = Look.heading(this, "Tandem", 26);
        name.setTypeface(Typeface.create(getResources().getFont(R.font.familjen), 700, false));
        name.setPadding(dp(10), 0, 0, dp(2));
        row.addView(name);
        col.addView(row);
    }

    /** Bluetooth + notifications and running in the background: asked for before anything else. */
    private void basics() {
        LinearLayout card = Look.card(this, Look.color(this, R.color.phone_wash));
        card.addView(Look.label(this, "This phone", Look.color(this, R.color.phone_ink)));
        TextView head = Look.heading(this, "Tandem needs a yes from you first", 19);
        head.setPadding(0, dp(4), 0, dp(2));
        card.addView(head);
        Button perms = Look.need(this, "Allow Bluetooth and notifications (needed)", v -> requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS}, 1));
        Button battery = Look.need(this, "Let Tandem run in the background (recommended)", v -> startActivity(new Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        perms.setBackground(Look.pressable(this, Look.round(Look.color(this, R.color.paper), dp(999)), dp(999)));
        battery.setBackground(Look.pressable(this, Look.round(Look.color(this, R.color.paper), dp(999)), dp(999)));
        card.addView(perms);
        card.addView(battery);
        col.addView(card);
        refreshers.add(() -> {
            boolean ok = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            boolean bg = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
            perms.setVisibility(ok ? View.GONE : View.VISIBLE);
            battery.setVisibility(bg ? View.GONE : View.VISIBLE);
            card.setVisibility(ok && bg ? View.GONE : View.VISIBLE);
            head.setText(ok ? "One more thing for this phone" : "Tandem needs a yes from you first");
        });
    }

    private void setupCard() {
        LinearLayout card = Look.card(this, Look.color(this, R.color.paper));
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        TextView head = Look.heading(this, "Set up", 22);
        steps.addView(head);
        String[] lines = {
                "Install Tandem on your Linux computer (tandem.aidenwb.com).",
                "Pair this phone with the computer in Bluetooth settings, like any device.",
                "Keep this screen open. The computer finds this phone and asks to pair; say yes here."};
        for (int i = 0; i < lines.length; i++) steps.addView(step(i + 1, lines[i]));
        steps.addView(Look.buttons(this,
                Look.button(this, "Bluetooth settings", Look.Kind.INK,
                        v -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))),
                Look.button(this, "Instructions", Look.Kind.LINE,
                        v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SITE))))));
        card.addView(steps);
        col.addView(card);

        // The pairing question, when a computer asks.
        LinearLayout ask = Look.card(this, Look.color(this, R.color.paper));
        GradientDrawable askBg = Look.round(Look.color(this, R.color.paper), dp(16));
        askBg.setStroke(dp(2), Look.color(this, R.color.pc));
        ask.setBackground(askBg);
        ask.addView(Look.label(this, "A computer wants to pair", Look.color(this, R.color.pc_ink)));
        TextView q = Look.heading(this, "", 24);
        q.setPadding(0, dp(6), 0, dp(4));
        ask.addView(q);
        ask.addView(Look.body(this, "Only allow it if it's your computer. It will be able to use the features you "
                + "turn on below.", 14));
        ask.addView(Look.buttons(this,
                Look.button(this, "Allow", Look.Kind.PHONE, v -> Link.get(this).answerPairing(true)),
                Look.button(this, "Deny", Look.Kind.LINE, v -> Link.get(this).answerPairing(false))));
        col.addView(ask);
        refreshers.add(() -> {
            Link.PendingPair p = Link.get(this).pending;
            card.setVisibility(!Pairing.paired(this) && p == null ? View.VISIBLE : View.GONE);
            ask.setVisibility(p != null ? View.VISIBLE : View.GONE);
            if (p != null) q.setText("Pair with " + p.name + "?");
        });
    }

    private View step(int n, String s) {
        LinearLayout r = Look.row(this);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(12), 0, 0);
        TextView num = Look.text(this, String.valueOf(n), 12, Look.mono(this, true), Look.color(this, R.color.ink));
        num.setGravity(Gravity.CENTER);
        num.setBackground(Look.round(Look.color(this, R.color.bg), dp(999)));
        r.addView(num, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = Look.body(this, s, 15);
        t.setTextColor(Look.color(this, R.color.ink));
        t.setPadding(dp(12), dp(2), 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    /** The link: this phone, the computer, the two lanes between them, and what's on the other end. */
    private void linkPanel() {
        LinearLayout card = Look.card(this, Look.color(this, R.color.paper));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout names = Look.row(this);
        LinearLayout me = new LinearLayout(this);
        me.setOrientation(LinearLayout.VERTICAL);
        me.addView(dotLabel("This phone", R.color.phone));
        TextView meName = Look.ellipsize(Look.heading(this, "", 16));
        me.addView(meName);
        LinearLayout them = new LinearLayout(this);
        them.setOrientation(LinearLayout.VERTICAL);
        them.setGravity(Gravity.END);
        them.addView(dotLabel("Computer", R.color.pc));
        TextView themName = Look.ellipsize(Look.heading(this, "", 16));
        themName.setGravity(Gravity.END);
        them.addView(themName);
        names.addView(me, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        names.addView(them, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(names);

        LinkView lanes = new LinkView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(6);
        card.addView(lanes, lp);

        TextView line = Look.heading(this, "", 22);
        card.addView(line);
        TextView detail = Look.body(this, "", 14);
        detail.setPadding(0, dp(4), 0, 0);
        card.addView(detail);

        LinearLayout facts = new LinearLayout(this);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setPadding(0, dp(6), 0, 0);
        Fact battery = fact(facts, "Computer battery");
        Fact phones = fact(facts, "Headphones");
        Fact via = fact(facts, "Network address");
        card.addView(facts);

        // The hub, as on the site: which device the headphones stay on.
        LinearLayout hub = Look.row(this);
        hub.setPadding(dp(4), dp(4), dp(4), dp(4));
        hub.setBackground(Look.round(Look.color(this, R.color.bg), dp(999)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        TextView hubLbl = Look.label(this, "The hub", Look.color(this, R.color.mute));
        hubLbl.setPadding(dp(12), 0, dp(6), 0);
        hub.addView(hubLbl);
        TextView hubPhone = hubOption("Phone", "phone");
        TextView hubPc = hubOption("Computer", "laptop");
        hub.addView(hubPhone, new LinearLayout.LayoutParams(0, dp(38), 1));
        hub.addView(hubPc, new LinearLayout.LayoutParams(0, dp(38), 1));
        card.addView(hub, hlp);

        Button sendClip = Look.button(this, "Send clipboard", Look.Kind.INK, v -> startActivity(ClipSync.grabIntent(this)
                .putExtra(ClipGrabActivity.EXTRA_MANUAL, true)));
        Button ring = Look.button(this, "Find computer", Look.Kind.LINE, v -> FindPhone.ringComputer(this));
        LinearLayout actions = Look.buttons(this, sendClip, ring);
        actions.setPadding(0, dp(16), 0, 0);
        card.addView(actions);
        col.addView(card);

        refreshers.add(() -> {
            Link link = Link.get(this);
            boolean paired = Pairing.paired(this);
            card.setVisibility(paired ? View.VISIBLE : View.GONE);
            if (!paired) return;
            boolean up = link.connected();
            String name = Pairing.name(this);
            meName.setText(Pairing.myName(this));
            themName.setText(name);
            lanes.set(link.bt != null, link.net != null);
            actions.setVisibility(up ? View.VISIBLE : View.GONE);
            ring.setVisibility(Settings.on(this, "find_phone") ? View.VISIBLE : View.GONE);
            sendClip.setVisibility(Settings.on(this, "clip_to_laptop") ? View.VISIBLE : View.GONE);
            if (!up) {
                line.setText("Not connected");
                detail.setText("It connects over Bluetooth when it's nearby with Tandem running, or over the "
                        + "network when you're on the same Wi-Fi (or Tailscale)."
                        + (link.error != null ? "\n" + link.error : ""));
                detail.setVisibility(View.VISIBLE);
                facts.setVisibility(View.GONE);
                hub.setVisibility(View.GONE);
                return;
            }
            line.setText(link.net != null ? "Connected" : "Connected over Bluetooth");
            String d = link.net == null ? "Big files and audio wait for a shared network." : "";
            if (LinkService.error != null) d += (d.isEmpty() ? "" : "\n") + LinkService.error;
            detail.setText(d);
            detail.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
            battery.set(Status.computerBattery >= 0
                    ? Status.computerBattery + "%" + (Status.computerCharging ? " · charging" : "") : null);
            phones.set(LinkService.headphones);
            via.set(link.net != null ? link.net.addr : null);
            facts.setVisibility(View.VISIBLE);

            long now = SystemClock.elapsedRealtime();
            boolean can = LinkService.hpLinked && LinkService.prefer != null && now - LinkService.lastAckAt <= 5000;
            hub.setVisibility(can ? View.VISIBLE : View.GONE);
            if (can) {
                boolean pc = "laptop".equals(LinkService.prefer);
                pick(hubPhone, !pc, R.color.phone);
                pick(hubPc, pc, R.color.pc);
            }
        });
    }

    private TextView hubOption(String label, String to) {
        TextView t = Look.text(this, label.toUpperCase(Locale.ROOT), 11, Look.mono(this, true), Look.color(this, R.color.mute));
        t.setLetterSpacing(0.05f);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> {
            if (!to.equals(LinkService.prefer)) LinkService.switchHub(this, to);
            refresh();
        });
        return t;
    }

    private void pick(TextView t, boolean on, int colorId) {
        t.setSelected(on);
        t.setTextColor(on ? 0xFFFFFFFF : Look.color(this, R.color.mute));
        t.setBackground(Look.pressable(this, on ? Look.round(Look.color(this, colorId), dp(999)) : null, dp(999)));
    }

    private TextView dotLabel(String s, int colorId) {
        TextView t = Look.label(this, "●  " + s, Look.color(this, R.color.mute));
        android.text.SpannableString sp = new android.text.SpannableString(t.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(Look.color(this, colorId)), 0, 1, 0);
        t.setText(sp);
        return t;
    }

    /** One "label: value" line under the link; hidden when there's nothing to say. */
    private final class Fact {
        final LinearLayout row;
        final TextView value;

        Fact(LinearLayout row, TextView value) {
            this.row = row;
            this.value = value;
        }

        void set(String v) {
            row.setVisibility(v == null ? View.GONE : View.VISIBLE);
            if (v != null) value.setText(v);
        }
    }

    private Fact fact(LinearLayout parent, String label) {
        LinearLayout r = Look.row(this);
        r.setPadding(0, dp(8), 0, 0);
        TextView l = Look.label(this, label, Look.color(this, R.color.mute));
        r.addView(l, new LinearLayout.LayoutParams(dp(132), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView v = Look.ellipsize(Look.text(this, "", 14, Look.body(this, true), Look.color(this, R.color.ink)));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(r);
        return new Fact(r, v);
    }

    private void footer() {
        section("About");
        LinearLayout card = Look.card(this, Look.color(this, R.color.paper));
        TextView about = Look.text(this, "", 12, Look.mono(this, false), Look.color(this, R.color.ink));
        about.setLineSpacing(0, 1.3f);
        card.addView(about);
        TextView me = Look.body(this, "", 14);
        me.setPadding(0, dp(6), 0, 0);
        card.addView(me);
        Button unpair = Look.button(this, "Unpair", Look.Kind.LINE, v -> new AlertDialog.Builder(this)
                .setTitle("Unpair from " + Pairing.name(this) + "?")
                .setMessage("Tandem stops working with it until you pair again.")
                .setPositiveButton("Unpair", (d, w) -> Link.get(this).unpair(true))
                .setNegativeButton("Cancel", null).show());
        Button source = Look.button(this, "Source and help", Look.Kind.LINE,
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO))));
        card.addView(Look.buttons(this, unpair, source));
        col.addView(card);
        refreshers.add(() -> {
            unpair.setVisibility(Pairing.paired(this) ? View.VISIBLE : View.GONE);
            String v;
            try {
                v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                v = "?";
            }
            about.setText("Tandem " + v + "\nFree software, GPL-3.0");
            me.setText("This phone is called " + Pairing.myName(this) + " on the computer."
                    + (Pairing.paired(this) ? " Paired with " + Pairing.name(this) + "." : ""));
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

    /** A section's features share one paper card, with a rule between them. */
    private LinearLayout group() {
        LinearLayout g = Look.card(this, Look.color(this, R.color.paper));
        g.setPadding(0, dp(2), 0, dp(2));
        col.addView(g);
        return g;
    }

    /**
     * A feature: which way it sends things, its title and description, the switch, buttons for what it still
     * needs, and room for its options (shown while it's on). Tapping anywhere on the row flips the switch.
     */
    private LinearLayout feature(LinearLayout group, String key, Look.Dir dir, String title, String summary, Need... needs) {
        if (group.getChildCount() > 0) group.addView(Look.divider(this, dp(60)));
        LinearLayout row = Look.row(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));

        Look.Badge badge = new Look.Badge(this, dir);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(28), dp(28));
        blp.topMargin = dp(1);
        row.addView(badge, blp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), 0, dp(10), 0);
        TextView t = Look.text(this, title, 16, Look.body(this, true), Look.color(this, R.color.ink));
        body.addView(t);
        TextView s = Look.body(this, summary, 14);
        s.setPadding(0, dp(3), 0, 0);
        body.addView(s);
        List<Button> grants = new ArrayList<>();
        for (Need n : needs) {
            Button g = Look.need(this, n.label, v -> n.grant());
            body.addView(g);
            grants.add(g);
        }
        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setPadding(0, dp(4), 0, 0);
        body.addView(opts);
        row.addView(body, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Switch sw = Look.toggle(this);
        sw.setChecked(Settings.on(this, key));
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener((b, on) -> {
            if (on == Settings.on(this, key)) return;
            Settings.set(this, key, on);
            if (on) for (Need n : needs) if (!n.met() && !"optional".equals(n.toString())) {
                n.grant();
                break;
            }
            refresh();
        });
        row.addView(sw);
        View top = clickable(row, sw);
        group.addView(top);
        refreshers.add(() -> {
            boolean on = Settings.on(this, key);
            if (sw.isChecked() != on) sw.setChecked(on);
            for (int i = 0; i < needs.length; i++) grants.get(i).setVisibility(on && !needs[i].met() ? View.VISIBLE : View.GONE);
            opts.setVisibility(on && opts.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        });
        return opts;
    }

    /** The row flips its switch when tapped, with pressed feedback over the whole row. */
    private View clickable(LinearLayout row, Switch sw) {
        row.setBackground(Look.pressable(this, null, 0));
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private void sub(LinearLayout parent, String key, String title, String summary) {
        LinearLayout r = Look.row(this);
        r.setPadding(0, dp(10), 0, dp(4));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = Look.text(this, title, 14.5f, Look.body(this, false), Look.color(this, R.color.ink));
        text.addView(t);
        if (summary != null) text.addView(Look.body(this, summary, 13));
        r.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = Look.toggle(this);
        sw.setChecked(Settings.on(this, key));
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener((b, on) -> {
            if (on != Settings.on(this, key)) Settings.set(this, key, on);
        });
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        parent.addView(r);
        refreshers.add(() -> {
            if (sw.isChecked() != Settings.on(this, key)) sw.setChecked(Settings.on(this, key));
        });
    }

    /** Under "Send my copies": the counters, and whether every copy goes over by itself (and how to get there). */
    private void clipNote(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(12));
        box.setBackground(Look.round(Look.color(this, R.color.bg), dp(10)));
        TextView counts = Look.text(this, "", 12, Look.mono(this, false), Look.color(this, R.color.ink));
        counts.setLineSpacing(0, 1.3f);
        box.addView(counts);
        TextView how = Look.body(this, "", 13.5f);
        how.setPadding(0, dp(6), 0, 0);
        how.setTextIsSelectable(true); // the adb command
        box.addView(how);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(box, lp);
        refreshers.add(() -> {
            long now = SystemClock.elapsedRealtime();
            counts.setText("↑ " + ClipSync.toLaptop + " sent" + ago(now, ClipSync.lastToLaptopAt)
                    + "\n↓ " + ClipSync.fromLaptop + " received" + ago(now, ClipSync.lastFromLaptopAt));
            String s;
            if (!ClipSync.canWatch(this)) {
                s = "Android only lets the app on screen read the clipboard, so copies go over when you tap Send "
                        + "clipboard (in the notification) or share text to Tandem. To send every copy automatically, "
                        + "run this once from a computer with adb:\nadb shell pm grant " + getPackageName()
                        + " android.permission.READ_LOGS";
            } else if (!android.provider.Settings.canDrawOverlays(this)) {
                s = "Every copy can go over automatically once Display over other apps is allowed.";
            } else if (ClipSync.approved) {
                s = "Every copy goes over automatically.";
            } else if (ClipSync.watching) {
                s = "Almost: allow Tandem access to device logs when Android asks (it only looks for \"something "
                        + "was copied\"). Switch away and back if the prompt is gone.";
            } else {
                s = "Not watching for copies. Close and reopen Tandem, and allow access to device logs.";
            }
            if (ClipSync.lastError != null) s += "\n" + ClipSync.lastError;
            how.setText(s);
        });
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
        TextView t = Look.heading(this, s, 21);
        t.setPadding(dp(4), dp(22), 0, dp(10));
        col.addView(t);
    }

    private TextView fieldLabel(String s) {
        TextView t = Look.text(this, s, 14, Look.body(this, true), Look.color(this, R.color.ink));
        t.setPadding(0, dp(14), 0, 0);
        return t;
    }

    private EditText numberField(String value) {
        EditText e = Look.field(this, value, InputType.TYPE_CLASS_NUMBER);
        e.setLayoutParams(new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) e.getLayoutParams()).topMargin = dp(6);
        return e;
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
