package com.megster.cordova.ble.central;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.util.Random;

public class BLEBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(BLECentralPlugin.NATURAL_TAG, "Broadcast receiver on receive");
        saveLog(new Date().toString() + " NATURAL - broadcast receiver on receive");

        ContextCompat.startForegroundService(context, new Intent(context, BLECentralPlugin.BLEService.class));

        try {
            JobScheduler tm = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
//            if(tm.getAllPendingJobs().size() > 0) {
//                Log.d( BLECentralPlugin.NATURAL_TAG, "Job already scheduled; skipping scheduling");
//                saveLog(new Date().toString() + " NATURAL broadcast - Job already scheduled; skipping scheduling");
//                return;
//            } else {
            JobInfo.Builder builder = new JobInfo.Builder(new Random().nextInt(), new ComponentName(context, BLECentralPlugin.BLEService.class));
            builder.setMinimumLatency(2 * 60 * 1000);
            builder.setOverrideDeadline(2 * 60 * 1000);

            Log.d(BLECentralPlugin.NATURAL_TAG, "Broadcast Scheduling job");
            saveLog(new Date().toString() + " NATURAL broadcast - scheduling job");

            tm.schedule(builder.build());
//            }
        } catch (Exception ex) {
            Log.e(BLECentralPlugin.NATURAL_TAG + " ERROR", ex.toString());
            saveLog(new Date().toString() + " NATURAL ERROR - " + ex.toString());
        }
    }

    public void saveLog(String text) {
        File logFile = new File("sdcard/log.txt");
        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            //BufferedWriter for performance, true to set append to file flag
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            buf.append(text);
            buf.newLine();
            buf.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
