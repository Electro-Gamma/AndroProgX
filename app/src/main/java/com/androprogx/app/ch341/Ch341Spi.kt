/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.ch341

/**
 * CH341A "SPI stream" command protocol - ported from flashrom's `ch341a_spi.c`
 * (GPLv2-or-later; see NOTICE.md at the repo root): `config_stream()`, `enable_pins()`,
 * `ch341a_spi_send_command()`, and the `swap_byte()` LSB/MSB bit-reversal it relies on
 * (the CH341A clocks the SPI stream LSB-first internally, so bytes are bit-reversed on the way
 * in and out to present normal MSB-first SPI semantics to callers).
 *
 * The CS/SCK/MOSI/MISO pins' *direction* is claimed once via [init] (called when a session
 * starts) and given back via [shutdown] (when it ends). CS *itself* toggles far more often than
 * that: every [transfer]/[writeThenRead] call is its own independently CS-bracketed SPI
 * transaction - i.e. every call pulses "chip select low ... chip select high" around itself,
 * exactly like one discrete SPI command to a flash/EEPROM chip. Commands that combine a real
 * write payload with a substantial read (a bulk content read) must use [writeThenRead], which
 * keeps the two as separate low-level bursts under that one CS pulse, rather than [transfer],
 * which sends both in the same packet sequence - see [writeThenRead]'s doc for why that
 * distinction is essential, not just a nicety.
 */
class Ch341Spi(private val dev: Ch341UsbDevice) {

    /** Set the first time [Ch341UsbDevice.pipelinedTransfer] fails, so later calls in this same
     *  session go straight to the sequential path instead of paying for a doomed pipelined
     *  attempt (up to a full USB timeout) on every single chunk of what might be an 8MB read.
     *  Android's UsbRequest/requestWait() async I/O is known to be flakier than plain
     *  bulkTransfer() on some devices/Android versions - if that's the case here, this makes the
     *  cost of finding out a one-time thing per connection rather than a per-chunk tax. Resets
     *  naturally on the next connect, since that constructs a fresh Ch341Spi. */
    @Volatile
    private var pipeliningDisabled = false

    fun configStream(speed: Int = SPEED_SINGLE_750K): Boolean =
        dev.bulkOut(byteArrayOf(CMD_I2C_STREAM.toByte(), (STM_SET or (speed and 0x07)).toByte(), STM_END.toByte()))

    /** Configures the UIO pins for SPI use (CS/SCK/MOSI as outputs) or releases them. Ported
     *  verbatim from flashrom's enable_pins() - see its comment there for the exact D0-D7 -> CS/
     *  SCK/MOSI/MISO pin mapping. */
    fun enablePins(enable: Boolean): Boolean {
        val buf = byteArrayOf(
            CMD_UIO_STREAM.toByte(),
            (UIO_OUT or 0x37).toByte(), // CS high (all), SCK=0, DOUT*=1
            (UIO_OUT or 0x37).toByte(),
            (UIO_OUT or 0x37).toByte(),
            (UIO_OUT or 0x37).toByte(),
            (UIO_OUT or 0x37).toByte(),
            (UIO_OUT or 0x36).toByte(), // CS low (all), SCK=0, DOUT*=1
            (UIO_DIR or (if (enable) 0x3F else 0x00)).toByte(),
            UIO_END.toByte()
        )
        return dev.bulkOut(buf)
    }

    fun init(speed: Int = SPEED_SINGLE_750K): Boolean = configStream(speed) && enablePins(true)

    fun shutdown(): Boolean = enablePins(false)

