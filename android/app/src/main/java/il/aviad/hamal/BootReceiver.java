package il.aviad.hamal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** אחרי הפעלה מחדש של הטלפון / עדכון האפליקציה: מפעיל שוב את קבלת ההתראות */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        HamalPlugin.prefs(c).edit().putBoolean("loc", false).apply();
        if (!HamalPlugin.prefs(c).getString("topics", "").isEmpty()) HamalService.ensure(c, false);
    }
}
