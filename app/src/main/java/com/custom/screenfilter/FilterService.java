package com.custom.screenfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;

public class FilterService extends Service {
    public static final String PREFS_NAME = "screen_filter_prefs";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_BRIGHTNESS = "brightness"; // 5.0 .. 100.0
    public static final String KEY_KELVIN = "kelvin";         // 1800 .. 6500
    public static final String KEY_SOFT_KEYS = "soft_keys";

    public static final String ACTION_UPDATE = "com.custom.screenfilter.UPDATE";
    public static final String ACTION_STOP = "com.custom.screenfilter.STOP";

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "screen_filter_channel";

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams layoutParams;

    public static boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        overlayView = new View(this);

        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        }

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        windowManager.addView(overlayView, layoutParams);
        isRunning = true;
        startForegroundNotification();
        applySavedSettings();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        applySavedSettings();
        return START_STICKY;
    }

    private void applySavedSettings() {
        if (overlayView == null || windowManager == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        float brightness = prefs.getFloat(KEY_BRIGHTNESS, 50.0f);
        int kelvin = prefs.getInt(KEY_KELVIN, 4000);
        boolean softKeys = prefs.getBoolean(KEY_SOFT_KEYS, false);

        overlayView.setBackgroundColor(computeOverlayColor(brightness, kelvin));
        layoutParams.buttonBrightness = softKeys
                ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;

        windowManager.updateViewLayout(overlayView, layoutParams);
    }

    public static int computeOverlayColor(float brightnessPct, int kelvin) {
        float b = Math.max(0.05f, Math.min(1.0f, brightnessPct / 100f));
        float d = 1.0f - b;
        float w = Math.max(0f, Math.min(1f, (6500f - kelvin) / (6500f - 1800f)));

        float t = 0.60f * w;
        float tr = 255f;
        float tg = 215f - 105f * w;
        float tb = Math.max(0f, 60f * (1f - 1.5f * w));

        float aEff = 1f - (1f - t) * (1f - d);
        if (aEff <= 1e-5f) {
            return Color.TRANSPARENT;
        }

        int a = clampByte(Math.round(aEff * 255f));
        int r = clampByte(Math.round(((1f - d) * t * tr) / aEff));
        int g = clampByte(Math.round(((1f - d) * t * tg) / aEff));
        int bl = clampByte(Math.round(((1f - d) * t * tb) / aEff));
        return Color.argb(a, r, g, bl);
    }

    private static int clampByte(int val) {
        return Math.max(0, Math.min(255, val));
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Screen Filter", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        Intent stopIntent = new Intent(this, FilterService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("Screen Filter active")
                .setContentText("Tap to adjust brightness & color temperature")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disable", stopPi);

        startForeground(NOTIF_ID, builder.build());
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
