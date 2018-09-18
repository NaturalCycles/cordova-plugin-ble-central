// (c) 2014-2016 Don Coleman
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.megster.cordova.ble.central;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Build;

import android.os.ParcelUuid;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL;
import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE;

public class BLECentralPlugin extends CordovaPlugin implements BluetoothAdapter.LeScanCallback {
    // actions
    private static final String SCAN = "scan";
    private static final String START_SCAN = "startScan";
    private static final String STOP_SCAN = "stopScan";
    private static final String START_SCAN_WITH_OPTIONS = "startScanWithOptions";
    private static final String BONDED_DEVICES = "bondedDevices";
    private static final String LIST = "list";

    private static final String CONNECT = "connect";
    private static final String AUTOCONNECT = "autoConnect";
    private static final String DISCONNECT = "disconnect";

    private static final String REQUEST_MTU = "requestMtu";
    private static final String REFRESH_DEVICE_CACHE = "refreshDeviceCache";

    private static final String READ = "read";
    private static final String WRITE = "write";
    private static final String WRITE_WITHOUT_RESPONSE = "writeWithoutResponse";

    private static final String READ_RSSI = "readRSSI";

    private static final String START_NOTIFICATION = "startNotification"; // register for characteristic notification
    private static final String STOP_NOTIFICATION = "stopNotification"; // remove characteristic notification

    private static final String IS_ENABLED = "isEnabled";
    private static final String IS_CONNECTED  = "isConnected";

    private static final String SETTINGS = "showBluetoothSettings";
    private static final String ENABLE = "enable";

    private static final String START_STATE_NOTIFICATIONS = "startStateNotifications";
    private static final String STOP_STATE_NOTIFICATIONS = "stopStateNotifications";

    // callbacks
    CallbackContext discoverCallback;
    private CallbackContext enableBluetoothCallback;

    public static final String NATURAL_TAG = "NATURAL_LOG";
    private static final String TAG = "BLEPlugin";
    private static final int REQUEST_ENABLE_BLUETOOTH = 1;

    static BluetoothAdapter bluetoothAdapter;

    // key is the MAC Address
    static Map<String, Peripheral> peripherals = new LinkedHashMap<String, Peripheral>();
    static CallbackContext callbackContext;
    static String macAddress;

    // scan options
    boolean reportDuplicates = false;

    // Android 23 requires new permissions for BluetoothLeScanner.startScan()
    private static final String ACCESS_COARSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final String WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;
    private static final int REQUEST_ACCESS_COARSE_LOCATION = 2;
    private static final int REQUEST_EXTERNAL_STORAGE = 3;
    private CallbackContext permissionCallback;
    public static UUID[] serviceUUIDs;
    private int scanSeconds;

    // Bluetooth state notification
    CallbackContext stateCallback;
    BroadcastReceiver stateReceiver;
    Map<Integer, String> bluetoothStates = new Hashtable<Integer, String>() {{
        put(BluetoothAdapter.STATE_OFF, "off");
        put(BluetoothAdapter.STATE_TURNING_OFF, "turningOff");
        put(BluetoothAdapter.STATE_ON, "on");
        put(BluetoothAdapter.STATE_TURNING_ON, "turningOn");
    }};

    public void onDestroy() {
        Log.d(NATURAL_TAG, "BLE central plugin on destroy");
//        cordova.getActivity().stopService(new Intent(cordova.getActivity(), BLEService.class));
        removeStateListener();
        super.onDestroy();
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        Log.d(NATURAL_TAG, "in on resume");
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        Log.d(NATURAL_TAG, "in initialise");
        initService();
    }