    /**
     * One CS-bracketed SPI transaction for simple, single-phase commands: opcode-only (WREN,
     * WRDI, chip erase), or opcode+tiny-payload that always stays within one 31-byte CH341A
     * packet (JEDEC-ID, a 1-byte status register read, EN4B/EX4B). Internally always drains
     * writeData.size + readCount bytes from the IN endpoint - see [rawTransfer]'s doc for why
     * that "always", even when readCount is 0, matters.
     *
     * Do NOT reach for this when [writeData] and a *substantial* [readCount] both need to be
     * present in the same logical command (a bulk content read, page-sized or larger) - use
     * [writeThenRead] for that instead. See [writeThenRead]'s doc for why combining a real
     * multi-byte address with a large read into one packet sequence here is exactly what broke
     * chip reads while a tiny single-packet JEDEC-ID read kept working fine.
     */
    fun transfer(writeData: ByteArray, readCount: Int): ByteArray? {
        if (!enablePins(true)) return null // CS low - SPI_CONTROLLER_Chip_Select_Low()
        val result = rawTransfer(writeData, readCount, allowPipelining = true)
        enablePins(false) // CS high - SPI_CONTROLLER_Chip_Select_High(); best-effort, like upstream,
        // so a hiccup on this trailing pulse doesn't throw away an otherwise-good read/write result.

        return result
    }

    /**
     * One CS-bracketed transaction split into two independent low-level bursts: [writeData]
     * clocked out with no read phase, then [readCount] bytes clocked in with no write phase -
     * instead of one call that mixes both into the same packet sequence.
     *
     * This mirrors IMSProg's own bulk-read code (`snor_read_param()` in spi_nor_flash.c), which
     * never combines the two: `SPI_CONTROLLER_Chip_Select_Low()`, then a `Write_NByte()` call
     * with readcnt=0 for the opcode+address, then a *separate* `Read_NByte()` call with
     * writecnt=0 for the data, then `SPI_CONTROLLER_Chip_Select_High()` - CS stays low across
     * both calls, but the address phase and the data phase are always two distinct bursts.
     * Searching that whole reference codebase, `ch341a_spi_send_command()` is never actually
     * called with both counts nonzero for a transfer of any real size - only ever through the
     * Write_NByte/Read_NByte wrappers, each hardcoding one side to zero. The combined form this
     * file used to use (one `transfer()` call encoding opcode+address *and* read-filler into the
     * same packet sequence, so the very first packet mixes real address bytes with 0xFF filler)
     * is a code path nobody has actually validated on hardware, even though the underlying C
     * function's signature technically allows it. Splitting it this way - matching what's
     * actually proven to work - is what fixes a real chip read failing at any chunk size while a
     * tiny single-packet JEDEC-ID read keeps working.
     *
     * If the first attempt (which allows [rawTransfer]'s pipelined fast path) fails, retries
     * once with pipelining disabled - see [attemptWriteThenRead]'s doc for why a plain retry of
     * just the failed phase would risk corrupting an already-partly-consumed SPI transaction, and
     * why the retry has to drop CS and start completely over instead.
     */
    fun writeThenRead(writeData: ByteArray, readCount: Int): ByteArray? {
        attemptWriteThenRead(writeData, readCount, allowPipelining = true)?.let { return it }
        return attemptWriteThenRead(writeData, readCount, allowPipelining = false)
    }

    /**
     * One attempt at [writeThenRead]. When [allowPipelining] is true and the read phase is long
     * enough to try [Ch341UsbDevice.pipelinedTransfer], a failure partway through it can leave
     * some packets already physically sent to the chip (e.g. packets 0-2 clocked out before
     * packet 3's reply times out) while CS is still held low from this same call's [enablePins].
     * Simply trying again on that same still-open transaction would resend those already-sent
     * bytes into a SPI command the chip has already partly consumed, corrupting it silently rather
     * than failing cleanly - a chip read returning garbage is a far worse outcome than a slower,
     * clean retry. So a failure here never retries mid-transaction: [writeThenRead] instead drops
     * CS (this function's own trailing [enablePins](false)) and calls this again with
     * [allowPipelining] false, which starts an entirely fresh CS-bracketed transaction with the
     * sequential-only path proven correct across five rounds of real-hardware debugging.
     */
    private fun attemptWriteThenRead(writeData: ByteArray, readCount: Int, allowPipelining: Boolean): ByteArray? {
        if (!enablePins(true)) return null
        val writeOk = writeData.isEmpty() || rawTransfer(writeData, 0, allowPipelining) != null
        val result = when {
            !writeOk -> null
            readCount > 0 -> rawTransfer(ByteArray(0), readCount, allowPipelining)
            else -> ByteArray(0)
        }
        enablePins(false)
        return result
    }

