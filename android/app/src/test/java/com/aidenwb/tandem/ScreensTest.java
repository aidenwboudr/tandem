package com.aidenwb.tandem;

import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowBuild;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.robolectric.Shadows.shadowOf;

/**
 * Builds MainActivity for real in a few states and draws it to PNGs in app/build/screens (the whole
 * scrolling page, plus a phone-sized crop of the top). Proves the screen builds; the PNGs are for
 * looking at the design without a device, and for store screenshots.
 *   ./gradlew testReleaseUnitTest --tests '*ScreensTest*'
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi")
public class ScreensTest {
    private final Application app = RuntimeEnvironment.getApplication();

    @Test
    public void firstRun() throws IOException {
        shoot("first-run");
    }

    @Test
    public void pairingPrompt() throws IOException {
        grantBasics();
        Link.get(app).pending = new Link.PendingPair("my-laptop", "00:00:00:00:00:00");
        shoot("pairing");
        Link.get(app).pending = null;
    }

    @Test
    public void connected() throws IOException {
        connect();
        shoot("connected");
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-night-xxhdpi")
    public void connectedNight() throws IOException {
        connect();
        shoot("connected-night");
    }

    @Test
    public void bluetoothOnly() throws IOException {
        connect();
        Link.get(app).net = null;
        LinkService.hpLinked = false;
        shoot("bluetooth-only");
    }

    @Before
    public void phoneName() {
        ShadowBuild.setModel("Pixel 8a"); // what the screens call "this phone"
    }

    private void grantBasics() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS);
        shadowOf(app.getSystemService(PowerManager.class)).setIgnoringBatteryOptimizations(app.getPackageName(), true);
    }

    private void connect() {
        grantBasics();
        Pairing.save(app, "c0ffee", "my-laptop", "00", "t", "00:00:00:00:00:00");
        Link link = Link.get(app);
        // Fake connections: whatever the screen sends goes nowhere.
        link.bt = new Link.Conn("bt", "00:00:00:00:00:01", null, null, new ByteArrayOutputStream());
        link.net = new Link.Conn("net", "192.168.1.42", null, null, new ByteArrayOutputStream());
        Status.computerBattery = 82;
        Status.computerCharging = true;
        LinkService.headphones = "MOMENTUM 4";
        LinkService.hpLinked = true;
        LinkService.prefer = "phone";
        LinkService.lastAckAt = SystemClock.elapsedRealtime();
        Settings.set(app, "notif_mirror", true);
        Settings.set(app, "otp_copy", true);
    }

    private void shoot(String name) throws IOException {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.runOnUiThread(() -> { });
        View content = ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        View page = ((ViewGroup) content).getChildAt(0); // the ScrollView's column
        int w = a.getResources().getDisplayMetrics().widthPixels;
        page.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        page.layout(0, 0, w, page.getMeasuredHeight());
        int bg = a.getColor(R.color.bg);
        int top = Math.round(36 * a.getResources().getDisplayMetrics().density); // room for a status bar
        Bitmap full = Bitmap.createBitmap(w, page.getMeasuredHeight() + top, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(full);
        c.drawColor(bg);
        c.translate(0, top);
        page.draw(c);
        File dir = new File("build/screens");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        save(full, new File(dir, name + "-full.png"));
        int h = Math.min(full.getHeight(), a.getResources().getDisplayMetrics().heightPixels);
        save(Bitmap.createBitmap(full, 0, 0, w, h), new File(dir, name + ".png"));
    }

    private static void save(Bitmap b, File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
