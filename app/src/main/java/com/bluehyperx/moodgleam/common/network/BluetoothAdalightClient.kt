package com.bluehyperx.moodgleam.common.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import com.bluehyperx.moodgleam.common.util.LedDataExtractor
import java.io.IOException
import java.io.OutputStream
import java.util.*

class BluetoothAdalightClient(
    private val mContext: Context,
    private val mPriority: Int,
    private val mBaudRate: Int,
    private val mDeviceAddress: String,
    protocol: String = "ada",
    smoothingEnabled: Boolean = true,
    smoothingPreset: String = "balanced",
    settlingTime: Int = 200,
    outputDelayMs: Long = 80L,
    updateFrequency: Int = 25,
) : HyperionClient {

    private val mProtocol = when (protocol.lowercase()) {
        "lbapa", "1" -> AdalightProtocolHelper.ProtocolType.LBAPA
        "awa", "2" -> AdalightProtocolHelper.ProtocolType.AWA
        else -> AdalightProtocolHelper.ProtocolType.ADA
    }

    private var mSocket: BluetoothSocket? = null
    private var mOutputStream: OutputStream? = null

    @Volatile
    private var mConnected = false

    @Volatile
    private var mConnectedDeviceName: String? = null

    private val mSmoothing: ColorSmoothing
    private var mLedDataBuffer: Array<ColorRgb>? = null

    // Сохраняем исходно запрошенную частоту, чтобы auto-throttle никогда не повышал её выше пользовательской
    private val mRequestedUpdateFrequency: Int = updateFrequency

    @Volatile
    private var mEffectiveUpdateFrequency: Int = updateFrequency

    @Volatile
    private var mLastAutoThrottlePacketSize: Int = -1

    init {
        mSmoothing = ColorSmoothing { leds -> sendLedData(leds) }
        mSmoothing.applyPreset(smoothingPreset)
        
        val presetValues = getPresetValues(smoothingPreset)
        if (settlingTime != presetValues.settlingTime) {
            mSmoothing.setSettlingTime(settlingTime)
        }
        if (outputDelayMs != presetValues.outputDelayMs) {
            mSmoothing.setOutputDelay(outputDelayMs)
        }
        if (updateFrequency != presetValues.updateFrequency) {
            mSmoothing.setUpdateFrequency(updateFrequency)
            mEffectiveUpdateFrequency = updateFrequency
        } else {
            mEffectiveUpdateFrequency = presetValues.updateFrequency
        }
        mSmoothing.setEnabled(smoothingEnabled)

        connect()
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    private fun connect() {
        val bluetoothManager = mContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw IOException("Bluetooth not supported on this device")
        
        val bluetoothAdapter = bluetoothManager.adapter
            ?: throw IOException("Bluetooth not supported on this device")

        if (!bluetoothAdapter.isEnabled) {
            throw IOException("Bluetooth is disabled. Please enable it in system settings.")
        }

        // Check for runtime permission on Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (mContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw IOException("Bluetooth Connect permission not granted. Please allow it in app settings.")
            }
        }

        var targetDevice: android.bluetooth.BluetoothDevice? = null

        if (mDeviceAddress.isNotEmpty()) {
            targetDevice = try {
                bluetoothAdapter.getRemoteDevice(mDeviceAddress)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        if (targetDevice == null) {
            // Try to auto-detect HC-05 or Ambilight among paired devices
            val pairedDevices = try { bluetoothAdapter.bondedDevices } catch (e: SecurityException) { null }
            targetDevice = pairedDevices?.firstOrNull { 
                val name = try { it.name ?: "" } catch (e: SecurityException) { "" }
                name.contains("HC-05", ignoreCase = true) || 
                name.contains("HC-06", ignoreCase = true) ||
                name.contains("Ambilight", ignoreCase = true)
            } ?: pairedDevices?.firstOrNull() 
        }

        if (targetDevice == null) {
            throw IOException("No paired Bluetooth devices found. Please pair your HC-05 or Ambilight module in system settings first.")
        }

        val finalAddress = targetDevice.address
        val finalName = try { targetDevice.name ?: "Unknown Device" } catch (e: SecurityException) { "Unknown Device" }

        // Standard SerialPortService ID
        val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        Log.d(TAG, "Connecting to Bluetooth device: $finalName ($finalAddress)")
        
        try {
            mSocket = targetDevice.createRfcommSocketToServiceRecord(sppUuid)
            // Cancel discovery as it slows down connection
            try { bluetoothAdapter.cancelDiscovery() } catch (e: SecurityException) {}
            
            mSocket?.connect()
            mOutputStream = mSocket?.outputStream
            
            mConnectedDeviceName = finalName
            mConnected = true
            mSmoothing.start()
            Log.i(TAG, "Successfully connected to Bluetooth Adalight device: $finalName")
        } catch (e: Exception) {
            mConnected = false
            cleanup()
            throw IOException("Failed to connect to Bluetooth device: ${e.message}", e)
        }
    }

    override fun isConnected(): Boolean {
        return mConnected && mSocket?.isConnected == true
    }

    @Throws(IOException::class)
    override fun disconnect() {
        mSmoothing.stop()
        
        // Turn off LEDs before disconnecting
        try {
            val ledCount = LedDataExtractor.getLedCount(mContext)
            val blackLeds = Array(ledCount) { ColorRgb(0, 0, 0) }
            val packet = AdalightProtocolHelper.createPacket(mProtocol, blackLeds)
            mOutputStream?.write(packet)
            mOutputStream?.flush()
            Thread.sleep(50) // Give module time to process the serial data
        } catch (e: Exception) {
            Log.e(TAG, "Error turning off LEDs on disconnect", e)
        }
        
        mConnected = false
        cleanup()
    }

    private fun cleanup() {
        Log.d(TAG, "Cleaning up Bluetooth resources")
        try {
            mOutputStream?.flush()
            mOutputStream?.close()
        } catch (_: Exception) {}
        mOutputStream = null
        
        try {
            mSocket?.close()
        } catch (_: Exception) {}
        mSocket = null
        
        // Brief pause to let the OS release the hardware resources
        try { Thread.sleep(100) } catch (_: Exception) {}
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        val ledCount = LedDataExtractor.getLedCount(mContext)
        val blackLeds = Array(ledCount) { ColorRgb(0, 0, 0) }
        mSmoothing.setTargetColors(blackLeds)
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(mPriority)
    }

    @Throws(IOException::class)
    override fun clearImmediate() {
        if (!isConnected()) return
        val ledCount = LedDataExtractor.getLedCount(mContext)
        val blackLeds = Array(ledCount) { ColorRgb(0, 0, 0) }
        val packet = AdalightProtocolHelper.createPacket(mProtocol, blackLeds)
        try {
            mOutputStream?.write(packet)
            mOutputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send immediate clear packet (BT)", e)
        }
    }

    override fun getConnectedDeviceName(): String? = mConnectedDeviceName

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        val ledCount = LedDataExtractor.getLedCount(mContext)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val leds = Array(ledCount) { ColorRgb(r, g, b) }
        mSmoothing.setTargetColors(leds)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (!isConnected()) {
            throw IOException("Not connected to Bluetooth device")
        }
        mLedDataBuffer = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer)
        if (mLedDataBuffer!!.isEmpty()) return
        mSmoothing.setTargetColors(mLedDataBuffer)
    }

    override fun setRawLedData(leds: Array<ColorRgb>, priority: Int) {
        mSmoothing.setTargetColors(leds)
    }

    private fun sendLedData(leds: Array<ColorRgb>) {
        if (!isConnected()) return

        val outputStream = mOutputStream
        if (outputStream == null) {
            Log.w(TAG, "OutputStream is null, connection lost")
            mConnected = false
            return
        }

        try {
            val packet = AdalightProtocolHelper.createPacket(mProtocol, leds)
            maybeAutoThrottle(packet.size)
            
            outputStream.write(packet)
            outputStream.flush()

            if (System.currentTimeMillis() % 2000 < 50) {
                Log.v(TAG, "Sent Bluetooth packet: ${leds.size} LEDs")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send Bluetooth data", e)
            mConnected = false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending Bluetooth data", e)
            mConnected = false
        }
    }

    private fun maybeAutoThrottle(packetSizeBytes: Int) {
        if (packetSizeBytes <= 0) return
        if (packetSizeBytes == mLastAutoThrottlePacketSize) return
        mLastAutoThrottlePacketSize = packetSizeBytes

        val maxBytesPerSecond = mBaudRate.toDouble() / 10.0
        val maxHz = maxBytesPerSecond / packetSizeBytes.toDouble()
        val safeHz = (maxHz * 0.90).toInt().coerceIn(1, 60)

        val desiredHz = minOf(mRequestedUpdateFrequency, safeHz)
        if (desiredHz != mEffectiveUpdateFrequency) {
            mEffectiveUpdateFrequency = desiredHz
            mSmoothing.setUpdateFrequency(desiredHz)
            Log.i(TAG, "Auto-throttle smoothing (BT): ${desiredHz}Hz (baud=$mBaudRate, packet=$packetSizeBytes bytes)")
        }
    }

    private data class PresetValues(
        val settlingTime: Int,
        val outputDelayMs: Long,
        val updateFrequency: Int,
    )

    private fun getPresetValues(preset: String): PresetValues {
        return when (preset.lowercase()) {
            "off" -> PresetValues(50, 0L, 60)
            "responsive" -> PresetValues(50, 0L, 60)
            "balanced" -> PresetValues(200, 80L, 25)
            "smooth" -> PresetValues(500, 200L, 20)
            else -> PresetValues(200, 80L, 25)
        }
    }

    companion object {
        private const val TAG = "BluetoothAdalightClient"
    }
}
