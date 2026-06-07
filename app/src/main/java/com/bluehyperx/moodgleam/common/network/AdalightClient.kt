package com.bluehyperx.moodgleam.common.network

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.bluehyperx.moodgleam.common.util.LedDataExtractor
import java.io.IOException

class AdalightClient(
    private val mContext: Context,
    private val mPriority: Int,
    private val mBaudRate: Int,
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

    private var mPort: UsbSerialPort? = null

    @Volatile
    private var mConnected = false

    private val mSmoothing: ColorSmoothing
    private var mLedDataBuffer: Array<ColorRgb>? = null

    // Сохраняем исходно запрошенную частоту, чтобы auto-throttle никогда не повышал её выше пользовательской
    private val mRequestedUpdateFrequency: Int = updateFrequency

    @Volatile
    private var mEffectiveUpdateFrequency: Int = updateFrequency

    @Volatile
    private var mLastAutoThrottlePacketSize: Int = -1

    init {
        // Initialize smoothing with callback to send data
        mSmoothing = ColorSmoothing { leds -> sendLedData(leds) }
        // Применить настройки сглаживания из preferences
        // Сначала применяем пресет как базовые значения
        mSmoothing.applyPreset(smoothingPreset)
        // Затем переопределяем настройками из preferences только если они отличаются от значений пресета
        // Это позволяет пресету работать, но пользователь может переопределить настройки вручную
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
            // Preset frequency remains active
            mEffectiveUpdateFrequency = presetValues.updateFrequency
        }
        // enabled всегда переопределяем, так как это отдельная настройка
        mSmoothing.setEnabled(smoothingEnabled)

        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        val usbManager = mContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw IOException("USB service not available on this device")

        // Find all available USB serial devices
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            throw IOException("No USB serial devices found. Please connect your Adalight device via USB OTG cable")
        }

        // Log all found devices for debugging
        Log.d(TAG, "Found " + availableDrivers.size + " USB serial device(s)")
        for (i in availableDrivers.indices) {
            val dev = availableDrivers[i].device
            Log.d(
                TAG, "Device " + i + ": VID=" + dev.vendorId + " PID=" + dev.productId +
                        " Name=" + dev.deviceName
            )
        }

        // Use the first available device
        val driver = availableDrivers[0]
        val device = driver.device

        // Check if we have permission.
        // На этом этапе диалог уже должен быть показан активити,
        // поэтому из сервиса мы только проверяем флаг.
        if (!usbManager.hasPermission(device)) {
            throw IOException("USB device permission denied. Please allow USB access when prompted, or grant permission manually in Android Settings > Apps > Hyperion Grabber > Permissions")
        }

        // Open the port
        val ports = driver.ports
        if (ports.isEmpty()) {
            throw IOException("No serial ports available on USB device")
        }

        mPort = ports[0]

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device. Please check USB connection and try again")

        try {
            mPort!!.open(connection)
            mPort!!.setParameters(mBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            mConnected = true
            mSmoothing.start()
            Log.i(
                TAG, "Successfully connected to Adalight device at " + mBaudRate + " baud (VID=" +
                        device.vendorId + " PID=" + device.productId + ")"
            )
        } catch (e: Exception) {
            mConnected = false
            // Release the underlying USB connection before failing — otherwise the device
            // stays "busy" and subsequent reconnect attempts fail until the app restarts.
            try {
                mPort?.close()
            } catch (_: Exception) {
            }
            try {
                connection.close()
            } catch (_: Exception) {
            }
            mPort = null
            throw IOException(
                "Failed to configure USB serial port: " + e.message +
                        ". Try different baud rate or check device compatibility", e
            )
        }
    }

    override fun isConnected(): Boolean {
        return mConnected && mPort != null
    }

    @Throws(IOException::class)
    override fun disconnect() {
        mSmoothing.stop()

        // Turn off LEDs before disconnecting
        try {
            val ledCount = LedDataExtractor.getLedCount(mContext)
            val blackLeds = Array(ledCount) { ColorRgb(0, 0, 0) }
            val packet = AdalightProtocolHelper.createPacket(mProtocol, blackLeds)
            mPort?.write(packet, 500)
            Thread.sleep(50) // Give module time to process the serial data
        } catch (e: Exception) {
            Log.e(TAG, "Error turning off LEDs on disconnect", e)
        }

        mConnected = false
        if (mPort != null) {
            try {
                mPort!!.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing port", e)
            }
            mPort = null
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        // Send all black LEDs
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
            mPort?.write(packet, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send immediate clear packet", e)
        }
    }

    override fun getConnectedDeviceName(): String? = "USB Adalight"

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        // Get LED count from preferences
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
    override fun setImage(
        data: ByteArray,
        width: Int,
        height: Int,
        priority: Int,
        duration_ms: Int,
    ) {
        if (!isConnected()) {
            throw IOException("Not connected to Adalight device")
        }

        // Extract LED data reusing buffer
        mLedDataBuffer =
            LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer)
        if (mLedDataBuffer!!.isEmpty()) return

        // Pass to smoothing
        mSmoothing.setTargetColors(mLedDataBuffer)
    }

    override fun setRawLedData(leds: Array<ColorRgb>, priority: Int) {
        mSmoothing.setTargetColors(leds)
    }

    // Callback from ColorSmoothing
    private fun sendLedData(leds: Array<ColorRgb>) {
        if (!isConnected()) return

        val port = mPort
        if (port == null) {
            Log.w(TAG, "Port is null, connection lost")
            mConnected = false
            return
        }

        try {
            val packet = AdalightProtocolHelper.createPacket(mProtocol, leds)
            maybeAutoThrottle(packet.size)
            port.write(packet, 1000)

            // Log for debugging occasionally
            if (System.currentTimeMillis() % 2000 < 50) {
                Log.v(TAG, "Sent packet: " + leds.size + " LEDs")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send data", e)
            mConnected = false
        } catch (e: NullPointerException) {
            Log.e(TAG, "USB connection lost (NullPointerException)", e)
            mConnected = false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending data", e)
            mConnected = false
        }
    }

    /**
     * Auto-throttle частоту сглаживания под выбранный baudrate и фактический размер пакета.
     * Это резко повышает стабильность при большом количестве LED (иначе данные начинают идти "без пауз",
     * Arduino теряет байты, и протокол рассинхронизируется).
     */
    private fun maybeAutoThrottle(packetSizeBytes: Int) {
        if (packetSizeBytes <= 0) return
        // Пересчитываем только если меняется размер (например, при смене протокола или количества LED)
        if (packetSizeBytes == mLastAutoThrottlePacketSize) return
        mLastAutoThrottlePacketSize = packetSizeBytes

        // Приблизительно: 1 байт = 10 бит (8N1)
        val maxBytesPerSecond = mBaudRate.toDouble() / 10.0
        val maxHz = maxBytesPerSecond / packetSizeBytes.toDouble()
        val safeHz = (maxHz * 0.90).toInt().coerceIn(1, 60)

        val desiredHz = minOf(mRequestedUpdateFrequency, safeHz)
        if (desiredHz != mEffectiveUpdateFrequency) {
            mEffectiveUpdateFrequency = desiredHz
            mSmoothing.setUpdateFrequency(desiredHz)
            Log.i(
                TAG,
                "Auto-throttle smoothing: ${desiredHz}Hz (baud=$mBaudRate, packet=$packetSizeBytes bytes)"
            )
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
            else -> PresetValues(200, 80L, 25) // balanced по умолчанию
        }
    }

    companion object {
        private const val TAG = "AdalightClient"
    }
}
