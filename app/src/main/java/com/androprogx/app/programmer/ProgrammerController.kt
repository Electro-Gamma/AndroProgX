/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.programmer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.provider.OpenableColumns
import com.androprogx.app.ch341.Ch341I2c
import com.androprogx.app.ch341.Ch341Spi
import com.androprogx.app.ch341.Ch341UsbDevice
import com.androprogx.app.display.OledCompositor
import com.androprogx.app.display.Ssd1306
import com.androprogx.app.eeprom.I2cEeprom
import com.androprogx.app.eeprom.SpiFlash
import com.androprogx.app.eeprom.SpiFlashDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProgrammerMode { I2C_EEPROM, SPI_FLASH, SSD1306_DISPLAY }

/**
 * Owns the CH341A (programmer-mode) USB connection for the "Programmer" tab and exposes one
 * suspend function per one-shot UI action, each hopping to [Dispatchers.IO] so a USB transfer
 * never blocks the UI thread - the same pattern [com.androprogx.app.BridgeService] uses for
 * the serial bridge, just for this separate, independent USB personality (see [Ch341UsbDevice]'s
 * class doc for why CH341A "programmer mode" needs its own connection instead of reusing
 * [com.androprogx.app.UsbSerialManager]).
 *
 * The clock/stats/animation screens are different from everything else here: they redraw forever
 * until stopped, so they run as a cancellable [Job] on [continuousScope] instead of a plain
 * suspend function - see [startClock]/[startStats]/[startAnimation]/[stopContinuous].
 */
class ProgrammerController(context: Context) {

    val usbDevice = Ch341UsbDevice(context)
    private var i2cBus: Ch341I2c? = null
    private var spiBus: Ch341Spi? = null
    // One SpiFlash instance per connection, not one per call: it carries the 4-byte-addressing
    // flag set by configureSpiAddressing(), which needs to survive across the separate
    // read/write/erase calls that follow it - a fresh SpiFlash(spiBus) per call would forget it.
    private var spiFlash: SpiFlash? = null
    private var display: Ssd1306? = null

    val isConnected: Boolean get() = usbDevice.isOpen

    fun findDevice(): UsbDevice? = usbDevice.findDevice()
    fun hasPermission(dev: UsbDevice): Boolean = usbDevice.hasPermission(dev)
    fun requestPermission(dev: UsbDevice, callback: (Boolean) -> Unit) = usbDevice.requestPermission(dev, callback)

    suspend fun connect(dev: UsbDevice, mode: ProgrammerMode): Boolean = withContext(Dispatchers.IO) {
        if (!usbDevice.open(dev)) return@withContext false
        val i2c = Ch341I2c(usbDevice)
        val spi = Ch341Spi(usbDevice)
        i2cBus = i2c
        spiBus = spi
        spiFlash = SpiFlash(spi)
        when (mode) {
            ProgrammerMode.SPI_FLASH -> spi.init()
            ProgrammerMode.I2C_EEPROM, ProgrammerMode.SSD1306_DISPLAY -> i2c.configSpeed(Ch341I2c.SPEED_400K)
        }
    }

    suspend fun disconnect(mode: ProgrammerMode) = withContext(Dispatchers.IO) {
        continuousJob?.cancelAndJoin() // wait for any running clock/stats/animation loop to fully stop first
        continuousJob = null
        stopI2cTcpServer()
        stopSpiTcpServer()
        if (mode == ProgrammerMode.SPI_FLASH) spiBus?.shutdown()
        usbDevice.close()
        i2cBus = null
        spiBus = null
        spiFlash = null
        display = null
    }

    // ---- I2C EEPROM (24Cxx) ------------------------------------------------

    suspend fun readEeprom(
        chip: I2cEeprom.Chip,
        onProgress: (done: Int, total: Int) -> Unit,
        onChunk: ((offset: Int, chunk: ByteArray) -> Unit)? = null
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val bus = i2cBus ?: return@withContext null
            I2cEeprom(bus).read(chip, 0, chip.totalBytes, onProgress, onChunk)
        }

