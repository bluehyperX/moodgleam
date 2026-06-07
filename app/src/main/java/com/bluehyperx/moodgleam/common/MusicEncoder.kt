package com.bluehyperx.moodgleam.common

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.common.network.ColorRgb
import com.bluehyperx.moodgleam.common.network.HyperionThread
import com.bluehyperx.moodgleam.common.network.WizClient
import com.bluehyperx.moodgleam.common.util.LedDataExtractor
import com.bluehyperx.moodgleam.common.util.Preferences
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

class MusicEncoder(
    private val context: Context,
    private val hyperionThread: HyperionThread?,
    private val projection: MediaProjection?,
    private val frameRate: Int = 30
) {
    private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    
    private var ledCount = 0
    private var colors: Array<ColorRgb>? = null
    
    // Configurable audio properties
    private var sampleRate = 22050
    private var sampleStep = 4
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 2048

    private var smoothedVolume = 0f
    private var bassLevel = 0f
    private var midLevel = 0f
    private var highLevel = 0f
    
    private var effectType = "vu_meter"
    private var frameCount = 0L

    // Background WiZ Sync Support
    private var mUseWizBackground = false
    private var mWizClient: WizClient? = null
    private var mWizLastR = 0
    private var mWizLastG = 0
    private var mWizLastB = 0

    // Performance Caching
    private var mCachedBrightness = 1.0f
    private var mCachedSensitivity = 50f
    private var mCachedPriority = 100
    private var mCachedWeightBass = 2.5f
    private var mCachedWeightMid = 1.5f
    private var mCachedWeightHigh = 3.0f
    private var mWizCalibR = 1.0f
    private var mWizCalibG = 1.0f
    private var mWizCalibB = 1.0f

    private var mWizBrightnessBoost = 1.0f

    private var mCachedThresholdBassMid = 1200
    private var mCachedThresholdMidHigh = 8000

    private var mCachedWizMode = "frequency"

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        
        val prefs = Preferences(context)
        effectType = prefs.getString(R.string.pref_key_current_music_effect, "vu_meter") ?: "vu_meter"
        
        mUseWizBackground = prefs.getBoolean(R.string.pref_key_music_use_wiz_background, false)
        if (mUseWizBackground) {
            val wizIp = prefs.getString(R.string.pref_key_wiz_background_ip, "") ?: ""
            if (wizIp.isNotEmpty()) {
                mWizClient = WizClient(wizIp)
                Log.i(TAG, "Initialized Background WiZ Client at $wizIp")
            }
        }
        
        // Read User Performance Configurations
        sampleRate = prefs.getString(R.string.pref_key_music_sampling_rate, "22050")?.toIntOrNull() ?: 22050
        sampleStep = prefs.getString(R.string.pref_key_music_sample_step, "4")?.toIntOrNull() ?: 4
        
        ledCount = LedDataExtractor.getLedCount(context)
        colors = Array(ledCount) { ColorRgb(0, 0, 0) }
        
        updateCachedSettings()

        try {
            bufferSize = max(AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat), 1024)

            if (projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Digital System Audio Capture (Android 10+)
                val config = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                    .build()
                
                audioRecord = AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                
                Log.i(TAG, "MusicEncoder started using Digital System Audio (Rate: $sampleRate, Step: $sampleStep)")
            } else {
                // FALLBACK: If projection is null, we use Microphone
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                Log.i(TAG, "MusicEncoder started using Microphone (Rate: $sampleRate, Step: $sampleStep)")
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                isRunning = false
                return
            }

            audioRecord?.startRecording()
            isRunning = true
            
            startProcessingThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        recordingThread?.interrupt()
        recordingThread = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        
        try {
            mWizClient?.disconnect()
            mWizClient = null
        } catch (_: Exception) {}
    }

    fun refreshSettings() {
        val prefs = Preferences(context)
        effectType = prefs.getString(R.string.pref_key_current_music_effect, "vu_meter") ?: "vu_meter"
        Log.i(TAG, "MusicEncoder style refreshed: $effectType")
    }

    private fun startProcessingThread() {
        recordingThread = Thread {
            val audioBuffer = ShortArray(256)
            while (isRunning && !Thread.currentThread().isInterrupted) {
                // 1. BLOCKING READ: Wait for the next sound sample (this naturally syncs the loop)
                val firstRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (firstRead <= 0) continue

                // 2. NON-BLOCKING DRAIN: Read any leftover data that built up while we were busy
                // This ensures we are always looking at the "current" millisecond
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    while (true) {
                        val drained = audioRecord?.read(audioBuffer, 0, audioBuffer.size, AudioRecord.READ_NON_BLOCKING) ?: 0
                        if (drained <= 0) break
                        // Keep the most recent chunk for processing
                        processAudioData(audioBuffer, drained)
                    }
                }

                // 3. Process the final current chunk and update the LEDs
                processAudioData(audioBuffer, firstRead)
                renderAndSendDirectly()
            }
        }.apply { 
            name = "InstantMusicThread"
            start() 
        }
    }

    private fun updateCachedSettings() {
        val prefs = Preferences(context)
        mCachedBrightness = prefs.getInt(R.string.pref_key_color_brightness, 100) / 100f

        // Load frequency weights from preferences
        mCachedWeightBass = (prefs.getString(R.string.pref_key_music_weight_bass, "2.5") ?: "2.5").toFloatOrNull() ?: 2.5f
        mCachedWeightMid = (prefs.getString(R.string.pref_key_music_weight_mid, "1.5") ?: "1.5").toFloatOrNull() ?: 1.5f
        mCachedWeightHigh = (prefs.getString(R.string.pref_key_music_weight_high, "3.0") ?: "3.0").toFloatOrNull() ?: 3.0f

        // Load frequency thresholds from preferences
        mCachedThresholdBassMid = (prefs.getString(R.string.pref_key_music_threshold_bass_mid, "1200") ?: "1200").toIntOrNull() ?: 1200
        mCachedThresholdMidHigh = (prefs.getString(R.string.pref_key_music_threshold_mid_high, "8000") ?: "8000").toIntOrNull() ?: 8000

        // Load WiZ hardware calibration multipliers
        mWizCalibR = (prefs.getString(R.string.pref_key_wiz_calib_r, "1.0") ?: "1.0").toFloatOrNull() ?: 1.0f
        mWizCalibG = (prefs.getString(R.string.pref_key_wiz_calib_g, "1.0") ?: "1.0").toFloatOrNull() ?: 1.0f
        mWizCalibB = (prefs.getString(R.string.pref_key_wiz_calib_b, "1.0") ?: "1.0").toFloatOrNull() ?: 1.0f

        // Load WiZ independent brightness multiplier
        mWizBrightnessBoost = (prefs.getString(R.string.pref_key_wiz_brightness, "100") ?: "100").toFloatOrNull()?.let { it / 100f } ?: 1.0f

        // Load WiZ sync mode
        mCachedWizMode = prefs.getString(R.string.pref_key_wiz_background_mode, "frequency") ?: "frequency"

        // Per-effect sensitivity: Use a specific key for each music visualization
        val sensitivityKey = "pref_key_music_sensitivity_$effectType"
        val rawSensitivity = prefs.getString(sensitivityKey, null) 
            ?: prefs.getString(R.string.pref_key_music_sensitivity, "50") 
            ?: "50"

        mCachedSensitivity = rawSensitivity.removeSuffix("f").toFloatOrNull() ?: 50f

        mCachedPriority = prefs.getInt(R.string.pref_key_priority, 100)
    }

    private fun renderAndSendDirectly() {
        val currentColors = colors ?: return

        // Refresh settings from preferences once per second (approx 30 frames)
        if (frameCount % 30 == 0L) {
            updateCachedSettings()
        }

        val boost = (mCachedSensitivity / 50f).pow(1.5f)

        when (effectType) {
            "vu_meter" -> renderVuMeter(currentColors, mCachedBrightness, boost)
            "pulse" -> renderPulse(currentColors, mCachedBrightness)
            "frequency_analyzer" -> renderFrequencyAnalyzer(currentColors, mCachedBrightness)
            "beat_flash" -> renderBeatFlash(currentColors, mCachedBrightness)
            "waterfall" -> renderWaterfall(currentColors, mCachedBrightness)
            "neon_trails" -> renderNeonTrails(currentColors, mCachedBrightness)
            "spectrogram" -> renderSpectrogram(currentColors, mCachedBrightness)
            "color_wave" -> renderColorWave(currentColors, mCachedBrightness, boost)
            else -> renderVuMeter(currentColors, mCachedBrightness, boost)
        }

        // Write directly to the hardware client to achieve absolute zero latency
        try {
            hyperionThread?.mClient?.get()?.let { client ->
                if (client.isConnected()) {
                    client.setRawLedData(currentColors, mCachedPriority)
                }
            }
        } catch (_: Exception) {}

        // Background WiZ Sync
        if (mUseWizBackground && mWizClient != null && mWizClient!!.isConnected()) {
            val targetR: Int
            val targetG: Int
            val targetB: Int

            if (mCachedWizMode == "average") {
                // MODE: LED Average - Bulb follows the overall visual state of the strip
                var totalR = 0L
                var totalG = 0L
                var totalB = 0L
                for (color in currentColors) {
                    totalR += color.red
                    totalG += color.green
                    totalB += color.blue
                }
                val count = currentColors.size.coerceAtLeast(1)
                targetR = ((totalR / count) * mWizCalibR).toInt().coerceIn(0, 255)
                targetG = ((totalG / count) * mWizCalibG).toInt().coerceIn(0, 255)
                targetB = ((totalB / count) * mWizCalibB).toInt().coerceIn(0, 255)
            } else {
                // MODE: Frequency Reactive - Bulb follows raw audio frequency levels
                targetR = (bassLevel * 255 * mCachedBrightness * mWizCalibR).toInt().coerceIn(0, 255)
                targetG = (midLevel * 255 * mCachedBrightness * mWizCalibG).toInt().coerceIn(0, 255)
                targetB = (highLevel * 255 * mCachedBrightness * mWizCalibB).toInt().coerceIn(0, 255)
            }

            // Apply independent WiZ brightness boost
            val boostedR = (targetR * mWizBrightnessBoost).toInt().coerceIn(0, 255)
            val boostedG = (targetG * mWizBrightnessBoost).toInt().coerceIn(0, 255)
            val boostedB = (targetB * mWizBrightnessBoost).toInt().coerceIn(0, 255)

            // Temporal smoothing for the background bulb (0.3f = more responsive, was 0.1f)
            mWizLastR = (mWizLastR * 0.7f + boostedR * 0.3f).toInt()
            mWizLastG = (mWizLastG * 0.7f + boostedG * 0.3f).toInt()
            mWizLastB = (mWizLastB * 0.7f + boostedB * 0.3f).toInt()

            // To prevent flickering background, we only send if the color has some intensity
            if (mWizLastR > 5 || mWizLastG > 5 || mWizLastB > 5) {
                // WizClient handles internal debouncing (approx 8 updates/sec)
                mWizClient?.setColor((mWizLastR shl 16) or (mWizLastG shl 8) or mWizLastB, 100)
            } else if (frameCount % 30 == 0L) {
                // Periodic 'keep-off' if everything is silent
                mWizClient?.clearImmediate()
            }
        }

        frameCount++
    }

    private fun processAudioData(data: ShortArray, length: Int) {
        var sum = 0f
        var bassSum = 0f
        var midSum = 0f
        var highSum = 0f

        // Noise Gate using Cached Sensitivity
        val gateThreshold = (200f - mCachedSensitivity).coerceAtLeast(10f) * 2.0f

        // Processing Optimization: Use user-configured sample step
        for (i in 0 until length step sampleStep) {
            val sample = abs(data[i].toFloat())
            val gatedSample = if (sample < gateThreshold) 0f else sample
            sum += gatedSample

            if (i >= sampleStep) {
                val delta = abs(data[i] - data[i-sampleStep])
                if (delta < mCachedThresholdBassMid) { // User-configurable Bass boundary
                    if (delta > gateThreshold / 2) bassSum += gatedSample
                }
                else if (delta < mCachedThresholdMidHigh) midSum += gatedSample // User-configurable Mid range
                else highSum += gatedSample // Only true sharp transients are Blue
            }
        }

        val processedCount = length / sampleStep
        val currentVolume = (sum / processedCount) / 32768f
        smoothedVolume = (smoothedVolume * 0.7f) + (currentVolume * 0.3f)

        // Sensitivity Boost: Logarithmic-like scaling for better dynamic range
        // 50 (default) -> boost of 1.0
        val boost = (mCachedSensitivity / 50f).pow(1.5f)

        bassLevel = ((bassSum / processedCount) / 32768f * boost * mCachedWeightBass).coerceIn(0f, 1f)
        midLevel = ((midSum / processedCount) / 32768f * boost * mCachedWeightMid).coerceIn(0f, 1f)
        highLevel = ((highSum / processedCount) / 32768f * boost * mCachedWeightHigh).coerceIn(0f, 1f)
    }

    private fun renderVuMeter(leds: Array<ColorRgb>, brightness: Float, boost: Float) {
        // VU Meter level now also depends on boost for better responsiveness at low sensitivity
        val level = (smoothedVolume * boost * 4.0f).coerceIn(0f, 1f)
        val activeCount = (ledCount * level).toInt()
        for (i in 0 until ledCount) {
            if (i < activeCount) {
                val ratio = i.toFloat() / ledCount
                val r = if (ratio > 0.6) 255 else (ratio * 400).toInt().coerceIn(0, 255)
                val g = if (ratio > 0.8) 0 else 255
                leds[i] = ColorRgb((r * brightness).toInt(), (g * brightness).toInt(), 0)
            } else fadeLed(leds, i, 0.7f)
        }
    }

    private fun renderPulse(leds: Array<ColorRgb>, brightness: Float) {
        val level = (bassLevel * 1.2f + highLevel * 0.3f).coerceIn(0f, 1f)
        val activeCount = (ledCount * level).toInt()
        val center = ledCount / 2
        val r = (bassLevel * 255 * brightness).toInt()
        val b = (highLevel * 255 * brightness).toInt()
        val g = (midLevel * 150 * brightness).toInt()
        for (i in 0 until ledCount) {
            val dist = abs(i - center)
            if (dist <= activeCount / 2) leds[i] = ColorRgb(r, g, b)
            else fadeLed(leds, i, 0.8f)
        }
    }

    private fun renderFrequencyAnalyzer(leds: Array<ColorRgb>, brightness: Float) {
        val sectionSize = max(ledCount / 3, 1)
        val r = (bassLevel * 255 * brightness).toInt()
        val g = (midLevel * 255 * brightness).toInt()
        val b = (highLevel * 255 * brightness).toInt()
        for (i in 0 until ledCount) {
            val section = i / sectionSize
            when (section % 3) {
                0 -> if (i % sectionSize < sectionSize * bassLevel) leds[i] = ColorRgb(r, 0, 0) else fadeLed(leds, i, 0.8f)
                1 -> if (i % sectionSize < sectionSize * midLevel) leds[i] = ColorRgb(0, g, 0) else fadeLed(leds, i, 0.8f)
                2 -> if (i % sectionSize < sectionSize * highLevel) leds[i] = ColorRgb(0, 0, b) else fadeLed(leds, i, 0.8f)
            }
        }
    }

    private fun renderBeatFlash(leds: Array<ColorRgb>, brightness: Float) {
        // Significantly lower threshold (0.25f instead of 0.45f) for much better responsiveness
        if (bassLevel > 0.25f) {
            val intensity = (bassLevel * brightness)
            val r = (intensity * 255).toInt()
            val g = (midLevel * 100 * brightness).toInt()
            for (i in leds.indices) leds[i] = ColorRgb(r, g, 0)
        } else for (i in leds.indices) fadeLed(leds, i, 0.4f)
    }

    private fun renderWaterfall(leds: Array<ColorRgb>, brightness: Float) {
        for (i in ledCount - 1 downTo 1) leds[i] = leds[i - 1]
        val r = (bassLevel * 255 * brightness).toInt()
        val g = (midLevel * 255 * brightness).toInt()
        val b = (highLevel * 255 * brightness).toInt()
        leds[0] = ColorRgb(r, g, b)
    }

    private fun renderNeonTrails(leds: Array<ColorRgb>, brightness: Float) {
        val center = ledCount / 2
        for (i in 0 until ledCount) fadeLed(leds, i, 0.85f)
        if (bassLevel > 0.4f || highLevel > 0.4f) {
            val r = (bassLevel * 255 * brightness).toInt()
            val b = (highLevel * 255 * brightness).toInt()
            val g = (midLevel * 100 * brightness).toInt()
            if (center in leds.indices) leds[center] = ColorRgb(r, g, b)
        }
        for (i in 0 until center) leds[i] = leds[i + 1]
        for (i in ledCount - 1 downTo center + 1) leds[i] = leds[i - 1]
    }

    private fun renderSpectrogram(leds: Array<ColorRgb>, brightness: Float) {
        for (i in 0 until ledCount) {
            val ratio = i.toFloat() / ledCount
            val intensity = when {
                ratio < 0.33f -> bassLevel
                ratio < 0.66f -> midLevel
                else -> highLevel
            }
            val r = if (ratio < 0.4f) (255 * intensity * brightness).toInt() else 0
            val g = if (ratio in 0.3f..0.7f) (255 * intensity * brightness).toInt() else 0
            val b = if (ratio > 0.6f) (255 * intensity * brightness).toInt() else 0
            leds[i] = ColorRgb(r, g, b)
        }
    }

    private fun renderColorWave(leds: Array<ColorRgb>, brightness: Float, boost: Float) {
        val waveSpeed = 0.2f
        val offset = frameCount * waveSpeed
        // Intensity now scales with smoothed volume and boost for better dynamics
        val intensity = (smoothedVolume * boost * 2.0f).coerceIn(0.1f, 1.0f)
        for (i in 0 until ledCount) {
            val hue = (i * 10.0f + offset) % 360f
            val rgb = hsvToRgb(hue.toDouble(), 1.0, (intensity * brightness).toDouble())
            leds[i] = rgb
        }
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): ColorRgb {
        val c = v * s
        val x = c * (1 - abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return ColorRgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    private fun fadeLed(leds: Array<ColorRgb>, index: Int, factor: Float) {
        val old = leds[index]
        leds[index] = ColorRgb((old.red * factor).toInt(), (old.green * factor).toInt(), (old.blue * factor).toInt())
    }

    companion object {
        private const val TAG = "MusicEncoder"
        private const val SectionCount = 10
    }
}
