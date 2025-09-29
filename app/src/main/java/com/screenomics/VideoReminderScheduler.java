package com.screenomics;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.Calendar;

public class VideoReminderScheduler {
    private static final String TAG = "VideoReminderScheduler";
    private static final String CHANNEL_ID = "video_reminder_channel";
    private static final int REMINDER_NOTIFICATION_ID = 3;
    private static final int REMINDER_REQUEST_CODE = 1001;

    // Video reminders completely disabled - no receiver functionality needed

    public static void scheduleReminders(Context context, int intervalHours) {
        // Video reminders disabled until IRB approval
        Log.d(TAG, "Video reminders requested but disabled until IRB approval");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("video_reminder_interval", 0);
        editor.putBoolean("video_reminders_enabled", false);
        editor.apply();
    }

    public static void scheduleNextReminder(Context context) {
        // Video reminders disabled until IRB approval
        Log.d(TAG, "scheduleNextReminder called but video reminders are disabled");
    }

    public static void cancelReminders(Context context) {
        // Video reminders disabled until IRB approval
        Log.d(TAG, "cancelReminders called but video reminders are disabled");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("video_reminders_enabled", false);
        editor.apply();
    }

    // Notification methods removed - video reminders disabled

    public static boolean areRemindersEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("video_reminders_enabled", false);
    }

    public static int getReminderInterval(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt("video_reminder_interval", 0);
    }
}