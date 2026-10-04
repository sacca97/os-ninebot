package openride.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import openride.core.protocol.Ble
import openride.core.session.ScooterLink
import java.util.UUID

class BleConnectException(message: String) : RuntimeException(message)

/** Raw BluetoothGatt wrapped in coroutines: connect, MTU, discover, enable notify, serialised writes. */
@SuppressLint("MissingPermission")
class AndroidGattLink private constructor() : ScooterLink {
    private val rx = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = rx.receiveAsFlow()
    override var maxChunk: Int = 20
        private set

    private var gate: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val writeLock = Mutex()
    private var pendingWrite: CompletableDeferred<Int>? = null

    private val connected = CompletableDeferred<Unit>()
    private val mtuDone = CompletableDeferred<Unit>()
    private val servicesDone = CompletableDeferred<Int>()
    private val notifyEnabled = CompletableDeferred<Int>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else {
                val err = BleConnectException("GATT state=$newState status=$status")
                connected.completeExceptionally(err)
                mtuDone.completeExceptionally(err)
                servicesDone.completeExceptionally(err)
                notifyEnabled.completeExceptionally(err)
                pendingWrite?.complete(-1)
                rx.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) maxChunk = (mtu - 3).coerceAtLeast(20)
            mtuDone.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDone.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            notifyEnabled.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            rx.trySend(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) c.value?.let { rx.trySend(it.copyOf()) }
        }
    }

    private suspend fun connect(context: Context, device: BluetoothDevice) {
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw BleConnectException("connectGatt returned null")
        gate = g
        withTimeout(15_000) { connected.await() }
        if (!g.requestMtu(247)) mtuDone.complete(Unit)
        withTimeoutOrNull(5_000) { mtuDone.await() }
        // Wait for onServicesDiscovered before anything else.
        if (!g.discoverServices()) throw BleConnectException("discoverServices failed")
        val st = withTimeout(10_000) { servicesDone.await() }
        if (st != BluetoothGatt.GATT_SUCCESS) throw BleConnectException("service discovery status=$st")
        val svc = g.getService(UUID.fromString(Ble.NUS_SERVICE)) ?: throw BleConnectException("UART service missing")
        writeChar = svc.getCharacteristic(UUID.fromString(Ble.NUS_WRITE)) ?: throw BleConnectException("write char missing")
        val notify = svc.getCharacteristic(UUID.fromString(Ble.NUS_NOTIFY)) ?: throw BleConnectException("notify char missing")
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD) ?: throw BleConnectException("CCCD missing")
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        }
        if (!ok) throw BleConnectException("enabling notifications failed")
        val ns = withTimeout(5_000) { notifyEnabled.await() }
        if (ns != BluetoothGatt.GATT_SUCCESS) throw BleConnectException("notify enable status=$ns")
    }

    override suspend fun write(chunk: ByteArray) = writeLock.withLock {
        val g = gate ?: throw BleConnectException("not connected")
        val c = writeChar ?: throw BleConnectException("not connected")
        repeat(10) {
            val done = CompletableDeferred<Int>()
            pendingWrite = done
            val started = if (Build.VERSION.SDK_INT >= 33) {
                val r = g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                if (r == android.bluetooth.BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) null
                else r == android.bluetooth.BluetoothStatusCodes.SUCCESS // busy => retry
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    c.value = chunk
                    g.writeCharacteristic(c)
                }
            }
            if (started == true) {
                val st = withTimeoutOrNull(2_000) { done.await() }
                if (st != null && st != BluetoothGatt.GATT_SUCCESS) throw BleConnectException("write status=$st")
                return@withLock
            }
            if (started == false && Build.VERSION.SDK_INT < 33) delay(50) else if (started == false) throw BleConnectException("write rejected")
            delay(50)
        }
        throw BleConnectException("write busy")
    }

    override suspend fun close() {
        gate?.let { it.disconnect(); it.close() }
        gate = null
        rx.close()
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Connects with a bounded number of retries (GATT 133 is common). Always closes failed attempts. */
        suspend fun open(context: Context, device: BluetoothDevice, attempts: Int = 3): AndroidGattLink {
            var last: Throwable? = null
            repeat(attempts) {
                val link = AndroidGattLink()
                try {
                    link.connect(context.applicationContext, device)
                    return link
                } catch (e: kotlinx.coroutines.CancellationException) {
                    link.close(); throw e
                } catch (e: Throwable) {
                    last = e
                    link.close()
                    delay(500)
                }
            }
            throw BleConnectException("Could not connect: ${last?.message}")
        }
    }
}
