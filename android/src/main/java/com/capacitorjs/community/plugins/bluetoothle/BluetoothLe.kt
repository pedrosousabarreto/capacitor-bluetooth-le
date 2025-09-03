package com.capacitorjs.community.plugins.bluetoothle

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
import android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.core.location.LocationManagerCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Logger
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.nio.ByteBuffer

import java.util.UUID


@SuppressLint("MissingPermission")
@CapacitorPlugin(
    name = "BluetoothLe",
    permissions = [
        Permission(
            strings = [
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ], alias = "ACCESS_COARSE_LOCATION"
        ),
        Permission(
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
            ], alias = "ACCESS_FINE_LOCATION"
        ),
        Permission(
            strings = [
                Manifest.permission.BLUETOOTH,
            ], alias = "BLUETOOTH"
        ),
        Permission(
            strings = [
                Manifest.permission.BLUETOOTH_ADMIN,
            ], alias = "BLUETOOTH_ADMIN"
        ),
        Permission(
            strings = [
                // Manifest.permission.BLUETOOTH_SCAN
                "android.permission.BLUETOOTH_SCAN",
            ], alias = "BLUETOOTH_SCAN"
        ),
        Permission(
            strings = [
                // Manifest.permission.BLUETOOTH_ADMIN
                "android.permission.BLUETOOTH_CONNECT",
            ], alias = "BLUETOOTH_CONNECT"
        ),
        Permission(
            strings = [
                "android.permission.BLUETOOTH_ADVERTISE",
            ], alias = "BLUETOOTH_ADVERTISE"
        )
    ]
)
class BluetoothLe : Plugin() {
    companion object {
        private val TAG = BluetoothLe::class.java.simpleName

        // maximal scan duration for requestDevice
        private const val MAX_SCAN_DURATION: Long = 30000
        private const val CONNECTION_TIMEOUT: Float = 10000.0F
        private const val DEFAULT_TIMEOUT: Float = 5000.0F
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var stateReceiver: BroadcastReceiver? = null
    private var deviceMap = HashMap<String, Device>()
    private var deviceScanner: DeviceScanner? = null
    private var displayStrings: DisplayStrings? = null
    private var aliases: Array<String> = arrayOf()

    override fun load() {
        displayStrings = getDisplayStrings()
    }

    @PluginMethod
    fun initialize(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val neverForLocation = call.getBoolean("androidNeverForLocation", false) as Boolean
            aliases = if (neverForLocation) {
                arrayOf(
                    "BLUETOOTH_SCAN",
                    "BLUETOOTH_CONNECT",
                )
            } else {
                arrayOf(
                    "BLUETOOTH_SCAN",
                    "BLUETOOTH_CONNECT",
                    "ACCESS_FINE_LOCATION",
                )
            }
        } else {
            aliases = arrayOf(
                "ACCESS_COARSE_LOCATION",
                "ACCESS_FINE_LOCATION",
                "BLUETOOTH",
                "BLUETOOTH_ADMIN",
            )
        }
        requestPermissionForAliases(aliases, call, "checkPermission")
    }

    @PermissionCallback
    private fun checkPermission(call: PluginCall) {
        val granted: List<Boolean> = aliases.map { alias ->
            getPermissionState(alias) == PermissionState.GRANTED
        }
        // all have to be true
        if (granted.all { it }) {
            runInitialization(call)
        } else {
            call.reject("Permission denied.")
        }
    }

    private fun runInitialization(call: PluginCall) {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            call.reject("BLE is not supported.")
            return
        }

