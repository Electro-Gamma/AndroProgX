/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.eeprom

import com.androprogx.app.ch341.Ch341I2c

/**
 * Reads and writes 24Cxx-family I2C EEPROMs over a [Ch341I2c] bus. The device-address encoding
 * (the [Chip.algorithm] byte and how it folds extra address bits into the 7-bit device-address
 * byte) is ported from IMSProg's `ch34xi2cBlockRead`/`ch34xi2cBlockWrite` (GPLv3; see NOTICE.md
 * at the repo root) - it's the standard trick 24Cxx parts above 16Kbit use, since a 7-bit device
 * address plus a 1-2 byte word address can't otherwise reach every byte on the larger chips.
 * This includes the two oddball variants IMSProg's database calls out separately (24LC1025 and
 * 24LC515, [Chip.algorithm] low nibble 0xA/0xE) - both fold their extra address bit into bit 2
 * of the device-address byte instead of bit 0, which is why they need their own branch below
 * rather than fitting the generic 1-/2-byte-address formula.
 */
class I2cEeprom(private val i2c: Ch341I2c) {

    /**
     * @param algorithm low nibble = address type: 0x1 = 1-byte word address, 0x2 = 2-byte word
     *   address, 0xA = 2-byte word address with the extra block bit folded into device-address
     *   bit 2 (24LC1025 only), 0xE = same but for bit 15 of a nominally-32K-per-half chip
     *   (24LC515 only). High nibble = bitmask of extra top address bits folded into the
     *   device-address byte (0 for chips whose word address alone covers the whole array).
     * @param manufacturer display-only vendor name, from IMSProg's own IMSProg.Dat entry for
     *   this exact part number - there's no I2C equivalent of SPI's JEDEC-ID read, so this can
     *   only ever be "whichever part the user picked from the list", never auto-detected.
     * @param vccVolts display-only supply voltage, likewise from IMSProg.Dat - real per-chip data
     *   (e.g. Ramtron's non-"L" FM24Cxx parts are 5V while their "CL"-suffixed siblings are
     *   3.3V), not a blanket assumption.
     */
    data class Chip(
        val name: String,
        val totalBytes: Int,
        val pageSize: Int,
        val algorithm: Int,
        val baseAddr7: Int = 0x50, // standard 24Cxx base address (1010xxx)
        val manufacturer: String = "Generic",
        val vccVolts: Double = 3.3
    ) {
        /** How many word-address bytes [algorithm] uses - the same test [addrBytesOf] applies,
         *  exposed for display purposes (the "Word address" parameter row). */
        val wordAddressBytes: Int get() = if ((algorithm and 0x0F) <= 1) 1 else 2
    }

    /** Per-transaction cap: matches ch34xi2cBlockRead/Write's 16-byte cap for the CH341A. */
    private val stepSize = 16

    private fun addrBytesOf(algorithm: Int) = if ((algorithm and 0x0F) <= 1) 1 else 2
    private fun blockMaskOf(algorithm: Int) = (algorithm shr 4) and 0x0F

