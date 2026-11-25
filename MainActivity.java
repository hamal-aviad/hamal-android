package il.aviad.hamal;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;

import org.json.JSONObject;

/**
 * המסך הראשי: טוען את האתר של חמ"ל אביעד (Capacitor).
 * תוספות: לחצן ווליום כ"לחץ ודבר", פתיחה מהתראה לכתובת הנכונה,
 * והאפליקציה ממשיכה לרוץ ברקע (מיקום, קשר) כל עוד השירות פעיל.
 */
public class MainActivity extends BridgeActivity {
    /** "off" / "down" / "up" — איזה לחצן ווליום משמש ללחץ-ודבר. נקבע מה-JS */
    static volatile String pttKey = "off";
    static volatile boolean visible = false;
    private boolean pttHeld = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(HamalPlugin.class);
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        boolean alarm = intent.getBooleanExtra("alarm", false);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(alarm);
            setTurnScreenOn(alarm);
        }
        final String url = intent.getStringExtra("url");
        if (url != null && url.startsWith("https://")) {
            try {
                // קישור מהתראה: אם הוא מהכתובת הישנה או החדשה, פותחים את אותו מסך בכתובת של האפליקציה
                android.net.Uri u = android.net.Uri.parse(url);
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.endsWith(".netlify.app") || host.endsWith(".pages.dev") || host.equals(android.net.Uri.parse(getBridge().getServerUrl()).getHost())) {
                    String rest = (u.getEncodedPath() == null ? "/" : u.getEncodedPath())
                            + (u.getEncodedQuery() == null ? "" : "?" + u.getEncodedQuery())
                            + (u.getEncodedFragment() == null ? "" : "#" + u.getEncodedFragment());
                    final String target = getBridge().getServerUrl().replaceAll("/+$", "") + rest;
                    final WebView wv = getBridge().getWebView();
                    wv.postDelayed(() -> wv.evaluateJavascript("try{location.href=" + JSONObject.quote(target) + "}catch(e){}", null), 600);
                }
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        visible = true;
        HamalService.stopAlarm();
        HamalService.cancelAlarmNotifs(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        visible = false;
        // ממשיכים להריץ את האפליקציה ברקע: מיקום באירוע / ביציאה לשטח, קשר וכו'
        try {
            WebView wv = getBridge().getWebView();
            wv.onResume();
            wv.resumeTimers();
        } catch (Exception ignored) { }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        String k = pttKey;
        boolean match = ("down".equals(k) && kc == KeyEvent.KEYCODE_VOLUME_DOWN)
                || ("up".equals(k) && kc == KeyEvent.KEYCODE_VOLUME_UP)
                || (!"off".equals(k) && kc == KeyEvent.KEYCODE_HEADSETHOOK);
        if (match) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && !pttHeld) {
                pttHeld = true;
                JSObject d = new JSObject();
                d.put("down", true);
                HamalPlugin.emit("ptt", d);
            } else if (e.getAction() == KeyEvent.ACTION_UP && pttHeld) {
                pttHeld = false;
                JSObject d = new JSObject();
                d.put("down", false);
                HamalPlugin.emit("ptt", d);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
