package com.aidenwb.tandem;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * "Tandem keyboard": an input method that types what the computer sends (`tandem type`). Pick it in the
 * keyboard switcher while typing from the computer; its bar has a button back to your usual keyboard.
 */
public class Keyboard extends InputMethodService {
    private static volatile Keyboard active;
    private static final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        active = this;
    }

    @Override
    public void onDestroy() {
        if (active == this) active = null;
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics()));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setText("Typing from " + Pairing.name(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button back = new Button(this);
        back.setText("Keyboard");
        back.setAllCaps(false);
        back.setOnClickListener(v -> {
            if (!switchToPreviousInputMethod()) requestHideSelf(0);
        });
        bar.addView(back);
        return bar;
    }

    static boolean isActive() {
        return active != null;
    }

    /** {"text": "..."} or {"key": "Backspace"|"Enter"|"Tab"|"Left"|...} from the computer. */
    static void onKey(JSONObject h) {
        main.post(() -> {
            Keyboard k = active;
            InputConnection ic = k == null ? null : k.getCurrentInputConnection();
            if (ic == null) return;
            String text = h.optString("text", "");
            if (!text.isEmpty()) {
                ic.commitText(text, 1);
                return;
            }
            switch (h.optString("key")) {
                case "Backspace":
                    CharSequence sel = ic.getSelectedText(0);
                    if (sel != null && sel.length() > 0) ic.commitText("", 1);
                    else ic.deleteSurroundingText(1, 0);
                    break;
                case "Delete":
                    ic.deleteSurroundingText(0, 1);
                    break;
                case "Enter":
                    k.sendDefaultEditorAction(true);
                    break;
                case "Tab":
                    k.sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB);
                    break;
                case "Left":
                    k.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                    break;
                case "Right":
                    k.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
                    break;
                case "Up":
                    k.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP);
                    break;
                case "Down":
                    k.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN);
                    break;
                case "Home":
                    k.sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME);
                    break;
                case "End":
                    k.sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END);
                    break;
                case "Escape":
                    k.requestHideSelf(0);
                    break;
                default:
            }
        });
    }
}
