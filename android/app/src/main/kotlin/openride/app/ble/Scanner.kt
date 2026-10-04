package openride.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import openride.core.protocol.Ble

/** [name] comes from the scan record: it is key material, so never from BluetoothDevice.name. */
class ScooterAd(val name: String, val device: BluetoothDevice, val rssi: Int) {
    val address: String get() = device.address
}

@SuppressLint("MissingPermission")
object Scanner {
    fun adapter(context: Context) = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** A scooter we already know (address + name from an earlier scan): no scan needed to connect. */
    fun known(context: Context, address: String, name: String): ScooterAd? =
        try { adapter(context)?.getRemoteDevice(address)?.let { ScooterAd(name, it, 0) } } catch (_: IllegalArgumentException) { null }

    fun scan(context: Context): Flow<ScooterAd> = callbackFlow {
        val scanner = adapter(context)?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off or unavailable"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: return
                trySend(ScooterAd(name, r.device, r.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setManufacturerData(Ble.MANUFACTURER_ID, ByteArray(0)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        awaitClose { scanner.stopScan(cb) }
    }
}
