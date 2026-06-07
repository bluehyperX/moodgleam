package com.bluehyperx.moodgleam.common.network

import kotlin.math.max

object AdalightProtocolHelper {

    enum class ProtocolType {
        ADA,    // Standard Adalight
        LBAPA,  // LightBerry APA102
        AWA     // Hyperserial
    }

    fun createPacket(protocol: ProtocolType, leds: Array<ColorRgb>): ByteArray {
        return when (protocol) {
            ProtocolType.ADA -> createAdaPacket(leds)
            ProtocolType.LBAPA -> createLbapaPacket(leds)
            ProtocolType.AWA -> createAwaPacket(leds)
        }
    }

    private fun createAdaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        val dataSize = ledCount * 3
        val packet = ByteArray(6 + dataSize)

        // Header
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()

        val ledCountMinusOne = ledCount - 1
        packet[3] = ((ledCountMinusOne shr 8) and 0xFF).toByte()
        packet[4] = (ledCountMinusOne and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        // RGB data
        var offset = 6
        for (led in leds) {
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        return packet
    }

    private fun createLbapaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        val startFrameSize = 4
        val endFrameSize = max((ledCount + 15) / 16, 4)
        val bytesPerLed = 4
        val dataSize = ledCount * bytesPerLed

        val packet = ByteArray(6 + startFrameSize + dataSize + endFrameSize)

        // Header (same as ADA but with ledCount, not ledCount-1)
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()

        // LBAPA uses ledCount directly, NOT ledCount-1 like standard Adalight
        packet[3] = ((ledCount shr 8) and 0xFF).toByte()
        packet[4] = (ledCount and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        // Start Frame (4 bytes 0x00)
        var offset = 6
        for (i in 0 until startFrameSize) {
            packet[offset++] = 0x00
        }

        // LED data: [0xFF, R, G, B] for each LED
        for (led in leds) {
            packet[offset++] = 0xFF.toByte()
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        // End Frame
        for (i in 0 until endFrameSize) {
            packet[offset++] = 0x00
        }

        return packet
    }

    private fun createAwaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        val dataSize = ledCount * 3
        // Checksum size = 3 bytes (Fletcher)
        val packet = ByteArray(6 + dataSize + 3)

        packet[0] = 'A'.code.toByte()
        packet[1] = 'w'.code.toByte()
        packet[2] = 'a'.code.toByte() // 'a' = no white calibration

        val ledCountMinusOne = ledCount - 1
        packet[3] = ((ledCountMinusOne shr 8) and 0xFF).toByte()
        packet[4] = (ledCountMinusOne and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        var offset = 6
        for (led in leds) {
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        // Fletcher Checksum
        var fletcher1 = 0
        var fletcher2 = 0
        var fletcherExt = 0
        // Hyperion implementation uses 0-based position
        var position = 0

        for (i in 0 until dataSize) {
            val `val` = packet[6 + i].toInt() and 0xFF

            fletcherExt = (fletcherExt + (`val` xor position)) % 255
            fletcher1 = (fletcher1 + `val`) % 255
            fletcher2 = (fletcher2 + fletcher1) % 255

            position = (position + 1) % 256
        }

        packet[offset++] = fletcher1.toByte()
        packet[offset++] = fletcher2.toByte()

        // Handle special case 0x41 ('A') to avoid confusion with header
        packet[offset] = if (fletcherExt == 0x41) 0xaa.toByte() else fletcherExt.toByte()

        return packet
    }
}
