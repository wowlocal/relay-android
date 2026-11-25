package org.telegram.relay.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.ui.LaunchActivity;

/**
 * "Keep connected in background" (A7c): a foreground service that keeps the app process, and with it each
 * account's Mattermost WebSocket, alive while the app is in the background, so new posts arrive and notify
 * without FCM. It holds no wake lock: the WebSocket's own pings keep the connection, and Android's network
 * callbacks reconnect it ({@link RelayLive}). Type {@code remoteMessaging} on Android 14+ (no daily limit),
 * {@code dataSync} semantics before.
 */
public class RelayKeepAlive extends Service {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    private static final String CHANNEL_ID = "relay_keep_connected";
    static final int NOTIFICATION_ID = 0x52454c41;

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "keep connected: startForeground", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (RelayAccount.get(a) != null && UserConfig.getInstance(a).isClientActivated()) {
                RelayBridge.get(a).start();
            }
        }
        RelayConfig.log(TAG, "keep connected: foreground service running");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        RelayConfig.log(TAG, "keep connected: service stopped");
        super.onDestroy();
    }

    private static Notification buildNotification(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, context.getString(R.string.RelayKeepConnectedChannel), NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                channel.enableVibration(false);
                channel.setSound(null, null);
                manager.createNotificationChannel(channel);
            }
        }
        Intent open = new Intent(context, LaunchActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(context.getString(R.string.RelayKeepConnectedTitle))
                .setContentText(context.getString(R.string.RelayKeepConnectedText))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(content)
                .build();
    }
}
