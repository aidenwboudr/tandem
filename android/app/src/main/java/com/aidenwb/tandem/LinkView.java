package com.aidenwb.tandem;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The link, drawn the way the site draws it: the phone (orange) on the left, the computer (teal) on the
 * right, and one lane between them for Bluetooth and one for the network. A lane that's up is solid,
 * half orange and half teal; a lane that's down is a grey dashed line, so it reads without colour too.
 */
final class LinkView extends View {
    private boolean bt, net;
    private float btT, netT; // 0 = drawn down, 1 = drawn up (animated between)
    private ValueAnimator anim;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final DashPathEffect dash;

    LinkView(Context c) {
        super(c);
        dash = new DashPathEffect(new float[]{Look.dp(c, 4), Look.dp(c, 5)}, 0);
        label.setTypeface(Look.mono(c, false));
        label.setTextSize(Look.dp(c, 9));
        label.setLetterSpacing(0.08f);
        label.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the text next to it says the same
    }

    void set(boolean bt, boolean net) {
        if (bt == this.bt && net == this.net && anim == null) return;
        boolean first = !isLaidOut();
        this.bt = bt;
        this.net = net;
        if (anim != null) anim.cancel();
        if (first) {
            btT = bt ? 1 : 0;
            netT = net ? 1 : 0;
            invalidate();
            return;
        }
        float b0 = btT, n0 = netT, b1 = bt ? 1 : 0, n1 = net ? 1 : 0;
        anim = ValueAnimator.ofFloat(0, 1).setDuration(320);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            btT = b0 + (b1 - b0) * f;
            netT = n0 + (n1 - n0) * f;
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                anim = null;
            }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Look.dp(getContext(), 92));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight(), cy = h / 2;

        // The phone: a tall rounded slab.
        float pw = 34 * d, ph = 60 * d;
        r.set(3 * d, cy - ph / 2, 3 * d + pw, cy + ph / 2);
        fillStroke(cv, r, 8 * d, Look.color(c, R.color.phone_wash), Look.color(c, R.color.phone), 3 * d);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Look.color(c, R.color.phone));
        cv.drawRoundRect(r.centerX() - 5 * d, r.top + 5 * d, r.centerX() + 5 * d, r.top + 7.5f * d, d, d, p);

        // The computer: a screen on a base.
        float lw = 62 * d, lh = 42 * d;
        float lx = w - lw - 8 * d;
        r.set(lx, cy - lh / 2 - 4 * d, lx + lw, cy + lh / 2 - 4 * d);
        fillStroke(cv, r, 4 * d, Look.color(c, R.color.pc_wash), Look.color(c, R.color.pc), 3 * d);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3 * d);
        p.setColor(Look.color(c, R.color.pc));
        cv.drawLine(lx - 6 * d, r.bottom + 7 * d, lx + lw + 6 * d, r.bottom + 7 * d, p);

        float x0 = 3 * d + pw + 10 * d, x1 = lx - 12 * d;
        lane(cv, x0, x1, cy - 13 * d, btT, "BLUETOOTH");
        lane(cv, x0, x1, cy + 17 * d, netT, "NETWORK");
    }

    private void lane(Canvas cv, float x0, float x1, float y, float t, String name) {
        Context c = getContext();
        float d = getResources().getDisplayMetrics().density;
        float mid = (x0 + x1) / 2;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        // Down: dashed grey, fading out as the solid line grows in from the middle.
        if (t < 1) {
            p.setPathEffect(dash);
            p.setStrokeWidth(2 * d);
            p.setColor(Look.color(c, R.color.rule));
            p.setAlpha(Math.round(255 * (1 - t)));
            cv.drawLine(x0, y, x1, y, p);
            p.setPathEffect(null);
            p.setAlpha(255);
        }
        if (t > 0) {
            float half = (mid - x0) * t;
            p.setStrokeWidth(3.5f * d);
            p.setColor(Look.color(c, R.color.phone));
            cv.drawLine(mid - half, y, mid, y, p);
            p.setColor(Look.color(c, R.color.pc));
            cv.drawLine(mid, y, mid + half, y, p);
        }
        label.setColor(Look.color(c, t > 0.5f ? R.color.ink : R.color.mute));
        cv.drawText(t > 0.5f ? name : name + " · OFF", mid, y - 6 * d, label);
    }

    private void fillStroke(Canvas cv, RectF r, float rad, int fill, int stroke, float sw) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        cv.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setColor(stroke);
        cv.drawRoundRect(r, rad, rad, p);
    }
}