        bluetoothAdapter =
            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (bluetoothAdapter == null) {
            call.reject("BLE is not available.")
            return
        }
        call.resolve()
    }



    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertisingSetCallback? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null

    @TargetApi(Build.VERSION_CODES.O)
    @PluginMethod
    fun stopAdvertising(call: PluginCall){
        if(advertiser == null || callback == null) return;

        advertiser!!.stopAdvertisingSet(callback)
    }


    @TargetApi(Build.VERSION_CODES.Q)
    @PluginMethod
    fun startAdvertising(call: PluginCall){
        if(advertiser == null) {
            val enabled = bluetoothAdapter?.isEnabled == true
            if (!enabled) {
                Toast.makeText(this.context, "startAdvertising is NOT enabled", Toast.LENGTH_SHORT)
                    .show();
                return
            }
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        }


        // Check if all features are supported
        if (!bluetoothAdapter!!.isLe2MPhySupported()) {
            Logger.warn(TAG, "2M PHY not supported!")
            return
        }
        if (!bluetoothAdapter!!.isLeExtendedAdvertisingSupported()) {
            Logger.warn(TAG, "LE Extended Advertising not supported!")
            return
        }

        Logger.warn(TAG, "Address: "+bluetoothAdapter!!.address+" Name: "+bluetoothAdapter!!.name);

        val maxDataLength: Int = bluetoothAdapter!!.getLeMaximumAdvertisingDataLength()

        // known GattCharacteristics UUIDs
        val deviceNameCharacteristicId = "00002a00-0000-1000-8000-00805f9b34fb";

        // known GattDescriptors UUIDs
        val clientCharacteristicConfigurationId = "00002902-0000-1000-8000-00805f9b34fb";


        val serviceUuidStr = "89efdd3a-a65b-40ce-b10d-44558cb9caaa";
        var uuid = ParcelUuid(UUID.fromString(serviceUuidStr));

        val readCharacteristicUuidStr = "89efdd3a-a65b-40ce-b10d-ccccccccccc1";
        val readCharacteristic2UuidStr = "89efdd3a-a65b-40ce-b10d-ccccccccccc2";
        val writeCharacteristicUuidStr = "89efdd3a-a65b-40ce-b10d-ccccccccccc3";


        val parameters = (AdvertisingSetParameters.Builder())
            .setLegacyMode(false)
            .setConnectable(true)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MIN)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_2M)
            .build()

        val advertiseData = AdvertiseData.Builder()
            //.setIncludeDeviceName(true)
            //.setIncludeTxPowerLevel(true)
            .addServiceUuid(uuid) // must be added to be found by service id
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(serviceUuidStr)))
//            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)
            .build()


        callback = object : AdvertisingSetCallback() {

            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet,txPower: Int,status: Int) {
                Logger.info(TAG, ("onAdvertisingSetStarted(): txPower:" + txPower + " , status: " + status))
//                currentAdvertisingSet = advertisingSet
                // After onAdvertisingSetStarted callback is called, you can modify the
                // advertising data and scan response data:
                if (status != ADVERTISE_SUCCESS) {
                    Logger.warn(TAG, "onAdvertisingSetStarted failed - status:$status")
                    return;
                }
            }

            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                Logger.info(TAG, "onAdvertisingDataSet() :status:$status")
            }

            override fun onScanResponseDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                Logger.info(TAG, "onScanResponseDataSet(): status:$status")
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                Logger.info(TAG, "onAdvertisingSetStopped():")
            }
        }


        advertiser!!.startAdvertisingSet(parameters, advertiseData, scanResponseData, null, null, callback)

        ////////////////////////////////////////
        // setup gatt service
        ////////////////////////////////////////

        val service = BluetoothGattService(UUID.fromString(serviceUuidStr), BluetoothGattService.SERVICE_TYPE_PRIMARY)

        //add a read characteristic.
        val psmReadCharacteristic: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(readCharacteristic2UuidStr),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        );

        val notifyReadCharacteristic: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(readCharacteristicUuidStr),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        );
        // needs a descriptor
        var readCharacteristicDesc: BluetoothGattDescriptor = BluetoothGattDescriptor(
            UUID.fromString(clientCharacteristicConfigurationId),
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        );
        notifyReadCharacteristic.addDescriptor(readCharacteristicDesc);


        //add a write characteristic.
        val writeCharacteristic: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(writeCharacteristicUuidStr),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

        service.addCharacteristic(psmReadCharacteristic)
        service.addCharacteristic(notifyReadCharacteristic)
        service.addCharacteristic(writeCharacteristic)


