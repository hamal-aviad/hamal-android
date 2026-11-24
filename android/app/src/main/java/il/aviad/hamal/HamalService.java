package il.aviad.hamal;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * שירות שרץ כל הזמן (עם התראה קבועה קטנה):
 * 1. מאזין לערוצי ntfy של המשתמש ומציג התראות. הקפצה (עדיפות 5) = צליל אזעקה שעובר מצב שקט.
 * 2. כשהאפליקציה מבקשת, שולח מיקום גם כשהמסך כבוי (אירוע / יציאה לשטח / הקפצה).
 */
public class HamalService extends Service {
    static final String CH_FG = "hamal_fg", CH_ALARM = "hamal_alarm", CH_HIGH = "hamal_high", CH_NORM = "hamal_norm";
    static final int FG_ID = 1;
    static volatile boolean connected = false;
    static volatile long lastMsgTs = 0;
    private static volatile Location lastLoc;
    private static MediaPlayer mp;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> seen = new HashMap<>();
    private static final java.util.Set<Integer> alarmIds = new java.util.HashSet<>();

    private volatile boolean running = false;
    private Thread ntfyThread;
    private volatile HttpURLConnection conn;
    private volatile String curTopics = "";
    private LocationManager lm;
    private LocationListener ll;
    private boolean locOn = false;

