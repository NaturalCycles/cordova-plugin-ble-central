// (c) 2104 Don Coleman
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

import android.app.Activity;

import android.bluetooth.*;
import android.os.Build;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import java.lang.reflect.Method;

/**
 * Peripheral wraps the BluetoothDevice and provides methods to convert to JSON.
 */
public class Peripheral extends BluetoothGattCallback {

    // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
    //public final static UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    public final static UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUIDHelper.uuidFromString("2902");
    private static final String TAG = "Peripheral";

    private static final int FAKE_PERIPHERAL_RSSI = 0x7FFFFFFF;

    private BluetoothDevice device;
    private byte[] advertisingData;
    private int advertisingRSSI;
    private boolean autoconnect = false;
    private boolean connected = false;
    private boolean connecting = false;
    private ConcurrentLinkedQueue<BLECommand> commandQueue = new ConcurrentLinkedQueue<BLECommand>();
    private boolean bleProcessing;

    BluetoothGatt gatt;

    private CallbackContext connectCallback;
    private CallbackContext refreshCallback;
    private CallbackContext readCallback;
    private CallbackContext writeCallback;
    private Activity currentActivity;

    private CallbackContext mCallbackContext;

    private Map<String, CallbackContext> notificationCallbacks = new HashMap<String, CallbackContext>();
    private List<BLECentralPlugin.ConnectionStateListener> listeners = new ArrayList<BLECentralPlugin.ConnectionStateListener>();

    public Peripheral(BluetoothDevice device) {

        LOG.d(TAG, "Creating un-scanned peripheral entry for address: " + device.getAddress());

        this.device = device;
        this.advertisingRSSI = FAKE_PERIPHERAL_RSSI;
        this.advertisingData = null;

    }

    public Peripheral(BluetoothDevice device, int advertisingRSSI, byte[] scanRecord) {

        this.device = device;
        this.advertisingRSSI = advertisingRSSI;
        this.advertisingData = scanRecord;

    }

    private void gattConnect() {

        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        connected = false;
        connecting = true;
        queueCleanup();
        callbackCleanup();

        BluetoothDevice device = getDevice();
        if (Build.VERSION.SDK_INT < 23) {
            gatt = device.connectGatt(currentActivity, autoconnect, this);
        } else {
            gatt = device.connectGatt(currentActivity, autoconnect, this, BluetoothDevice.TRANSPORT_LE);
        }

    }

    public void connect(CallbackContext callbackContext, Activity activity, boolean auto) {
        currentActivity = activity;
        autoconnect = auto;
        connectCallback = callbackContext;

        gattConnect();

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    // the app requested the central disconnect from the peripheral
    // disconnect the gatt, do not call connectCallback.error
    public void disconnect() {
        connected = false;
        connecting = false;

        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        queueCleanup();
        callbackCleanup();
    }

    // the peripheral disconnected
    // always call connectCallback.error to notify the app
    private void peripheralDisconnected() {
        connected = false;
        connecting = false;

        // don't remove the gatt for autoconnect
        if (!autoconnect && gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }

        sendDisconnectMessage();

        queueCleanup();
        callbackCleanup();
    }

    // notify the phone that the peripheral disconnected
    private void sendDisconnectMessage() {
        if (connectCallback != null) {
            JSONObject message = this.asJSONObject("Peripheral Disconnected");
            if (autoconnect) {
                PluginResult result = new PluginResult(PluginResult.Status.ERROR, message);
                result.setKeepCallback(true);
                connectCallback.sendPluginResult(result);
            } else {
                connectCallback.error(message);
                connectCallback = null;
            }
        }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        LOG.d(TAG, "mtu=" + mtu + ", status=" + status);
        super.onMtuChanged(gatt, mtu, status);
    }

    public void requestMtu(int mtuValue) {
        if (gatt != null) {
            LOG.d(TAG, "requestMtu mtu=" + mtuValue);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                gatt.requestMtu(mtuValue);
            }
        }
    }

