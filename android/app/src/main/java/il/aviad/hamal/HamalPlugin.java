package il.aviad.hamal;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** הגשר בין האתר (JS) לבין היכולות של הטלפון: התראות מ-ntfy, מיקום ברקע, לחצן קשר */
@CapacitorPlugin(
        name = "HamalNative",
        permissions = {
                @Permission(alias = "location", strings = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}),
                @Permission(alias = "notifications", strings = {"android.permission.POST_NOTIFICATIONS"}),
                @Permission(alias = "mic", strings = {Manifest.permission.RECORD_AUDIO})
        })
public class HamalPlugin extends Plugin {
    static HamalPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    static void emit(String ev, JSObject d) {
        HamalPlugin p = inst;
        if (p != null) p.notifyListeners(ev, d, false);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("hamal", Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void info(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            r.put("version", pi.versionName);
        } catch (Exception ignored) { }
        r.put("sdk", Build.VERSION.SDK_INT);
        r.put("repo", c.getString(R.string.gh_repo));
        try { r.put("build", Integer.parseInt(c.getString(R.string.build_no))); } catch (Exception ignored) { }
        r.put("notif", NotificationManagerCompat.from(c).areNotificationsEnabled());
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        r.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        boolean fs = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            fs = nm != null && nm.canUseFullScreenIntent();
        }
        r.put("fullScreen", fs);
        r.put("location", getPermissionState("location") == PermissionState.GRANTED);
        r.put("topics", prefs(c).getString("topics", ""));
        r.put("connected", HamalService.connected);
        r.put("lastMsg", HamalService.lastMsgTs);
        call.resolve(r);
    }

    @PluginMethod
    public void setTopics(PluginCall call) {
        JSArray arr = call.getArray("topics");
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; arr != null && i < arr.length(); i++) {
                String t = arr.getString(i);
                if (t == null || !t.matches("[A-Za-z0-9_-]{1,64}")) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(t);
            }
        } catch (Exception ignored) { }
        String server = call.getString("server", "https://ntfy.sh");
        prefs(getContext()).edit().putString("topics", sb.toString()).putString("server", server).apply();
        HamalService.ensure(getContext(), true);
        call.resolve();
    }

    @PluginMethod
    public void startLocation(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "locPermDone");
            return;
        }
        prefs(getContext()).edit().putBoolean("loc", true).apply();
        HamalService.ensure(getContext(), false);
        call.resolve();
    }

    @PermissionCallback
    private void locPermDone(PluginCall call) {
        if (getPermissionState("location") == PermissionState.GRANTED) {
            prefs(getContext()).edit().putBoolean("loc", true).apply();
            HamalService.ensure(getContext(), false);
            call.resolve();
        } else call.reject("denied");
    }

    @PluginMethod
    public void stopLocation(PluginCall call) {
        prefs(getContext()).edit().putBoolean("loc", false).apply();
        HamalService.ensure(getContext(), false);
        call.resolve();
    }

    @PluginMethod
    public void lastLocation(PluginCall call) {
        JSObject r = HamalService.lastLocObj();
        call.resolve(r == null ? new JSObject() : r);
    }

    @PluginMethod
    public void setPtt(PluginCall call) {
        String k = call.getString("key", "off");
        MainActivity.pttKey = ("down".equals(k) || "up".equals(k)) ? k : "off";
        call.resolve();
    }

    @PluginMethod
    public void requestNotif(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notifPermDone");
        } else call.resolve();
    }

    @PermissionCallback
    private void notifPermDone(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Context c = getContext();
        String what = call.getString("what", "app");
        Intent i;
        try {
            if ("battery".equals(what)) {
                i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
            } else if ("fullscreen".equals(what) && Build.VERSION.SDK_INT >= 34) {
                i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + c.getPackageName()));
            } else if ("notif".equals(what) && Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void testAlarm(PluginCall call) {
        int pr = call.getInt("priority", 5);
        HamalService.showMessage(getContext(), "test-" + System.currentTimeMillis(), null,
                pr >= 5 ? "🚨 בדיקת אזעקה" : "🔔 בדיקת התראה",
                pr >= 5 ? "כך תיראה הקפצה: צליל אזעקה גם במצב שקט. פתח את האפליקציה כדי להפסיק." : "כך תיראה התראה רגילה.",
                pr, null, true);
        call.resolve();
    }

    private static String safeName(String n) {
        n = n == null ? "file" : n.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
        return n.isEmpty() ? "file" : (n.length() > 100 ? n.substring(n.length() - 100) : n);
    }

    /** שמירת קובץ שהאתר יצר (אקסל, גיבוי) בתיקיית ההורדות של הטלפון */
    @PluginMethod
    public void saveFile(PluginCall call) {
        Context c = getContext();
        String name = safeName(call.getString("name", "file"));
        String mime = call.getString("mime", "application/octet-stream");
        try {
            byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("insert failed");
                try (OutputStream o = c.getContentResolver().openOutputStream(uri)) {
                    if (o == null) throw new Exception("open failed");
                    o.write(b);
                }
                v.clear();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                c.getContentResolver().update(uri, v, null, null);
                JSObject r = new JSObject();
                r.put("saved", true);
                call.resolve(r);
            } else {
                // טלפונים ישנים: במקום תיקיית הורדות פותחים "שתף" עם הקובץ
                Uri uri = cacheFile(c, name, b);
                Intent i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent ch = Intent.createChooser(i, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(ch);
                JSObject r = new JSObject();
                r.put("shared", true);
                call.resolve(r);
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    private static Uri cacheFile(Context c, String name, byte[] b) throws Exception {
        File dir = new File(c.getCacheDir(), "share");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir");
        File f = new File(dir, name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
        return FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
    }

    /** "שתף" של אנדרואיד (טקסט, קישור, תמונות) במקום navigator.share שלא קיים בתוך אפליקציה */
    @PluginMethod
    public void share(PluginCall call) {
        Context c = getContext();
        try {
            String text = call.getString("text", "");
            String title = call.getString("title", "");
            JSArray files = call.getArray("files");
            ArrayList<Uri> uris = new ArrayList<>();
            String mime = "text/plain";
            for (int k = 0; files != null && k < files.length(); k++) {
                org.json.JSONObject f = files.getJSONObject(k);
                uris.add(cacheFile(c, safeName(f.optString("name", "file")), Base64.decode(f.optString("data", ""), Base64.DEFAULT)));
                String m = f.optString("mime", "application/octet-stream");
                mime = uris.size() == 1 ? m : (mime.equals(m) ? m : "*/*");
            }
            Intent i;
            if (uris.size() > 1) {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            } else {
                i = new Intent(Intent.ACTION_SEND).setType(mime);
                if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            }
            if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
            if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(i, title == null || title.isEmpty() ? "שיתוף" : title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(ch);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }
}