    static void ensure(Context c, boolean topicsChanged) {
        Intent i = new Intent(c, HamalService.class);
        i.putExtra("topicsChanged", topicsChanged);
        try {
            ContextCompat.startForegroundService(c, i);
        } catch (Exception ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = HamalPlugin.prefs(this);
        boolean wantLoc = p.getBoolean("loc", false) && hasLocPerm();
        startFg(wantLoc);
        updateLocation(wantLoc);
        String topics = p.getString("topics", "");
        if (ntfyThread == null || !ntfyThread.isAlive()) {
            startNtfy();
        } else if (!topics.equals(curTopics)) {
            HttpURLConnection c = conn;
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        HttpURLConnection c = conn;
        if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        updateLocation(false);
        super.onDestroy();
    }

    private boolean hasLocPerm() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startFg(boolean withLoc) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CH_FG)
                .setSmallIcon(R.drawable.ic_stat_hamal)
                .setContentTitle("חמ\"ל אביעד פעיל")
                .setContentText(withLoc ? "מקבל התראות · המיקום שלך משותף" : "מקבל התראות והקפצות")
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pi)
                .build();
        int type = 0;
        if (Build.VERSION.SDK_INT >= 34) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
            if (withLoc) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        } else if (Build.VERSION.SDK_INT >= 29) {
            type = withLoc ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
        }
        try {
            ServiceCompat.startForeground(this, FG_ID, n, type);
        } catch (Exception e) {
            try { ServiceCompat.startForeground(this, FG_ID, n, Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING : 0); } catch (Exception ignored) { }
        }
    }

    /* ---------------- מיקום ---------------- */
    private void updateLocation(boolean on) {
        if (on == locOn) return;
        locOn = on;
        if (lm == null) lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        if (on) {
            ll = new LocationListener() {
                @Override public void onLocationChanged(Location l) { onLoc(l); }
                @Override public void onStatusChanged(String s, int i, Bundle b) { }
                @Override public void onProviderEnabled(String s) { }
                @Override public void onProviderDisabled(String s) { }
            };
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5, ll, Looper.getMainLooper());
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 10, ll, Looper.getMainLooper());
                Location g = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (g != null) onLoc(g);
            } catch (SecurityException ignored) { }
        } else if (ll != null) {
            try { lm.removeUpdates(ll); } catch (Exception ignored) { }
            ll = null;
        }
    }

    private void onLoc(Location l) {
        Location prev = lastLoc;
        // מעדיפים מיקום מדויק: מיקום רשת לא דורס מיקום GPS טרי
        if (prev != null && LocationManager.NETWORK_PROVIDER.equals(l.getProvider()) && LocationManager.GPS_PROVIDER.equals(prev.getProvider())
                && System.currentTimeMillis() - prev.getTime() < 30000 && l.getAccuracy() > prev.getAccuracy()) return;
        lastLoc = l;
        HamalPlugin.emit("location", locObj(l));
    }

    static JSObject lastLocObj() {
        Location l = lastLoc;
        return l == null ? null : locObj(l);
    }

    private static JSObject locObj(Location l) {
        JSObject d = new JSObject();
        d.put("lat", l.getLatitude());
        d.put("lng", l.getLongitude());
        d.put("acc", l.hasAccuracy() ? l.getAccuracy() : 50);
        d.put("ts", l.getTime());
        if (l.hasSpeed()) d.put("speed", l.getSpeed());
        if (l.hasBearing()) d.put("heading", l.getBearing());
        if (l.hasAltitude()) d.put("alt", l.getAltitude());
        return d;
    }

    /* ---------------- ntfy ---------------- */
    private void startNtfy() {
        ntfyThread = new Thread(() -> {
            long backoff = 3000;
            while (running) {
                SharedPreferences p = HamalPlugin.prefs(this);
                String topics = p.getString("topics", "");
                String server = p.getString("server", "https://ntfy.sh");
                curTopics = topics;
                if (topics.isEmpty()) { connected = false; sleep(15000); continue; }
                String since = p.getString("since_" + topics.hashCode(), "");
                String url = server + "/" + topics + "/json?since=" + (since.isEmpty() ? "1m" : since);
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    conn = c;
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(80000); // ntfy שולח keepalive כל 45 שניות
                    c.setRequestProperty("User-Agent", "HamalAviadApp");
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    connected = true;
                    backoff = 3000;
                    String line;
                    while (running && (line = br.readLine()) != null) {
                        if (!topics.equals(HamalPlugin.prefs(this).getString("topics", ""))) break;
                        if (line.trim().isEmpty()) continue;
                        try { handleLine(line, topics); } catch (Exception ignored) { }
                    }
                } catch (Exception e) {
                    connected = false;
                } finally {
                    connected = false;
                    if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
                }
                if (!running) break;
                sleep(backoff);
                backoff = Math.min(backoff * 2, 60000);
            }
        }, "ntfy");
        ntfyThread.start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void handleLine(String line, String topics) throws Exception {
        JSONObject m = new JSONObject(line);
        String ev = m.optString("event");
        String id = m.optString("id", "");
        String seq = m.optString("sequence_id", "");
        if (!id.isEmpty() && ("message".equals(ev) || "message_delete".equals(ev) || "message_clear".equals(ev)))
            HamalPlugin.prefs(this).edit().putString("since_" + topics.hashCode(), id).apply();
        if ("message_delete".equals(ev) || "message_clear".equals(ev)) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel((seq.isEmpty() ? id : seq).hashCode());
            return;
        }
        if (!"message".equals(ev)) return;
        lastMsgTs = System.currentTimeMillis();
        int pr = m.optInt("priority", 3);
        boolean alarmTag = false;
        JSONArray tags = m.optJSONArray("tags");
        if (tags != null) for (int i = 0; i < tags.length(); i++) if ("rotating_light".equals(tags.optString(i))) alarmTag = true;
        if (alarmTag && pr >= 5) pr = 5;
        showMessage(this, id, seq, m.optString("title", "חמ\"ל אביעד"), m.optString("message", ""), pr, m.optString("click", null), false);
    }

    /** מציג התראה. עדיפות 5 = אזעקה (צליל אזעקה בלופ עד שפותחים, גם במצב שקט). */
    static void showMessage(Context ctx, String id, String seq, String title, String msg, int pr, String click, boolean test) {
        // אותה הודעה בדיוק מכמה ערוצים בתוך 2 דקות = מוצגת פעם אחת
        String key = title + "|" + msg;
        long now = System.currentTimeMillis();
        synchronized (seen) {
            Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue() > 120000) it.remove();
            if (!test && seen.containsKey(key)) return;
            seen.put(key, now);
        }
        createChannels(ctx);
        boolean alarm = pr >= 5;
        boolean chat = title != null && title.startsWith("💬");
        int nid = chat ? "chat".hashCode() : (seq != null && !seq.isEmpty() ? seq : (id == null ? key : id)).hashCode();
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (click != null && !click.isEmpty()) open.putExtra("url", click);
        if (alarm) open.putExtra("alarm", true);
        PendingIntent pi = PendingIntent.getActivity(ctx, nid, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, alarm ? CH_ALARM : pr == 4 ? CH_HIGH : CH_NORM)
                .setSmallIcon(R.drawable.ic_stat_hamal)
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setColor(alarm ? 0xFFC62A1F : 0xFF3F5A1E)
                .setPriority(alarm ? NotificationCompat.PRIORITY_MAX : pr == 4 ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT);
        if (alarm) {
            b.setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(pi, true).setOnlyAlertOnce(false);
        } else if (chat) {
            b.setCategory(NotificationCompat.CATEGORY_MESSAGE);
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        try {
            if (nm != null) nm.notify(nid, b.build());
        } catch (SecurityException ignored) { }
        if (alarm) {
            synchronized (alarmIds) { alarmIds.add(nid); }
            if (!MainActivity.visible || test) startAlarm(ctx);
        }
    }

    static void cancelAlarmNotifs(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        synchronized (alarmIds) { alarmIds.clear(); }
    }

    /** צליל אזעקה בערוץ "שעון מעורר": נשמע גם כשהטלפון על שקט. נעצר כשפותחים את האפליקציה, או אחרי דקה. */
    static void startAlarm(Context ctx) {
        main.post(() -> {
            stopAlarm();
            try {
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                mp.setDataSource(ctx, u);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
                main.postDelayed(HamalService::stopAlarm, 60000);
            } catch (Exception ignored) { }
            try {
                Vibrator v = (Vibrator) ctx.getSystemService(VIBRATOR_SERVICE);
                if (v != null) {
                    long[] pat = {0, 700, 300, 700, 300, 700, 300, 700};
                    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pat, -1));
                    else v.vibrate(pat, -1);
                }
            } catch (Exception ignored) { }
        });
    }

    static void stopAlarm() {
        main.post(() -> {
            MediaPlayer p = mp;
            mp = null;
            if (p != null) try { p.stop(); p.release(); } catch (Exception ignored) { }
        });
    }

    static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH_ALARM) != null) return;
        NotificationChannel fg = new NotificationChannel(CH_FG, "שירות פעיל", NotificationManager.IMPORTANCE_MIN);
        fg.setDescription("התראה קבועה שמאפשרת לקבל הקפצות ולשתף מיקום");
        fg.setShowBadge(false);
        NotificationChannel al = new NotificationChannel(CH_ALARM, "הקפצות ואזעקות", NotificationManager.IMPORTANCE_HIGH);
        al.setDescription("הקפצת כיתת כוננות, מצוקה וחירום. צליל אזעקה שעובר מצב שקט");
        al.setSound(null, null); // הצליל מושמע בנפרד בערוץ השעון המעורר
        al.enableVibration(true);
        al.setVibrationPattern(new long[]{0, 700, 300, 700, 300, 700});
        al.setBypassDnd(true);
        al.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationChannel hi = new NotificationChannel(CH_HIGH, "חשוב", NotificationManager.IMPORTANCE_HIGH);
        hi.setDescription("דיווחים, תזכורות שמירה וסיורים");
        NotificationChannel no = new NotificationChannel(CH_NORM, "רגיל", NotificationManager.IMPORTANCE_DEFAULT);
        no.setDescription("יוצאים לשטח, צ'אט והודעות כלליות");
        nm.createNotificationChannel(fg);
        nm.createNotificationChannel(al);
        nm.createNotificationChannel(hi);
        nm.createNotificationChannel(no);
    }
}