    /** Builds the (deviceAddressByte, wordAddressBytes) pair for [address] per [chip]'s algorithm. */
    private fun addressFor(chip: Chip, address: Int): Pair<Int, ByteArray> {
        val mask = blockMaskOf(chip.algorithm)
        val base = chip.baseAddr7 shl 1
        return when (chip.algorithm and 0x0F) {
            0x0A -> { // 24LC1025: address bit 16 -> device-address bit 2
                val hiBits = ((address and 0x010000) ushr 14) and mask
                (hiBits shl 1 or base) to byteArrayOf(((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte())
            }
            0x0E -> { // 24LC515: address bit 15 -> device-address bit 2
                val hiBits = ((address and 0x008000) ushr 13) and mask
                (hiBits shl 1 or base) to byteArrayOf(((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte())
            }
            0, 1 -> {
                val hiBits = (address ushr 8) and mask
                (hiBits shl 1 or base) to byteArrayOf((address and 0xFF).toByte())
            }
            else -> { // 0x02 and anything else: plain 2-byte word address
                val hiBits = (address ushr 16) and mask
                (hiBits shl 1 or base) to byteArrayOf(((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte())
            }
        }
    }

    /**
     * Reads [length] bytes starting at [startAddress]. [onProgress] is called after each
     * internal chunk with (bytesDoneSoFar, totalBytes). Returns null on the first USB failure.
     */
    /** [onChunk], if given, fires after each [stepSize]-sized piece lands - see
     *  [SpiFlash.read]'s doc for why (matches IMSProg's own live-updating hex view). */
    fun read(
        chip: Chip,
        startAddress: Int,
        length: Int,
        onProgress: ((Int, Int) -> Unit)? = null,
        onChunk: ((offset: Int, chunk: ByteArray) -> Unit)? = null
    ): ByteArray? {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = minOf(stepSize, length - offset)
            val address = startAddress + offset
            val (devAddr, wordAddr) = addressFor(chip, address)
            val resp = i2c.transaction(
                listOf(
                    Ch341I2c.Phase.Write(byteArrayOf(devAddr.toByte()) + wordAddr),
                    Ch341I2c.Phase.RepeatStart,
                    Ch341I2c.Phase.Write(byteArrayOf((devAddr or 1).toByte())),
                    Ch341I2c.Phase.Read(n)
                )
            ) ?: return null
            System.arraycopy(resp, 0, out, offset, n)
            onChunk?.invoke(offset, out.copyOfRange(offset, offset + n))
            offset += n
            onProgress?.invoke(offset, length)
        }
        return out
    }

    /**
     * Writes [data] starting at [startAddress], respecting [Chip.pageSize] page boundaries (a
     * write may not cross one) and waiting out each chip's internal write cycle between chunks.
     * Returns false on the first USB failure.
     */
    fun write(chip: Chip, startAddress: Int, data: ByteArray, onProgress: ((Int, Int) -> Unit)? = null): Boolean {
        var offset = 0
        while (offset < data.size) {
            val address = startAddress + offset
            val bytesLeftInPage = chip.pageSize - (address % chip.pageSize)
            val n = minOf(stepSize, bytesLeftInPage, data.size - offset)
            val (devAddr, wordAddr) = addressFor(chip, address)
            val chunk = byteArrayOf(devAddr.toByte()) + wordAddr + data.copyOfRange(offset, offset + n)
            if (i2c.transaction(listOf(Ch341I2c.Phase.Write(chunk))) == null) return false
            // 24Cxx parts need a few ms to finish their internal write cycle before the next access.
            i2c.delayMs(10)
            offset += n
            onProgress?.invoke(offset, data.size)
        }
        return true
    }

    /**
     * Fills the whole chip with [fillByte] (0xFF by default). 24Cxx parts have no hardware erase
     * instruction - any byte can be written directly to any value - so "erase" here just means
     * "write the blank/erased value everywhere", the same thing IMSProg's blank-check/erase
     * feature effectively does for I2C EEPROMs.
     */
    fun erase(chip: Chip, fillByte: Byte = 0xFF.toByte(), onProgress: ((Int, Int) -> Unit)? = null): Boolean =
        write(chip, 0, ByteArray(chip.totalBytes) { fillByte }, onProgress)

    /** Result of [detectSize]: [confirmed] = false means "at least this big", not a confirmed exact match. */
    data class SizeDetectionResult(val chip: Chip, val confirmed: Boolean)

    /**
     * Probes the chip's actual capacity using the classic address-wraparound trick: 24Cxx parts
     * have no ID command, but a byte written at address 0 and read back at address N will come
     * back unchanged if the chip's real size is N (its internal word-address counter wrapped to
     * 0), and read back as something else if the chip is bigger than N.
     *
     * Deliberately narrow about which [candidates] it trusts, for two independent reasons:
     * - Only chips with no extra address-folding bits ([blockMaskOf] == 0) are tried. Chips that
     *   DO fold extra bits into the device-address byte (24C04/08/16, 24LC515, 24LC1025) reuse
     *   those same physical pins for board-level chip-address *selection* on smaller parts -
     *   probing different fold-bit values there risks addressing a different chip on the bus
     *   (if one exists) rather than a different byte on this one, which this method can't
     *   distinguish from a wrap. Those parts stay manual-selection-only.
     * - Only tries [baseChip]'s word-address byte width (1 vs 2 bytes) - the wrong width risks
     *   sending an address byte where the chip expects data, corrupting a byte.
     * - Any candidate at or beyond the word-address field's own width (256 for 1 byte, 65536 for
     *   2 bytes) is dropped too: a plain word-address counter can't express an address past its
     *   own field width at all, so testing one there would trivially collapse to address 0's
     *   encoding regardless of the real chip's size, rather than genuinely wrapping.
     *
     * What's left is a smaller but hardware-safe ladder (the 2-byte family still meaningfully
     * covers 4KB/8KB/16KB/32KB - the sizes people most often reach for this on). Temporarily
     * overwrites byte 0 (restored before returning either way) - don't run this on a chip you
     * can't afford to briefly disturb, and don't run it on a write-protected chip.
     *
     * Returns null on a USB failure or if nothing in [candidates] is safely testable. Otherwise
     * [SizeDetectionResult.confirmed] is true for a genuine wraparound match, or false when every
     * testable candidate wrapped and the *largest* one is returned as an "at least this big,
     * actual size unconfirmed" guess.
     */
    fun detectSize(baseChip: Chip, candidates: List<Chip> = COMMON_CHIPS): SizeDetectionResult? {
        val addrBytes = addrBytesOf(baseChip.algorithm)
        val fieldLimit = 1L shl (8 * addrBytes) // widest value a plain word address of this width can hold
        val testable = candidates
            .filter { addrBytesOf(it.algorithm) == addrBytes && blockMaskOf(it.algorithm) == 0 && it.totalBytes < fieldLimit }
            .sortedBy { it.totalBytes }
        if (testable.isEmpty()) return null

        val probe = testable.first()
        val original = read(probe, 0, 1) ?: return null
        val marker = (original[0] + 1).toByte()
        if (!write(probe, 0, byteArrayOf(marker))) return null

        var detected: Chip? = null
        for (candidate in testable) {
            val readBack = read(candidate, candidate.totalBytes, 1)
            if (readBack != null && readBack[0] == marker) {
                detected = candidate
                break
            }
        }
        write(probe, 0, original) // restore, regardless of what we found
        return if (detected != null) SizeDetectionResult(detected, confirmed = true)
        else SizeDetectionResult(testable.last(), confirmed = false)
    }

    companion object {
        /**
         * 24Cxx presets, extracted directly from IMSProg's own IMSProg.Dat (its "24_EEPROM"
         * category) the same way [SpiFlashDatabase]'s SPI NOR table was - every entry there
         * except its 11 "COMMON" rows, which were dropped as exact-name duplicates of Microchip's
         * own entries (e.g. "COMMON"'s "24C64" and "MICROCHIP"'s "24C64" are byte-for-byte
         * identical). Pick "Custom" in the UI for anything not listed here.
         *
         * This replaces a smaller, hand-written 13-entry list that turns out to have had a real
         * bug: its "24C1024 (128 KB)" used algorithm 0x02 (plain 2-byte address, no fold bits) -
         * but a plain 2-byte address only reaches 65536 bytes (64KB), so every access past the
         * first half of a real 128KB chip would silently wrap back to address 0 instead of
         * reaching the upper half, rather than erroring - the worst kind of bug for a device
         * meant to read/write real data faithfully. IMSProg's own database uses 0x12 for these
         * (matching the same address-folding trick this file's 24LC1025/24LC515 branches already
         * used correctly for their own, differently-positioned fold bit) - trusting it here, like
         * everywhere else in this file, avoids assuming a value nobody had actually verified.
         */
        val COMMON_CHIPS = listOf(
            Chip("24C01 (128 B)", 128, 8, 0x01, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C01 (128 B)", 128, 8, 0x01, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("HT24C01 (128 B)", 128, 8, 0x01, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C01 (128 B)", 128, 8, 0x01, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C01 (128 B)", 128, 8, 0x01, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("24C02 (256 B)", 256, 8, 0x01, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C02 (256 B)", 256, 8, 0x01, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("HT24C02 (256 B)", 256, 8, 0x01, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C02 (256 B)", 256, 8, 0x01, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C02 (256 B)", 256, 8, 0x01, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("24C04 (512 B)", 512, 16, 0x11, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C04 (512 B)", 512, 16, 0x11, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("FM24C04A (512 B)", 512, 16, 0x11, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 5.0),
            Chip("FM24CL04A (512 B)", 512, 16, 0x11, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 3.3),
            Chip("HT24C04 (512 B)", 512, 16, 0x11, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C04 (512 B)", 512, 16, 0x11, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C04 (512 B)", 512, 16, 0x11, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("MB85RC04 (512 B)", 512, 16, 0x11, manufacturer = "Ramxeed/Fujitsu (FRAM)", vccVolts = 3.3),
            Chip("24C08 (1 KB)", 1024, 16, 0x31, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C08 (1 KB)", 1024, 16, 0x31, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("HT24C08 (1 KB)", 1024, 16, 0x31, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C08 (1 KB)", 1024, 16, 0x31, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C08 (1 KB)", 1024, 16, 0x31, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("24C16 (2 KB)", 2048, 16, 0x71, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C16 (2 KB)", 2048, 16, 0x71, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("FM24C16A (2 KB)", 2048, 16, 0x71, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 5.0),
            Chip("FM24CL16A (2 KB)", 2048, 16, 0x71, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 3.3),
            Chip("HT24C16 (2 KB)", 2048, 16, 0x71, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C16 (2 KB)", 2048, 16, 0x71, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C16 (2 KB)", 2048, 16, 0x71, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("MB85RC16 (2 KB)", 2048, 16, 0x71, manufacturer = "Ramxeed/Fujitsu (FRAM)", vccVolts = 3.3),
            Chip("24C32 (4 KB)", 4096, 32, 0x02, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C32 (4 KB)", 4096, 32, 0x02, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("FM24C32A (4 KB)", 4096, 32, 0x02, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 5.0),
            Chip("FM24CL32A (4 KB)", 4096, 32, 0x02, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 3.3),
            Chip("HT24C32 (4 KB)", 4096, 32, 0x02, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C32 (4 KB)", 4096, 32, 0x02, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C32 (4 KB)", 4096, 32, 0x02, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("24C64 (8 KB)", 8192, 32, 0x02, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C64 (8 KB)", 8192, 32, 0x02, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("FM24C64A (8 KB)", 8192, 32, 0x02, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 5.0),
            Chip("FM24CL64A (8 KB)", 8192, 32, 0x02, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 3.3),
            Chip("HT24C64 (8 KB)", 8192, 32, 0x02, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C64 (8 KB)", 8192, 32, 0x02, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C64 (8 KB)", 8192, 32, 0x02, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("MB85RC64 (8 KB)", 8192, 32, 0x02, manufacturer = "Ramxeed/Fujitsu (FRAM)", vccVolts = 3.3),
            Chip("24C128 (16 KB)", 16384, 32, 0x02, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C128 (16 KB)", 16384, 32, 0x02, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("FM24C128 (16 KB)", 16384, 32, 0x02, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 5.0),
            Chip("HT24C128 (16 KB)", 16384, 32, 0x02, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C128 (16 KB)", 16384, 32, 0x02, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C128 (16 KB)", 16384, 32, 0x02, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("MB85RC128 (16 KB)", 16384, 32, 0x02, manufacturer = "Ramxeed/Fujitsu (FRAM)", vccVolts = 3.3),
            Chip("24C256 (32 KB)", 32768, 32, 0x02, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C256 (32 KB)", 32768, 32, 0x02, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("FM24C256 (32 KB)", 32768, 32, 0x02, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 5.0),
            Chip("HT24C256 (32 KB)", 32768, 32, 0x02, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C256 (32 KB)", 32768, 32, 0x02, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C256 (32 KB)", 32768, 32, 0x02, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("MB85RC256 (32 KB)", 32768, 32, 0x02, manufacturer = "Ramxeed/Fujitsu (FRAM)", vccVolts = 3.3),
            Chip("24C512 (64 KB)", 65536, 32, 0x02, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("24LC515 (64 KB)", 65536, 32, 0x7E, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C512 (64 KB)", 65536, 32, 0x02, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("FM24C512 (64 KB)", 65536, 32, 0x02, manufacturer = "Ramtron/Cypress (FRAM)", vccVolts = 5.0),
            Chip("HT24C512 (64 KB)", 65536, 32, 0x02, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C512 (64 KB)", 65536, 32, 0x02, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C512 (64 KB)", 65536, 32, 0x02, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("MB85RC512 (64 KB)", 65536, 32, 0x02, manufacturer = "Ramxeed/Fujitsu (FRAM)", vccVolts = 3.3),
            Chip("24C1024 (128 KB)", 131072, 32, 0x12, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("24LC1025 (128 KB)", 131072, 32, 0x7A, manufacturer = "Microchip", vccVolts = 3.3),
            Chip("AT24C1024 (128 KB)", 131072, 32, 0x12, manufacturer = "Atmel/Microchip", vccVolts = 3.3),
            Chip("HT24C1024 (128 KB)", 131072, 32, 0x12, manufacturer = "Holtek", vccVolts = 3.3),
            Chip("IS24C1024 (128 KB)", 131072, 32, 0x12, manufacturer = "ISSI", vccVolts = 3.3),
            Chip("M24C1024 (128 KB)", 131072, 32, 0x12, manufacturer = "STMicroelectronics", vccVolts = 3.3),
            Chip("MB85RC1MT (128 KB)", 131072, 32, 0x12, manufacturer = "Ramxeed/Fujitsu (FRAM)", vccVolts = 3.3),
            Chip("AT24CM02 (256 KB)", 262144, 32, 0x32, manufacturer = "Atmel/Microchip", vccVolts = 3.3)
        )
    }
}