    /**
     * Uses reflection to refresh the device cache. This *might* be helpful if a peripheral changes
     * services or characteristics and does not correctly implement Service Changed 0x2a05
     * on Generic Attribute Service 0x1801.
     *
     * Since this uses an undocumented API it's not guaranteed to work.
     *
     */
    public void refreshDeviceCache(CallbackContext callback, final long timeoutMillis) {
        LOG.d(TAG, "refreshDeviceCache");

        boolean success = false;
        if (gatt != null) {
            try {
                final Method refresh = gatt.getClass().getMethod("refresh");
                if (refresh != null) {
                    success = (Boolean)refresh.invoke(gatt);
                    if (success) {
                        this.refreshCallback = callback;
                        Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                LOG.d(TAG, "Waiting " + timeoutMillis + " milliseconds before discovering services");
                                gatt.discoverServices();
                            }
                        }, timeoutMillis);
                    }
                } else {
                    LOG.w(TAG, "Refresh method not found on gatt");
                }
            } catch(Exception e) {
                LOG.e(TAG, "refreshDeviceCache Failed", e);
            }
        }

        if (!success) {
            callback.error("Service refresh failed");
        }
    }

    public boolean isUnscanned() {
        return advertisingData == null;
    }

    public JSONObject asJSONObject()  {

        JSONObject json = new JSONObject();

        try {
            json.put("name", device.getName());
            json.put("id", device.getAddress()); // mac address
            if (advertisingData != null) {
                json.put("advertising", byteArrayToJSON(advertisingData));
            }
            // TODO real RSSI if we have it, else
            if (advertisingRSSI != FAKE_PERIPHERAL_RSSI) {
                json.put("rssi", advertisingRSSI);
            }
        } catch (JSONException e) { // this shouldn't happen
            e.printStackTrace();
        }

        return json;
    }

    public JSONObject asJSONObject(String errorMessage)  {

        JSONObject json = new JSONObject();

        try {
            json.put("name", device.getName());
            json.put("id", device.getAddress()); // mac address
            json.put("errorMessage", errorMessage);
        } catch (JSONException e) { // this shouldn't happen
            e.printStackTrace();
        }

        return json;
    }

    public JSONObject asJSONObject(BluetoothGatt gatt) {

        JSONObject json = asJSONObject();

        try {
            JSONArray servicesArray = new JSONArray();
            JSONArray characteristicsArray = new JSONArray();
            json.put("services", servicesArray);
            json.put("characteristics", characteristicsArray);

            if (connected && gatt != null) {
                for (BluetoothGattService service : gatt.getServices()) {
                    servicesArray.put(UUIDHelper.uuidToString(service.getUuid()));

                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        JSONObject characteristicsJSON = new JSONObject();
                        characteristicsArray.put(characteristicsJSON);

                        characteristicsJSON.put("service", UUIDHelper.uuidToString(service.getUuid()));
                        characteristicsJSON.put("characteristic", UUIDHelper.uuidToString(characteristic.getUuid()));
                        //characteristicsJSON.put("instanceId", characteristic.getInstanceId());

                        characteristicsJSON.put("properties", Helper.decodeProperties(characteristic));
                        // characteristicsJSON.put("propertiesValue", characteristic.getProperties());

                        if (characteristic.getPermissions() > 0) {
                            characteristicsJSON.put("permissions", Helper.decodePermissions(characteristic));
                            // characteristicsJSON.put("permissionsValue", characteristic.getPermissions());
                        }

                        JSONArray descriptorsArray = new JSONArray();

                        for (BluetoothGattDescriptor descriptor: characteristic.getDescriptors()) {
                            JSONObject descriptorJSON = new JSONObject();
                            descriptorJSON.put("uuid", UUIDHelper.uuidToString(descriptor.getUuid()));
                            descriptorJSON.put("value", descriptor.getValue()); // always blank

                            if (descriptor.getPermissions() > 0) {
                                descriptorJSON.put("permissions", Helper.decodePermissions(descriptor));
                                // descriptorJSON.put("permissionsValue", descriptor.getPermissions());
                            }
                            descriptorsArray.put(descriptorJSON);
                        }
                        if (descriptorsArray.length() > 0) {
                            characteristicsJSON.put("descriptors", descriptorsArray);
                        }
                    }
                }
            }
        } catch (JSONException e) { // TODO better error handling
            e.printStackTrace();
        }

        return json;
    }

    static JSONObject byteArrayToJSON(byte[] bytes) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("CDVType", "ArrayBuffer");
        object.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        return object;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isConnecting() {
        return connecting;
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);

        Log.d(TAG, "on services discovered");

        try {
            registerNotifyCallback(
                    mCallbackContext,
                    UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                    UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
            );
        } catch (Exception ex) {
            Log.e(TAG + "-error", ex.toString());
        }

        // refreshCallback is a kludge for refreshing services, if it exists, it temporarily
        // overrides the connect callback. Unfortunately this edge case make the code confusing.

        if (status == BluetoothGatt.GATT_SUCCESS) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, this.asJSONObject(gatt));
            result.setKeepCallback(true);
            if (refreshCallback != null) {
                refreshCallback.sendPluginResult(result);
                refreshCallback = null;
            } else {
                connectCallback.sendPluginResult(result);
            }
        } else {
            LOG.e(TAG, "Service discovery failed. status = " + status);
            if (refreshCallback != null) {
                refreshCallback.error(this.asJSONObject("Service discovery failed"));
                refreshCallback = null;
            } else {
                connectCallback.error(this.asJSONObject("Service discovery failed"));
                disconnect();
            }
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

        this.gatt = gatt;

        if (newState == BluetoothGatt.STATE_CONNECTED) {
            LOG.d(TAG, "onConnectionStateChange CONNECTED");

            for(BLECentralPlugin.ConnectionStateListener l: listeners) {
                LOG.d(TAG, "onConnectionStateChange calling listener");
                l.peripheralConnected();
            }

            connected = true;
            connecting = false;
            gatt.discoverServices();
        } else {  // Disconnected
            LOG.d(TAG, "onConnectionStateChange DISCONNECTED");
            connected = false;
            peripheralDisconnected();

        }

    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        LOG.d(TAG, "onCharacteristicChanged " + characteristic);

        onResult(characteristic.getValue(), characteristic);

        CallbackContext callback = notificationCallbacks.get(generateHashKey(characteristic));

        if (callback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, characteristic.getValue());
            result.setKeepCallback(true);
            callback.sendPluginResult(result);
        }
    }

    private void onResult(byte[] bytes, BluetoothGattCharacteristic characteristic) {
        int[] data = new int[bytes.length];
        for (int i=0; i<data.length; i++) {
            data[i] = bytes[i] & 0xff;
        }
        StringBuilder stringBuilder = new StringBuilder();
        String[] hexData = new String[bytes.length];
        for(int i=0; i<hexData.length; i++) {
            byte b = bytes[i];
            stringBuilder.append(String.format("%02X ", b));
            hexData[i] = (String.format("%02X ", b));
        }

        LOG.d(TAG, "onCharacteristicChanged get value as string " + String.valueOf(Arrays.toString(data)));
        LOG.d(TAG, "onCharacteristicChanged get value in HEX " + String.valueOf(stringBuilder.toString()));

        String cmd = hexData[4];
        LOG.d(TAG, "onCharacteristicChanged cmd " + cmd);

        switch (cmd.trim().toLowerCase()) {
            case "a1":
                createResponseToA1(characteristic);
                break;
            case "a3":
                createResponseToA3(data[6], data[7]);
                break;
            default:
                handleTemperatureReceived(data);
        }
    }

    private void handleTemperatureReceived(int[] data) {
        int year = data[0];
        int month = data[1];
        int day = data[2];
        int hour = data[3];
        int minutes = data[4];
        int temp1 = data[5];
        int temp2 = data[6];
        int battery = (data[7] + 100) / 100;

        LOG.d(TAG, "handleTemperatureReceived year " + year);
        LOG.d(TAG, "handleTemperatureReceived month " + month);
        LOG.d(TAG, "handleTemperatureReceived day " + day);
        LOG.d(TAG, "handleTemperatureReceived hour " + hour);
        LOG.d(TAG, "handleTemperatureReceived minutes " + minutes);
        LOG.d(TAG, "handleTemperatureReceived temp1 " + temp1);
        LOG.d(TAG, "handleTemperatureReceived temp2 " + temp2);
        LOG.d(TAG, "handleTemperatureReceived battery " + battery);

        BLECentralPlugin.BLEService.saveLog(new Date().toString() + " NATURAL - received temperature: " + temp1 + "." + temp2);

        for(BLECentralPlugin.ConnectionStateListener l: listeners) {
            LOG.d(TAG, "handleTemperatureReceived calling listener");
            l.temperatureReceived(String.valueOf(temp1) + "." + String.valueOf(temp2));
        }
    }

    private void createResponseToA1(BluetoothGattCharacteristic characteristic) {
        LOG.d(TAG, "Create response to A1");

        Calendar date = new GregorianCalendar();
        byte[] response = new byte[12];
        // '_' marks constant values
        response[0] = (byte) (0x4d & 0xFF); // _Header
        response[1] = (byte) (0xfc & 0xFF); // _Device
        response[2] = (byte) (0x00 & 0xFF); // _Length_H
        response[3] = (byte) (0x08 & 0xFF); // _Length_L
        response[4] = (byte) (0xa1 & 0xFF); // _CMD
        response[5] = (byte) (Integer.parseInt(String.valueOf(date.get(Calendar.YEAR)).substring(2)) & 0xFF); // Year (20XX)
        response[6] = (byte) ((date.get(Calendar.MONTH) + 1) & 0xFF); // Month
        response[7] = (byte) (date.get(Calendar.DAY_OF_MONTH) & 0xFF); // Day
        response[8] = (byte) (date.get(Calendar.HOUR_OF_DAY) & 0xFF); // Hour
        response[9] = (byte) (date.get(Calendar.MINUTE) & 0xFF); // Minute
        response[10] = (byte) (0x07 & 0xFF); // CtrlMode (XXXX {buzzer [for the new batch per Protocol v1.0.4]} {dateFormat} {backlight} {unit})
        response[11] = (byte) (0x3e & 0xFF); // Checksum

        Log.d(TAG, "create response to a1, response: " + String.valueOf(Arrays.toString(response)));

//        if(serviceUUID != null) {
        writeCharacteristic(
                writeCallback,
                UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
                response,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
//        }
    }

    private void createResponseToA3(int dcntH, int dcntL) {
        LOG.d(TAG, "Create response to A3");

        byte[] response = new byte[8];
        // '_' marks constant values
        response[0] = (byte) (0x4d & 0xFF);// _Header
        response[1] = (byte) (0xfc & 0xFF); // _Device
        response[2] = (byte) (0x00 & 0xFF);// _Length_H
        response[3] = (byte) (0x04 & 0xFF);// _Length_L
        response[4] = (byte) (0xa3 & 0xFF);// CMD
        response[5] = (byte) dcntH;// DCnt_H
        response[6] = (byte) dcntL;// DCnt_L
        response[7] = (byte) (0xbb & 0xFF);// _Checksum

        Log.d(TAG, "create response to a3, response: " + String.valueOf(Arrays.toString(response)));

        writeCharacteristic(
                writeCallback,
                UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
                response,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
        LOG.d(TAG, "onCharacteristicRead " + characteristic);

        synchronized(this) {
            if (readCallback != null) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    readCallback.success(characteristic.getValue());
                } else {
                    readCallback.error("Error reading " + characteristic.getUuid() + " status=" + status);
                }

                readCallback = null;
            }
        }

        commandCompleted();
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);
        LOG.d(TAG, "onCharacteristicWrite, characteristic status: " + status);

        synchronized(this) {
            if (writeCallback != null) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeCallback.success();
                } else {
                    writeCallback.error(status);
                }

                writeCallback = null;
            }
        }

        commandCompleted();
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorWrite(gatt, descriptor, status);
        LOG.d(TAG, "onDescriptorWrite " + descriptor);
        commandCompleted();
    }


    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        super.onReadRemoteRssi(gatt, rssi, status);
        synchronized(this) {
            if (readCallback != null) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    updateRssi(rssi);
                    readCallback.success(rssi);
                } else {
                    readCallback.error("Error reading RSSI status=" + status);
                }

                readCallback = null;
            }
        }
        commandCompleted();
    }

    // Update rssi and scanRecord.
    public void update(int rssi, byte[] scanRecord) {
        this.advertisingRSSI = rssi;
        this.advertisingData = scanRecord;
    }

    public void updateRssi(int rssi) {
        advertisingRSSI = rssi;
    }

    // This seems way too complicated
    private void registerNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {

        boolean success = false;

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findNotifyCharacteristic(service, characteristicUUID);
        String key = generateHashKey(serviceUUID, characteristic);

        if (characteristic != null) {

            notificationCallbacks.put(key, callbackContext);

            if (gatt.setCharacteristicNotification(characteristic, true)) {

                // Why doesn't setCharacteristicNotification write the descriptor?
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
                if (descriptor != null) {

                    // prefer notify over indicate
                    if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    } else {
                        LOG.w(TAG, "Characteristic " + characteristicUUID + " does not have NOTIFY or INDICATE property set");
                    }

                    if (gatt.writeDescriptor(descriptor)) {
                        success = true;
                    } else {
                        callbackContext.error("Failed to set client characteristic notification for " + characteristicUUID);
                    }

                } else {
                    callbackContext.error("Set notification failed for " + characteristicUUID);
                }

            } else {
                callbackContext.error("Failed to register notification for " + characteristicUUID);
            }

        } else {
            callbackContext.error("Characteristic " + characteristicUUID + " not found");
        }

        if (!success) {
            commandCompleted();
        }
    }

    private void removeNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findNotifyCharacteristic(service, characteristicUUID);
        String key = generateHashKey(serviceUUID, characteristic);

        if (characteristic != null) {

            notificationCallbacks.remove(key);

            if (gatt.setCharacteristicNotification(characteristic, false)) {
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
                callbackContext.success();
            } else {
                // TODO we can probably ignore and return success anyway since we removed the notification callback
                callbackContext.error("Failed to stop notification for " + characteristicUUID);
            }

        } else {
            callbackContext.error("Characteristic " + characteristicUUID + " not found");
        }

        commandCompleted();

    }

    // Some devices reuse UUIDs across characteristics, so we can't use service.getCharacteristic(characteristicUUID)
    // instead check the UUID and properties for each characteristic in the service until we find the best match
    // This function prefers Notify over Indicate
    private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;

        // Check for Notify first
        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        if (characteristic != null) return characteristic;

        // If there wasn't Notify Characteristic, check for Indicate
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
        if (characteristic == null) {
            characteristic = service.getCharacteristic(characteristicUUID);
        }

        return characteristic;
    }

    private void readCharacteristic(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {

        boolean success = false;

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);

        if (service == null) {
            callbackContext.error("Service " + serviceUUID + " not found.");
            return;
        }

        BluetoothGattCharacteristic characteristic = findReadableCharacteristic(service, characteristicUUID);

        if (characteristic == null) {
            callbackContext.error("Characteristic " + characteristicUUID + " not found.");
        } else {
            synchronized(this) {
                readCallback = callbackContext;
                if (gatt.readCharacteristic(characteristic)) {
                    success = true;
                } else {
                    readCallback = null;
                    callbackContext.error("Read failed");
                }
            }
        }

        if (!success) {
            commandCompleted();
        }

    }

    private void readRSSI(CallbackContext callbackContext) {

        boolean success = false;

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        synchronized(this) {
            readCallback = callbackContext;

            if (gatt.readRemoteRssi()) {
                success = true;
            } else {
                readCallback = null;
                callbackContext.error("Read RSSI failed");
            }
        }

        if (!success) {
            commandCompleted();
        }

    }

    // Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
    // and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
    private BluetoothGattCharacteristic findReadableCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;

        int read = BluetoothGattCharacteristic.PROPERTY_READ;

        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & read) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
        if (characteristic == null) {
            characteristic = service.getCharacteristic(characteristicUUID);
        }

        return characteristic;
    }

    private void writeCharacteristic(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {
        Log.d(TAG, "writeCharacteristic");

        Log.d(TAG, "serviceUUID: " + serviceUUID.toString());
        Log.d(TAG, "characteristicUUID: " + characteristicUUID.toString());
        Log.d(TAG, "writeType: " + writeType);
        Log.d(TAG, "data: " + String.valueOf(Arrays.toString(data)));

        boolean success = false;

        if (gatt == null) {
//            callbackContext.error("BluetoothGatt is null");
            Log.d(TAG, "BluetoothGatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);

        if (service == null) {
//            callbackContext.error("Service " + serviceUUID + " not found.");
            Log.d(TAG, "service not found");
            return;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
//        BluetoothGattCharacteristic characteristic = findWritableCharacteristic(service, characteristicUUID, writeType);
        Log.d(TAG, "writeCharacteristic, characteristic: " + characteristic.getUuid());

        if (characteristic == null) {
//            callbackContext.error("Characteristic " + characteristicUUID + " not found.");
            Log.d(TAG, "characteristic not found");
        } else {
            characteristic.setValue(data);
            byte[] testBytes = characteristic.getValue();

            int[] testData = new int[testBytes.length];
            for (int i=0; i<testData.length; i++) {
                testData[i] = testBytes[i] & 0xff;
            }
            StringBuilder stringBuilder = new StringBuilder();
            String[] hexData = new String[testBytes.length];
            for(int i=0; i<hexData.length; i++) {
                byte b = testBytes[i];
                stringBuilder.append(String.format("%02X ", b));
                hexData[i] = (String.format("%02X ", b));
            }

            LOG.d(TAG, "setValue, value as string " + String.valueOf(Arrays.toString(testData)));
            LOG.d(TAG, "setValue, value in HEX " + String.valueOf(stringBuilder.toString()));
            characteristic.setWriteType(writeType);
            synchronized(this) {
                writeCallback = callbackContext;

                if (gatt.writeCharacteristic(characteristic)) {
                    success = true;
                } else {
                    writeCallback = null;
//                    callbackContext.error("Write failed");
                    Log.d(TAG, "Write failed");
                }
            }
        }

        if (!success) {
            commandCompleted();
        }

    }

    // Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
    // and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
    private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGattService service, UUID characteristicUUID, int writeType) {
        BluetoothGattCharacteristic characteristic = null;

        // get write property
        int writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE;
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        }

        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & writeProperty) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
        if (characteristic == null) {
            characteristic = service.getCharacteristic(characteristicUUID);
        }

        return characteristic;
    }

    public void queueRead(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.READ);
        queueCommand(command);
    }

    public void queueWrite(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, data, writeType);
        queueCommand(command);
    }

    public void queueRegisterNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.REGISTER_NOTIFY);
        queueCommand(command);
    }

    public void queueRemoveNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.REMOVE_NOTIFY);
        queueCommand(command);
    }


    public void queueReadRSSI(CallbackContext callbackContext) {
        BLECommand command = new BLECommand(callbackContext, null, null, BLECommand.READ_RSSI);
        queueCommand(command);
    }

    private void queueCleanup() {
        bleProcessing = false;
        BLECommand command;
        for (;;) {
            command = commandQueue.poll();
            if (command != null) {
                command.getCallbackContext().error("Peripheral Disconnected");
            }
            else {
                break;
            }
        }
    }

    private void callbackCleanup() {
        synchronized(this) {
            if (readCallback != null) {
                readCallback.error(this.asJSONObject("Peripheral Disconnected"));
                readCallback = null;
                commandCompleted();
            }
            if (writeCallback != null) {
                writeCallback.error(this.asJSONObject("Peripheral Disconnected"));
                writeCallback = null;
                commandCompleted();
            }
        }
    }

    // add a new command to the queue
    private void queueCommand(BLECommand command) {
        LOG.d(TAG,"Queuing Command " + command);
        commandQueue.add(command);

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        command.getCallbackContext().sendPluginResult(result);

        if (!bleProcessing) {
            processCommands();
        }
    }

    // command finished, queue the next command
    private void commandCompleted() {
        LOG.d(TAG,"Processing Complete");
        bleProcessing = false;
        processCommands();
    }

    // process the queue
    private void processCommands() {
        LOG.d(TAG,"Processing Commands");

        if (bleProcessing) { return; }

        BLECommand command = commandQueue.poll();
        if (command != null) {
            if (command.getType() == BLECommand.READ) {
                LOG.d(TAG,"Read " + command.getCharacteristicUUID());
                bleProcessing = true;
                readCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else if (command.getType() == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                LOG.d(TAG,"Write " + command.getCharacteristicUUID());
                bleProcessing = true;
                writeCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID(), command.getData(), command.getType());
            } else if (command.getType() == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                LOG.d(TAG,"Write No Response " + command.getCharacteristicUUID());
                bleProcessing = true;
                writeCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID(), command.getData(), command.getType());
            } else if (command.getType() == BLECommand.REGISTER_NOTIFY) {
                LOG.d(TAG,"Register Notify " + command.getCharacteristicUUID());
                bleProcessing = true;
                registerNotifyCallback(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else if (command.getType() == BLECommand.REMOVE_NOTIFY) {
                LOG.d(TAG,"Remove Notify " + command.getCharacteristicUUID());
                bleProcessing = true;
                removeNotifyCallback(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else if (command.getType() == BLECommand.READ_RSSI) {
                LOG.d(TAG,"Read RSSI");
                bleProcessing = true;
                readRSSI(command.getCallbackContext());
            } else {
                // this shouldn't happen
                throw new RuntimeException("Unexpected BLE Command type " + command.getType());
            }
        } else {
            LOG.d(TAG, "Command Queue is empty.");
        }

    }

    private String generateHashKey(BluetoothGattCharacteristic characteristic) {
        return generateHashKey(characteristic.getService().getUuid(), characteristic);
    }

    private String generateHashKey(UUID serviceUUID, BluetoothGattCharacteristic characteristic) {
        return String.valueOf(serviceUUID) + "|" + characteristic.getUuid() + "|" + characteristic.getInstanceId();
    }

    public void registerConnectionStateListener(BLECentralPlugin.ConnectionStateListener listener) {
        listeners.add(listener);
    }

}