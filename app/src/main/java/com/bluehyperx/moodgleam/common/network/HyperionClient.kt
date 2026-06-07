package com.bluehyperx.moodgleam.common.network

import java.io.IOException

interface HyperionClient {
    fun isConnected(): Boolean

    @Throws(IOException::class)
    fun disconnect()

    @Throws(IOException::class)
    fun clear(priority: Int)

    @Throws(IOException::class)
    fun clearAll()

    @Throws(IOException::class)
    fun setColor(color: Int, priority: Int)

    @Throws(IOException::class)
    fun setColor(color: Int, priority: Int, duration_ms: Int)

    @Throws(IOException::class)
    fun setImage(data: ByteArray, width: Int, height: Int, priority: Int)

    @Throws(IOException::class)
    fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int)

    @Throws(IOException::class)
    fun setRawLedData(leds: Array<ColorRgb>, priority: Int)

    fun setScene(sceneId: Int, speed: Int = 100) {}

    @Throws(IOException::class)
    fun clearImmediate()

    fun getConnectedDeviceName(): String?
}
