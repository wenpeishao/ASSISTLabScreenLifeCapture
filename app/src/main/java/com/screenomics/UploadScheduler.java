package com.screenomics;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.Calendar;


public class UploadScheduler extends BroadcastReceiver {
    private AlarmManager alarm;
    private PendingIntent alarmIntent;
    private Context context;

    public UploadScheduler() {}
    // will be flagged as unused, but is called by the alarm intent below.

    public UploadScheduler(Context context) {
        this.alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, UploadScheduler.class);

        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }else{
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        this.alarmIntent = PendingIntent.getBroadcast(context, 0, intent, intentflags);
        this.context = context;

        System.out.println("Resetting alarms!");
        alarm.cancel(this.alarmIntent);
    }

    private void setAlarm(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        long now = System.currentTimeMillis();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);

        float diff =  (cal.getTimeInMillis() - System.currentTimeMillis()) / 1000 / 60;
        if (diff < 0) {
            cal.add(Calendar.DATE, 1);
            diff = (cal.getTimeInMillis() - System.currentTimeMillis()) / 1000 / 60;
        }

        Logger.i(context, "ALM!" + diff);
        alarm.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), AlarmManager.INTERVAL_DAY, alarmIntent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        
        if (action != null && action.equals("android.intent.action.BOOT_COMPLETED")) {
            // Re-setup auto upload after device restart
            setupAutoUpload(context);
            
            if (InternetConnection.checkWiFiConnection(context)) {
                startUpload(context, false);
            } else {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                boolean continueWithoutWifi = prefs.getBoolean("continueWithoutWifi", false);
                if (continueWithoutWifi) {
                    startUpload(context, true);
                }
            }
        } else if (action != null && action.equals("AUTO_UPLOAD_ACTION")) {
            // Handle 12-hour automatic upload
            android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Auto upload triggered");
            
            // Clear old logs first
            clearOldLogs(context);
            
            // Check WiFi preference and upload
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean continueWithoutWifi = prefs.getBoolean("continueWithoutWifi", false);
            
            if (InternetConnection.checkWiFiConnection(context)) {
                android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Starting auto upload with WiFi");
                startUpload(context, false);
            } else if (continueWithoutWifi) {
                android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Starting auto upload with mobile data");
                startUpload(context, true);
            } else {
                android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Skipping auto upload - no WiFi and mobile data disabled");
            }
        }
    }
    
    private void clearOldLogs(Context context) {
        try {
            // Clear Logger logs
            Logger.reset(context);
            android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Old logs cleared");
        } catch (Exception e) {
            android.util.Log.e("SCREENOMICS_AUTO_UPLOAD", "Error clearing logs", e);
        }
    }

    public static void setAlarmInXSeconds(Context context, int seconds) {
        Calendar cal = Calendar.getInstance();
        long now = System.currentTimeMillis();
        cal.setTimeInMillis(now + (seconds * 1000));

        System.out.printf("SETTING ALARM TO RUN IN: %d%n", (cal.getTimeInMillis() - System.currentTimeMillis()) / 1000);
        Logger.i(context, "ALM" + (cal.getTimeInMillis() - System.currentTimeMillis()) / 1000);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, UploadScheduler.class);

        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }else{
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent alarmIntent = PendingIntent.getBroadcast(context, 0, intent, intentflags);
        alarm.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), alarmIntent);
    }


    public static void startUpload(Context context, boolean continueWithoutWifi) {
        File f_encrypt = new File(context.getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String participant = prefs.getString("participant", "MISSING_PARTICIPANT_NAME");
        String key = prefs.getString("participantKey", "MISSING_KEY");
        Intent intent = new Intent(context, UploadService.class);
        intent.putExtra("dirPath", f_encrypt.getAbsolutePath());
        intent.putExtra("continueWithoutWifi", continueWithoutWifi);
        intent.putExtra("participant", participant);
        intent.putExtra("key", key);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        }
    }

    // Set up automatic upload every 12 hours
    public static void setupAutoUpload(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, UploadScheduler.class);
        intent.setAction("AUTO_UPLOAD_ACTION");

        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }else{
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent alarmIntent = PendingIntent.getBroadcast(context, 1, intent, intentflags);
        
        // Cancel any existing auto upload alarms
        alarm.cancel(alarmIntent);
        
        // Set first alarm for 12 hours from now, then repeat every 12 hours
        long twelveHoursInMillis = 12 * 60 * 60 * 1000L; // 12 hours in milliseconds
        long firstRunTime = System.currentTimeMillis() + twelveHoursInMillis;
        
        alarm.setRepeating(AlarmManager.RTC_WAKEUP, firstRunTime, twelveHoursInMillis, alarmIntent);
        
        android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Auto upload scheduled every 12 hours starting at: " + 
            new java.util.Date(firstRunTime));
    }

    public static void cancelAutoUpload(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, UploadScheduler.class);
        intent.setAction("AUTO_UPLOAD_ACTION");

        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE;
        }else{
            intentflags = PendingIntent.FLAG_NO_CREATE;
        }

        PendingIntent alarmIntent = PendingIntent.getBroadcast(context, 1, intent, intentflags);
        if (alarmIntent != null) {
            alarm.cancel(alarmIntent);
            android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Auto upload cancelled");
        }
    }
}