//        var respData:String = "very long response that needs to be broken up unto multiple parts, VERY LONG RESPONSE THAT NEEDS TO BE BROKEN UP UNTO MULTIPLE PARTS, very long response that needs to be broken up unto multiple parts, VERY LONG RESPONSE THAT NEEDS TO BE BROKEN UP UNTO MULTIPLE PARTS, very long response that needs to be broken up unto multiple parts, VERY LONG RESPONSE THAT NEEDS TO BE BROKEN UP UNTO MULTIPLE PARTS, END";
        var respData:String = "{\"transferId\":\"a1d05beb-4b4a-49f9-8160-95b53dc7365e\",\"transferDate\":\"2025-08-25T23:05: 32.778Z\",\"senderDeviceId\":\"Pixel\",\"senderWalletAddress\":\"Pedro_Pixel\",\"recipientDeviceId\":\"iPhone\",\"recipientWalletAddress\":\"Elsa_iPhone\",\"tokens\":[{\"tokenId\":\"aec22fb45ab25a7ed9cd35e70417a045593d2a5b97c189c8cd2dc46b3781f9d2\",\"mintedForWalletAddress\":\"Pedro_Pixel\",\"signature\":\"TW5iwo3CmsOHw5rCpTBOw57ChsKrwpPChsOxwoHDqsOCwpvDkVXDvCfChcK8wot5KyjDuGYPw6vCscK0FVZOw7XCjSXCgxxSw6rCi8O5wrtBw5bDgAxcwr/CgDktwqdCaW1nw5rDp1FIw48MwqPCvyXDsmQHwq4gD8OqNCPDisOWTHkKW8Okw58yw6jCqMK3w7JFwpZrJT3DsD9ywqR4wps9w6DDlHLDh01mw7bClm7CusOyF8K5wrnCgnbCjAXCh0oaeG8qwp8QJcK5wrbCvMOQFU9XXFZiwpfDscKDYMO3QXYnTsO5w743w58lUMOpwpvCgsO5cm9iG8OBK8Kue2ZJw7lLwrERSsKhw7jCtcOowqBwwpgPwoYIdxIlQ1TCh8OTw6hiwppOw58lw5vChcKzwp7CusKnw7PDp8KwwqQ5K0RIw6nCu3/DimrCnRTDjcKTw7LDrlzDlMKuMsOzTU5jwqPCm8O0wohuPz7ChsKuwqBySjouw6PCqyLDph/DiyZtwpY=\"},{\"tokenId\":\"2a22547ba9243edd7ee353f9428609a00a0ad81af133a71e7f1299ae3ae73e19\",\"mintedForWalletAddress\":\"Pedro_Pixel\",\"signature\":\"VcK/wr5fPcOyNQk+eSMFwqvCgMKmw6Bsw4BSwoJfbTLDtnVHZTRuV2zDm8O2w7jCj8O8Z8OOdFRlMHw6wqnCvnRQOcO+wo9iNcKoUnZ+w5zCgCE5S8KAw5wJw5lmw7FJwrZxfRPDmcOHw75Rw6/Do0zDrcKCw60kw7/CoMKHw6VBwrQWw53CgcOJwqEMwqpswr/DqUVJAcO/wpt+c8ODXkzCjBXDqsOIwrc/bMOLwpRpw4lXIj0uw6fDp25RTTPChRDDicOJQihQCiZ2wq/ClcKGwpDCq8KhBSIAwqjDvMOHwpvDsWTDn1rClQXDgcOvbMOfw63DtHfDnsOtEz47VgMow7AgwqxREcKNwoASecOgGMKqw4NUBMOpwovCu8KHwq/CnFXCrWQIwoDDsXlUelnClQHCtsOffz8iw6gONlFQW2kbYzTDosO8wrkQw49qwqLDj8K+bTDCpsODP0LCjMO4XDDDtH1kwr7Dk1cNfkp0w7HDlX0A\"},{\"tokenId\":\"9c9a6e4fb84c640b793618ff64c23f7ea0396743b7b3c5a56858d22fd1e19436\",\"mintedForWalletAddress\":\"Pedro_Pixel\",\"signature\":\"wqg/w6hEw5FUwr5sQsKyFATDi8KbRzHCiMO6w40aIMOCwpsKYBnCocKXw7/DgMOGwqMtMcKEb8OWWRkTFMOMw63DgMOcOUs4wpzCk8O7wrjDnxTCkHHCvVsDw6DCpGxdwqXDulU/QcO4XsK4ccO3w7lXwoDDv3DCkjLCt8KwDXrCiMOvw5Row73ChcKCwozDj8K5wrZBw4BlCMOmHE7CvcOjXDzCr8KlP3fDhlhAGsOfw6Nnw4HCl8Kmck5jPsO4ccO8V2g/FcOLWQ8JwqzDkhjCqcOUw5pAFWfDkjd0w6ZVBzrChcO5w6vCuADDvsK+w6jDuVlkwq9eEGRzZcOwR8OBw74OSMKhLxDCqlfDpMK4w5MQQ8OkwqXCjEjCh8OowpPCiErCrRp0AihKfy4FQmtFPhJOw6E9KsOkw5olQwwUw4t6YcOEBsKuwqJjwrbDgnzDuTI0YcOrGXNowqITwrLDmVTDhyjDpMOZwoImw7lAwoV1w73Ds1LDr8O+\"}],\"previousTransfers\":[],\"hash\":\"36c38d2d2e47d0aa4793870c7f821c583c59865dc017c2bd0b1e4d0336256cbf\",\"senderSignature\":\"wonDicKdWsKFw5fCicOGw5hmw4oKAX01w7Qlw73Dt8KKw4YTWmg2w4zCr8KHw7gTG8KreMO2w77DkVY3wrLDpsKsw49VwolPVQw8woYNwpvDs3XDqsOYZsKJwoTDosKCM8O7w6zCoMOWB8OqN8KJwqMTwrEOIcKLVSFFbB1HQwcQw4LDl1BqOinDn8OewpwUwpYKwoIKwo1uw4vDosOfwrXDkMOlwqHDi0wtw6U4w7Vyw55Ow5rDhD3Dr8KDwpQawoPCoR7DqMOOw4zCsGDChlV3w6pkw44JcH7DtcOLw6kQO8KSeMOsQ8KPw4XCqMKbw77CmXA9bzfDr8K1ZsKNwrrDs1DDjWPDkirClUhRByHCpcKqAh7DjcKOw4XCgU7DoCh6DsOKU8OEwooow6ZKNMK4TcK9w77DvhlELiHCt8Obw6d/w6B0w67CgcOowp/DpcKTacOvw6fDjsOqLsOzNxLCucO3GzgmwpRDCsKhwrbCsUXCgxvDlMOew6HCtGY2woJewpd/woR9wprCpExK\",\"recipientSignature\":\"U8OIwo7DpC3Cv8K4w7p/wrsiV8Oawrpfw6sjdwXDo8OyUHkZw6/DkzBATAfDicKeRMO1wqgSwolOwqdlwp8uExY1FmvCgxATw4PDkFjCvcO5wp3DgsOqw4Vgw6YcCw7Dm8KJw7XDuXYIwokeCsKBDsKYbMOvY8OowrHClAR1AhIDeg7Ch8K9QmHDkUbCo8Kdw5XDksOyUsOIw4DCvTBDwr1lQiYfw4rCuMKHNMOvw7rDpSPDtkFWw5rCpsKkECbCgMKSw6EfRSkjwqMDwpY+woxdw7/CvS3Cs8KLHcKpwoLCmg5Sw73CqsOvdSlNNGnChznCtHAHQ0DDpMOWwqo9wrkkw6vDvz7CscKHZSMWDnfCnsOqwrcvwrDDtlHCkVQ/PQxpwpLCtk3DqwVzC8KMaMOhFcKGwoUxw4xqQMODwoDDv8OawoVXw7cHw5wrwrLCqh/Dq8OkGMK2w7pSURoeBydzQcOBwpDCqsKnw74swokwwovCmnzCkMOIRcKvw4bCgcOC\"}";
        var negotiatedMtu:Int = 0;

        var chunkIndex = 0;
        var chunks: List<String>? = null;

        val callback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?,status: Int,newState: Int) {
                super.onConnectionStateChange(device, status, newState)

                if(device == null) return;
                Logger.info(TAG, "onConnectionStateChange() device address:" +device!!.address.toString() + " status: "+status.toString());
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                super.onServiceAdded(status, service)
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                if(bluetoothGattServer == null) {
                    return;
                }

                if(characteristic!!.uuid.compareTo(UUID.fromString(readCharacteristic2UuidStr)) !== 0) {
                    bluetoothGattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0,null);
                    return;
                }

                // send psm
                val psm = serverSocket!!.psm;
                bluetoothGattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(psm).array());

                Logger.info(TAG, "onCharacteristicReadRequest: sent PSM $psm");
                return;

