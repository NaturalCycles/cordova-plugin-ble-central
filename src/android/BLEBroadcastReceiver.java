package com.megster.cordova.ble.central;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.ParcelUuid;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.naturalcycles.cordova.R;

import org.apache.cordova.LOG;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class BLEBroadcastReceiver extends BroadcastReceiver implements BLECentralPlugin.ConnectionStateListener {

    private Context mContext;

    @Override
    public void onReceive(Context context, Intent intent) {
        mContext = context;

        createBroadcastNotification("NC broadcast received");
        LOG.d(BLECentralPlugin.NATURAL_TAG, "BLEBroadcastReceiver on receive");
        BLECentralPlugin.BLEService.saveLog(BLECentralPlugin.NATURAL_TAG + "BLEBroadcastReceiver on receive");
        if(intent != null) {
            LOG.d(BLECentralPlugin.NATURAL_TAG, "Intent action: " + intent.getAction());
        }

        SharedPreferences prefs = context.getSharedPreferences(BLECentralPlugin.MAC_ADDRESS_PREFS, Context.MODE_PRIVATE);
        String macAddress = prefs.getString(BLECentralPlugin.MAC_ADDRESS, null) == null ? "18:7A:93:6F:B5:6D" : prefs.getString(BLECentralPlugin.MAC_ADDRESS, null);

        if(macAddress != null) {
            Log.d(BLECentralPlugin.NATURAL_TAG, "init bluetoothlescanner scan, mac address: " + macAddress);

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(macAddress).setServiceUuid(
                    new ParcelUuid(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"))
            ).build();
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(filter);
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).setReportDelay(5000).build();
            adapter.getBluetoothLeScanner().startScan(filters, settings, new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    // We scan with report delay > 0. This will never be called.
                    Log.d(BLECentralPlugin.NATURAL_TAG, "ScanCallback, scan result: " + result.toString());
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    if (!results.isEmpty()) {
                        ScanResult result = results.get(0);
                        BluetoothDevice device = result.getDevice();
                        String deviceAddress = device.getAddress();
                        // Device detected, we can automatically connect to it and stop the scan
                        Log.d(BLECentralPlugin.NATURAL_TAG, "ScanCallback, batch scan results: " + deviceAddress);

                        Peripheral peripheral = new Peripheral(device);

                        device.connectGatt(context, true, peripheral);
                        adapter.getBluetoothLeScanner().stopScan(this);

                        LOG.d(BLECentralPlugin.NATURAL_TAG, "calling register listener from broadcast receiver");
                        peripheral.registerConnectionStateListener(BLEBroadcastReceiver.this);
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    // Scan error
                    Log.d(BLECentralPlugin.NATURAL_TAG, "ScanCallback, scan failed");
                }
            });
        } else {
            Log.d(BLECentralPlugin.NATURAL_TAG, "NOT initialising bluetoothlescanner scan,");
        }

    }

    @TargetApi(26)
    private void createBroadcastNotification(String text) {
        String NOTIFICATION_CHANNEL_ID = "com.naturalcycles.cordova.blebroadcastreceiver.broadcast";
        String channelName = "BLEBroadcastReceiver_channel_broadcast";
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT);
        chan.setLockscreenVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(mContext, NOTIFICATION_CHANNEL_ID);
        Notification notification = notificationBuilder.setOngoing(false)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        manager.notify(new Random().nextInt(100), notification);
    }

    @TargetApi(26)
    private void createNotification(String text) {
        String NOTIFICATION_CHANNEL_ID = "com.naturalcycles.cordova.blebroadcastreceiver";
        String channelName = "BLEBroadcastReceiver_channel";
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT);
        chan.setLockscreenVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(mContext, NOTIFICATION_CHANNEL_ID);
        Notification notification = notificationBuilder.setOngoing(false)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        manager.notify(new Random().nextInt(100), notification);
    }

    @Override
    public void peripheralConnected() {
        createNotification("Thermometer connected");
    }

    @Override
    public void temperatureReceived(String temperature) {
        createNotification("Temperature received: " + temperature);
    }
}
