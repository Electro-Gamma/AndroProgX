/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.ch341

/**
 * CH341A "I2C stream" command protocol - ported from IMSProg's `ch34x_i2c.c` and the shared
 * `config_stream()` helper in flashrom's `ch341a_spi.c` (GPLv2-or-later / GPLv3-or-later; see
 * NOTICE.md at the repo root). Builds and sends the same 0xAA-prefixed byte streams those C
 * drivers do; the bit-banging happens inside the CH341A itself, this just assembles the command
 * bytes and unpacks the response.
 *
 * One [transaction] call = one complete I2C transaction bracketed by a START and a STOP
 * condition, sent as a single bulk OUT transfer. Multiple [Phase.Write]/[Phase.Read] entries in
 * one call share that START/STOP (i.e. a write-then-read with a repeated START in between, the
 * standard way to address-then-read an EEPROM or any other I2C device register).
 */
class Ch341I2c(private val dev: Ch341UsbDevice) {

    sealed class Phase {
        /** Clocks [data] out onto SDA, MSB first per byte (normal I2C bit order - no swap needed). */
        data class Write(val data: ByteArray) : Phase()
        /** Clocks [count] bytes in from SDA (master ACKs all but the last, which gets NAK'd). */
        data class Read(val count: Int) : Phase()
        /** Emits an extra (repeated) START condition without an intervening STOP. */
        object RepeatStart : Phase()
    }

    /** Sets the I2C bus clock: 0=20kHz, 1=100kHz, 2=400kHz, 3=750kHz. */
    fun configSpeed(speed: Int): Boolean =
        dev.bulkOut(byteArrayOf(CMD_I2C_STREAM.toByte(), (STM_SET or (speed and 0x07)).toByte(), STM_END.toByte()))

    /** Asks the chip to wait up to 15ms before the next command (used between EEPROM writes). */
    fun delayMs(ms: Int): Boolean =
        dev.bulkOut(byteArrayOf(CMD_I2C_STREAM.toByte(), (STM_MS or (ms and 0x0F)).toByte(), STM_END.toByte()))

    /**
     * Runs one START..STOP I2C transaction built from [phases] and returns the bytes clocked in
     * by any [Phase.Read] entries, concatenated in order. Returns null on a USB-level failure;
     * note this does NOT distinguish an ACK from a NAK at the I2C level (the CH341A doesn't
     * report that back per-byte), so a missing/unresponsive device address is only detectable by
     * the read data coming back all-0xFF or all-0x00, not as an explicit error here.
     */
    fun transaction(phases: List<Phase>): ByteArray? {
        val cmd = ArrayList<Byte>(64)
        cmd.add(CMD_I2C_STREAM.toByte())
        cmd.add(STM_STA.toByte())
        var totalRead = 0

        for (phase in phases) {
            when (phase) {
                is Phase.RepeatStart -> cmd.add(STM_STA.toByte())
                is Phase.Write -> {
                    var off = 0
                    while (off < phase.data.size) {
                        val n = minOf(MAX_CHUNK, phase.data.size - off)
                        cmd.add((STM_OUT or n).toByte())
                        for (i in 0 until n) cmd.add(phase.data[off + i])
                        off += n
                    }
                }
                is Phase.Read -> {
                    val n = phase.count
                    if (n > 0) {
                        // n-1 bytes ACK'd normally, chunked to stay under the 6-bit count field,
                        // then one bare STM_IN for the final byte (NAK'd) - matches
                        // ch34xi2cBlockRead's "STM_IN|(size-1)" + trailing bare "STM_IN" pattern.
                        var remaining = n - 1
                        while (remaining > 0) {
                            val chunk = minOf(MAX_CHUNK, remaining)
                            cmd.add((STM_IN or chunk).toByte())
                            remaining -= chunk
                        }
                        cmd.add(STM_IN.toByte())
                    }
                    totalRead += n
                }
            }
        }
        cmd.add(STM_STO.toByte())
        cmd.add(STM_END.toByte())

        if (!dev.bulkOut(cmd.toByteArray())) return null
        if (totalRead == 0) return ByteArray(0)
        return dev.readExactly(totalRead)
    }

    /** Convenience: a plain write-only transaction to [deviceAddr7] (7-bit address, no R/W bit). */
    fun writeRaw(deviceAddr7: Int, data: ByteArray): Boolean =
        transaction(listOf(Phase.Write(byteArrayOf((deviceAddr7 shl 1).toByte()) + data))) != null

    /**
     * Convenience: write [writeData] (typically device-address byte + register/word address),
     * then a repeated START and read [readLen] bytes back - the standard "set pointer, then
     * read" transaction shape used by EEPROMs and most other I2C peripherals.
     */
    fun writeThenRead(writeData: ByteArray, readDeviceAddrByte: Int, readLen: Int): ByteArray? =
        transaction(
            listOf(
                Phase.Write(writeData),
                Phase.RepeatStart,
                Phase.Write(byteArrayOf(readDeviceAddrByte.toByte())),
                Phase.Read(readLen)
            )
        )

    companion object {
        private const val CMD_I2C_STREAM = 0xAA
        private const val STM_STA = 0x74
        private const val STM_STO = 0x75
        private const val STM_OUT = 0x80
        private const val STM_IN = 0xC0
        private const val STM_SET = 0x60
        private const val STM_MS = 0x50
        private const val STM_END = 0x00

        /** CH341A_CMD_I2C_STM_MAX in flashrom: min(0x3F, CH341_PACKET_LENGTH). */
        private const val MAX_CHUNK = 32

        const val SPEED_20K = 0
        const val SPEED_100K = 1
        const val SPEED_400K = 2
        const val SPEED_750K = 3
    }
}
