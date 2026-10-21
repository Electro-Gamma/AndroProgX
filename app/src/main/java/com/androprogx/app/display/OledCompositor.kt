/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.display

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds graphics for [Ssd1306.drawBitmap] (circles, tick marks, hands, bars) and computes the
 * data rows for the clock/stats screens - deliberately does NOT draw any of their *text* itself.
 *
 * That split matters: Canvas/Paint text, even with anti-aliasing turned off, still gets rasterized
 * through Android's font hinter, which leaves partial-coverage edge pixels - fine on a real
 * (grayscale) screen, but once [Ssd1306.drawBitmap] hard-thresholds that down to 1-bit, thin
 * strokes and small glyphs can flip unpredictably and come out dotted/broken. Plain shapes
 * (circles, rects, lines) don't have that problem - there's no glyph rasterizer involved - so
 * this class still uses Canvas for those. For *text*, [ProgrammerController] draws it with
 * [Ssd1306.drawText] instead: a hand-plotted bitmap font that's either on or off per pixel, so it
 * can't come out garbled no matter how small the panel is.
 *
 * [renderText] is the one exception - it's what backs the custom-TTF/OTF "Draw Text" option,
 * which by definition can't use the built-in font. It fixes the same underlying problem a
 * different way: supersampling. It renders at several times the target size (where Android's
 * anti-aliasing is accurate) and then downscales with filtering, so each final pixel reflects a
 * genuine coverage average instead of one potentially-mishinted sample point.
 *
 * [computeStatsRows] intentionally does not try to reproduce stats.py's CPU%/CPU-temperature
 * gauges: a phone has no accessible "CPU usage" or "CPU temperature" API without root. Battery
 * level/temperature stand in for both - the closest thing the platform actually exposes, and
 * conceptually the same kind of reading (the device's own power/thermal state).
 */
object OledCompositor {

    private fun blankCanvas(width: Int, height: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        return bmp to canvas
    }

    private fun strokePaint(strokeWidth: Float = 1f): Paint = Paint().apply {
        isAntiAlias = false
        color = Color.WHITE
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }

    private fun fillPaint(): Paint = Paint().apply {
        isAntiAlias = false
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /**
     * This device's IP address, preferring the active WiFi network's address - that's the one a
     * PC on the same LAN can actually reach for I2C-over-TCP, unlike a cellular-data or VPN IP.
     * Falls back to the active cellular network's IP if WiFi isn't connected, and to a bare
     * interface scan as a last resort. Used for both the Stats dashboard and the I2C-over-TCP
     * status line, so both agree with each other.
     */
    fun wifiOrCellularIpAddress(context: Context): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                ipForTransport(cm, NetworkCapabilities.TRANSPORT_WIFI)?.let { return it }
                ipForTransport(cm, NetworkCapabilities.TRANSPORT_CELLULAR)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return localIpAddress()
    }

    private fun ipForTransport(cm: ConnectivityManager, transport: Int): String? {
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(transport)) continue
            val linkProps = cm.getLinkProperties(network) ?: continue
            for (linkAddress in linkProps.linkAddresses) {
                val addr = linkAddress.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
            }
        }
        return null
    }

    /** Last-resort fallback if [wifiOrCellularIpAddress] can't get anything from
     * ConnectivityManager - a bare scan of every network interface for a non-loopback IPv4. */
    private fun localIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: "?"
                }
            }
        } catch (_: Exception) {
        }
        return "no ip"
    }

    // ---- Custom-font text (the one path that still needs Canvas text rendering) -------------

    /**
     * Renders [text] with [typeface] (system default if null) at [textSizePx], word-wrapped to
     * the panel width by Android's own line breaker. Renders at [supersample]x size first (where
     * anti-aliasing is accurate) and downscales with filtering - see the class doc for why -
     * before [Ssd1306.drawBitmap] thresholds it to 1-bit.
     */
    fun renderText(width: Int, height: Int, text: String, typeface: Typeface?, textSizePx: Float, supersample: Int = 3): Bitmap {
        val bigW = width * supersample
        val bigH = height * supersample
        val bigBmp = Bitmap.createBitmap(bigW, bigH, Bitmap.Config.ARGB_8888)
        val bigCanvas = Canvas(bigBmp)
        bigCanvas.drawColor(Color.BLACK)
        val tp = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = textSizePx * supersample
            this.typeface = typeface ?: Typeface.DEFAULT
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, tp, bigW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        layout.draw(bigCanvas)
        val result = Bitmap.createScaledBitmap(bigBmp, width, height, true)
        bigBmp.recycle()
        return result
    }

    // ---- Clock --------------------------------------------------------------------------------

    /**
     * Just the analog face's graphics - circle, 12/3/6/9 tick marks, hands - no text. Deliberately
     * doesn't draw hour numerals: twelve numbers around a 30px-radius circle reads as clutter, not
     * information, on real hardware. The caller draws the digital time/date with [Ssd1306.drawText]
     * afterward, in the space left of the face - see [clockDateAreaWidth].
     */
    fun renderClockFace(width: Int, height: Int): Bitmap {
        val (bmp, canvas) = blankCanvas(width, height)
        val radius = min(width, height) / 2f - 3f
        val cx = width - radius - 3f
        val cy = height / 2f

        canvas.drawCircle(cx, cy, radius, strokePaint(2f))

        if (radius >= 10f) {
            val tickPaint = strokePaint(2f)
            for (i in 0 until 4) {
                val angle = Math.toRadians((i * 90).toDouble())
                val ox = sin(angle).toFloat()
                val oy = -cos(angle).toFloat()
                canvas.drawLine(
                    cx + ox * radius * 0.78f, cy + oy * radius * 0.78f,
                    cx + ox * radius * 0.95f, cy + oy * radius * 0.95f,
                    tickPaint
                )
            }
        }

        val now = Calendar.getInstance()
        val hours = now.get(Calendar.HOUR)
        val minutes = now.get(Calendar.MINUTE)
        val seconds = now.get(Calendar.SECOND)

        fun hand(lengthRatio: Float, angleDeg: Double, strokeWidth: Float) {
            val angle = Math.toRadians(angleDeg)
            val hx = cx + (radius * lengthRatio * sin(angle)).toFloat()
            val hy = cy - (radius * lengthRatio * cos(angle)).toFloat()
            canvas.drawLine(cx, cy, hx, hy, strokePaint(strokeWidth))
        }
        hand(0.50f, (hours % 12) * 30.0 + minutes * 0.5, 3f)   // hour - thick, unambiguous
        hand(0.70f, minutes * 6.0 + seconds * 0.1, 2.5f)       // minute
        hand(0.85f, seconds * 6.0, 1.5f)                        // second - thin, least important

        return bmp
    }

    /** Panel width, in pixels, left of the clock face - where it's safe to draw date/time text
     * without overlapping [renderClockFace]'s circle. */
    fun clockDateAreaWidth(width: Int, height: Int): Int {
        val radius = min(width, height) / 2f - 3f
        val cx = width - radius - 3f
        return (cx - radius - 4f).toInt().coerceAtLeast(0)
    }

    // ---- Phone stats dashboard ---------------------------------------------------------------

    data class StatRow(val label: String, val pct: Int?)

    /** Battery %, battery temperature, RAM used/total, storage used/total, and this device's IP -
     * see the class doc for why these replace stats.py's CPU%/CPU-temp readings. Rows are dropped
     * (not shrunk) on shorter panels so nothing gets small enough to be illegible - just one
     * combined row on very small/narrow panels. Pure data, no drawing - see [renderStatsBars] for
     * the matching bar graphics and draw each row's label with [Ssd1306.drawText]. */
    fun computeStatsRows(width: Int, height: Int, context: Context): List<StatRow> {
        val battIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battPct = battIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        } ?: -1
        val battTempC = (battIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val ramTotalMb = memInfo.totalMem / (1024 * 1024)
        val ramUsedMb = ramTotalMb - memInfo.availMem / (1024 * 1024)
        val ramPct = if (ramTotalMb > 0) (ramUsedMb * 100 / ramTotalMb).toInt() else 0

        val stat = StatFs(context.filesDir.absolutePath)
        val totalBytes = stat.totalBytes
        val usedBytes = totalBytes - stat.availableBytes
        val storagePct = if (totalBytes > 0) (usedBytes * 100 / totalBytes).toInt() else 0
        val totalGb = totalBytes / (1024.0 * 1024 * 1024)
        val usedGb = usedBytes / (1024.0 * 1024 * 1024)

        val ip = wifiOrCellularIpAddress(context)

        return when {
            width < 90 || height < 28 ->
                listOf(StatRow("BAT ${battPct}% RAM ${ramPct}%", null))
            height >= 56 ->
                listOf(
                    StatRow("BAT ${battPct}% ${battTempC}C", battPct),
                    StatRow("RAM ${ramUsedMb}/${ramTotalMb}MB", ramPct),
                    StatRow(String.format(Locale.US, "ROM %.1f/%.1fGB", usedGb, totalGb), storagePct),
                    StatRow(ip, null)
                )
            else ->
                listOf(StatRow("BAT ${battPct}% RAM ${ramPct}%", null), StatRow(ip, null))
        }
    }

    /** Bar graphics matching [computeStatsRows]' [rows] (same order, one row-height band each) -
     * no text; skips the bar entirely for any row with a null pct (e.g. an IP-address row). */
    fun renderStatsBars(width: Int, height: Int, rows: List<StatRow>): Bitmap {
        val (bmp, canvas) = blankCanvas(width, height)
        val rowH = height.toFloat() / rows.size
        rows.forEachIndexed { i, row ->
            val pct = row.pct ?: return@forEachIndexed
            val top = i * rowH
            val barTop = top + 9f // below one line of 7px text + a 2px gap
            val barBottom = (top + rowH - 2f).coerceAtLeast(barTop + 3f)
            if (barBottom > height.toFloat()) return@forEachIndexed
            val barRect = RectF(2f, barTop, width - 2f, barBottom)
            canvas.drawRect(barRect, strokePaint(1.5f))
            val clamped = pct.coerceIn(0, 100)
            if (clamped > 0) {
                canvas.drawRect(
                    RectF(
                        barRect.left + 1.5f, barRect.top + 1.5f,
                        barRect.left + 1.5f + (barRect.width() - 3f) * clamped / 100f, barRect.bottom - 1.5f
                    ),
                    fillPaint()
                )
            }
        }
        return bmp
    }
}
