/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.display

import com.androprogx.app.ch341.Ch341I2c

/**
 * Driver for SSD1306-based I2C OLED panels (128x64, 128x32, 96x16, and 64x32 - the common
 * "0.96 inch" / "0.91 inch" / "0.69 inch" / "0.49 inch" modules), talking over a [Ch341I2c] bus
 * via the CH341A. The init sequence and command set are the standard ones from Solomon Systech's
 * SSD1306 datasheet (identical across every SSD1306 library, and cross-checked here
 * command-by-command against the classic ioctl/SMBus-based Python reference driver this was
 * ported from - the init order/values, clear(), and framebuffer bit-packing all already matched
 * that reference exactly).
 *
 * The per-model COM-pin/contrast table in [panelConfig], the 64-wide column-address offset in
 * [flush], and the hardware scroll commands were pulled from Adafruit's official SSD1306 Arduino
 * library (`Adafruit_SSD1306.cpp`) - which also caught a real bug in this file: contrast used to
 * be bucketed by height alone, which silently gave 96x16 panels 128x32's contrast value (0x8F)
 * instead of their own (0xAF internal / 0x10 external VCC). 64x32 support is new, added from the
 * same table.
 *
 * The 5x7 text font in [FONT_5X7] is Adafruit_GFX's classic `glcdfont.c` "Standard ASCII 5x7
 * font" (the one nearly every Adafruit display library ships), covering printable ASCII
 * 0x20-0x7E - space through `~` - with real upper- and lowercase glyphs. It replaces this file's
 * previous hand-rolled, uppercase/digits-only subset. Both Adafruit libraries are BSD-licensed
 * (Copyright (c) 2012 Adafruit Industries); see their bundled `license.txt`.
 *
 * I2C framing: every write is a `[control byte, payload bytes...]` pair with no register
 * address - control byte 0x00 means "payload is command bytes", 0x40 means "payload is
 * display-RAM data". This class keeps an in-memory framebuffer ([fb]) and only touches the
 * display when [flush] is called, so callers can draw several things before paying for the I2C
 * traffic - [init] is the one exception, since it also flushes a blanked buffer so the panel
 * starts from a clean screen instead of showing stale/power-on GDDRAM noise until the caller's
 * next draw.
 *
 * Pass [externalVcc] = true only if your module's datasheet explicitly calls for an external
 * charge-pump supply - the vast majority of these boards (anything without a dedicated VCC pin
 * note) use the internal charge pump, which is the default here and in the Python reference.
 */