    @Override
    public void onStart() {
        super.onStart();
//        Log.d(NATURAL_TAG, "in on start");
//        if(!isBLEServiceRunning(BLEService.class)) {
//            Log.d(NATURAL_TAG, "Starting service");
//            BLEService.saveLog(new Date().toString() + " NATURAL - starting service");
//            cordova.getActivity().startService(new Intent(cordova.getActivity(), BLEService.class));
////            ContextCompat.startForegroundService(cordova.getActivity(), new Intent(cordova.getActivity(), BLEService.class));
//        } else {
//            Log.d(NATURAL_TAG, "Service already started - skipping start");
//            BLEService.saveLog(new Date().toString() + " NATURAL - skipping start service");
//        }
//
//        IntentFilter filter = new IntentFilter("com.megster.cordova.ble.central.BLERestart");
//        BroadcastReceiver mReceiver = new BLEBroadcastReceiver();
//        cordova.getActivity().registerReceiver(mReceiver, filter);
//
//        JobScheduler tm = (JobScheduler) cordova.getActivity().getSystemService(Context.JOB_SCHEDULER_SERVICE);
//        Log.d(NATURAL_TAG, "number of pending jobs: " + tm.getAllPendingJobs().size());
//        if(tm.getAllPendingJobs().size() > 0) {
//            Log.d(NATURAL_TAG, "Job already scheduled; skipping scheduling");
//            BLEService.saveLog(new Date().toString() + " NATURAL - scheduling job");
//            return;
//        } else {
//            JobInfo.Builder builder = new JobInfo.Builder(new Random().nextInt(), new ComponentName(cordova.getActivity(), BLEService.class));
//            builder.setMinimumLatency(5000);
//            builder.setOverrideDeadline(1 * 60 * 1000);
//
//            Log.d(NATURAL_TAG, "Scheduling job from on start");
//            tm.schedule(builder.build());
//        }

//        ContextCompat.startForegroundService(cordova.getActivity(), new Intent(cordova.getActivity(), BLEService.class));
    }

    private void initService() {
        Log.d(NATURAL_TAG, "in on start");
        if(!isBLEServiceRunning(BLEService.class)) {
            Log.d(NATURAL_TAG, "Starting service");
            BLEService.saveLog(new Date().toString() + " NATURAL - starting service");
            cordova.getActivity().startService(new Intent(cordova.getActivity(), BLEService.class));
//            ContextCompat.startForegroundService(cordova.getActivity(), new Intent(cordova.getActivity(), BLEService.class));
        } else {
            Log.d(NATURAL_TAG, "Service already started - skipping start");
            BLEService.saveLog(new Date().toString() + " NATURAL - skipping start service");
        }

        IntentFilter filter = new IntentFilter("com.megster.cordova.ble.central.BLERestart");
        BroadcastReceiver mReceiver = new BLEBroadcastReceiver();
        cordova.getActivity().registerReceiver(mReceiver, filter);

        JobScheduler tm = (JobScheduler) cordova.getActivity().getSystemService(Context.JOB_SCHEDULER_SERVICE);
        Log.d(NATURAL_TAG, "number of pending jobs: " + tm.getAllPendingJobs().size());
        if(tm.getAllPendingJobs().size() > 0) {
            Log.d(NATURAL_TAG, "Job already scheduled; skipping scheduling");
            BLEService.saveLog(new Date().toString() + " NATURAL - scheduling job");
            return;
        } else {
            JobInfo.Builder builder = new JobInfo.Builder(new Random().nextInt(), new ComponentName(cordova.getActivity(), BLEService.class));
            builder.setMinimumLatency(2 * 60 * 1000);
            builder.setOverrideDeadline(2 * 60 * 1000);

            Log.d(NATURAL_TAG, "Scheduling job from init service");
            tm.schedule(builder.build());
        }
    }

