package com.screenomics;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.Calendar;

public class VideoReminderScheduler extends BroadcastReceiver {
    private static final String TAG = "VideoReminderScheduler";
    private static final String CHANNEL_ID = "video_reminder_channel";
    private static final int REMINDER_NOTIFICATION_ID = 3;
    private static final int REMINDER_REQUEST_CODE = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            scheduleNextReminder(context);
        } else if ("VIDEO_REMINDER_ALARM".equals(action)) {
            showReminderNotification(context);
            scheduleNextReminder(context);
        }
    }

    public static void scheduleReminders(Context context, int intervalHours) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("video_reminder_interval", intervalHours);
        editor.putBoolean("video_reminders_enabled", intervalHours > 0);
        editor.apply();

        if (intervalHours > 0) {
            scheduleNextReminder(context);
            Log.d(TAG, "Video reminders scheduled every " + intervalHours + " hours");
        } else {
            cancelReminders(context);
            Log.d(TAG, "Video reminders cancelled");
        }
    }

    public static void scheduleNextReminder(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int intervalHours = prefs.getInt("video_reminder_interval", 0);
        boolean remindersEnabled = prefs.getBoolean("video_reminders_enabled", false);

        if (!remindersEnabled || intervalHours <= 0) {
            return;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, VideoReminderScheduler.class);
        intent.setAction("VIDEO_REMINDER_ALARM");

        int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REMINDER_REQUEST_CODE, intent, flags);

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR_OF_DAY, intervalHours);
        long triggerTime = calendar.getTimeInMillis();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }
            
            Log.d(TAG, "Next video reminder scheduled for: " + calendar.getTime().toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule reminder", e);
        }
    }

    public static void cancelReminders(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, VideoReminderScheduler.class);
        intent.setAction("VIDEO_REMINDER_ALARM");

        int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE;
        } else {
            flags = PendingIntent.FLAG_NO_CREATE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REMINDER_REQUEST_CODE, intent, flags);
        
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("video_reminders_enabled", false);
        editor.apply();

        Log.d(TAG, "Video reminders cancelled");
    }

    private void showReminderNotification(Context context) {
        createNotificationChannel(context);

        Intent recordIntent = new Intent(context, MainActivity.class);
        recordIntent.putExtra("start_video_recording", true);
        recordIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent recordPendingIntent = PendingIntent.getActivity(context, 0, recordIntent, flags);

        Intent dismissIntent = new Intent(context, VideoReminderScheduler.class);
        dismissIntent.setAction("DISMISS_REMINDER");
        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(context, 1, dismissIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.dna)
                .setContentTitle("MindPulse Video Reminder")
                .setContentText("Time to record a video! Tap to start recording.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(recordPendingIntent)
                .addAction(R.drawable.dna, "Record Now", recordPendingIntent)
                .addAction(R.drawable.dna, "Dismiss", dismissPendingIntent);

        NotificationManager notificationManager = 
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(REMINDER_NOTIFICATION_ID, builder.build());

        Log.d(TAG, "Video reminder notification shown");
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Video Reminders",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifications to remind you to record videos");
            channel.enableVibration(true);
            channel.setShowBadge(true);

            NotificationManager notificationManager = 
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public static boolean areRemindersEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("video_reminders_enabled", false);
    }

    public static int getReminderInterval(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt("video_reminder_interval", 0);
    }
}