    suspend fun writeEeprom(chip: I2cEeprom.Chip, data: ByteArray, onProgress: (done: Int, total: Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val bus = i2cBus ?: return@withContext false
            I2cEeprom(bus).write(chip, 0, data, onProgress)
        }

    /** Fills the whole chip with [fillByte] (0xFF by default) - see [I2cEeprom.erase]. */
    suspend fun eraseEeprom(
        chip: I2cEeprom.Chip,
        fillByte: Byte = 0xFF.toByte(),
        onProgress: (done: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val bus = i2cBus ?: return@withContext false
        I2cEeprom(bus).erase(chip, fillByte, onProgress)
    }

    /** Address-wraparound capacity probe restricted to [baseChip]'s address-byte family - see [I2cEeprom.detectSize]. */
    suspend fun detectEepromSize(baseChip: I2cEeprom.Chip): I2cEeprom.SizeDetectionResult? = withContext(Dispatchers.IO) {
        val bus = i2cBus ?: return@withContext null
        I2cEeprom(bus).detectSize(baseChip)
    }

    // ---- SPI NOR flash (25xx) ----------------------------------------------

    suspend fun readSpiJedecId(): SpiFlash.JedecId? = withContext(Dispatchers.IO) {
        spiFlash?.readJedecId()
    }

    /** Reads the JEDEC ID and looks it up in [SpiFlashDatabase] - see [SpiFlash.detectChip]. */
    suspend fun detectSpiChip(): SpiFlash.DetectedChip? = withContext(Dispatchers.IO) {
        spiFlash?.detectChip()
    }

    /**
     * Call once after picking/detecting a chip, before read/write/erase, so chips over 16MB
     * switch to 4-byte addressing first - see [SpiFlash.configureAddressing].
     */
    suspend fun configureSpiAddressing(sizeBytes: Long): Boolean = withContext(Dispatchers.IO) {
        spiFlash?.configureAddressing(sizeBytes) ?: false
    }

    suspend fun readSpiFlash(
        length: Int,
        onProgress: (done: Int, total: Int) -> Unit,
        onChunk: ((offset: Int, chunk: ByteArray) -> Unit)? = null
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            spiFlash?.read(0, length, onProgress = onProgress, onChunk = onChunk)
        }

    suspend fun eraseSpiFlashChip(): Boolean = withContext(Dispatchers.IO) {
        spiFlash?.eraseChip() ?: false
    }

    suspend fun writeSpiFlash(data: ByteArray, pageSize: Int, onProgress: (done: Int, total: Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            // eraseFirst=false: Write is now a pure page-program, matching having a dedicated
            // Erase button as its own explicit step rather than Write silently re-erasing the
            // covered sectors every time. Page program can only clear bits (1->0), never set them
            // back to 1, so writing over non-blank flash without erasing first will silently AND
            // the new data with whatever was already there - the target range needs to already be
            // blank (fresh chip, or Erase tapped first) for the write to actually take.
            spiFlash?.write(0, data, pageSize, eraseFirst = false, onProgress = onProgress) ?: false
        }

    // ---- SSD1306 OLED -------------------------------------------------------

    suspend fun initDisplay(address7: Int, width: Int, height: Int): Boolean = withContext(Dispatchers.IO) {
        val bus = i2cBus ?: return@withContext false
        val d = Ssd1306(bus, address7, width, height)
        val ok = d.init()
        if (ok) display = d
        ok
    }

    suspend fun displayClear(): Boolean = withContext(Dispatchers.IO) {
        val d = display ?: return@withContext false
        d.clear()
        d.flush()
    }

    suspend fun displayTestPattern(): Boolean = withContext(Dispatchers.IO) {
        val d = display ?: return@withContext false
        d.testPattern()
        d.flush()
    }

    /** Clears the panel and draws [text] with the built-in pixel font - see [Ssd1306.drawText]
     *  for the word-wrap/newline rules, or [displayCustomText] to use a loaded TTF/OTF instead. */
    suspend fun displayText(text: String): Boolean = withContext(Dispatchers.IO) {
        val d = display ?: return@withContext false
        d.clear()
        d.drawText(0, 0, text)
        d.flush()
    }

    /** Same as [displayText] but rendered with [customFontTypeface] via Android's own text
     *  layout/wrapping - only meaningful once [loadCustomFont] or [loadBundledFont] has loaded
     *  something. */
    suspend fun displayCustomText(text: String): Boolean = withContext(Dispatchers.IO) {
        val d = display ?: return@withContext false
        val frame = OledCompositor.renderText(d.width, d.height, text, customFontTypeface, textSizePx = d.height / 4f)
        d.clear()
        d.drawBitmap(frame, dither = false)
        val ok = d.flush()
        frame.recycle()
        ok
    }

    /** Scales/thresholds [bitmap] onto the panel and flushes it - see [Ssd1306.drawBitmap]. */
    suspend fun displayBitmap(bitmap: Bitmap, dither: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val d = display ?: return@withContext false
        d.drawBitmap(bitmap, dither = dither)
        d.flush()
    }

    // ---- Custom font (for Draw Text / the clock's date) ---------------------

    var customFontTypeface: Typeface? = null
        private set
    var customFontName: String? = null
        private set

    /** Loads [uri] as a [Typeface] - accepts any file the user picks rather than filtering by
     *  MIME type, since SAF's MIME sniffing for .ttf/.otf is inconsistent across devices/vendors.
     *  Returns false (and leaves any previously-loaded font in place) if it isn't a valid font. */
    fun loadCustomFont(context: Context, uri: Uri): Boolean {
        val typeface = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                Typeface.Builder(pfd.fileDescriptor).build()
            }
        } catch (e: Exception) {
            null
        }
        if (typeface == null) return false
        customFontTypeface = typeface
        customFontName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "font"
        return true
    }

    /** Loads one of the two fonts bundled in `assets/fonts/` (see [BUNDLED_FONTS]) - same effect
     *  as [loadCustomFont], just from the APK's own assets instead of a picked file. */
    fun loadBundledFont(context: Context, assetPath: String, displayName: String): Boolean {
        val typeface = try {
            Typeface.createFromAsset(context.assets, assetPath)
        } catch (e: Exception) {
            null
        }
        if (typeface == null) return false
        customFontTypeface = typeface
        customFontName = displayName
        return true
    }

    /** A font bundled in `assets/fonts/`, offered as a one-tap alternative to picking a file. */
    companion object {
        data class BundledFont(val assetPath: String, val displayName: String)

        val BUNDLED_FONTS = listOf(
            BundledFont("fonts/alagard.ttf", "Alagard"),
            BundledFont("fonts/PixelOperator.ttf", "PixelOperator")
        )
    }

    fun clearCustomFont() {
        customFontTypeface = null
        customFontName = null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    // ---- Clock / Stats / Animate - continuous redraw loops -------------------

    private val continuousScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var continuousJob: Job? = null

    val isContinuousRunning: Boolean get() = continuousJob?.isActive == true

    /** Stops whichever of [startClock]/[startStats]/[startAnimation] is currently running, if any. */
    fun stopContinuous() {
        continuousJob?.cancel()
        continuousJob = null
    }

    /** Redraws an analog clock (circle + ticks + hands) plus a digital HH:mm/date readout once a
     *  second, until [stopContinuous] is called or a write fails. If [customFontTypeface] is
     *  loaded, the time/date text is rendered with it (via [OledCompositor.renderText]'s
     *  supersampling, so it doesn't garble); otherwise it's drawn with [Ssd1306.drawText]'s
     *  built-in pixel font, which is immune to garbling by construction. Either way the face
     *  graphics are drawn first and the text is merged on top with [Ssd1306.drawBitmapOverlay] /
     *  [Ssd1306.drawText] rather than replacing the frame, so the face is never erased.
     *  [onStopped] fires exactly once, off the main thread, whenever the loop ends for any reason. */
    fun startClock(intervalMs: Long = 1000L, onStopped: (error: String?) -> Unit = {}) {
        val d = display
        if (d == null) {
            onStopped("Connect in SSD1306 Display mode first")
            return
        }
        stopContinuous()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEE dd/MM", Locale.getDefault())
        continuousJob = continuousScope.launch {
            var error: String? = null
            try {
                while (isActive) {
                    val face = OledCompositor.renderClockFace(d.width, d.height)
                    d.clear()
                    d.drawBitmap(face, dither = false)
                    face.recycle()

                    val dateAreaWidth = OledCompositor.clockDateAreaWidth(d.width, d.height)
                    val now = Date()
                    val hm = timeFormat.format(now)
                    val dayDate = dateFormat.format(now)
                    val font = customFontTypeface

                    if (font != null && dateAreaWidth >= 18) {
                        if (d.height >= 44) {
                            val timeH = (d.height * 0.42f).toInt().coerceAtLeast(8)
                            val timeBmp = OledCompositor.renderText(dateAreaWidth, timeH, hm, font, textSizePx = timeH * 0.85f)
                            d.drawBitmapOverlay(timeBmp, 1, 0)
                            timeBmp.recycle()

                            val dateH = (d.height * 0.20f).toInt().coerceAtLeast(7)
                            val dateBmp = OledCompositor.renderText(dateAreaWidth, dateH, dayDate, font, textSizePx = dateH * 0.85f)
                            d.drawBitmapOverlay(dateBmp, 1, timeH + 4)
                            dateBmp.recycle()
                        } else {
                            val lineH = d.height.coerceAtLeast(8)
                            val bmp = OledCompositor.renderText(dateAreaWidth, lineH, dayDate, font, textSizePx = lineH * 0.75f)
                            d.drawBitmapOverlay(bmp, 1, 0)
                            bmp.recycle()
                        }
                    } else {
                        if (dateAreaWidth >= hm.length * 6) {
                            d.drawText(1, 0, hm, maxWidth = dateAreaWidth)
                        }
                        if (d.height >= 16 && dateAreaWidth >= dayDate.length * 6) {
                            d.drawText(1, 8, dayDate, maxWidth = dateAreaWidth)
                        }
                    }

                    val ok = d.flush()
                    if (!ok) {
                        error = "Clock: display write failed"
                        break
                    }
                    delay(intervalMs)
                }
            } finally {
                onStopped(error)
            }
        }
    }

    /** Redraws a battery/RAM/storage/IP dashboard once a second - same lifecycle as [startClock],
     *  and the same graphics-via-Canvas/text-via-[Ssd1306.drawText] split. */
    fun startStats(context: Context, intervalMs: Long = 1000L, onStopped: (error: String?) -> Unit = {}) {
        val d = display
        if (d == null) {
            onStopped("Connect in SSD1306 Display mode first")
            return
        }
        stopContinuous()
        continuousJob = continuousScope.launch {
            var error: String? = null
            try {
                while (isActive) {
                    val rows = OledCompositor.computeStatsRows(d.width, d.height, context)
                    val bars = OledCompositor.renderStatsBars(d.width, d.height, rows)
                    d.clear()
                    d.drawBitmap(bars, dither = false)
                    bars.recycle()

                    val rowH = d.height / rows.size
                    rows.forEachIndexed { i, row -> d.drawText(2, i * rowH, row.label, maxWidth = d.width - 2) }

                    val ok = d.flush()
                    if (!ok) {
                        error = "Stats: display write failed"
                        break
                    }
                    delay(intervalMs)
                }
            } finally {
                onStopped(error)
            }
        }
    }

    /** Loops through [frames] (already-decoded, in display order), [frameDelayMs] apart, until
     *  [stopContinuous] is called or a write fails - the same idea as `pbm_gif_all.py`'s
     *  folder-of-PBM-frames loop, just from Android-picked image files instead of a PBM folder. */
    fun startAnimation(frames: List<Bitmap>, frameDelayMs: Long = 100L, onStopped: (error: String?) -> Unit = {}) {
        val d = display
        if (d == null) {
            onStopped("Connect in SSD1306 Display mode first")
            return
        }
        if (frames.isEmpty()) {
            onStopped("No frames to animate")
            return
        }
        stopContinuous()
        continuousJob = continuousScope.launch {
            var error: String? = null
            try {
                outer@ while (isActive) {
                    for (frame in frames) {
                        if (!isActive) break@outer
                        d.clear()
                        d.drawBitmap(frame, dither = false)
                        val ok = d.flush()
                        if (!ok) {
                            error = "Animation: display write failed"
                            break@outer
                        }
                        delay(frameDelayMs)
                    }
                }
            } finally {
                onStopped(error)
            }
        }
    }

    // ---- I2C-over-TCP -----------------------------------------------------

    private var i2cTcpServer: I2cTcpServer? = null

    val isI2cTcpServerRunning: Boolean get() = i2cTcpServer?.isRunning == true

    /**
     * Starts forwarding I2C transactions from network clients to whichever I2C bus is currently
     * connected - see [I2cTcpServer]'s class doc for the wire protocol and a tested client.
     * Only meaningful in [ProgrammerMode.I2C_EEPROM]
     * or [ProgrammerMode.SSD1306_DISPLAY] (those are the modes that configure the shared CH341A
     * clock for I2C speed rather than SPI speed - see [connect]).
     */
    fun startI2cTcpServer(
        port: Int,
        onClientConnected: (String) -> Unit,
        onClientDisconnected: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        stopI2cTcpServer()
        val server = I2cTcpServer { i2cBus }
        server.onClientConnected = onClientConnected
        server.onClientDisconnected = onClientDisconnected
        server.onError = onError
        server.start(port)
        i2cTcpServer = server
    }

    fun stopI2cTcpServer() {
        i2cTcpServer?.stop()
        i2cTcpServer = null
    }

    // ---- SPI-over-TCP -------------------------------------------------------

    private var spiTcpServer: SpiTcpServer? = null

    val isSpiTcpServerRunning: Boolean get() = spiTcpServer?.isRunning == true

    /**
     * Starts forwarding SPI transactions from network clients to whichever SPI bus is currently
     * connected - see [SpiTcpServer]'s class doc for the wire protocol.
     *
     * Unlike [startI2cTcpServer], this does its own [Ch341Spi.init] right here rather than relying
     * on [connect] having already done it: [connect] only calls [Ch341Spi.init] for
     * [ProgrammerMode.SPI_FLASH], but this page's own "TCP Server" mode always connects as
     * [ProgrammerMode.I2C_EEPROM] (see [com.androprogx.app.MainActivity]'s `UiPage.TCP_SERVER`) so
     * that I2C-over-TCP works out of the box - which would otherwise leave [spiBus] holding a
     * never-initialized [Ch341Spi] whenever someone starts this server from that page. Calling
     * [Ch341Spi.init] again when it's already been done (e.g. because the app happened to connect
     * in [ProgrammerMode.SPI_FLASH] instead) is harmless - it's just the CS/SCK/MOSI pin setup and
     * a stream-speed write, not a stateful handshake - so doing it unconditionally here is simpler
     * than tracking which case applies.
     */
    fun startSpiTcpServer(
        port: Int,
        onClientConnected: (String) -> Unit,
        onClientDisconnected: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        stopSpiTcpServer()
        spiBus?.init()
        val server = SpiTcpServer { spiBus }
        server.onClientConnected = onClientConnected
        server.onClientDisconnected = onClientDisconnected
        server.onError = onError
        server.start(port)
        spiTcpServer = server
    }

    fun stopSpiTcpServer() {
        spiTcpServer?.stop()
        spiTcpServer = null
    }
}