//
//                if(chunks == null) {
//                    chunks = respData.chunked(negotiatedMtu);
//                }
///*
//
//                if(chunkIndex >= chunks.size){
//
//                }
//
//                Logger.info(TAG, "onCharacteristicReadRequest - sending chunk index: $chunkIndex");
//                bluetoothGattServer!!.sendResponse(
//                    device, requestId, BluetoothGatt.GATT_SUCCESS,
//                    offset, chunks!![offset].toByteArray(Charsets.UTF_8)
//                );
//                chunkIndex++;
//*/
//
///*
//
//                    var respDataParts =  respData.chunked(negotiatedMtu);
//                    respDataParts.forEachIndexed{index, part->
//                        Logger.info(TAG, "characteristic: sent part offset $offset chunk len: ${part.length} ret offset: ${index*negotiatedMtu}")
//                        bluetoothGattServer!!.sendResponse(
//                            device, requestId, BluetoothGatt.GATT_SUCCESS,
////                        retOffset, respDataParts[offset].toByteArray(Charsets.UTF_8)
//                            index*negotiatedMtu, part.toByteArray(Charsets.UTF_8)
//                        );
//                    }
//
//                    var chunkIndex = (respData.length + negotiatedMtu -1) / negotiatedMtu;
//                    if(offset >= respDataParts.size){
//                        //error
//                        Logger.error("Invalid offset");
//                        return
//                    }
//
//                    var retOffset = offset;
////                    if(respDataParts[offset].length < negotiatedMtu){
////                        retOffset = -1;
////                    }
//*/
//
//                if(offset > respData.length){
//                    bluetoothGattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
////                        retOffset, respDataParts[offset].toByteArray(Charsets.UTF_8)
//                        offset,  byteArrayOf()
//                    );
//                    Logger.info(TAG, "characteristic: requested offset $offset chunk len: EMPTY");
//                    return;
//                }
//                val end = if(offset + negotiatedMtu >= respData.length) respData.length else offset + negotiatedMtu;
//                val respStr = respData.substring(offset, end);
//
//                Logger.info(TAG, "characteristic: requested offset $offset chunk len: ${respStr.length} ")
//
//                bluetoothGattServer!!.sendResponse(
//                    device, requestId, BluetoothGatt.GATT_SUCCESS,
//                    offset, respStr.toByteArray(Charsets.UTF_8)
//                );


            }

            override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
                                                      preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)


                bluetoothGattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);

                if(value === "EOF".toByteArray(Charsets.UTF_8)){
                    Logger.info(TAG, "Received EOF");
                }else{
//                    Logger.info(TAG, "Received write data: "+ Base64.getDecoder().decode(value));
                    Logger.info(TAG, "Received write data: "+ value?.toString(Charsets.US_ASCII));
                }


//                writeCharacteristic.setValue(value?.toString(Charsets.UTF_8));
            }

            override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)

                Logger.info(TAG, "got onDescriptorWriteRequest with offset: "+offset);

                if(descriptor!!.uuid.compareTo(readCharacteristicDesc.uuid) !== 0) {
                    bluetoothGattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, byteArrayOf());
                    return;
                }

                bluetoothGattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf());

                Thread.sleep(100);
