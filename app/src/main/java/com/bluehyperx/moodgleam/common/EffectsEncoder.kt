package com.bluehyperx.moodgleam.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bluehyperx.moodgleam.R
import com.bluehyperx.moodgleam.common.network.HyperionThread
import com.bluehyperx.moodgleam.common.util.Preferences
import java.nio.ByteBuffer
import kotlin.math.sin

class EffectsEncoder(
    private val context: Context,
    private val hyperionThread: HyperionThread?,
    private val frameRate: Int = 30
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var effectType = "rainbow"
    private var frameCount = 0L

    private val width = 64
    private val height = 64
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint().apply { isAntiAlias = true }
    private val byteBuffer = ByteBuffer.allocate(width * height * 4)
    private var rgbBuffer = ByteArray(width * height * 3)

    private val runEffect = object : Runnable {
        override fun run() {
            if (!isRunning) return
            
            generateFrame()
            
            byteBuffer.rewind()
            bitmap.copyPixelsToBuffer(byteBuffer)
            val rgbaArray = byteBuffer.array()
            var rgbIndex = 0
            for (i in 0 until (width * height * 4) step 4) {
                rgbBuffer[rgbIndex++] = rgbaArray[i]     // R
                rgbBuffer[rgbIndex++] = rgbaArray[i + 1] // G
                rgbBuffer[rgbIndex++] = rgbaArray[i + 2] // B
            }

            hyperionThread?.mListener?.sendFrame(rgbBuffer, width, height)
            
            frameCount++
            handler.postDelayed(this, (1000 / frameRate).toLong())
        }
    }

    fun start() {
        if (isRunning) return
        
        val prefs = Preferences(context)
        effectType = prefs.getString(R.string.pref_key_current_effect, "rainbow") ?: "rainbow"
        
        isRunning = true
        handler.post(runEffect)
        Log.i(TAG, "EffectsEncoder started with effect: $effectType")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(runEffect)
        Log.i(TAG, "EffectsEncoder stopped")
    }

    fun refreshSettings() {
        val prefs = Preferences(context)
        effectType = prefs.getString(R.string.pref_key_current_effect, "rainbow") ?: "rainbow"
        Log.i(TAG, "EffectsEncoder settings refreshed: $effectType")
    }

    private fun generateFrame() {
        val prefs = Preferences(context)
        
        // Handle WiZ Hardware Scenes
        val wizScene = prefs.getInt("pref_key_wiz_scene", 0)
        val wizScenesEnabled = prefs.getBoolean(R.string.pref_key_wiz_scenes_enabled, true)
        if (wizScenesEnabled && wizScene > 0 && "wiz".equals(hyperionThread?.mConnectionType, ignoreCase = true)) {
            val speedKey = "pref_key_effect_speed_$effectType"
            val speedStr = prefs.getString(speedKey, "100") ?: "100"
            val speed = speedStr.toIntOrNull() ?: 100
            
            hyperionThread?.receiver?.sendScene(wizScene, speed)
            return
        }

        // Per-effect pace: Use a specific key for each effect's speed
        val speedKey = "pref_key_effect_speed_$effectType"
        val speedStr = prefs.getString(speedKey, "50") ?: "50"
        val speedBase = speedStr.toFloatOrNull() ?: 50f
        val speedMultiplier = speedBase / 50f 
        
        val globalBrightness = prefs.getInt(R.string.pref_key_color_brightness, 100) / 100f

        when (effectType) {
            "rainbow" -> {
                val cx = width / 2f
                val cy = height / 2f
                val colors = intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED)
                val shader = SweepGradient(cx, cy, colors, null)
                val matrix = android.graphics.Matrix()
                matrix.postRotate(((frameCount * 3f) * speedMultiplier) % 360f, cx, cy)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                paint.alpha = (globalBrightness * 255).toInt()
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            "sunset" -> {
                paint.shader = null
                val time = frameCount * 0.01f * speedMultiplier
                // Cycle through gold, orange, pink, purple
                val phase = (time % 1.0f)
                val color1 = Color.HSVToColor(floatArrayOf(45f, 0.9f, 1f)) // Gold
                val color2 = Color.HSVToColor(floatArrayOf(20f, 1f, 1f))   // Orange
                val color3 = Color.HSVToColor(floatArrayOf(330f, 0.8f, 1f)) // Pink
                val color4 = Color.HSVToColor(floatArrayOf(270f, 0.7f, 0.8f)) // Purple
                
                for (y in 0 until height) {
                    val ratio = y.toFloat() / height
                    val finalColor = when {
                        phase < 0.33f -> interpolateColor(color1, color2, phase / 0.33f)
                        phase < 0.66f -> interpolateColor(color2, color3, (phase - 0.33f) / 0.33f)
                        else -> interpolateColor(color3, color4, (phase - 0.66f) / 0.34f)
                    }
                    paint.color = finalColor
                    paint.alpha = (globalBrightness * 255).toInt()
                    canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
                }
            }
            "ocean" -> {
                paint.shader = null
                val time = frameCount * 0.03f * speedMultiplier
                for (y in 0 until height step 4) {
                    for (x in 0 until width step 4) {
                        val noise = (sin(x * 0.1 + time) + sin(y * 0.1 + time * 1.2)).toFloat()
                        val hue = 200f + noise * 20f // Range 180 (Cyan) to 220 (Blue)
                        val brightness = (0.7f + noise * 0.3f).coerceIn(0f, 1f)
                        paint.color = Color.HSVToColor((globalBrightness * 255).toInt(), floatArrayOf(hue, 0.8f, brightness))
                        canvas.drawRect(x.toFloat(), y.toFloat(), (x + 4).toFloat(), (y + 4).toFloat(), paint)
                    }
                }
            }
            "fireplace" -> {
                paint.shader = null
                val time = frameCount * 0.1f * speedMultiplier
                canvas.drawColor(Color.BLACK)
                for (i in 0 until 15) {
                    val x = (Math.random() * width).toFloat()
                    val y = (height - Math.random() * height * (0.3 + sin(time + i) * 0.2)).toFloat()
                    val size = (Math.random() * 15 + 5).toFloat()
                    val hue = (Math.random() * 30).toFloat() // Red to Orange
                    paint.color = Color.HSVToColor((globalBrightness * 255).toInt(), floatArrayOf(hue, 1f, 1f))
                    canvas.drawCircle(x, y, size, paint)
                }
            }
            "aurora" -> {
                paint.shader = null
                val time = frameCount * 0.02f * speedMultiplier
                canvas.drawColor(Color.BLACK)
                for (i in 0 until 3) {
                    val offset = i * 20f
                    for (x in 0 until width step 2) {
                        val yBase = height * 0.5f
                        val yVar = (sin(x * 0.05 + time + offset) * 20 + sin(x * 0.1 - time * 0.7) * 10).toFloat()
                        val hue = (140f + i * 40f + sin(time) * 20f) % 360f // Green to Teal/Purple
                        paint.color = Color.HSVToColor((globalBrightness * 150).toInt(), floatArrayOf(hue, 0.7f, 1f))
                        canvas.drawRect(x.toFloat(), yBase + yVar - 15f, (x + 2).toFloat(), yBase + yVar + 15f, paint)
                    }
                }
            }
            "color_wave" -> {
                paint.shader = null
                val color1Key = "pref_key_effect_color1_$effectType"
                val color2Key = "pref_key_effect_color2_$effectType"
                
                val customColor1 = if (prefs.contains(color1Key)) prefs.getInt(color1Key, 0) else null
                val customColor2 = if (prefs.contains(color2Key)) prefs.getInt(color2Key, 0) else null
                
                val time = frameCount * 0.1f * speedMultiplier
                for (x in 0 until width step 2) {
                    if (customColor1 != null || customColor2 != null) {
                        // Use selected colors (defaulting to Neo-Mint/Magenta if only one is selected)
                        val c1 = customColor1 ?: 0xFFA2FFB3.toInt()
                        val c2 = customColor2 ?: 0xFFE6006F.toInt()
                        
                        val wave = (0.5f + 0.5f * sin((x * 0.1 + time).toDouble()).toFloat())
                        paint.color = interpolateColor(c1, c2, wave)
                        paint.alpha = (globalBrightness * 255).toInt()
                    } else {
                        // Default full rainbow
                        val hue = (x * 10f + time * 50f) % 360f
                        paint.color = Color.HSVToColor((globalBrightness * 255).toInt(), floatArrayOf(hue, 1f, 1f))
                    }
                    canvas.drawRect(x.toFloat(), 0f, (x + 2).toFloat(), height.toFloat(), paint)
                }
            }
            "police" -> {
                paint.shader = null
                val color1Key = "pref_key_effect_color1_$effectType"
                val color2Key = "pref_key_effect_color2_$effectType"
                
                val c1 = if (prefs.contains(color1Key)) prefs.getInt(color1Key, Color.BLUE) else Color.BLUE
                val c2 = if (prefs.contains(color2Key)) prefs.getInt(color2Key, Color.RED) else Color.RED
                
                val cycleFrames = (5 / speedMultiplier).toLong().coerceAtLeast(1L)
                val isPhase1 = (frameCount / cycleFrames) % 2 == 0L
                val br = (255 * globalBrightness).toInt()
                
                if (isPhase1) {
                    paint.color = applyAlpha(c1, br)
                    canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
                    paint.color = Color.BLACK
                    canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
                } else {
                    paint.color = Color.BLACK
                    canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
                    paint.color = applyAlpha(c2, br)
                    canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
                }
            }
            "knight_rider" -> {
                paint.shader = null
                val colorKey = "pref_key_effect_color1_$effectType"
                val baseColor = prefs.getInt(colorKey, Color.RED)
                canvas.drawColor(Color.rgb((Color.red(baseColor) * 0.1 * globalBrightness).toInt(), (Color.green(baseColor) * 0.1 * globalBrightness).toInt(), (Color.blue(baseColor) * 0.1 * globalBrightness).toInt()))
                
                val cycleLength = (60 / speedMultiplier).toInt().coerceAtLeast(1)
                val pos = (frameCount % (cycleLength * 2))
                val normalizedPos = if (pos < cycleLength) pos.toFloat() else (cycleLength * 2 - pos).toFloat()
                val progress = normalizedPos / cycleLength
                
                val barX = progress * width
                paint.color = baseColor
                paint.alpha = (globalBrightness * 255).toInt()
                canvas.drawRect(barX - 8f, 0f, barX + 8f, height.toFloat(), paint)
            }
            "solid" -> {
                paint.shader = null
                val colorKey = "pref_key_effect_color1_$effectType"
                val baseColor = prefs.getInt(colorKey, prefs.getInt(R.string.pref_key_effect_color, Color.WHITE))
                val r = (Color.red(baseColor) * globalBrightness).toInt()
                val g = (Color.green(baseColor) * globalBrightness).toInt()
                val b = (Color.blue(baseColor) * globalBrightness).toInt()
                canvas.drawRGB(r, g, b)
            }
            "breathing" -> {
                paint.shader = null
                val colorKey = "pref_key_effect_color1_$effectType"
                val baseColor = prefs.getInt(colorKey, prefs.getInt(R.string.pref_key_effect_color, Color.CYAN))
                val pulse = (sin(frameCount * Math.PI / 30.0 * speedMultiplier) * 0.5 + 0.5).toFloat()
                val finalBrightness = globalBrightness * pulse
                
                val r = (Color.red(baseColor) * finalBrightness).toInt()
                val g = (Color.green(baseColor) * finalBrightness).toInt()
                val b = (Color.blue(baseColor) * finalBrightness).toInt()
                canvas.drawRGB(r, g, b)
            }
            else -> {
                canvas.drawColor(Color.BLACK)
            }
        }
    }

    private fun applyAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun interpolateColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = (Color.red(c1) * (1 - ratio) + Color.red(c2) * ratio).toInt()
        val g = (Color.green(c1) * (1 - ratio) + Color.green(c2) * ratio).toInt()
        val b = (Color.blue(c1) * (1 - ratio) + Color.blue(c2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    companion object {
        private const val TAG = "EffectsEncoder"
    }
}