    private boolean isBLEServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) cordova.getActivity().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public void onReset() {
        removeStateListener();
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        LOG.d(TAG, "action = " + action);
        LOG.d(TAG, "NATURAL action = " + action);

        if (bluetoothAdapter == null) {
            Activity activity = cordova.getActivity();
            boolean hardwareSupportsBLE = activity.getApplicationContext()
                    .getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
                    Build.VERSION.SDK_INT >= 18;
            if (!hardwareSupportsBLE) {
                LOG.w(TAG, "This hardware does not support Bluetooth Low Energy.");
                callbackContext.error("This hardware does not support Bluetooth Low Energy.");
                return false;
            }
            BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        boolean validAction = true;

        if (action.equals(SCAN)) {

            serviceUUIDs = parseServiceUUIDList(args.getJSONArray(0));
            int scanSeconds = args.getInt(1);
            resetScanOptions();
            findLowEnergyDevices(callbackContext, serviceUUIDs, scanSeconds);

        } else if (action.equals(START_SCAN)) {

            serviceUUIDs = parseServiceUUIDList(args.getJSONArray(0));
            resetScanOptions();
            findLowEnergyDevices(callbackContext, serviceUUIDs, -1);

        } else if (action.equals(STOP_SCAN)) {

//            TODO: try to replace with bluetoothAdapter.getBluetoothLeScanner().stopScan();
            bluetoothAdapter.stopLeScan(this);
            callbackContext.success();

        } else if (action.equals(LIST)) {

            listKnownDevices(callbackContext);

        } else if (action.equals(CONNECT)) {

            macAddress = args.getString(0);
            BLECentralPlugin.callbackContext = callbackContext;

            connect(callbackContext, macAddress);

        } else if (action.equals(AUTOCONNECT)) {

            macAddress = args.getString(0);
            autoConnect(callbackContext, macAddress);

        } else if (action.equals(DISCONNECT)) {

            String macAddress = args.getString(0);
            disconnect(callbackContext, macAddress);

        } else if (action.equals(REQUEST_MTU)) {

            String macAddress = args.getString(0);
            int mtuValue = args.getInt(1);
            requestMtu(callbackContext, macAddress, mtuValue);

        } else if (action.equals(REFRESH_DEVICE_CACHE)) {

            String macAddress = args.getString(0);
            long timeoutMillis = args.getLong(1);

            refreshDeviceCache(callbackContext, macAddress, timeoutMillis);

        } else if (action.equals(READ)) {

            String macAddress = args.getString(0);
            UUID serviceUUID = uuidFromString(args.getString(1));
            UUID characteristicUUID = uuidFromString(args.getString(2));
            read(callbackContext, macAddress, serviceUUID, characteristicUUID);

        } else if (action.equals(READ_RSSI)) {

            String macAddress = args.getString(0);
            readRSSI(callbackContext, macAddress);

        } else if (action.equals(WRITE)) {

            String macAddress = args.getString(0);
            UUID serviceUUID = uuidFromString(args.getString(1));
            UUID characteristicUUID = uuidFromString(args.getString(2));
            byte[] data = args.getArrayBuffer(3);
            int type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            write(callbackContext, macAddress, serviceUUID, characteristicUUID, data, type);

        } else if (action.equals(WRITE_WITHOUT_RESPONSE)) {

            String macAddress = args.getString(0);
            UUID serviceUUID = uuidFromString(args.getString(1));
            UUID characteristicUUID = uuidFromString(args.getString(2));
            byte[] data = args.getArrayBuffer(3);
            int type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            write(callbackContext, macAddress, serviceUUID, characteristicUUID, data, type);

        } else if (action.equals(START_NOTIFICATION)) {

            String macAddress = args.getString(0);
            UUID serviceUUID = uuidFromString(args.getString(1));
            UUID characteristicUUID = uuidFromString(args.getString(2));
            registerNotifyCallback(callbackContext, macAddress, serviceUUID, characteristicUUID);

        } else if (action.equals(STOP_NOTIFICATION)) {

            String macAddress = args.getString(0);
            UUID serviceUUID = uuidFromString(args.getString(1));
            UUID characteristicUUID = uuidFromString(args.getString(2));
            removeNotifyCallback(callbackContext, macAddress, serviceUUID, characteristicUUID);

        } else if (action.equals(IS_ENABLED)) {

            if (bluetoothAdapter.isEnabled()) {
                callbackContext.success();
            } else {
                callbackContext.error("Bluetooth is disabled.");
            }

        } else if (action.equals(IS_CONNECTED)) {

            String macAddress = args.getString(0);

            if (peripherals.containsKey(macAddress) && peripherals.get(macAddress).isConnected()) {
                callbackContext.success();
            } else {
                callbackContext.error("Not connected.");
            }

        } else if (action.equals(SETTINGS)) {

            Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            cordova.getActivity().startActivity(intent);
            callbackContext.success();

        } else if (action.equals(ENABLE)) {

            enableBluetoothCallback = callbackContext;
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            cordova.startActivityForResult(this, intent, REQUEST_ENABLE_BLUETOOTH);

        } else if (action.equals(START_STATE_NOTIFICATIONS)) {

            if (this.stateCallback != null) {
                callbackContext.error("State callback already registered.");
            } else {
                this.stateCallback = callbackContext;
                addStateListener();
                sendBluetoothStateChange(bluetoothAdapter.getState());
            }

        } else if (action.equals(STOP_STATE_NOTIFICATIONS)) {

            if (this.stateCallback != null) {
                // Clear callback in JavaScript without actually calling it
                PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
                result.setKeepCallback(false);
                this.stateCallback.sendPluginResult(result);
                this.stateCallback = null;
            }
            removeStateListener();
            callbackContext.success();

        } else if (action.equals(START_SCAN_WITH_OPTIONS)) {
            serviceUUIDs = parseServiceUUIDList(args.getJSONArray(0));
            JSONObject options = args.getJSONObject(1);

            resetScanOptions();
            this.reportDuplicates = options.optBoolean("reportDuplicates", false);
            findLowEnergyDevices(callbackContext, serviceUUIDs, -1);

        } else if (action.equals(BONDED_DEVICES)) {

            getBondedDevices(callbackContext);

        } else {

            validAction = false;

        }

        return validAction;
    }

    private void getBondedDevices(CallbackContext callbackContext) {
        JSONArray bonded = new JSONArray();
        Set<BluetoothDevice> bondedDevices =  bluetoothAdapter.getBondedDevices();

        for (BluetoothDevice device : bondedDevices) {
            device.getBondState();
            int type = device.getType();

            // just low energy devices (filters out classic and unknown devices)
            if (type == DEVICE_TYPE_LE || type == DEVICE_TYPE_DUAL) {
                Peripheral p = new Peripheral(device);
                bonded.put(p.asJSONObject());
            }
        }

        callbackContext.success(bonded);
    }

    private UUID[] parseServiceUUIDList(JSONArray jsonArray) throws JSONException {
        List<UUID> serviceUUIDs = new ArrayList<UUID>();

        for(int i = 0; i < jsonArray.length(); i++){
            String uuidString = jsonArray.getString(i);
            serviceUUIDs.add(uuidFromString(uuidString));
        }

        return serviceUUIDs.toArray(new UUID[jsonArray.length()]);
    }

    private void onBluetoothStateChange(Intent intent) {
        final String action = intent.getAction();

        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            sendBluetoothStateChange(state);
        }
    }

    private void sendBluetoothStateChange(int state) {
        if (this.stateCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, this.bluetoothStates.get(state));
            result.setKeepCallback(true);
            this.stateCallback.sendPluginResult(result);
        }
    }

    private void addStateListener() {
        if (this.stateReceiver == null) {
            this.stateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onBluetoothStateChange(intent);
                }
            };
        }

        try {
            IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            webView.getContext().registerReceiver(this.stateReceiver, intentFilter);
        } catch (Exception e) {
            LOG.e(TAG, "Error registering state receiver: " + e.getMessage(), e);
        }
    }

    private void removeStateListener() {
        if (this.stateReceiver != null) {
            try {
                webView.getContext().unregisterReceiver(this.stateReceiver);
            } catch (Exception e) {
                LOG.e(TAG, "Error unregistering state receiver: " + e.getMessage(), e);
            }
        }
        this.stateCallback = null;
        this.stateReceiver = null;
    }

    private void connect(CallbackContext callbackContext, String macAddress) {
        if (!peripherals.containsKey(macAddress) && BLECentralPlugin.this.bluetoothAdapter.checkBluetoothAddress(macAddress)) {
            BluetoothDevice device = BLECentralPlugin.this.bluetoothAdapter.getRemoteDevice(macAddress);
            Peripheral peripheral = new Peripheral(device);
            peripherals.put(macAddress, peripheral);
        }

        Peripheral peripheral = peripherals.get(macAddress);
        if (peripheral != null) {
            peripheral.connect(callbackContext, cordova.getActivity(), false);
        } else {
            callbackContext.error("Peripheral " + macAddress + " not found.");
        }

    }

    private void autoConnect(CallbackContext callbackContext, String macAddress) {
        Peripheral peripheral = peripherals.get(macAddress);

        // allow auto-connect to connect to devices without scanning
        if (peripheral == null) {
            if (BluetoothAdapter.checkBluetoothAddress(macAddress)) {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
                peripheral = new Peripheral(device);
                peripherals.put(device.getAddress(), peripheral);
            } else {
                callbackContext.error(macAddress + " is not a valid MAC address.");
                return;
            }
        }

        peripheral.connect(callbackContext, cordova.getActivity(), true);

    }

    private void disconnect(CallbackContext callbackContext, String macAddress) {

        Peripheral peripheral = peripherals.get(macAddress);
        if (peripheral != null) {
            peripheral.disconnect();
            callbackContext.success();
        } else {
            String message = "Peripheral " + macAddress + " not found.";
            LOG.w(TAG, message);
            callbackContext.error(message);
        }

    }

    private void requestMtu(CallbackContext callbackContext, String macAddress, int mtuValue) {

        Peripheral peripheral = peripherals.get(macAddress);
        if (peripheral != null) {
            peripheral.requestMtu(mtuValue);
        }
        callbackContext.success();
    }

    private void refreshDeviceCache(CallbackContext callbackContext, String macAddress, long timeoutMillis) {

        Peripheral peripheral = peripherals.get(macAddress);

        if (peripheral != null) {
            peripheral.refreshDeviceCache(callbackContext, timeoutMillis);
        } else {
            String message = "Peripheral " + macAddress + " not found.";
            LOG.w(TAG, message);
            callbackContext.error(message);
        }
    }

    private void read(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID) {

        Peripheral peripheral = peripherals.get(macAddress);

        if (peripheral == null) {
            callbackContext.error("Peripheral " + macAddress + " not found.");
            return;
        }

        if (!peripheral.isConnected()) {
            callbackContext.error("Peripheral " + macAddress + " is not connected.");
            return;
        }

        //peripheral.readCharacteristic(callbackContext, serviceUUID, characteristicUUID);
        peripheral.queueRead(callbackContext, serviceUUID, characteristicUUID);

    }

    private void readRSSI(CallbackContext callbackContext, String macAddress) {

        Peripheral peripheral = peripherals.get(macAddress);

        if (peripheral == null) {
            callbackContext.error("Peripheral " + macAddress + " not found.");
            return;
        }

        if (!peripheral.isConnected()) {
            callbackContext.error("Peripheral " + macAddress + " is not connected.");
            return;
        }
        peripheral.queueReadRSSI(callbackContext);
    }

    private void write(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID,
                       byte[] data, int writeType) {

        Peripheral peripheral = peripherals.get(macAddress);

        if (peripheral == null) {
            callbackContext.error("Peripheral " + macAddress + " not found.");
            return;
        }

        if (!peripheral.isConnected()) {
            callbackContext.error("Peripheral " + macAddress + " is not connected.");
            return;
        }

        //peripheral.writeCharacteristic(callbackContext, serviceUUID, characteristicUUID, data, writeType);
        peripheral.queueWrite(callbackContext, serviceUUID, characteristicUUID, data, writeType);

    }

    private void registerNotifyCallback(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID) {

        Peripheral peripheral = peripherals.get(macAddress);
        if (peripheral != null) {

            if (!peripheral.isConnected()) {
                callbackContext.error("Peripheral " + macAddress + " is not connected.");
                return;
            }

            //peripheral.setOnDataCallback(serviceUUID, characteristicUUID, callbackContext);
            peripheral.queueRegisterNotifyCallback(callbackContext, serviceUUID, characteristicUUID);

        } else {

            callbackContext.error("Peripheral " + macAddress + " not found");

        }

    }

    private void removeNotifyCallback(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID) {

        Peripheral peripheral = peripherals.get(macAddress);
        if (peripheral != null) {

            if (!peripheral.isConnected()) {
                callbackContext.error("Peripheral " + macAddress + " is not connected.");
                return;
            }

            peripheral.queueRemoveNotifyCallback(callbackContext, serviceUUID, characteristicUUID);

        } else {

            callbackContext.error("Peripheral " + macAddress + " not found");

        }

    }

    private void findLowEnergyDevices(CallbackContext callbackContext, UUID[] serviceUUIDs, int scanSeconds) {

        if (!locationServicesEnabled()) {
            callbackContext.error("Location Services are disabled");
            return;
        }

        if(!PermissionHelper.hasPermission(this, ACCESS_COARSE_LOCATION)) {
            // save info so we can call this method again after permissions are granted
            permissionCallback = callbackContext;
            this.serviceUUIDs = serviceUUIDs;
            this.scanSeconds = scanSeconds;
            PermissionHelper.requestPermission(this, REQUEST_ACCESS_COARSE_LOCATION, ACCESS_COARSE_LOCATION);
            return;
        }

        if(!PermissionHelper.hasPermission(this, WRITE_EXTERNAL_STORAGE)) {
            // save info so we can call this method again after permissions are granted
            permissionCallback = callbackContext;
            this.serviceUUIDs = serviceUUIDs;
            this.scanSeconds = scanSeconds;
            PermissionHelper.requestPermission(this, REQUEST_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE);
            return;
        }

        // return error if already scanning
        if (bluetoothAdapter.isDiscovering()) {
            LOG.w(TAG, "Tried to start scan while already running.");
            callbackContext.error("Tried to start scan while already running.");
            return;
        }

        // clear non-connected cached peripherals
        for(Iterator<Map.Entry<String, Peripheral>> iterator = peripherals.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<String, Peripheral> entry = iterator.next();
            Peripheral device = entry.getValue();
            boolean connecting = device.isConnecting();
            if (connecting){
                LOG.d(TAG, "Not removing connecting device: " + device.getDevice().getAddress());
            }
            if(!entry.getValue().isConnected() && !connecting) {
                iterator.remove();
            }
        }

        discoverCallback = callbackContext;

        if (serviceUUIDs != null && serviceUUIDs.length > 0) {
//            TODO: try to replace with bluetoothAdapter.getBluetoothLeScanner().startScan();
            bluetoothAdapter.startLeScan(serviceUUIDs, this);
        } else {
//            TODO: try to replace with bluetoothAdapter.getBluetoothLeScanner().startScan();
            bluetoothAdapter.startLeScan(this);
        }

        if (scanSeconds > 0) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    LOG.d(TAG, "Stopping Scan");
//            TODO: try to replace with bluetoothAdapter.getBluetoothLeScanner().stopScan();
                    BLECentralPlugin.this.bluetoothAdapter.stopLeScan(BLECentralPlugin.this);
                }
            }, scanSeconds * 1000);
        }

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    private boolean locationServicesEnabled() {
        int locationMode = 0;
        try {
            locationMode = Settings.Secure.getInt(cordova.getActivity().getContentResolver(), Settings.Secure.LOCATION_MODE);
        } catch (Settings.SettingNotFoundException e) {
            LOG.e(TAG, "Location Mode Setting Not Found", e);
        }
        return (locationMode > 0);
    }

    private void listKnownDevices(CallbackContext callbackContext) {

        JSONArray json = new JSONArray();

        // do we care about consistent order? will peripherals.values() be in order?
        for (Map.Entry<String, Peripheral> entry : peripherals.entrySet()) {
            Peripheral peripheral = entry.getValue();
            if (!peripheral.isUnscanned()) {
                json.put(peripheral.asJSONObject());
            }
        }

        PluginResult result = new PluginResult(PluginResult.Status.OK, json);
        callbackContext.sendPluginResult(result);
    }

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {

        String address = device.getAddress();
        boolean alreadyReported = peripherals.containsKey(address) && !peripherals.get(address).isUnscanned();

        if (!alreadyReported) {

            Peripheral peripheral = new Peripheral(device, rssi, scanRecord);
            peripherals.put(device.getAddress(), peripheral);

            if (discoverCallback != null) {
                PluginResult result = new PluginResult(PluginResult.Status.OK, peripheral.asJSONObject());
                result.setKeepCallback(true);
                discoverCallback.sendPluginResult(result);
            }

        } else {
            Peripheral peripheral = peripherals.get(address);
            peripheral.update(rssi, scanRecord);
            if (reportDuplicates && discoverCallback != null) {
                PluginResult result = new PluginResult(PluginResult.Status.OK, peripheral.asJSONObject());
                result.setKeepCallback(true);
                discoverCallback.sendPluginResult(result);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {

            if (resultCode == Activity.RESULT_OK) {
                LOG.d(TAG, "User enabled Bluetooth");
                if (enableBluetoothCallback != null) {
                    enableBluetoothCallback.success();
                }
            } else {
                LOG.d(TAG, "User did *NOT* enable Bluetooth");
                if (enableBluetoothCallback != null) {
                    enableBluetoothCallback.error("User did not enable Bluetooth");
                }
            }

            enableBluetoothCallback = null;
        }
    }

    /* @Override */
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        for(int result:grantResults) {
            if(result == PackageManager.PERMISSION_DENIED) {
                LOG.d(TAG, "User *rejected* Coarse Location Access");
                this.permissionCallback.error("Location permission not granted.");
                return;
            }
        }

        switch(requestCode) {
            case REQUEST_ACCESS_COARSE_LOCATION:
                LOG.d(TAG, "User granted Coarse Location Access");
                findLowEnergyDevices(permissionCallback, serviceUUIDs, scanSeconds);
                this.permissionCallback = null;
                this.serviceUUIDs = null;
                this.scanSeconds = -1;
                break;
        }
    }

    private UUID uuidFromString(String uuid) {
        return UUIDHelper.uuidFromString(uuid);
    }

    /**
     * Reset the BLE scanning options
     */
    private void resetScanOptions() {
        this.reportDuplicates = false;
    }

    public static class BLEService extends JobService {

        private BroadcastReceiver mReceiver;

        public BLEService() {}

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            try {
                IntentFilter filter = new IntentFilter("com.megster.cordova.ble.central.BLERestart");
                mReceiver = new BLEBroadcastReceiver();
                registerReceiver(mReceiver, filter);
            } catch (Exception ex) {
                Log.d(NATURAL_TAG + " ERROR", ex.toString());
                saveLog(new Date().toString() + " NATURAL ERROR - " + ex.toString());
            }

            Log.d(NATURAL_TAG, "on start command");
            saveLog( new Date().toString() + " NATURAL - on start command");
            return START_STICKY;
        }

        @Override
        public boolean onStartJob(final JobParameters params) {
            // The work that this service "does" is simply wait for a certain duration and finish
            // the job (on another thread).

            Log.d(NATURAL_TAG, "start job");
            saveLog(new Date().toString() + " NATURAL - start job");
            // Uses a handler to delay the execution of jobFinished().
            final Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.d(NATURAL_TAG, "job running");
                    saveLog(new Date().toString() + " NATURAL - job running");
//                jobFinished(params, false);

                    Notification notification =
                            new NotificationCompat.Builder(BLEService.this, "NaturalBLE")
                                    .setContentTitle("My notification")
                                    .setContentText("Hello World!").build();
                    startForeground(1, notification);

                    if(bluetoothAdapter == null) {
                        BluetoothManager bluetoothManager = (BluetoothManager) BLEService.this.getSystemService(Context.BLUETOOTH_SERVICE);
                        bluetoothAdapter = bluetoothManager.getAdapter();
                    }

                    try {
                        if (!peripherals.containsKey(macAddress) &&
                                bluetoothAdapter.checkBluetoothAddress(macAddress)) {
                            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
                            Peripheral peripheral = new Peripheral(device);
                            peripherals.put(macAddress, peripheral);
                        }

                        if(serviceUUIDs != null) {
                            List<ScanFilter> filters = new ArrayList();
                            ScanFilter scanFilter = new ScanFilter.Builder()
                                    .setServiceUuid(new ParcelUuid(serviceUUIDs[0]))
                                    .build();
                            filters.add(scanFilter);

                            ScanSettings settings = new ScanSettings.Builder()
                                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                                    .build();
                            bluetoothAdapter.getBluetoothLeScanner().startScan(filters, settings, new ScanCallback() {
                                @Override
                                public void onScanResult(int callbackType, ScanResult result) {
                                    super.onScanResult(callbackType, result);
                                    Log.d(BLECentralPlugin.NATURAL_TAG, "scan result");
                                    saveLog(new Date().toString() + " NATURAL - scan result");

                                    bluetoothAdapter.getBluetoothLeScanner().stopScan(this);
                                    Log.d(BLECentralPlugin.NATURAL_TAG, "scan stopped");
                                    saveLog(new Date().toString() + " NATURAL - scan stopped");
                                }
                            });

                            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
                            device.connectGatt(BLEService.this, true, new Peripheral(device));
                            Log.d(BLECentralPlugin.NATURAL_TAG, "connect gatt called on device");
                            saveLog(new Date().toString() + " NATURAL - connect gatt called on device");
                        } else {
//                            TODO check if makes any sense
                            bluetoothAdapter.startDiscovery();
                            Log.d(BLECentralPlugin.NATURAL_TAG, "start discovery");
                            saveLog(new Date().toString() + " NATURAL - start discovery");
                        }
                    } catch (Exception ex) {
                        Log.e(NATURAL_TAG + " ERROR", ex.toString());
                        saveLog(new Date().toString() + " NATURAL ERROR - " + ex.toString());
                    }

                    Handler handler2 = new Handler();
                    handler2.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(NATURAL_TAG, "before stop service");
                            saveLog(new Date().toString() + " NATURAL - before stop service");

                            stopForeground(true);
//                            stopSelf();
//                            stopService(foregroundIntent);
                            Log.d(NATURAL_TAG, "service stopped");
                            saveLog(new Date().toString() + " NATURAL - service stopped");

                            JobInfo.Builder builder = new JobInfo.Builder(new Random().nextInt(), new ComponentName(BLEService.this, BLEService.class));
                            builder.setMinimumLatency(2 * 60 * 1000);
                            builder.setOverrideDeadline(2 * 60 * 1000);
                            JobScheduler tm = (JobScheduler) BLEService.this.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                            tm.schedule(builder.build());

                            Log.d(NATURAL_TAG, "schedule job from handler2");
                            saveLog(new Date().toString() + " NATURAL - schedule job from handler2");
                        }
                    }, 10000);

                }
            }, 5000);

            // Return true as there's more work to be done with this job.
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            // Return false to drop the job.
            return true;
        }

        @Override
        public void onDestroy() {
            Log.d(NATURAL_TAG + " EXIT", "service on destroy");
            saveLog(new Date().toString() + " NATURAL EXIT - service on destroy");

            Intent broadcastIntent = new Intent("com.megster.cordova.ble.central.BLERestart");
            sendBroadcast(broadcastIntent);

            try {
                unregisterReceiver(mReceiver);
            } catch(Exception ex) {
                Log.e(NATURAL_TAG + " ERROR", ex.toString());
                saveLog("NATURAL ERROR - " + ex.toString());
            }
            super.onDestroy();
        }

        public static void saveLog(String text) {
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

}
