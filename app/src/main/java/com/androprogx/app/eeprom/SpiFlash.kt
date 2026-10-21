/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.eeprom

import com.androprogx.app.ch341.Ch341Spi

/**
 * Standard SPI NOR flash ("25xx" family - Winbond W25Qxx, GigaDevice GD25Qxx, Macronix MX25Lxx,
 * EON/XMC 25QHxxx, etc.) operations built on top of [Ch341Spi]'s raw transfer primitive. The
 * command set here (JEDEC ID, READ, WREN, page program, sector/chip erase, status polling, 4-byte
 * addressing, status-register unprotect) is the industry-standard SPI NOR instruction set - de
 * facto identical across manufacturers - not anything specific to IMSProg or flashrom; only the
 * underlying byte-transport ([Ch341Spi]) was ported from GPL code. Chip *identification*
 * ([SpiFlashDatabase]) does draw on IMSProg's chip database - see that file's doc comment.
 */
class SpiFlash(private val spi: Ch341Spi) {

    data class JedecId(val manufacturer: Int, val memoryType: Int, val capacityCode: Int) {
        /** Standard JEDEC convention: capacity in bytes = 2^capacityCode (valid for most parts). */
        val sizeBytes: Long get() = if (capacityCode in 8..30) 1L shl capacityCode else 0L
    }

    /** Bundles the raw JEDEC ID with the looked-up [SpiFlashDatabase.ChipPreset] - see
     *  [detectChip]. Keeping the raw ID around (rather than just the resolved name/size, as this
     *  used to return) is what lets the caller show the actual manufacturer and JEDEC bytes
     *  alongside the resolved chip name, instead of only ever showing "-" for those fields. */
    data class DetectedChip(val jedecId: JedecId, val preset: SpiFlashDatabase.ChipPreset)

    /** Set by [configureAddressing] - chips over 16MB need a 4th address byte and an explicit mode switch. */
    private var use4ByteAddr = false

    fun readJedecId(): JedecId? {
        val r = spi.transfer(byteArrayOf(CMD_JEDEC_ID.toByte()), 3) ?: return null
        return JedecId(r[0].toInt() and 0xFF, r[1].toInt() and 0xFF, r[2].toInt() and 0xFF)
    }

    /** Reads the JEDEC ID and looks it up in [SpiFlashDatabase]. Returns null on a USB failure or
     *  if no chip answers (see [SpiFlashDatabase.identify]). */
    fun detectChip(): DetectedChip? {
        val id = readJedecId() ?: return null
        val preset = SpiFlashDatabase.identify(id.manufacturer, id.memoryType, id.capacityCode) ?: return null
        return DetectedChip(id, preset)
    }

    fun readStatus(): Int? {
        val r = spi.transfer(byteArrayOf(CMD_RDSR.toByte()), 1) ?: return null
        return r[0].toInt() and 0xFF
    }

    private fun writeEnable(): Boolean = spi.transfer(byteArrayOf(CMD_WREN.toByte()), 0) != null

