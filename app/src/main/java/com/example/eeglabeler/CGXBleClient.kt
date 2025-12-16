package com.example.eeglabeler

import android.Manifest
import android.annotation.SuppressLint
import android.os.ParcelUuid
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

data class ScanHit(val name: String?, val address: String, val rssi: Int)

class CgxBleClient(private val ctx: Context) {

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = manager.adapter
    private val scanner get() = adapter.bluetoothLeScanner

    private val SERVICE_UUID = UUID.fromString("2456E1B9-26E2-8F83-E744-F34F01E9D701")
    private val CHAR_A = UUID.fromString("2456E1B9-26E2-8F83-E744-F34F01E9D703")
    private val CHAR_B = UUID.fromString("2456E1B9-26E2-8F83-E744-F34F01E9D704")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null
    private var scanCb: ScanCallback? = null

    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private val _scanHits = MutableSharedFlow<ScanHit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val scanHits = _scanHits.asSharedFlow()

    private val _notifyBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val notifyBytes = _notifyBytes.asSharedFlow()

    fun isBluetoothReady(): Boolean = adapter != null && adapter.isEnabled

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (!isBluetoothReady()) { _events.tryEmit("error: bluetooth_disabled"); return }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCb = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) { _events.tryEmit("scan_failed:$errorCode") }
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                _scanHits.tryEmit(ScanHit(result.device.name, result.device.address, result.rssi))
            }
        }
		//scanner.startScan(null, settings, scanCb)
        scanner.startScan(listOf(filter), settings, scanCb)
        _events.tryEmit("scan_started")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        val cb = scanCb ?: return
        scanner.stopScan(cb)
        scanCb = null
        _events.tryEmit("scan_stopped")
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        disconnect()
        val dev = adapter.getRemoteDevice(address)
        _events.tryEmit("connecting:$address")
        gatt =
            dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        _events.tryEmit("disconnected")
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit("conn_error:status=$status")
                disconnect()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _events.tryEmit("connected")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _events.tryEmit("disconnected")
                    disconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { _events.tryEmit("svc_error:$status"); return }

            val svc = g.getService(SERVICE_UUID) ?: run { _events.tryEmit("svc_missing"); return }
            val ch = svc.getCharacteristic(CHAR_A) ?: svc.getCharacteristic(CHAR_B)
                ?: run { _events.tryEmit("char_missing"); return }

            val ok1 = g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD) ?: run { _events.tryEmit("cccd_missing"); return }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok2 = g.writeDescriptor(cccd)

            _events.tryEmit("notify_enable:set=$ok1 write=$ok2")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { _notifyBytes.tryEmit(it) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            _events.tryEmit(if (status == BluetoothGatt.GATT_SUCCESS) "notify_enabled" else "notify_error:$status")
        }
    }
}
