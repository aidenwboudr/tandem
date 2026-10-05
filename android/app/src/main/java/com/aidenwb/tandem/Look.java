package com.aidenwb.tandem;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * The brand (docs/BRAND.md) as views: Familjen Grotesk for headings, Atkinson Hyperlegible for text,
 * Martian Mono for labels and buttons; the phone in orange, the computer in teal, everything else ink.
 */
final class Look {
    /** Which way a feature sends things, drawn as the site's direction badges. */
    enum Dir { TO_PC, TO_PHONE, BOTH, LOCAL }

    enum Kind { INK, LINE, PHONE, TEXT }

    private Look() {}

    static int color(Context c, int id) {
        return c.getColor(id);
    }

    static Typeface display(Context c) {
        return Typeface.create(c.getResources().getFont(R.font.familjen), 650, false);
    }

    static Typeface body(Context c, boolean bold) {
        return Typeface.create(c.getResources().getFont(R.font.atkinson), bold ? 700 : 400, false);
    }

    static Typeface mono(Context c, boolean semibold) {
        return Typeface.create(c.getResources().getFont(R.font.martian), semibold ? 600 : 500, false);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, Typeface face, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        return t;
    }

    static TextView heading(Context c, String s, float sp) {
        TextView t = text(c, s, sp, display(c), color(c, R.color.ink));
        t.setLetterSpacing(-0.02f);
        return t;
    }

    /** Body text; `backticked` parts are set in mono on a fog chip, like code on the site. */
    static TextView body(Context c, String s, float sp) {
        TextView t = text(c, "", sp, body(c, false), color(c, R.color.mute));
        t.setLineSpacing(0, 1.15f);
        t.setText(code(c, s));
        return t;
    }

    static CharSequence code(Context c, String s) {
        if (s.indexOf('`') < 0) return s;
        SpannableStringBuilder b = new SpannableStringBuilder();
        String[] parts = s.split("`", -1);
        for (int i = 0; i < parts.length; i++) {
            int at = b.length();
            b.append(parts[i]);
            if (i % 2 == 1) {
                b.setSpan(new TypefaceSpan(mono(c, false)), at, b.length(), 0);
                b.setSpan(new RelativeSizeSpan(0.86f), at, b.length(), 0);
                b.setSpan(new ForegroundColorSpan(color(c, R.color.ink)), at, b.length(), 0);
            }
        }
        return b;
    }

    /** Small uppercase mono label, like the site's "your Android phone". */
    static TextView label(Context c, String s, int color) {
        TextView t = text(c, s.toUpperCase(Locale.ROOT), 10.5f, mono(c, false), color);
        t.setLetterSpacing(0.06f);
        return t;
    }

    static GradientDrawable round(int fill, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Pressed feedback (a ripple) over bg, clipped to the same corners. bg may be null. */
    static Drawable pressable(Context c, Drawable bg, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(color(c, R.color.mute) & 0x44FFFFFF), bg,
                round(0xFF000000, radiusPx));
    }

