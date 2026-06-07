package com.bluehyperx.moodgleam.common.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Client for Philips WiZ bulbs using local UDP protocol (Port 38899).
 * Sends JSON payloads to control color and brightness.
 */
class WizClient(
    private val host: String,
    private val port: Int = 38899
) : HyperionClient {

    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var isConnected = false
    private var lastSendTime = 0L
    private val DEBOUNCE_MS = 120L // Don't flood WiZ with more than ~8 updates/sec

    init {
        try {
            socket = DatagramSocket()
            address = InetAddress.getByName(host)
            isConnected = true
            
            // Register with the bulb (essential for many WiZ firmware versions)
            sendCommand("{\"method\":\"registration\",\"params\":{\"register\":true}}")
            
            // Sync initial state
            syncState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WiZ socket: ${e.message}")
            isConnected = false
        }
    }

    private fun syncState() {
        // Query current state from bulb
        sendCommand("{\"method\":\"getPilot\",\"params\":{}}")
    }

    override fun isConnected(): Boolean = isConnected

    override fun disconnect() {
        // Mark as disconnected immediately so no new color updates are accepted
        isConnected = false
        
        // Start a background thread to send 'off' commands and then close the socket.
        // This ensures the bulb actually turns off while allowing disconnect() to return immediately.
        Thread {
            try {
                repeat(3) {
                    sendCommand("{\"method\":\"setPilot\",\"params\":{\"state\":false}}")
                    try { Thread.sleep(120) } catch (_: Exception) {}
                }
            } finally {
                // Final resource cleanup after the 'off' sequence completes
                val s = socket
                socket = null
                s?.close()
            }
        }.start()
    }

    override fun clear(priority: Int) {
        // Using a 120ms interval between retries as per reference implementation
        Thread {
            repeat(3) {
                sendCommand("{\"method\":\"setPilot\",\"params\":{\"state\":false}}")
                try { Thread.sleep(120) } catch (_: Exception) {}
            }
        }.start()
    }

    override fun clearAll() {
        clear(0)
    }

    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, 0)
    }

    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        if (!shouldSend()) return
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        sendRgbWithDimming(r, g, b)
    }

    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, 0)
    }

    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (!shouldSend()) return
        if (data.size < 3) return
        val centerX = width / 2
        val centerY = height / 2
        val offset = (centerY * width + centerX) * 3
        if (offset + 2 < data.size) {
            val r = data[offset].toInt() and 0xFF
            val g = data[offset + 1].toInt() and 0xFF
            val b = data[offset + 2].toInt() and 0xFF
            sendRgbWithDimming(r, g, b)
        }
    }

    override fun setRawLedData(leds: Array<ColorRgb>, priority: Int) {
        if (!shouldSend()) return
        if (leds.isEmpty()) return
        var r = 0L
        var g = 0L
        var b = 0L
        for (led in leds) {
            r += led.red
            g += led.green
            b += led.blue
        }
        r /= leds.size
        g /= leds.size
        b /= leds.size
        
        sendRgbWithDimming(r.toInt(), g.toInt(), b.toInt())
    }

    private fun sendRgbWithDimming(r: Int, g: Int, b: Int) {
        val maxVal = maxOf(r, g, b)
        if (maxVal == 0) {
            // If color is pure black, just turn it off
            sendCommand("{\"method\":\"setPilot\",\"params\":{\"state\":false}}")
            return
        }

        // WiZ prefers colors sent with maximum saturation and a separate dimming value (10-100)
        // This produces significantly better color accuracy on the hardware.
        val dimming = ((maxVal / 255f) * 100).toInt().coerceIn(10, 100)
        
        val normR = (r * 255) / maxVal
        val normG = (g * 255) / maxVal
        val normB = (b * 255) / maxVal
        
        val json = "{\"method\":\"setPilot\",\"params\":{\"r\":$normR,\"g\":$normG,\"b\":$normB,\"dimming\":$dimming,\"state\":true}}"
        sendCommand(json)
    }

    override fun setScene(sceneId: Int, speed: Int) {
        sendCommand("{\"method\":\"setPilot\",\"params\":{\"sceneId\":$sceneId,\"speed\":$speed}}")
    }

    override fun clearImmediate() {
        clear(0)
    }

    override fun getConnectedDeviceName(): String? = "WiZ Bulb ($host)"

    private fun shouldSend(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < DEBOUNCE_MS) return false
        lastSendTime = now
        return true
    }

    private fun sendCommand(json: String) {
        val s = this.socket ?: return
        val addr = this.address ?: return
        
        try {
            val data = json.toByteArray()
            val packet = DatagramPacket(data, data.size, addr, port)
            s.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending WiZ command: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WizClient"
    }
}