class Ssd1306(
    private val i2c: Ch341I2c,
    private val address7: Int = DEFAULT_ADDRESS,
    val width: Int = 128,
    val height: Int = 64,
    private val externalVcc: Boolean = false
) {
    private val pages = height / 8
    val fb = ByteArray(width * pages)

    private fun cmd(vararg bytes: Int): Boolean =
        i2c.writeRaw(address7, byteArrayOf(0x00.toByte()) + bytes.map { it.toByte() }.toByteArray())

    /**
     * COM-pin configuration byte and default contrast for this exact (width, height) pair - both
     * are per-model, not just per-height. Cross-checked against Adafruit's official SSD1306
     * Arduino library's `begin()`, which keys them off (WIDTH, HEIGHT) together rather than
     * height alone - see the class doc for the bug that bucketing by height alone caused for
     * 96x16 panels. Falls back to the mild 128x32 values (which are also the pre-branch defaults
     * in Adafruit's own begin()) for any other size.
     */
    private fun panelConfig(): Pair<Int, Int> = when {
        width == 128 && height == 64 -> 0x12 to (if (externalVcc) 0x9F else 0xCF)
        width == 96 && height == 16 -> 0x02 to (if (externalVcc) 0x10 else 0xAF)
        width == 64 && height == 32 -> 0x12 to (if (externalVcc) 0x10 else 0xCF)
        else -> 0x02 to 0x8F // 128x32, and the safe default for anything unrecognized
    }

    /** Contrast value for this panel's size/VCC combination - shared by [init] and [setDim]. */
    private fun normalContrast(): Int = panelConfig().second

    /**
     * Runs the standard SSD1306 power-on init sequence, then blanks and flushes the display so
     * it starts from a clean screen rather than whatever was left in GDDRAM. Call once before
     * drawing anything.
     */
    fun init(): Boolean {
        val (comPins, contrast) = panelConfig()
        // 96x16 modules commonly ship with a slower clock-divide default than the others - see
        // the Python reference this was originally cross-checked against.
        val clockDiv = if (height <= 16) 0x60 else 0x80
        val chargePump = if (externalVcc) 0x10 else 0x14
        val precharge = if (externalVcc) 0x22 else 0xF1
        val ok = cmd(Cmd.DISPLAY_OFF) &&
            cmd(Cmd.SET_DISPLAY_CLOCK_DIV, clockDiv) &&
            cmd(Cmd.SET_MULTIPLEX, height - 1) &&
            cmd(Cmd.SET_DISPLAY_OFFSET, 0x00) &&
            cmd(Cmd.SET_START_LINE) && // start line = 0
            cmd(Cmd.CHARGE_PUMP, chargePump) &&
            cmd(Cmd.MEMORY_MODE, 0x00) && // horizontal addressing mode
            cmd(Cmd.SEGREMAP) &&
            cmd(Cmd.COMSCANDEC) && // COM scan direction, remapped
            cmd(Cmd.SET_COM_PINS, comPins) &&
            cmd(Cmd.SET_CONTRAST, contrast) &&
            cmd(Cmd.SET_PRECHARGE, precharge) &&
            cmd(Cmd.SET_VCOM_DETECT, 0x40) && // VCOMH deselect level
            cmd(Cmd.DISPLAY_ALL_ON_RESUME) && // resume RAM content display
            cmd(Cmd.NORMAL_DISPLAY) && // normal (not inverted)
            cmd(Cmd.DISPLAY_ON)
        clear()
        return ok && flush()
    }

    fun setContrast(value: Int): Boolean = cmd(Cmd.SET_CONTRAST, value and 0xFF)

    /** Dims the display to minimum contrast if [dim] is true, or restores this panel's normal
     * contrast if false - mirrors the classic Python driver's dim(). */
    fun setDim(dim: Boolean): Boolean = cmd(Cmd.SET_CONTRAST, if (dim) 0x00 else normalContrast())

    fun setInverted(inverted: Boolean): Boolean = cmd(if (inverted) Cmd.INVERT_DISPLAY else Cmd.NORMAL_DISPLAY)

    fun setDisplayOn(on: Boolean): Boolean = cmd(if (on) Cmd.DISPLAY_ON else Cmd.DISPLAY_OFF)

    fun clear() = fb.fill(0.toByte())

    fun setPixel(x: Int, y: Int, on: Boolean) {
        if (x !in 0 until width || y !in 0 until height) return
        val idx = x + (y / 8) * width
        val bit = 1 shl (y % 8)
        fb[idx] = if (on) (fb[idx].toInt() or bit).toByte() else (fb[idx].toInt() and bit.inv()).toByte()
    }

    /**
     * Word-wraps [text] to fit within [maxWidthPx] at [charAdvance] px/character, honoring
     * explicit '\n' line breaks. A single word longer than one line is hard-broken rather than
     * overflowing. Pure layout helper for [drawText] - has no display side effects of its own.
     */
    private fun wrapLines(text: String, maxWidthPx: Int, charAdvance: Int): List<String> {
        val maxChars = maxOf(1, maxWidthPx / charAdvance)
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            var current = StringBuilder()
            for (word in paragraph.split(" ")) {
                if (word.length > maxChars) {
                    if (current.isNotEmpty()) {
                        lines.add(current.toString())
                        current = StringBuilder()
                    }
                    var remaining = word
                    while (remaining.length > maxChars) {
                        lines.add(remaining.substring(0, maxChars))
                        remaining = remaining.substring(maxChars)
                    }
                    current.append(remaining)
                    continue
                }
                val candidateLength = if (current.isEmpty()) word.length else current.length + 1 + word.length
                if (candidateLength > maxChars) {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                }
            }
            lines.add(current.toString())
        }
        return lines
    }

    /**
     * Draws 5x7 text with 1px spacing between glyphs (6px advance per character) and 8px line
     * height, top-left at ([x], [y]). Word-wraps to stay within [maxWidth] px (defaults to the
     * rest of the panel to the right of [x]) and honors explicit '\n' line breaks; case is
     * preserved now that [FONT_5X7] has real upper- and lowercase glyphs. Characters outside
     * printable ASCII (and anything past the bottom edge) fall back to blank, same as any other
     * out-of-bounds draw - see [setPixel].
     */
    fun drawText(x: Int, y: Int, text: String, maxWidth: Int = width - x) {
        val charAdvance = 6
        val lineHeight = 8
        val maxWidthPx = maxOf(charAdvance, maxWidth)
        var cy = y
        for (line in wrapLines(text, maxWidthPx, charAdvance)) {
            var cx = x
            for (ch in line) {
                val glyph = FONT_5X7[ch] ?: FONT_5X7[' ']!!
                for (col in 0 until 5) {
                    val bits = glyph[col]
                    for (row in 0 until 7) {
                        if ((bits shr row) and 1 == 1) setPixel(cx + col, cy + row, true)
                    }
                }
                cx += charAdvance
            }
            cy += lineHeight
        }
    }

    /** Fills the framebuffer with a coarse checkerboard - useful as a hardware sanity check. */
    fun testPattern() {
        for (y in 0 until height) {
            for (x in 0 until width) {
                setPixel(x, y, ((x / 4) + (y / 4)) % 2 == 0)
            }
        }
    }

    /**
     * Sets the framebuffer from [bitmap] (scaled to the panel's resolution first if it isn't
     * already that size). When [dither] is true (the default), applies Floyd-Steinberg error
     * diffusion while thresholding against [threshold] (0-255, ITU-R BT.601 luminance weights) -
     * the same algorithm and luminance weights PIL's `Image.convert('1')` uses by default, so
     * this matches what the classic Python driver's example (open an image, `.convert('1')`,
     * then display()) actually puts on screen, instead of the harsher look of a flat per-pixel
     * threshold. Pass `dither = false` for line art / already-1-bit images, where dithering just
     * adds noise.
     */
    fun drawBitmap(bitmap: android.graphics.Bitmap, threshold: Int = 128, dither: Boolean = true) {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        if (dither) {
            val lum = FloatArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = scaled.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    lum[y * width + x] = (r * 299 + g * 587 + b * 114) / 1000f
                }
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val old = lum[idx]
                    val on = old >= threshold
                    setPixel(x, y, on)
                    val error = old - (if (on) 255f else 0f)
                    if (x + 1 < width) lum[idx + 1] += error * 7f / 16f
                    if (y + 1 < height) {
                        if (x > 0) lum[idx + width - 1] += error * 3f / 16f
                        lum[idx + width] += error * 5f / 16f
                        if (x + 1 < width) lum[idx + width + 1] += error * 1f / 16f
                    }
                }
            }
        } else {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = scaled.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val luminance = (r * 299 + g * 587 + b * 114) / 1000
                    setPixel(x, y, luminance >= threshold)
                }
            }
        }
        if (scaled !== bitmap) scaled.recycle()
    }

    /**
     * Merges [bitmap] into the framebuffer at ([x], [y]) instead of replacing it: only ever turns
     * a pixel ON where the source is bright at or above [threshold]; a dark source pixel leaves
     * whatever was already there alone, rather than clearing it the way [drawBitmap] would.
     * [bitmap] is drawn at its own size, unscaled - size it to the area you want first.
     *
     * This is what lets a custom-font clock overlay its time/date text onto the already-drawn
     * analog face: [drawBitmap] can't be called a second time for that (its black background
     * would erase the face wherever the two overlap), but this can.
     */
    fun drawBitmapOverlay(bitmap: android.graphics.Bitmap, x: Int, y: Int, threshold: Int = 128) {
        for (by in 0 until bitmap.height) {
            val py = y + by
            if (py !in 0 until height) continue
            for (bx in 0 until bitmap.width) {
                val px = x + bx
                if (px !in 0 until width) continue
                val pixel = bitmap.getPixel(bx, by)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (r * 299 + g * 587 + b * 114) / 1000
                if (luminance >= threshold) setPixel(px, py, true)
            }
        }
    }

    /**
     * Sends the whole framebuffer to the panel's GDDRAM, [FLUSH_CHUNK_BYTES] data bytes per I2C
     * transaction. This used to send the entire buffer (up to 1024 bytes for a 128x64 panel) as
     * one transaction, which is not actually reliable on real CH341A hardware - see
     * [com.androprogx.app.eeprom.I2cEeprom]'s `stepSize`, which documents the same 16-byte
     * per-transaction cap for the chip's I2C block reads/writes and has always respected it.
     * Only the tiny few-byte writes [cmd] sends ever worked; this is what actually didn't.
     */
    fun flush(): Boolean {
        // 64-wide panels (e.g. 64x32) are physically wired to only the middle 64 columns of the
        // SSD1306's 128-column-wide GDDRAM, so their column range needs a +0x20 offset - a real
        // hardware quirk pulled from Adafruit_SSD1306.cpp's display(), not something a
        // from-scratch driver would otherwise know to do.
        val colOffset = if (width == 64) 0x20 else 0x00
        if (!cmd(Cmd.COLUMN_ADDR, colOffset, colOffset + width - 1)) return false // column address range
        if (!cmd(Cmd.PAGE_ADDR, 0, pages - 1)) return false // page address range
        var offset = 0
        while (offset < fb.size) {
            val n = minOf(FLUSH_CHUNK_BYTES, fb.size - offset)
            val chunk = byteArrayOf(0x40.toByte()) + fb.copyOfRange(offset, offset + n)
            if (!i2c.writeRaw(address7, chunk)) return false
            offset += n
        }
        return true
    }

    // ---- Scrolling --------------------------------------------------------------------------
    // Ported from Adafruit_SSD1306.cpp's startscrollright/startscrollleft/startscrolldiagright/
    // startscrolldiagleft/stopscroll. Once activated, scrolling runs entirely in the SSD1306's
    // own hardware with no further I2C traffic - call [stopScroll] to stop it. [startPage] and
    // [stopPage] are 0-based, inclusive GDDRAM page indices (default: the whole framebuffer).

    /** Starts a continuous right-horizontal hardware scroll of GDDRAM pages [startPage]..[stopPage]. */
    fun startScrollRight(startPage: Int = 0, stopPage: Int = pages - 1): Boolean =
        cmd(Cmd.RIGHT_HORIZONTAL_SCROLL, 0x00, startPage, 0x00, stopPage, 0x00, 0xFF) &&
            cmd(Cmd.ACTIVATE_SCROLL)

    /** Same as [startScrollRight], scrolling the other direction. */
    fun startScrollLeft(startPage: Int = 0, stopPage: Int = pages - 1): Boolean =
        cmd(Cmd.LEFT_HORIZONTAL_SCROLL, 0x00, startPage, 0x00, stopPage, 0x00, 0xFF) &&
            cmd(Cmd.ACTIVATE_SCROLL)

    /**
     * Starts a combined vertical+horizontal ("diagonal") scroll, scrolling right, of GDDRAM
     * pages [startPage]..[stopPage]. The vertical component always sweeps the panel's full
     * [height], per the SSD1306's SET_VERTICAL_SCROLL_AREA command.
     */
    fun startScrollDiagRight(startPage: Int = 0, stopPage: Int = pages - 1): Boolean =
        cmd(Cmd.SET_VERTICAL_SCROLL_AREA, 0x00, height) &&
            cmd(Cmd.VERTICAL_AND_RIGHT_HORIZONTAL_SCROLL, 0x00, startPage, 0x00, stopPage, 0x01) &&
            cmd(Cmd.ACTIVATE_SCROLL)

    /** Same as [startScrollDiagRight], scrolling the other direction. */
    fun startScrollDiagLeft(startPage: Int = 0, stopPage: Int = pages - 1): Boolean =
        cmd(Cmd.SET_VERTICAL_SCROLL_AREA, 0x00, height) &&
            cmd(Cmd.VERTICAL_AND_LEFT_HORIZONTAL_SCROLL, 0x00, startPage, 0x00, stopPage, 0x01) &&
            cmd(Cmd.ACTIVATE_SCROLL)

    /**
     * Stops any scroll started by [startScrollRight]/[startScrollLeft]/[startScrollDiagRight]/
     * [startScrollDiagLeft]. Framebuffer contents are unaffected; call [flush] afterward if you
     * want to draw a fresh frame.
     */
    fun stopScroll(): Boolean = cmd(Cmd.DEACTIVATE_SCROLL)

    companion object {
        // 0x3C is the near-universal default on cheap SSD1306 breakout boards regardless of
        // size. (Adafruit's own boards default to 0x3C only for 32-pixel-tall panels and 0x3D
        // for the rest - override [address7] explicitly if a scan shows your module differs.)
        const val DEFAULT_ADDRESS = 0x3C

        /** Matches I2cEeprom.stepSize - the CH341A's real per-transaction cap for I2C block ops. */
        private const val FLUSH_CHUNK_BYTES = 16

        /**
         * Named SSD1306 command opcodes (see the datasheet, or Adafruit_SSD1306.h's SSD1306_*
         * defines, which these names/values are taken from) - so call sites above read like the
         * datasheet instead of bare hex.
         */
        private object Cmd {
            const val SET_CONTRAST = 0x81
            const val CHARGE_PUMP = 0x8D
            const val MEMORY_MODE = 0x20
            const val COLUMN_ADDR = 0x21
            const val PAGE_ADDR = 0x22
            const val SEGREMAP = 0xA1 // SEGREMAP (0xA0) | 0x1
            const val SET_VERTICAL_SCROLL_AREA = 0xA3
            const val DISPLAY_ALL_ON_RESUME = 0xA4
            const val NORMAL_DISPLAY = 0xA6
            const val INVERT_DISPLAY = 0xA7
            const val SET_MULTIPLEX = 0xA8
            const val DISPLAY_OFF = 0xAE
            const val DISPLAY_ON = 0xAF
            const val RIGHT_HORIZONTAL_SCROLL = 0x26
            const val LEFT_HORIZONTAL_SCROLL = 0x27
            const val VERTICAL_AND_RIGHT_HORIZONTAL_SCROLL = 0x29
            const val VERTICAL_AND_LEFT_HORIZONTAL_SCROLL = 0x2A
            const val DEACTIVATE_SCROLL = 0x2E
            const val ACTIVATE_SCROLL = 0x2F
            const val COMSCANDEC = 0xC8
            const val SET_DISPLAY_OFFSET = 0xD3
            const val SET_DISPLAY_CLOCK_DIV = 0xD5
            const val SET_PRECHARGE = 0xD9
            const val SET_COM_PINS = 0xDA
            const val SET_VCOM_DETECT = 0xDB
            const val SET_START_LINE = 0x40
        }

        /**
         * Adafruit_GFX's classic `glcdfont.c` "Standard ASCII 5x7 font" (BSD-licensed,
         * Copyright (c) 2012 Adafruit Industries), covering printable ASCII 0x20-0x7E with real
         * upper- and lowercase glyphs - 5 column bytes per glyph, bit0 = top row .. bit6 =
         * bottom row of a 5x7 cell. Replaces this file's earlier hand-rolled,
         * uppercase/digits-only subset.
         */
        private val FONT_5X7: Map<Char, IntArray> = mapOf(
            ' ' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00),
            '!' to intArrayOf(0x00, 0x00, 0x5F, 0x00, 0x00),
            '"' to intArrayOf(0x00, 0x07, 0x00, 0x07, 0x00),
            '#' to intArrayOf(0x14, 0x7F, 0x14, 0x7F, 0x14),
            '$' to intArrayOf(0x24, 0x2A, 0x7F, 0x2A, 0x12),
            '%' to intArrayOf(0x23, 0x13, 0x08, 0x64, 0x62),
            '&' to intArrayOf(0x36, 0x49, 0x56, 0x20, 0x50),
            '\'' to intArrayOf(0x00, 0x08, 0x07, 0x03, 0x00),
            '(' to intArrayOf(0x00, 0x1C, 0x22, 0x41, 0x00),
            ')' to intArrayOf(0x00, 0x41, 0x22, 0x1C, 0x00),
            '*' to intArrayOf(0x2A, 0x1C, 0x7F, 0x1C, 0x2A),
            '+' to intArrayOf(0x08, 0x08, 0x3E, 0x08, 0x08),
            ',' to intArrayOf(0x00, 0x80, 0x70, 0x30, 0x00),
            '-' to intArrayOf(0x08, 0x08, 0x08, 0x08, 0x08),
            '.' to intArrayOf(0x00, 0x00, 0x60, 0x60, 0x00),
            '/' to intArrayOf(0x20, 0x10, 0x08, 0x04, 0x02),
            '0' to intArrayOf(0x3E, 0x51, 0x49, 0x45, 0x3E),
            '1' to intArrayOf(0x00, 0x42, 0x7F, 0x40, 0x00),
            '2' to intArrayOf(0x72, 0x49, 0x49, 0x49, 0x46),
            '3' to intArrayOf(0x21, 0x41, 0x49, 0x4D, 0x33),
            '4' to intArrayOf(0x18, 0x14, 0x12, 0x7F, 0x10),
            '5' to intArrayOf(0x27, 0x45, 0x45, 0x45, 0x39),
            '6' to intArrayOf(0x3C, 0x4A, 0x49, 0x49, 0x31),
            '7' to intArrayOf(0x41, 0x21, 0x11, 0x09, 0x07),
            '8' to intArrayOf(0x36, 0x49, 0x49, 0x49, 0x36),
            '9' to intArrayOf(0x46, 0x49, 0x49, 0x29, 0x1E),
            ':' to intArrayOf(0x00, 0x00, 0x14, 0x00, 0x00),
            ';' to intArrayOf(0x00, 0x40, 0x34, 0x00, 0x00),
            '<' to intArrayOf(0x00, 0x08, 0x14, 0x22, 0x41),
            '=' to intArrayOf(0x14, 0x14, 0x14, 0x14, 0x14),
            '>' to intArrayOf(0x00, 0x41, 0x22, 0x14, 0x08),
            '?' to intArrayOf(0x02, 0x01, 0x59, 0x09, 0x06),
            '@' to intArrayOf(0x3E, 0x41, 0x5D, 0x59, 0x4E),
            'A' to intArrayOf(0x7C, 0x12, 0x11, 0x12, 0x7C),
            'B' to intArrayOf(0x7F, 0x49, 0x49, 0x49, 0x36),
            'C' to intArrayOf(0x3E, 0x41, 0x41, 0x41, 0x22),
            'D' to intArrayOf(0x7F, 0x41, 0x41, 0x41, 0x3E),
            'E' to intArrayOf(0x7F, 0x49, 0x49, 0x49, 0x41),
            'F' to intArrayOf(0x7F, 0x09, 0x09, 0x09, 0x01),
            'G' to intArrayOf(0x3E, 0x41, 0x41, 0x51, 0x73),
            'H' to intArrayOf(0x7F, 0x08, 0x08, 0x08, 0x7F),
            'I' to intArrayOf(0x00, 0x41, 0x7F, 0x41, 0x00),
            'J' to intArrayOf(0x20, 0x40, 0x41, 0x3F, 0x01),
            'K' to intArrayOf(0x7F, 0x08, 0x14, 0x22, 0x41),
            'L' to intArrayOf(0x7F, 0x40, 0x40, 0x40, 0x40),
            'M' to intArrayOf(0x7F, 0x02, 0x1C, 0x02, 0x7F),
            'N' to intArrayOf(0x7F, 0x04, 0x08, 0x10, 0x7F),
            'O' to intArrayOf(0x3E, 0x41, 0x41, 0x41, 0x3E),
            'P' to intArrayOf(0x7F, 0x09, 0x09, 0x09, 0x06),
            'Q' to intArrayOf(0x3E, 0x41, 0x51, 0x21, 0x5E),
            'R' to intArrayOf(0x7F, 0x09, 0x19, 0x29, 0x46),
            'S' to intArrayOf(0x26, 0x49, 0x49, 0x49, 0x32),
            'T' to intArrayOf(0x03, 0x01, 0x7F, 0x01, 0x03),
            'U' to intArrayOf(0x3F, 0x40, 0x40, 0x40, 0x3F),
            'V' to intArrayOf(0x1F, 0x20, 0x40, 0x20, 0x1F),
            'W' to intArrayOf(0x3F, 0x40, 0x38, 0x40, 0x3F),
            'X' to intArrayOf(0x63, 0x14, 0x08, 0x14, 0x63),
            'Y' to intArrayOf(0x03, 0x04, 0x78, 0x04, 0x03),
            'Z' to intArrayOf(0x61, 0x59, 0x49, 0x4D, 0x43),
            '[' to intArrayOf(0x00, 0x7F, 0x41, 0x41, 0x41),
            '\\' to intArrayOf(0x02, 0x04, 0x08, 0x10, 0x20),
            ']' to intArrayOf(0x00, 0x41, 0x41, 0x41, 0x7F),
            '^' to intArrayOf(0x04, 0x02, 0x01, 0x02, 0x04),
            '_' to intArrayOf(0x40, 0x40, 0x40, 0x40, 0x40),
            '`' to intArrayOf(0x00, 0x03, 0x07, 0x08, 0x00),
            'a' to intArrayOf(0x20, 0x54, 0x54, 0x78, 0x40),
            'b' to intArrayOf(0x7F, 0x28, 0x44, 0x44, 0x38),
            'c' to intArrayOf(0x38, 0x44, 0x44, 0x44, 0x28),
            'd' to intArrayOf(0x38, 0x44, 0x44, 0x28, 0x7F),
            'e' to intArrayOf(0x38, 0x54, 0x54, 0x54, 0x18),
            'f' to intArrayOf(0x00, 0x08, 0x7E, 0x09, 0x02),
            'g' to intArrayOf(0x18, 0xA4, 0xA4, 0x9C, 0x78),
            'h' to intArrayOf(0x7F, 0x08, 0x04, 0x04, 0x78),
            'i' to intArrayOf(0x00, 0x44, 0x7D, 0x40, 0x00),
            'j' to intArrayOf(0x20, 0x40, 0x40, 0x3D, 0x00),
            'k' to intArrayOf(0x7F, 0x10, 0x28, 0x44, 0x00),
            'l' to intArrayOf(0x00, 0x41, 0x7F, 0x40, 0x00),
            'm' to intArrayOf(0x7C, 0x04, 0x78, 0x04, 0x78),
            'n' to intArrayOf(0x7C, 0x08, 0x04, 0x04, 0x78),
            'o' to intArrayOf(0x38, 0x44, 0x44, 0x44, 0x38),
            'p' to intArrayOf(0xFC, 0x18, 0x24, 0x24, 0x18),
            'q' to intArrayOf(0x18, 0x24, 0x24, 0x18, 0xFC),
            'r' to intArrayOf(0x7C, 0x08, 0x04, 0x04, 0x08),
            's' to intArrayOf(0x48, 0x54, 0x54, 0x54, 0x24),
            't' to intArrayOf(0x04, 0x04, 0x3F, 0x44, 0x24),
            'u' to intArrayOf(0x3C, 0x40, 0x40, 0x20, 0x7C),
            'v' to intArrayOf(0x1C, 0x20, 0x40, 0x20, 0x1C),
            'w' to intArrayOf(0x3C, 0x40, 0x30, 0x40, 0x3C),
            'x' to intArrayOf(0x44, 0x28, 0x10, 0x28, 0x44),
            'y' to intArrayOf(0x4C, 0x90, 0x90, 0x90, 0x7C),
            'z' to intArrayOf(0x44, 0x64, 0x54, 0x4C, 0x44),
            '{' to intArrayOf(0x00, 0x08, 0x36, 0x41, 0x00),
            '|' to intArrayOf(0x00, 0x00, 0x77, 0x00, 0x00),
            '}' to intArrayOf(0x00, 0x41, 0x36, 0x08, 0x00),
            '~' to intArrayOf(0x02, 0x01, 0x02, 0x04, 0x02)
        )
    }
}