    private fun waitWhileBusy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val sr = readStatus() ?: return false
            if (sr and SR_WIP == 0) return true
            Thread.sleep(2)
        }
        return false
    }

    /**
     * Call once after picking/detecting a chip, before any read/write/erase, with the chip's
     * total size. Chips over 16MB (128Mbit) can't be addressed with the standard 3-byte SPI NOR
     * address, so above that this both flips every following [addr] call over to 4 bytes and
     * issues the generic "enter 4-byte mode" command (0xB7) the chip needs first. This covers the
     * common Winbond/GigaDevice/EON/XMC parts; a handful of Spansion/Cypress chips use bank-
     * register commands instead of 0xB7/0xE9 and aren't covered here - see CH341_IMSPROG_NOTES.md
     * for what IMSProg does for those.
     */
    fun configureAddressing(sizeBytes: Long): Boolean {
        val need4Byte = sizeBytes > 0x1000000L
        if (need4Byte == use4ByteAddr) return true
        use4ByteAddr = need4Byte
        return spi.transfer(byteArrayOf((if (need4Byte) CMD_EN4B else CMD_EX4B).toByte()), 0) != null
    }

    private fun addr(address: Int): ByteArray = if (use4ByteAddr) {
        byteArrayOf(
            ((address shr 24) and 0xFF).toByte(), ((address shr 16) and 0xFF).toByte(),
            ((address shr 8) and 0xFF).toByte(), (address and 0xFF).toByte()
        )
    } else {
        byteArrayOf(((address shr 16) and 0xFF).toByte(), ((address shr 8) and 0xFF).toByte(), (address and 0xFF).toByte())
    }

    /**
     * Clears the status register's block-protect bits (BP0-BP3). Some W25Q/GD25Q parts ship - or
     * get left by a previous tool - with these set, which silently turns program/erase into a
     * no-op. [eraseChip] and [write] (when erasing first) both call this automatically; exposed
     * separately in case a caller wants to unprotect without immediately erasing/writing.
     * Ported from IMSProg's `snorUnprotect()` (see NOTICE.md).
     */
    fun unprotect(): Boolean {
        val sr = readStatus() ?: return false
        val cleared = sr and 0xC3 // keep WIP/WEL (bits 0-1) and bits 6-7, clear BP0-BP3 (bits 2-5)
        if (cleared == sr) return true
        if (!writeEnable()) return false
        return spi.transfer(byteArrayOf(CMD_WRSR.toByte(), cleared.toByte()), 0) != null
    }

    /** Reads [length] bytes starting at [address]. Returns null on the first USB failure.
     *
     *  Uses [Ch341Spi.writeThenRead] rather than a single combined [Ch341Spi.transfer] call -
     *  the opcode+address (4-5 bytes) is its own write-only burst, then the chip data is its own
     *  read-only burst, both under one CS pulse. See [Ch341Spi.writeThenRead]'s doc: combining
     *  them into one call, so the first packet mixes real address bytes with read-filler, is an
     *  untested code path on real CH341A hardware and is what made every read past a single tiny
     *  JEDEC-ID-sized transfer fail.
     *
     *  [onChunk], if given, fires after each [READ_CHUNK]-sized piece lands - matching IMSProg's
     *  own read loop, which pumps Qt's event loop after every chunk so its hex view visibly fills
     *  in as the read progresses rather than only appearing once the whole thing finishes.
     */
    fun read(
        address: Int,
        length: Int,
        onProgress: ((Int, Int) -> Unit)? = null,
        onChunk: ((offset: Int, chunk: ByteArray) -> Unit)? = null
    ): ByteArray? {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = minOf(READ_CHUNK, length - offset)
            val cmd = byteArrayOf(CMD_READ.toByte()) + addr(address + offset)
            val r = spi.writeThenRead(cmd, n) ?: return null
            System.arraycopy(r, 0, out, offset, n)
            onChunk?.invoke(offset, out.copyOfRange(offset, offset + n))
            offset += n
            onProgress?.invoke(offset, length)
        }
        return out
    }

    /** Erases the whole chip. Can take tens of seconds to minutes on larger parts. */
    fun eraseChip(timeoutMs: Long = 200_000): Boolean {
        if (!unprotect()) return false
        if (!writeEnable()) return false
        if (spi.transfer(byteArrayOf(CMD_CHIP_ERASE.toByte()), 0) == null) return false
        return waitWhileBusy(timeoutMs)
    }

    /** Erases one 4KB sector containing [address]. */
    fun eraseSector4k(address: Int): Boolean {
        if (!writeEnable()) return false
        val cmd = byteArrayOf(CMD_SECTOR_ERASE_4K.toByte()) + addr(address and 0xFFF000.toInt())
        if (spi.transfer(cmd, 0) == null) return false
        return waitWhileBusy(10_000)
    }

    /**
     * Erases every 4KB sector covering [address] until [address]+[length], then writes [data]
     * page-by-page (respecting [pageSize], default 256B which covers virtually all SPI NOR
     * parts). Pass [eraseFirst] = false to skip erasing (e.g. writing to already-erased space).
     */
    fun write(
        address: Int,
        data: ByteArray,
        pageSize: Int = 256,
        eraseFirst: Boolean = true,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        if (eraseFirst) {
            if (!unprotect()) return false
            var sectorAddr = address - (address % 4096)
            val endAddr = address + data.size
            while (sectorAddr < endAddr) {
                if (!eraseSector4k(sectorAddr)) return false
                sectorAddr += 4096
            }
        }
        var offset = 0
        while (offset < data.size) {
            val addrNow = address + offset
            val bytesLeftInPage = pageSize - (addrNow % pageSize)
            val n = minOf(bytesLeftInPage, data.size - offset)
            if (!writeEnable()) return false
            val cmd = byteArrayOf(CMD_PAGE_PROGRAM.toByte()) + addr(addrNow) + data.copyOfRange(offset, offset + n)
            if (spi.transfer(cmd, 0) == null) return false
            if (!waitWhileBusy(3_000)) return false
            offset += n
            onProgress?.invoke(offset, data.size)
        }
        return true
    }

    companion object {
        private const val CMD_WREN = 0x06
        private const val CMD_RDSR = 0x05
        private const val CMD_WRSR = 0x01
        private const val CMD_READ = 0x03
        private const val CMD_PAGE_PROGRAM = 0x02
        private const val CMD_SECTOR_ERASE_4K = 0x20
        private const val CMD_CHIP_ERASE = 0xC7
        private const val CMD_JEDEC_ID = 0x9F
        private const val CMD_EN4B = 0xB7 // enter 4-byte address mode
        private const val CMD_EX4B = 0xE9 // exit 4-byte address mode
        private const val SR_WIP = 0x01 // write-in-progress bit

        /** Read chunk size. Two earlier versions of this comment are now out of date: one blamed
         *  4096 on USB fragmentation gaps and shrank it to 256 (didn't help - the real bug was
         *  [Ch341Spi.transfer] mixing the opcode+address write with read-filler in one packet
         *  sequence, fixed by switching [read] to [Ch341Spi.writeThenRead]); a second round found
         *  that even writeThenRead failed until [Ch341Spi] stopped batching every packet's OUT
         *  send before draining any replies and started sending+draining one packet at a time.
         *
         *  With that per-packet interleaving now living inside rawTransfer() itself, this
         *  constant no longer affects correctness at all - only how often [onProgress] fires and
         *  how many separate write-phase (opcode+address) round trips a whole read needs. 16384
         *  cuts that per-chunk overhead by 16x over the previous 1024 while still giving smooth
         *  progress updates (512 of them for an 8MB chip). It will NOT fix the read being slower
         *  than desktop IMSProg overall, though: on a real device this hardware's bulk endpoints
         *  report maxPacketSize=32 - exactly one CH341A packet - so an 8MB read needs on the order
         *  of 270,000 individual 31-byte data packets no matter how this constant is set, and each
         *  one is its own synchronous Android bulkTransfer() OUT+IN round trip. Desktop IMSProg
         *  gets its speed by keeping many of those round trips in flight at once (its own driver
         *  comment mentions queuing up to 32 simultaneous async reads); matching that here would
         *  mean rewriting this transport on Android's async UsbRequest API instead of the simple
         *  synchronous bulkTransfer() calls this file uses now - a real rewrite of code that took
         *  several rounds to get correct, not a constant to tune.
         */
        private const val READ_CHUNK = 16384
    }
}