    /**
     * Builds and sends one or more 0xA8-prefixed CH341A packets for [writeData] (real bytes,
     * bit-reversed per [swapByte]) followed by [readCount] bytes of 0xFF filler, then reads back
     * the result. Does not touch CS - callers ([transfer], [writeThenRead]) bracket it.
     *
     * Matches `ch341a_spi_send_command()`'s own `usb_transfer(..., writecnt + readcnt, ...)`
     * call, which reads back that many bytes *unconditionally* - never conditioned on readcnt
     * being nonzero. Always draining writeData.size + readCount bytes here, even when readCount
     * is 0, mirrors that: skipping the drain for write-only calls (as this file used to) leaves
     * the CH341A's full-duplex echo of the write phase sitting in the endpoint's queue, where the
     * *next* transfer() call reads it back as if it were real data - silently corrupting or
     * misaligning whatever comes after any WREN/erase/page-program/EN4B/EX4B call.
     *
     * Builds every packet up front, then - only when [allowPipelining] is true - tries
     * [Ch341UsbDevice.pipelinedTransfer] first for any multi-packet transfer (worth the setup
     * cost above 2 packets); see its doc for why that's a best-effort fast path, not the only way
     * this can succeed, and see [attemptWriteThenRead]'s doc for why a failed pipelined attempt
     * must never fall through to the sequential path *within the same CS-bracketed transaction*
     * (only [writeThenRead]'s own fresh-transaction retry may retry with pipelining disabled).
     * Falling back to (and, for 1-2 packets or when pipelining isn't allowed, going straight to)
     * sending and draining **one packet at a time** - full OUT packet, then that packet's own IN
     * reply, before building the next one - is what's actually proven correct: IMSProg's own
     * desktop driver queues up to 32 simultaneous async IN reads specifically because, per its
     * own comment, "the device sends the 31 reply data bytes to each 32-byte packet" - i.e. the
     * CH341A replies per packet, not once at the end, and (empirically, from a real device
     * reporting maxPacketSize=32 - exactly the CH341A's own packet size) its internal reply
     * buffer only holds a handful of packets' worth of unclaimed data before the device stops
     * accepting more OUT packets until the host drains it. Sending every packet up front with
     * nothing draining in between (as an earlier version of this file did) fills that buffer and
     * wedges the device partway through any transfer longer than a few packets - which is exactly
     * why a tiny single-packet JEDEC-ID read always worked while any real multi-packet chip read
     * consistently failed at the same packet index regardless of the overall transfer size.
     */
    private fun rawTransfer(writeData: ByteArray, readCount: Int, allowPipelining: Boolean): ByteArray? {
        val total = writeData.size + readCount
        if (total == 0) return ByteArray(0)

        // Leading all-zero 32-byte packet - matches the original's zeroed wbuf[0] header. No
        // reply is read for this one: it carries no 0xA8 command, so nothing echoes back for it.
        if (!dev.bulkOut(ByteArray(32))) return null

        // Build every packet up front - shared by both the pipelined fast path and the
        // sequential path below, so both consume the exact same packet structure and can never
        // disagree with each other about it.
        val packetBytes = ArrayList<ByteArray>()
        val packetWriteNow = ArrayList<Int>()
        val packetReadNow = ArrayList<Int>()
        run {
            var writeLeft = writeData.size
            var readLeft = readCount
            var writeOff = 0
            val packetCount = (total + SUBCHUNK - 1) / SUBCHUNK
            for (p in 0 until packetCount) {
                val writeNow = minOf(SUBCHUNK, writeLeft)
                val readNow = minOf(SUBCHUNK - writeNow, readLeft)
                val bytes = ByteArray(1 + writeNow + readNow)
                bytes[0] = CMD_SPI_STREAM.toByte()
                for (i in 0 until writeNow) {
                    bytes[1 + i] = swapByte(writeData[writeOff + i]).toByte()
                }
                for (i in 0 until readNow) {
                    bytes[1 + writeNow + i] = 0xFF.toByte()
                }
                packetBytes.add(bytes)
                packetWriteNow.add(writeNow)
                packetReadNow.add(readNow)
                writeOff += writeNow
                writeLeft -= writeNow
                readLeft -= readNow
            }
        }

        val result = if (readCount > 0) ByteArray(readCount) else null

        // Fast path: only when the caller allows it (see this function's doc and
        // attemptWriteThenRead's doc for why a retry must not reach this with allowPipelining
        // true on the same transaction), only worth attempting above a couple of packets - for
        // 1-2, the pipelining setup isn't worth it and the sequential path below is just as fast
        // - and only if pipelining hasn't already proven unreliable this session (see
        // [pipeliningDisabled]'s doc).
        if (allowPipelining && !pipeliningDisabled && packetBytes.size > 2) {
            val replySizes = packetBytes.indices.map { packetWriteNow[it] + packetReadNow[it] }
            val replies = dev.pipelinedTransfer(packetBytes, replySizes)
            if (replies != null) {
                var replyOff = 0
                var resultOff = 0
                for (i in packetBytes.indices) {
                    val writeNow = packetWriteNow[i]
                    val readNow = packetReadNow[i]
                    if (readNow > 0) {
                        for (j in 0 until readNow) {
                            result!![resultOff + j] = swapByte(replies[replyOff + writeNow + j]).toByte()
                        }
                        resultOff += readNow
                    }
                    replyOff += writeNow + readNow
                }
                return result ?: ByteArray(0)
            }
            // Pipelining failed partway through this CS-bracketed transaction - some packets may
            // already be physically sent to the chip. Do NOT fall through to the sequential loop
            // below on this same transaction (see attemptWriteThenRead's doc): report failure and
            // let the caller drop CS and retry the whole thing fresh instead. Also stop trying it
            // again this session (see pipeliningDisabled's doc) so a systematic failure costs one
            // slow chunk, not every chunk of the rest of this read.
            pipeliningDisabled = true
            return null
        }

        var resultOff = 0
        for (i in packetBytes.indices) {
            if (!dev.bulkOut(packetBytes[i])) return null
            val writeNow = packetWriteNow[i]
            val readNow = packetReadNow[i]
            val replyLen = writeNow + readNow
            if (replyLen > 0) {
                val reply = dev.readExactly(replyLen) ?: return null
                if (readNow > 0) {
                    for (j in 0 until readNow) {
                        result!![resultOff + j] = swapByte(reply[writeNow + j]).toByte()
                    }
                    resultOff += readNow
                }
            }
        }
        return result ?: ByteArray(0)
    }

    private fun swapByte(b: Byte): Int {
        var x = b.toInt() and 0xFF
        x = ((x shr 1) and 0x55) or ((x shl 1) and 0xAA)
        x = ((x shr 2) and 0x33) or ((x shl 2) and 0xCC)
        x = ((x shr 4) and 0x0F) or ((x shl 4) and 0xF0)
        return x and 0xFF
    }

    companion object {
        private const val CMD_I2C_STREAM = 0xAA // config_stream() shares the I2C stream command
        private const val CMD_SPI_STREAM = 0xA8
        private const val CMD_UIO_STREAM = 0xAB

        private const val STM_SET = 0x60
        private const val STM_END = 0x00

        private const val UIO_OUT = 0x80
        private const val UIO_DIR = 0x40
        private const val UIO_END = 0x20

        /** CH341_PACKET_LENGTH (32) minus 1 command byte, i.e. max data bytes per sub-packet. */
        private const val SUBCHUNK = 31

        /** CH341A_STM_SPI_DBL bit unset = single data-rate, matches flashrom's default. */
        const val SPEED_SINGLE_750K = 0x03
    }
}
