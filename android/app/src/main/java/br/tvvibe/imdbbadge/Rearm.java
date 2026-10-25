package br.tvvibe.imdbbadge;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Switches accessibility off and on again. The Apple TV app only turns on its accessibility layer
 * (and so names the focused tile) when accessibility is switched on while it is running; started with
 * accessibility already on, it stays silent. Runs as a receiver, not in BadgeService, because
 * switching off unbinds the service; goAsync keeps the process alive until the setting is back.
 * Needs WRITE_SECURE_SETTINGS, granted over ADB by instalar.sh.
 */
public class Rearm extends BroadcastReceiver {
    private static final String TAG = "ImdbBadge";

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        new Thread(() -> {
            ContentResolver cr = context.getContentResolver();
            String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
            String before = Settings.Secure.getString(cr, key);
            try {
                Settings.Secure.putString(cr, key, "");
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
                Thread.sleep(1_500);
            } catch (SecurityException e) {
                Log.w(TAG, "no WRITE_SECURE_SETTINGS; run instalar.sh again", e);
            } catch (InterruptedException ignored) {
            } finally {
                try {
                    Settings.Secure.putString(cr, key, before);
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                } catch (SecurityException ignored) {
                }
                Log.i(TAG, "accessibility switched off and on to wake the Apple TV app");
                result.finish();
            }
        }).start();
    }
}