//                bluetoothGattServer!!.notifyCharacteristicChanged(device!!, readCharacteristic, false, "pedro".toByteArray())

                var respDataParts =  respData.chunked(negotiatedMtu-1);
                respDataParts.forEachIndexed{index, part->
                    Logger.info(TAG, "characteristic notification: sent part offset $offset chunk len: ${part.length} ret offset: ${index*negotiatedMtu}")
                    bluetoothGattServer!!.notifyCharacteristicChanged(device!!, notifyReadCharacteristic, true,
                        part.toByteArray(Charsets.UTF_8)
                    );
                }
                bluetoothGattServer!!.notifyCharacteristicChanged(device!!, notifyReadCharacteristic, true,
                    "EOF".toByteArray(Charsets.UTF_8)
                );

            }

            override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
                super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            }

            override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
                super.onNotificationSent(device, status)
            }

            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                super.onMtuChanged(device, mtu);
                negotiatedMtu = mtu-1;
            }

            override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
                super.onExecuteWrite(device, requestId, execute)
            }
        }

        bluetoothGattServer = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).openGattServer(this.context, callback)

        bluetoothGattServer!!.addService(service);

        Toast.makeText(this.context,"advertising started", Toast.LENGTH_SHORT).show();
        Logger.info(TAG, "bluetooth device address: " + bluetoothAdapter!!.address.toString())
        Logger.info(TAG, "bluetooth device name: ${bluetoothAdapter!!.name}")

        serverSocket = bluetoothAdapter!!.listenUsingInsecureL2capChannel();

        Logger.info(TAG, "bluetooth channel setup waiting for connection - PSM is: ${serverSocket!!.psm}")
        socket = serverSocket!!.accept()

        Logger.info(TAG, "bluetooth channel got connection")

        while(true){
            val avail = socket!!.inputStream.available()
            if(avail>0) {
                val tempBuf = ByteArray(avail)
                socket!!.inputStream.read(tempBuf)

                socket!!.outputStream.write("FROM_SERVER".toByteArray())
            }
        }

    }






    @PluginMethod
    fun isEnabled(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        val enabled = bluetoothAdapter?.isEnabled == true
        val result = JSObject()
        result.put("value", enabled)
        call.resolve(result)
    }

    @PluginMethod
    fun requestEnable(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        val intent = Intent(ACTION_REQUEST_ENABLE)
        startActivityForResult(call, intent, "handleRequestEnableResult")
    }

    @ActivityCallback
    private fun handleRequestEnableResult(call: PluginCall, result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            call.resolve()
        } else {
            call.reject("requestEnable failed.")
        }
    }

    @PluginMethod
    fun enable(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        val result = bluetoothAdapter?.enable()
        if (result != true) {
            call.reject("Enable failed.")
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun disable(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        val result = bluetoothAdapter?.disable()
        if (result != true) {
            call.reject("Disable failed.")
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun startEnabledNotifications(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return

        try {
            createStateReceiver()
        } catch (e: Error) {
            Logger.error(
                TAG, "Error while registering enabled state receiver: ${e.localizedMessage}", e
            )
            call.reject("startEnabledNotifications failed.")
            return
        }
        call.resolve()
    }

    private fun createStateReceiver() {
        if (stateReceiver == null) {
            stateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                        )
                        val enabled = state == BluetoothAdapter.STATE_ON
                        val result = JSObject()
                        result.put("value", enabled)
                        try {
                            notifyListeners("onEnabledChanged", result)
                        } catch (e: ConcurrentModificationException) {
                            Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
                        }
                    }
                }
            }
            val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            context.registerReceiver(stateReceiver, intentFilter)
        }
    }

    @PluginMethod
    fun stopEnabledNotifications(call: PluginCall) {
        if (stateReceiver != null) {
            context.unregisterReceiver(stateReceiver)
        }
        stateReceiver = null
        call.resolve()
    }

    @PluginMethod
    fun isLocationEnabled(call: PluginCall) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = LocationManagerCompat.isLocationEnabled(lm)
        Logger.debug(TAG, "location $enabled")
        val result = JSObject()
        result.put("value", enabled)
        call.resolve(result)
    }

    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        val intent = Intent(ACTION_LOCATION_SOURCE_SETTINGS)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun openBluetoothSettings(call: PluginCall) {
        val intent = Intent(ACTION_BLUETOOTH_SETTINGS)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:" + activity.packageName)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun setDisplayStrings(call: PluginCall) {
        displayStrings = DisplayStrings(
            call.getString(
                "scanning", displayStrings!!.scanning
            ) as String,
            call.getString(
                "cancel", displayStrings!!.cancel
            ) as String,
            call.getString(
                "availableDevices", displayStrings!!.availableDevices
            ) as String,
            call.getString(
                "noDeviceFound", displayStrings!!.noDeviceFound
            ) as String,
        )
        call.resolve()
    }

    @PluginMethod
    fun requestDevice(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        val scanFilters = getScanFilters(call) ?: return
        val scanSettings = getScanSettings(call) ?: return
        val namePrefix = call.getString("namePrefix", "") as String

        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            Logger.error(TAG, "Error in requestDevice: ${e.localizedMessage}", e)
            call.reject(e.localizedMessage)
            return
        }

        deviceScanner = DeviceScanner(
            context,
            bluetoothAdapter!!,
            scanDuration = MAX_SCAN_DURATION,
            displayStrings = displayStrings!!,
            showDialog = true,
        )
        deviceScanner?.startScanning(
            scanFilters, scanSettings, false, namePrefix, { scanResponse ->
                run {
                    if (scanResponse.success) {
                        if (scanResponse.device == null) {
                            call.reject("No device found.")
                        } else {
                            val bleDevice = getBleDevice(scanResponse.device)
                            call.resolve(bleDevice)
                        }
                    } else {
                        call.reject(scanResponse.message)

                    }
                }
            }, null
        )
    }

    @PluginMethod
    fun requestLEScan(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        val scanFilters = getScanFilters(call) ?: return
        val scanSettings = getScanSettings(call) ?: return
        val namePrefix = call.getString("namePrefix", "") as String
        val allowDuplicates = call.getBoolean("allowDuplicates", false) as Boolean

        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            Logger.error(TAG, "Error in requestLEScan: ${e.localizedMessage}", e)
            call.reject(e.localizedMessage)
            return
        }

        deviceScanner = DeviceScanner(
            context,
            bluetoothAdapter!!,
            scanDuration = null,
            displayStrings = displayStrings!!,
            showDialog = false,
        )
        deviceScanner?.startScanning(
            scanFilters,
            scanSettings,
            allowDuplicates,
            namePrefix,
            { scanResponse ->
                run {
                    if (scanResponse.success) {
                        call.resolve()
                    } else {
                        call.reject(scanResponse.message)
                    }
                }
            },
            { result ->
                run {
                    val scanResult = getScanResult(result)
                    try {
                        notifyListeners("onScanResult", scanResult)
                    } catch (e: ConcurrentModificationException) {
                        Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
                    }
                }
            })
    }

    @PluginMethod
    fun stopLEScan(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            Logger.error(TAG, "Error in stopLEScan: ${e.localizedMessage}", e)
        }
        call.resolve()
    }

    @PluginMethod
    fun getDevices(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        val deviceIds = (call.getArray("deviceIds", JSArray()) as JSArray).toList<String>()
        val bleDevices = JSArray()
        deviceIds.forEach { deviceId ->
            val bleDevice = JSObject()
            bleDevice.put("deviceId", deviceId)
            bleDevices.put(bleDevice)
        }
        val result = JSObject()
        result.put("devices", bleDevices)
        call.resolve(result)
    }

    @PluginMethod
    fun getConnectedDevices(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return
        val bluetoothManager =
            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        val devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        val bleDevices = JSArray()
        devices.forEach { device ->
            bleDevices.put(getBleDevice(device))
        }
        val result = JSObject()
        result.put("devices", bleDevices)
        call.resolve(result)
    }

    @PluginMethod
    fun getBondedDevices(call: PluginCall) {
        assertBluetoothAdapter(call) ?: return

        val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            call.reject("Bluetooth is not supported on this device")
            return
        }

        val bondedDevices = bluetoothAdapter.bondedDevices
        val bleDevices = JSArray()

        bondedDevices.forEach { device ->
            bleDevices.put(getBleDevice(device))
        }

        val result = JSObject()
        result.put("devices", bleDevices)
        call.resolve(result)
    }

    @PluginMethod
    fun connect(call: PluginCall) {
        val device = getOrCreateDevice(call) ?: return
        val timeout = call.getFloat("timeout", CONNECTION_TIMEOUT)!!.toLong()
        device.connect(timeout) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    private fun onDisconnect(deviceId: String) {
        try {
            notifyListeners("disconnected|${deviceId}", null)
        } catch (e: ConcurrentModificationException) {
            Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
        }
    }

    @PluginMethod
    fun createBond(call: PluginCall) {
        val device = getOrCreateDevice(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.createBond(timeout) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun isBonded(call: PluginCall) {
        val device = getOrCreateDevice(call) ?: return
        val isBonded = device.isBonded()
        val result = JSObject()
        result.put("value", isBonded)
        call.resolve(result)
    }

    @PluginMethod
    fun disconnect(call: PluginCall) {
        val device = getOrCreateDevice(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.disconnect(timeout) { response ->
            run {
                if (response.success) {
                    deviceMap.remove(device.getId())
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun getServices(call: PluginCall) {
        val device = getDevice(call) ?: return
        val services = device.getServices()
        val bleServices = JSArray()
        services.forEach { service ->
            val bleCharacteristics = JSArray()
            service.characteristics.forEach { characteristic ->
                val bleCharacteristic = JSObject()
                bleCharacteristic.put("uuid", characteristic.uuid)
                bleCharacteristic.put("properties", getProperties(characteristic))
                val bleDescriptors = JSArray()
                characteristic.descriptors.forEach { descriptor ->
                    val bleDescriptor = JSObject()
                    bleDescriptor.put("uuid", descriptor.uuid)
                    bleDescriptors.put(bleDescriptor)
                }
                bleCharacteristic.put("descriptors", bleDescriptors)
                bleCharacteristics.put(bleCharacteristic)
            }
            val bleService = JSObject()
            bleService.put("uuid", service.uuid)
            bleService.put("characteristics", bleCharacteristics)
            bleServices.put(bleService)
        }
        val ret = JSObject()
        ret.put("services", bleServices)
        call.resolve(ret)
    }

    private fun getProperties(characteristic: BluetoothGattCharacteristic): JSObject {
        val properties = JSObject()
        properties.put(
            "broadcast",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST > 0
        )
        properties.put(
            "read", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ > 0
        )
        properties.put(
            "writeWithoutResponse",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0
        )
        properties.put(
            "write", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0
        )
        properties.put(
            "notify", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0
        )
        properties.put(
            "indicate",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0
        )
        properties.put(
            "authenticatedSignedWrites",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE > 0
        )
        properties.put(
            "extendedProperties",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS > 0
        )
        return properties
    }

    @PluginMethod
    fun discoverServices(call: PluginCall) {
        val device = getDevice(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.discoverServices(timeout) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun getMtu(call: PluginCall) {
        val device = getDevice(call) ?: return
        val mtu = device.getMtu()
        val ret = JSObject()
        ret.put("value", mtu)
        call.resolve(ret)
    }

    @PluginMethod
    fun requestConnectionPriority(call: PluginCall) {
        val device = getDevice(call) ?: return
        val connectionPriority = call.getInt("connectionPriority", -1) as Int
        if (connectionPriority < BluetoothGatt.CONNECTION_PRIORITY_BALANCED || connectionPriority > BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) {
            call.reject("Invalid connectionPriority.")
            return
        }

        val result = device.requestConnectionPriority(connectionPriority)
        if (result) {
            call.resolve()
        } else {
            call.reject("requestConnectionPriority failed.")
        }
    }

    @PluginMethod
    fun readRssi(call: PluginCall) {
        val device = getDevice(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.readRssi(timeout) { response ->
            run {
                if (response.success) {
                    val ret = JSObject()
                    ret.put("value", response.value)
                    call.resolve(ret)
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun read(call: PluginCall) {
        val device = getDevice(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.read(characteristic.first, characteristic.second, timeout) { response ->
            run {
                if (response.success) {
                    val ret = JSObject()
                    ret.put("value", response.value)
                    call.resolve(ret)
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun write(call: PluginCall) {
        val device = getDevice(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        val value = call.getString("value", null)
        if (value == null) {
            call.reject("Value required.")
            return
        }
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.write(
            characteristic.first, characteristic.second, value, writeType, timeout
        ) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun writeWithoutResponse(call: PluginCall) {
        val device = getDevice(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        val value = call.getString("value", null)
        if (value == null) {
            call.reject("Value required.")
            return
        }
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.write(
            characteristic.first, characteristic.second, value, writeType, timeout
        ) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun readDescriptor(call: PluginCall) {
        val device = getDevice(call) ?: return
        val descriptor = getDescriptor(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.readDescriptor(
            descriptor.first, descriptor.second, descriptor.third, timeout
        ) { response ->
            run {
                if (response.success) {
                    val ret = JSObject()
                    ret.put("value", response.value)
                    call.resolve(ret)
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun writeDescriptor(call: PluginCall) {
        val device = getDevice(call) ?: return
        val descriptor = getDescriptor(call) ?: return
        val value = call.getString("value", null)
        if (value == null) {
            call.reject("Value required.")
            return
        }
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        device.writeDescriptor(
            descriptor.first, descriptor.second, descriptor.third, value, timeout
        ) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    @PluginMethod
    fun startNotifications(call: PluginCall) {
        val device = getDevice(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        device.setNotifications(characteristic.first, characteristic.second, true, { response ->
            run {
                val key =
                    "notification|${device.getId()}|${(characteristic.first)}|${(characteristic.second)}"
                val ret = JSObject()
                ret.put("value", response.value)
                try {
                    notifyListeners(key, ret)
                } catch (e: ConcurrentModificationException) {
                    Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
                }
            }
        }, { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        })
    }

    @PluginMethod
    fun stopNotifications(call: PluginCall) {
        val device = getDevice(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        device.setNotifications(
            characteristic.first, characteristic.second, false, null
        ) { response ->
            run {
                if (response.success) {
                    call.resolve()
                } else {
                    call.reject(response.value)
                }
            }
        }
    }

    private fun assertBluetoothAdapter(call: PluginCall): Boolean? {
        if (bluetoothAdapter == null) {
            call.reject("Bluetooth LE not initialized.")
            return null
        }
        return true
    }

    private fun getScanFilters(call: PluginCall): List<ScanFilter>? {
        val filters: ArrayList<ScanFilter> = ArrayList()

        val services = (call.getArray("services", JSArray()) as JSArray).toList<String>()
        val manufacturerDataArray = call.getArray("manufacturerData", JSArray())
        val name = call.getString("name", null)

        try {
            // Create filters based on services
            for (service in services) {
                val filter = ScanFilter.Builder()
                filter.setServiceUuid(ParcelUuid.fromString(service))
                if (name != null) {
                    filter.setDeviceName(name)
                }
                filters.add(filter.build())
            }

            // Manufacturer Data Handling (with optional parameters)
            manufacturerDataArray?.let {
                for (i in 0 until it.length()) {
                    val manufacturerDataObject = it.getJSONObject(i)

                    val companyIdentifier = manufacturerDataObject.getInt("companyIdentifier")

                    val dataPrefix = if (manufacturerDataObject.has("dataPrefix")) {
                        val dataPrefixObject = manufacturerDataObject.getJSONObject("dataPrefix")
                        val byteLength = dataPrefixObject.length()

                        ByteArray(byteLength).apply {
                            for (idx in 0 until byteLength) {
                                val key = idx.toString()
                                this[idx] = (dataPrefixObject.getInt(key) and 0xFF).toByte()
                            }
                        }
                    } else null


                    val mask = if (manufacturerDataObject.has("mask")) {
                        val maskObject = manufacturerDataObject.getJSONObject("mask")
                        val byteLength = maskObject.length()

                        ByteArray(byteLength).apply {
                            for (idx in 0 until byteLength) {
                                val key = idx.toString()
                                this[idx] = (maskObject.getInt(key) and 0xFF).toByte()
                            }
                        }
                    } else null

                    val filterBuilder = ScanFilter.Builder()

                    if (dataPrefix != null && mask != null) {
                        filterBuilder.setManufacturerData(companyIdentifier, dataPrefix, mask)
                    } else if (dataPrefix != null) {
                        filterBuilder.setManufacturerData(companyIdentifier, dataPrefix)
                    } else {
                        // Android requires at least dataPrefix for manufacturer filters.
                        call.reject("dataPrefix is required when specifying manufacturerData.")
                        return null
                    }

                    if (name != null) {
                        filterBuilder.setDeviceName(name)
                    }

                    filters.add(filterBuilder.build())
                }
            }
            // Create filters when providing only name
            if (name != null && filters.isEmpty()) {
                val filterBuilder = ScanFilter.Builder()
                filterBuilder.setDeviceName(name)
                filters.add(filterBuilder.build())
            }

            return filters;
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid UUID or Manufacturer data provided.")
            return null
        } catch (e: Exception) {
            call.reject("Invalid or malformed filter data provided.")
            return null
        }
    }

    private fun getScanSettings(call: PluginCall): ScanSettings? {
        val scanSettings = ScanSettings.Builder()
        val scanMode = call.getInt("scanMode", ScanSettings.SCAN_MODE_BALANCED) as Int
        try {
            scanSettings.setScanMode(scanMode)
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid scan mode.")
            return null
        }
        return scanSettings.build()
    }

    private fun getBleDevice(device: BluetoothDevice): JSObject {
        val bleDevice = JSObject()
        bleDevice.put("deviceId", device.address)
        if (device.name != null) {
            bleDevice.put("name", device.name)
        }

        val uuids = JSArray()
        device.uuids?.forEach { uuid -> uuids.put(uuid.toString()) }
        if (uuids.length() > 0) {
            bleDevice.put("uuids", uuids)
        }

        return bleDevice
    }

    private fun getScanResult(result: ScanResult): JSObject {
        val scanResult = JSObject()

        val bleDevice = getBleDevice(result.device)
        scanResult.put("device", bleDevice)

        if (result.device.name != null) {
            scanResult.put("localName", result.device.name)
        }

        scanResult.put("rssi", result.rssi)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            scanResult.put("txPower", result.txPower)
        } else {
            scanResult.put("txPower", 127)
        }

        val manufacturerData = JSObject()
        val manufacturerSpecificData = result.scanRecord?.manufacturerSpecificData
        if (manufacturerSpecificData != null) {
            for (i in 0 until manufacturerSpecificData.size()) {
                val key = manufacturerSpecificData.keyAt(i)
                val bytes = manufacturerSpecificData.get(key)
                manufacturerData.put(key.toString(), bytesToString(bytes))
            }
        }
        scanResult.put("manufacturerData", manufacturerData)

        val serviceDataObject = JSObject()
        val serviceData = result.scanRecord?.serviceData
        serviceData?.forEach {
            serviceDataObject.put(it.key.toString(), bytesToString(it.value))
        }
        scanResult.put("serviceData", serviceDataObject)

        val uuids = JSArray()
        result.scanRecord?.serviceUuids?.forEach { uuid -> uuids.put(uuid.toString()) }
        scanResult.put("uuids", uuids)

        scanResult.put("rawAdvertisement", result.scanRecord?.bytes?.let { bytesToString(it) })
        return scanResult
    }

    private fun getDisplayStrings(): DisplayStrings {
        return DisplayStrings(
            config.getString(
                "displayStrings.scanning", "Scanning..."
            ),
            config.getString(
                "displayStrings.cancel", "Cancel"
            ),
            config.getString(
                "displayStrings.availableDevices", "Available devices"
            ),
            config.getString(
                "displayStrings.noDeviceFound", "No device found"
            ),
        )
    }

    private fun getDeviceId(call: PluginCall): String? {
        val deviceId = call.getString("deviceId", null)
        if (deviceId == null) {
            call.reject("deviceId required.")
            return null
        }
        return deviceId
    }

    private fun getOrCreateDevice(call: PluginCall): Device? {
        assertBluetoothAdapter(call) ?: return null
        val deviceId = getDeviceId(call) ?: return null
        val device = deviceMap[deviceId]
        if (device != null) {
            return device
        }
        return try {
            val newDevice = Device(
                activity.applicationContext, bluetoothAdapter!!, deviceId
            ) {
                onDisconnect(deviceId)
            }
            deviceMap[deviceId] = newDevice
            newDevice
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid deviceId")
            null
        }
    }

    private fun getDevice(call: PluginCall): Device? {
        assertBluetoothAdapter(call) ?: return null
        val deviceId = getDeviceId(call) ?: return null
        val device = deviceMap[deviceId]
        if (device == null || !device.isConnected()) {
            call.reject("Not connected to device.")
            return null
        }
        return device
    }

    private fun getCharacteristic(call: PluginCall): Pair<UUID, UUID>? {
        val serviceString = call.getString("service", null)
        val serviceUUID: UUID?
        try {
            serviceUUID = UUID.fromString(serviceString)
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid service UUID.")
            return null
        }
        if (serviceUUID == null) {
            call.reject("Service UUID required.")
            return null
        }
        val characteristicString = call.getString("characteristic", null)
        val characteristicUUID: UUID?
        try {
            characteristicUUID = UUID.fromString(characteristicString)
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid characteristic UUID.")
            return null
        }
        if (characteristicUUID == null) {
            call.reject("Characteristic UUID required.")
            return null
        }
        return Pair(serviceUUID, characteristicUUID)
    }

    private fun getDescriptor(call: PluginCall): Triple<UUID, UUID, UUID>? {
        val characteristic = getCharacteristic(call) ?: return null
        val descriptorString = call.getString("descriptor", null)
        val descriptorUUID: UUID?
        try {
            descriptorUUID = UUID.fromString(descriptorString)
        } catch (e: IllegalAccessException) {
            call.reject("Invalid descriptor UUID.")
            return null
        }
        if (descriptorUUID == null) {
            call.reject("Descriptor UUID required.")
            return null
        }
        return Triple(characteristic.first, characteristic.second, descriptorUUID)
    }
}
