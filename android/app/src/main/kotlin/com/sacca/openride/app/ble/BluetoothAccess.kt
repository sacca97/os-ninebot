package com.sacca.openride.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Is Bluetooth usable right now: permission granted and the radio on. Shared by the home and scan screens. */
object BluetoothAccess {
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(c: Context) =
        requiredPermissions().all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    fun isEnabled(c: Context) = c.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Emits the current state, then every change (the radio being switched on or off from anywhere). */
    fun enabledFlow(c: Context): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { trySend(isEnabled(c)) }
        }
        ContextCompat.registerReceiver(c, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(isEnabled(c))
        awaitClose { c.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}
