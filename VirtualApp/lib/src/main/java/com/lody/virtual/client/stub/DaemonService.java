package com.lody.virtual.client.stub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;


/**
 * Keeps the VA engine (server) process alive as a foreground service.
 *
 * Android 8+ (API 26): startService() from background is blocked —
 *   must use startForegroundService() and call startForeground() within 5 s.
 * Android 12+ (API 31): startForegroundService() is also blocked from the
 *   background unless the caller is in a whitelisted state. We wrap in
 *   try-catch so a failure here is non-fatal (the server was already started
 *   by the ContentProvider call; the daemon is just a keepalive guard).
 * Android 8+: startForeground() requires a Notification with a valid
 *   NotificationChannel — passing new Notification() crashes silently.
 */
public class DaemonService extends Service {

    private static final String TAG = "DaemonService";
    private static final String CHANNEL_ID = "va_engine";
    private static final int NOTIFY_ID = 1001;

    public static void startup(Context context) {
        Intent intent = new Intent(context, DaemonService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            Log.w(TAG, "startup: could not start DaemonService: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFY_ID, buildNotification(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        startup(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Builds a minimal foreground-service notification.
     * API 26+: creates (or re-uses) a NotificationChannel with IMPORTANCE_MIN
     * so the notification is silent and hidden from the user's status bar.
     */
    static Notification buildNotification(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Virtual Engine",
                        NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            return new Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle("Virtual Engine")
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .build();
        } else {
            return new Notification.Builder(context)
                    .setContentTitle("Virtual Engine")
                    .build();
        }
    }

    /**
     * InnerService is kept for manifest compatibility (already declared in
     * AndroidManifest.xml) but its only job was to detach the foreground
     * notification — that is now handled properly in DaemonService itself.
     */
    public static final class InnerService extends Service {

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            stopSelf();
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}