    /** A paper card: rounded, flat (the brand has no drop shadows in the app). */
    static LinearLayout card(Context c, int fill) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(fill, dp(c, 16)));
        l.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    /** The site's buttons: mono, 10dp corners, ink fill / ink outline / phone orange / bare text. */
    static Button button(Context c, String s, Kind kind, View.OnClickListener l) {
        Button b = new Button(c, null, 0, 0);
        b.setText(s);
        b.setAllCaps(false);
        b.setTypeface(mono(c, true));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        b.setLetterSpacing(0.01f);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setStateListAnimator(null);
        float r = dp(c, 10);
        GradientDrawable bg;
        switch (kind) {
            case INK:
                bg = round(color(c, R.color.ink), r);
                b.setTextColor(color(c, R.color.on_ink));
                break;
            case PHONE:
                bg = round(color(c, R.color.phone), r);
                b.setTextColor(0xFFFFFFFF);
                break;
            case LINE:
                bg = round(0, r);
                bg.setStroke(dp(c, 2), color(c, R.color.ink));
                b.setTextColor(color(c, R.color.ink));
                break;
            default:
                bg = null;
                b.setTextColor(color(c, R.color.ink));
                b.setPadding(dp(c, 4), dp(c, 10), dp(c, 4), dp(c, 10));
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.getPaint().setUnderlineText(true);
        }
        b.setBackground(pressable(c, bg, r));
        b.setOnClickListener(l);
        return b;
    }

    /** "Allow …": something this phone still has to grant, in the phone's colour. */
    static Button need(Context c, String s, View.OnClickListener l) {
        Button b = new Button(c, null, 0, 0);
        b.setText("●  " + s);
        b.setAllCaps(false);
        b.setTypeface(body(c, true));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(color(c, R.color.phone_ink));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(c, 40));
        b.setMinimumHeight(dp(c, 40));
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setStateListAnimator(null);
        b.setBackground(pressable(c, round(color(c, R.color.phone_wash), dp(c, 999)), dp(c, 999)));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        b.setLayoutParams(lp);
        return b;
    }

    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** Buttons side by side with a gap. */
    static LinearLayout buttons(Context c, View... bs) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 12), 0, 0);
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(c, 8);
            r.addView(bs[i], lp);
        }
        return r;
    }

    static EditText field(Context c, String value, int type) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTypeface(mono(c, false));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setTextColor(color(c, R.color.ink));
        e.setHintTextColor(color(c, R.color.mute));
        GradientDrawable bg = round(color(c, R.color.bg), dp(c, 10));
        GradientDrawable focused = round(color(c, R.color.bg), dp(c, 10));
        focused.setStroke(dp(c, 2), color(c, R.color.ink));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, bg);
        e.setBackground(s);
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        e.setLayoutParams(lp);
        return e;
    }

    /** Ink track when on, rule-grey when off; the thumb keeps a ring so "off" reads without colour. */
    static Switch toggle(Context c) {
        Switch s = new Switch(c);
        float h = dp(c, 28), w = dp(c, 48);
        GradientDrawable on = round(color(c, R.color.ink), h / 2);
        on.setSize((int) w, (int) h);
        GradientDrawable off = round(color(c, R.color.rule), h / 2);
        off.setSize((int) w, (int) h);
        GradientDrawable dis = round(color(c, R.color.rule) & 0x66FFFFFF, h / 2);
        dis.setSize((int) w, (int) h);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{-android.R.attr.state_enabled}, dis);
        track.addState(new int[]{android.R.attr.state_checked}, on);
        track.addState(new int[]{}, off);
        int d = (int) h;
        GradientDrawable thumbOn = new GradientDrawable();
        thumbOn.setShape(GradientDrawable.OVAL);
        thumbOn.setColor(color(c, R.color.on_ink));
        thumbOn.setStroke(dp(c, 4), color(c, R.color.ink));
        thumbOn.setSize(d, d);
        GradientDrawable thumbOff = new GradientDrawable();
        thumbOff.setShape(GradientDrawable.OVAL);
        thumbOff.setColor(color(c, R.color.paper));
        thumbOff.setStroke(dp(c, 4), color(c, R.color.rule));
        thumbOff.setSize(d, d);
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, thumbOn);
        thumb.addState(new int[]{}, thumbOff);
        s.setTrackDrawable(track);
        s.setThumbDrawable(thumb);
        s.setTrackTintList(null);
        s.setThumbTintList(null);
        s.setShowText(false);
        s.setSwitchMinWidth((int) w);
        return s;
    }

    static View divider(Context c, int insetLeft) {
        View v = new View(c);
        v.setBackgroundColor(color(c, R.color.rule));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1) / 2 + 1));
        lp.leftMargin = insetLeft;
        v.setLayoutParams(lp);
        return v;
    }

    static TextView ellipsize(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** The site header's mark: an orange ring and a teal ring joined by a short ink stroke. */
    static final class Mark extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mark(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth() / 40f, getHeight() / 20f);
            float ox = (getWidth() - 40 * s) / 2, oy = (getHeight() - 20 * s) / 2;
            p.setStrokeWidth(2.6f * s);
            p.setColor(color(getContext(), R.color.phone));
            cv.drawCircle(ox + 10 * s, oy + 10 * s, 7 * s, p);
            p.setColor(color(getContext(), R.color.pc));
            cv.drawCircle(ox + 30 * s, oy + 10 * s, 7 * s, p);
            p.setColor(color(getContext(), R.color.ink));
            cv.drawLine(ox + 17 * s, oy + 10 * s, ox + 23 * s, oy + 10 * s, p);
        }
    }

    /** A feature's direction: orange disc = phone to computer, teal = computer to phone, split = both. */
    static final class Badge extends View {
        private final Dir dir;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final RectF oval = new RectF();

        Badge(Context c, Dir dir) {
            super(c);
            this.dir = dir;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2, cx = w / 2, cy = h / 2;
            oval.set(cx - r, cy - r, cx + r, cy + r);
            p.setStyle(Paint.Style.FILL);
            switch (dir) {
                case TO_PC:
                    p.setColor(color(c, R.color.phone));
                    cv.drawCircle(cx, cy, r, p);
                    break;
                case TO_PHONE:
                    p.setColor(color(c, R.color.pc));
                    cv.drawCircle(cx, cy, r, p);
                    break;
                case BOTH:
                    p.setColor(color(c, R.color.phone));
                    cv.drawArc(oval, 90, 180, true, p);
                    p.setColor(color(c, R.color.pc));
                    cv.drawArc(oval, 270, 180, true, p);
                    break;
                case LOCAL: // stays on the phone: a hollow orange ring, no arrow
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * 0.28f);
                    p.setColor(color(c, R.color.phone));
                    cv.drawCircle(cx, cy, r * 0.62f, p);
                    return;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.17f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xFFFFFFFF);
            float a = r * 0.45f, head = r * 0.26f;
            arrow.reset();
            arrow.moveTo(cx - a, cy);
            arrow.lineTo(cx + a, cy);
            if (dir != Dir.TO_PHONE) { // head on the right: toward the computer
                arrow.moveTo(cx + a - head, cy - head);
                arrow.lineTo(cx + a, cy);
                arrow.lineTo(cx + a - head, cy + head);
            }
            if (dir != Dir.TO_PC) { // head on the left: toward the phone
                arrow.moveTo(cx - a + head, cy - head);
                arrow.lineTo(cx - a, cy);
                arrow.lineTo(cx - a + head, cy + head);
            }
            cv.drawPath(arrow, p);
        }
    }
}
