/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.eeprom

/**
 * SPI NOR flash ("25xx" family) identification for the chip picker and the "Detect" button.
 *
 * The manufacturer-ID table and every exact part entry below are parsed directly out of
 * IMSProg's own chip database (`IMSProg.Dat`, part of IMSProg 1.8.6, GPLv3 - see NOTICE.md at
 * the repo root) - every one of its 490 unique SPI_FLASH-type records (502 raw rows; 12 shared a
 * JEDEC triple with another part and were merged - see the comment above [EXACT_CHIPS]), not a
 * hand-picked subset. Extracted with a small script that reads IMSProg.Dat's fixed 68-byte
 * records the same way MainWindow::progInit() does on the desktop side (chip-type/manufacturer/
 * name as comma-separated text in the first 0x30 bytes, JEDEC ID bytes at 0x30-0x32, chip size as
 * a little-endian u32 at 0x34), filtered to the "SPI_FLASH" chip-type records only - IMSProg's
 * other six chip-type families (24_EEPROM, 93_EEPROM, 95_EEPROM, 25_EEPROM, 45_EEPROM, SPI_NAND)
 * use different command sets this app doesn't implement, so they're out of scope here.
 *
 * Anything not in [EXACT_CHIPS] still gets identified: the JEDEC convention (third ID byte =
 * log2 of the capacity in bytes) holds for most vendors, so [identify] falls back to that for a
 * part number this table has never heard of - just with a generic name instead of an exact one.
 * It's only a fallback, though - see [EXACT_CHIPS]'s doc for why every listed part stores its
 * real size explicitly rather than relying on that convention for everything.
 */
object SpiFlashDatabase {

    data class ChipPreset(val name: String, val sizeBytes: Long) {
        override fun toString(): String = name
    }

    /** manufacturer ID byte -> vendor name. Covers every vendor in IMSProg's SPI_FLASH database,
     *  including 0xE5 "Dosilicon" - distinct from 0x54 "Dosilicon/Douqi", which is a different ID
     *  byte the same company's older/other-family parts use; IMSProg's own database uses both. */
    private val MANUFACTURERS = mapOf(
        0x01 to "Spansion/Cypress", 0x0B to "XTX", 0x0E to "ACE",
        0x1C to "EON", 0x1F to "Atmel/Adesto", 0x20 to "XMC",
        0x37 to "AMIC", 0x4A to "ExcelSemi", 0x54 to "Dosilicon/Douqi",
        0x5E to "Zbit", 0x62 to "onsemi", 0x68 to "Boya", 0x7F to "Pflash",
        0x85 to "Puya", 0x8C to "ESMT", 0x9D to "ISSI", 0xA1 to "Fudan Micro",
        0xB3 to "Ucundata", 0xBA to "Zetta", 0xBF to "PCT/SST",
        0xC2 to "Macronix", 0xC4 to "Giantec", 0xC8 to "GigaDevice",
        0xD5 to "Nantronix", 0xD8 to "Yuchuang", 0xE0 to "Paragon",
        0xEF to "Winbond", 0xF8 to "Fidelix", 0xE5 to "Dosilicon"
        // Note: manufacturer byte 0x20 is "XMC" for the vast majority of parts using it (their
        // XM25Qxx family), but IMSProg.Dat also lists a handful of Micron/ST N25Q/MT25Q parts
        // under the same byte with a different memory-type byte (0xBA/0xBB rather than XMC's
        // 0x40/0x70). Those chips' own exact name in [EXACT_CHIPS] still resolves correctly since
        // it's keyed on the full (manufacturer, memoryType, capacityCode) triple - only the
        // separate, name-independent "Manufacturer" display field (see [manufacturerName]) would
        // show "XMC" for them, since that lookup only has the single manufacturer byte to go on.
    )

    /**
     * (manufacturer, memoryType, capacityCode) -> the real [ChipPreset] (name AND size), per
     * IMSProg.Dat - see this file's top doc comment for how every entry here was extracted.
     *
     * Size is stored explicitly for every single entry here, never derived from
     * `1L shl capacityCode`, because a real, non-trivial minority of parts don't follow that
     * convention on their actual JEDEC ID byte: 18 of these 490 have a real size that doesn't
     * equal 2^capacityCode at all (e.g. Spansion's older FL-series, ExcelSemi's ES25P/ES25M
     * families, Winbond's stacked-die W25M512 "multi-die" parts), and 51 more have a
     * capacityCode byte outside the 8..30 range [identify]'s generic fallback treats as sane
     * (Atmel's older AT25DF/AT26DF series encodes size in the *memoryType* byte instead and
     * reports capacityCode values like 0x00/0x01; AMIC's A25L "PT" sub-family and Pflash's
     * Pm25LQ/Pm25LD parts similarly run past 30). Trusting IMSProg's own per-chip size for every
     * entry - rather than reapplying a convention that provably doesn't hold for all of them -
     * avoids silently mis-sizing exactly those chips, which is the one thing a chip programmer
     * really can't afford to get wrong.
     *
     * 12 of the 502 raw IMSProg.Dat rows shared their full (manufacturer, memoryType,
     * capacityCode) triple with another row - the same physical part sold under two different
     * marketing names, in every case but one (both names are kept, joined with " / "). The one
     * exception - AMIC's A25L20PT and A25L40PT genuinely share a JEDEC triple in IMSProg's own
     * database (IMSProg itself can't disambiguate them by ID either) - keeps the smaller of the
     * two sizes, so a wrong guess under-reads rather than reads past the real chip's end; the
     * combined entry is tagged "[ambiguous JEDEC ID - verify size]" so it's not mistaken for a
     * confident exact match if it ever shows up in a detect result.
     */
    private val EXACT_CHIPS: Map<Triple<Int, Int, Int>, ChipPreset> = buildMap {
        // --- ACE (0x0E) ---
        put(Triple(0x0E, 0x40, 0x10), ChipPreset("ACE25AC512G", 65_536L))
        put(Triple(0x0E, 0x40, 0x11), ChipPreset("ACE25AC100G", 131_072L))
        put(Triple(0x0E, 0x40, 0x12), ChipPreset("ACE25AC200G", 262_144L))
        put(Triple(0x0E, 0x40, 0x13), ChipPreset("ACE25AC400G", 524_288L))
        // --- AMIC (0x37) ---
        put(Triple(0x37, 0x20, 0x10), ChipPreset("A25L05P", 65_536L))
        put(Triple(0x37, 0x30, 0x11), ChipPreset("A25L010", 131_072L))
        put(Triple(0x37, 0x20, 0x21), ChipPreset("A25L10PT", 131_072L))
        put(Triple(0x37, 0x20, 0x11), ChipPreset("A25L10PU", 131_072L))
        put(Triple(0x37, 0x30, 0x12), ChipPreset("A25L020", 262_144L))
        put(Triple(0x37, 0x20, 0x22), ChipPreset("A25L20PT / A25L40PT [ambiguous JEDEC ID - verify size]", 262_144L))
        put(Triple(0x37, 0x20, 0x12), ChipPreset("A25L20PU", 262_144L))
        put(Triple(0x37, 0x30, 0x13), ChipPreset("A25L040", 524_288L))
        put(Triple(0x37, 0x20, 0x13), ChipPreset("A25L40PU", 524_288L))
        put(Triple(0x37, 0x30, 0x14), ChipPreset("A25L080", 1_048_576L))
        put(Triple(0x37, 0x20, 0x24), ChipPreset("A25L80PT", 1_048_576L))
        put(Triple(0x37, 0x20, 0x14), ChipPreset("A25L80PU", 1_048_576L))
        put(Triple(0x37, 0x40, 0x14), ChipPreset("A25LQ080", 1_048_576L))
        put(Triple(0x37, 0x30, 0x15), ChipPreset("A25L016", 2_097_152L))
        put(Triple(0x37, 0x20, 0x25), ChipPreset("A25L16PT", 2_097_152L))
        put(Triple(0x37, 0x20, 0x15), ChipPreset("A25L16PU", 2_097_152L))
        put(Triple(0x37, 0x40, 0x15), ChipPreset("A25LQ16", 2_097_152L))
        put(Triple(0x37, 0x30, 0x16), ChipPreset("A25L032", 4_194_304L))
        put(Triple(0x37, 0x40, 0x16), ChipPreset("A25LQ32", 4_194_304L))
        put(Triple(0x37, 0x40, 0x17), ChipPreset("A25LQ64", 8_388_608L))
        // --- Atmel/Adesto (0x1F) ---
        put(Triple(0x1F, 0x45, 0x01), ChipPreset("AT25DF081A", 1_048_576L))
        put(Triple(0x1F, 0x46, 0x00), ChipPreset("AT26DF161", 2_097_152L))
        put(Triple(0x1F, 0x47, 0x00), ChipPreset("AT25DF321", 4_194_304L))
        put(Triple(0x1F, 0x42, 0x16), ChipPreset("AT25SL321(1.8V)", 4_194_304L))
        put(Triple(0x1F, 0x48, 0x00), ChipPreset("AT25DF641", 8_388_608L))
        put(Triple(0x1F, 0x43, 0x17), ChipPreset("AT25SL641(1.8V)", 8_388_608L))
        put(Triple(0x1F, 0x42, 0x18), ChipPreset("AT25SL128A(1.8V)", 16_777_216L))
        // --- Boya (0x68) ---
        put(Triple(0x68, 0x40, 0x10), ChipPreset("BY25D05AS", 65_536L))
        put(Triple(0x68, 0x10, 0x10), ChipPreset("BY25Q05AW", 65_536L))
        put(Triple(0x68, 0x40, 0x11), ChipPreset("BY25D10AS", 131_072L))
        put(Triple(0x68, 0x60, 0x11), ChipPreset("BY25Q10AL(1.8V)", 131_072L))
        put(Triple(0x68, 0x10, 0x11), ChipPreset("BY25Q10AW", 131_072L))
        put(Triple(0x68, 0x40, 0x12), ChipPreset("BY25D20AS", 262_144L))
        put(Triple(0x68, 0x60, 0x12), ChipPreset("BY25Q20AL(1.8V)", 262_144L))
        put(Triple(0x68, 0x10, 0x12), ChipPreset("BY25Q20BL(1.8V)", 262_144L))
        put(Triple(0x68, 0x40, 0x13), ChipPreset("BY25D40AS", 524_288L))
        put(Triple(0x68, 0x60, 0x13), ChipPreset("BY25Q40AL(1.8V)", 524_288L))
        put(Triple(0x68, 0x10, 0x13), ChipPreset("BY25Q40BL", 524_288L))
        put(Triple(0x68, 0x10, 0x14), ChipPreset("BY25Q80AW(1.8V)", 1_048_576L))
        put(Triple(0x68, 0x40, 0x14), ChipPreset("BY25Q80BS", 1_048_576L))
        put(Triple(0x68, 0x10, 0x15), ChipPreset("BY25Q16BL(1.8V)", 2_097_152L))
        put(Triple(0x68, 0x40, 0x15), ChipPreset("BY25Q16BS", 2_097_152L))
        put(Triple(0x68, 0x60, 0x16), ChipPreset("BY25Q32AL(1.8V)", 4_194_304L))
        put(Triple(0x68, 0x40, 0x16), ChipPreset("BY25Q32BS", 4_194_304L))
        put(Triple(0x68, 0x60, 0x17), ChipPreset("BY25Q64AL(1.8V)", 8_388_608L))
        put(Triple(0x68, 0x40, 0x17), ChipPreset("BY25Q64AS", 8_388_608L))
        put(Triple(0x68, 0x40, 0x18), ChipPreset("BY25Q128AS", 16_777_216L))
        put(Triple(0x68, 0x60, 0x18), ChipPreset("BY25Q128EL(1.8V)", 16_777_216L))
        put(Triple(0x68, 0x41, 0x18), ChipPreset("BY25Q128FS", 16_777_216L))
        put(Triple(0x68, 0x40, 0x19), ChipPreset("BY25Q256ES", 33_554_432L))
        put(Triple(0x68, 0x49, 0x19), ChipPreset("BY25Q256FS", 33_554_432L))
        // --- Dosilicon (0xE5) ---
        put(Triple(0xE5, 0x41, 0x17), ChipPreset("DS25M64E(1.8V)", 8_388_608L))
        put(Triple(0xE5, 0x31, 0x17), ChipPreset("DS25Q64A", 8_388_608L))
        put(Triple(0xE5, 0x42, 0x18), ChipPreset("DS25M4AB(1.8V)", 16_777_216L))
        put(Triple(0xE5, 0x41, 0x18), ChipPreset("DS25M4AE(1.8V)", 16_777_216L))
        put(Triple(0xE5, 0x31, 0x18), ChipPreset("DS25Q4AA", 16_777_216L))
        put(Triple(0xE5, 0x42, 0x19), ChipPreset("DS25M4BA(1.8V)", 33_554_432L))
        put(Triple(0xE5, 0x40, 0x1A), ChipPreset("DS25M4CB(1.8V)", 67_108_864L))
        // --- Dosilicon/Douqi (0x54) ---
        put(Triple(0x54, 0x40, 0x17), ChipPreset("DQ25Q64AS", 8_388_608L))
        // --- EON (0x1C) ---
        put(Triple(0x1C, 0x20, 0x11), ChipPreset("EN25B10T", 131_072L))
        put(Triple(0x1C, 0x42, 0x11), ChipPreset("EN25E10A", 131_072L))
        put(Triple(0x1C, 0x31, 0x11), ChipPreset("EN25F10A", 131_072L))
        put(Triple(0x1C, 0x38, 0x11), ChipPreset("EN25S10A(1.8V)", 131_072L))
        put(Triple(0x1C, 0x20, 0x12), ChipPreset("EN25B20T", 262_144L))
        put(Triple(0x1C, 0x42, 0x13), ChipPreset("EN25E40A", 262_144L))
        put(Triple(0x1C, 0x31, 0x12), ChipPreset("EN25F20A", 262_144L))
        put(Triple(0x1C, 0x38, 0x12), ChipPreset("EN25S20A(1.8V)", 262_144L))
        put(Triple(0x1C, 0x20, 0x13), ChipPreset("EN25B40T", 524_288L))
        put(Triple(0x1C, 0x31, 0x13), ChipPreset("EN25F40", 524_288L))
        put(Triple(0x1C, 0x30, 0x13), ChipPreset("EN25Q40A", 524_288L))
        put(Triple(0x1C, 0x38, 0x13), ChipPreset("EN25S40A(1.8V)", 524_288L))
        put(Triple(0x1C, 0x20, 0x14), ChipPreset("EN25B80T", 1_048_576L))
        put(Triple(0x1C, 0x31, 0x14), ChipPreset("EN25F80", 1_048_576L))
        put(Triple(0x1C, 0x30, 0x14), ChipPreset("EN25Q80B", 1_048_576L))
        put(Triple(0x1C, 0x38, 0x14), ChipPreset("EN25S80B(1.8V)", 1_048_576L))
        put(Triple(0x1C, 0x51, 0x14), ChipPreset("EN25T80", 1_048_576L))
        put(Triple(0x1C, 0x20, 0x15), ChipPreset("EN25B16T", 2_097_152L))
        put(Triple(0x1C, 0x31, 0x15), ChipPreset("EN25F16", 2_097_152L))
        put(Triple(0x1C, 0x30, 0x15), ChipPreset("EN25Q16", 2_097_152L))
        put(Triple(0x1C, 0x41, 0x15), ChipPreset("EN25QE16A", 2_097_152L))
        put(Triple(0x1C, 0x70, 0x15), ChipPreset("EN25QH16", 2_097_152L))
        put(Triple(0x1C, 0x61, 0x15), ChipPreset("EN25QW16A", 2_097_152L))
        put(Triple(0x1C, 0x38, 0x15), ChipPreset("EN25S16B(1.8V)", 2_097_152L))
        put(Triple(0x1C, 0x48, 0x15), ChipPreset("EN25SE16A(1.8V)", 2_097_152L))
        put(Triple(0x1C, 0x51, 0x15), ChipPreset("EN25T16", 2_097_152L))
        put(Triple(0x1C, 0x20, 0x16), ChipPreset("EN25B32T", 4_194_304L))
        put(Triple(0x1C, 0x31, 0x16), ChipPreset("EN25F32", 4_194_304L))
        put(Triple(0x1C, 0x30, 0x16), ChipPreset("EN25Q32C", 4_194_304L))
        put(Triple(0x1C, 0x60, 0x16), ChipPreset("EN25QA32B", 4_194_304L))
        put(Triple(0x1C, 0x41, 0x16), ChipPreset("EN25QE32A", 4_194_304L))
        put(Triple(0x1C, 0x70, 0x16), ChipPreset("EN25QH32B", 4_194_304L))
        put(Triple(0x1C, 0x61, 0x16), ChipPreset("EN25QW32A", 4_194_304L))
        put(Triple(0x1C, 0x48, 0x16), ChipPreset("EN25SE32A(1.8V)", 4_194_304L))
        put(Triple(0x1C, 0x20, 0x17), ChipPreset("EN25B64T / EN25P64", 8_388_608L))
        put(Triple(0x1C, 0x30, 0x17), ChipPreset("EN25Q64", 8_388_608L))
        put(Triple(0x1C, 0x60, 0x17), ChipPreset("EN25QA64A", 8_388_608L))
        put(Triple(0x1C, 0x70, 0x17), ChipPreset("EN25QH64A", 8_388_608L))
        put(Triple(0x1C, 0x71, 0x17), ChipPreset("EN25QX64A", 8_388_608L))
        put(Triple(0x1C, 0x38, 0x17), ChipPreset("EN25S64A(1.8V)", 8_388_608L))
        put(Triple(0x1C, 0x78, 0x17), ChipPreset("EN25SX64A(1.8V)", 8_388_608L))
        put(Triple(0x1C, 0x40, 0x17), ChipPreset("GM25Q64A", 8_388_608L))
        put(Triple(0x1C, 0x30, 0x18), ChipPreset("EN25Q128", 16_777_216L))
        put(Triple(0x1C, 0x60, 0x18), ChipPreset("EN25QA128A", 16_777_216L))
        put(Triple(0x1C, 0x70, 0x18), ChipPreset("EN25QH128A", 16_777_216L))
        put(Triple(0x1C, 0x71, 0x18), ChipPreset("EN25QX128A", 16_777_216L))
        put(Triple(0x1C, 0x78, 0x18), ChipPreset("EN25SX128A(1.8V)", 16_777_216L))
        put(Triple(0x1C, 0x40, 0x18), ChipPreset("GM25Q128A", 16_777_216L))
        put(Triple(0x1C, 0x70, 0x19), ChipPreset("EN25Q256", 33_554_432L))
        put(Triple(0x1C, 0x71, 0x19), ChipPreset("EN25QX256A", 33_554_432L))
        put(Triple(0x1C, 0x73, 0x19), ChipPreset("EN25QY256A", 33_554_432L))
        put(Triple(0x1C, 0x78, 0x19), ChipPreset("EN25SX256A(1.8V)", 33_554_432L))
        // --- ESMT (0x8C) ---
        put(Triple(0x8C, 0x20, 0x13), ChipPreset("F25L004A", 524_288L))
        put(Triple(0x8C, 0x20, 0x14), ChipPreset("F25L008A", 2_097_152L))
        put(Triple(0x8C, 0x21, 0x15), ChipPreset("F25L016", 2_097_152L))
        put(Triple(0x8C, 0x41, 0x15), ChipPreset("F25L16QA", 2_097_152L))
        put(Triple(0x8C, 0x21, 0x16), ChipPreset("F25L032", 4_194_304L))
        put(Triple(0x8C, 0x20, 0x16), ChipPreset("F25L32PA", 4_194_304L))
        put(Triple(0x8C, 0x41, 0x16), ChipPreset("F25L32QA", 4_194_304L))
        put(Triple(0x8C, 0x21, 0x17), ChipPreset("F25L064", 8_388_608L))
        put(Triple(0x8C, 0x41, 0x17), ChipPreset("F25L64QA", 8_388_608L))
        // --- ExcelSemi (0x4A) ---
        put(Triple(0x4A, 0x20, 0x11), ChipPreset("ES25P10", 262_144L))
        put(Triple(0x4A, 0x20, 0x12), ChipPreset("ES25P20", 524_288L))
        put(Triple(0x4A, 0x32, 0x13), ChipPreset("ES25M40A", 1_048_576L))
        put(Triple(0x4A, 0x20, 0x13), ChipPreset("ES25P40", 1_048_576L))
        put(Triple(0x4A, 0x32, 0x14), ChipPreset("ES25M80A", 2_097_152L))
        put(Triple(0x4A, 0x20, 0x14), ChipPreset("ES25P80", 2_097_152L))
        put(Triple(0x4A, 0x32, 0x15), ChipPreset("ES25M16A", 4_194_304L))
        put(Triple(0x4A, 0x20, 0x15), ChipPreset("ES25P16", 4_194_304L))
        put(Triple(0x4A, 0x20, 0x16), ChipPreset("ES25P32", 8_388_608L))
        // --- Fidelix (0xF8) ---
        put(Triple(0xF8, 0x42, 0x13), ChipPreset("FM25M04A(1.8V)", 524_288L))
        put(Triple(0xF8, 0x32, 0x13), ChipPreset("FM25Q04A", 524_288L))
        put(Triple(0xF8, 0x42, 0x14), ChipPreset("FM25M08A(1.8V)", 1_048_576L))
        put(Triple(0xF8, 0x32, 0x14), ChipPreset("FM25Q08A", 1_048_576L))
        put(Triple(0xF8, 0x42, 0x15), ChipPreset("FM25M16A(1.8V)", 2_097_152L))
        put(Triple(0xF8, 0x32, 0x15), ChipPreset("FM25Q16A", 2_097_152L))
        put(Triple(0xF8, 0x42, 0x16), ChipPreset("FM25M32A(1.8V)", 4_194_304L))
        put(Triple(0xF8, 0x32, 0x16), ChipPreset("FM25Q32A", 4_194_304L))
        put(Triple(0xF8, 0x42, 0x17), ChipPreset("FM25M64A(1.8V)", 8_388_608L))
        put(Triple(0xF8, 0x32, 0x17), ChipPreset("FM25Q64A", 8_388_608L))
        put(Triple(0xF8, 0x42, 0x18), ChipPreset("FM25M4AA(1.8V)", 16_777_216L))
        put(Triple(0xF8, 0x32, 0x18), ChipPreset("FM25Q128A", 16_777_216L))
        // --- Fudan Micro (0xA1) ---
        put(Triple(0xA1, 0x31, 0x10), ChipPreset("FM25F005", 65_536L))
        put(Triple(0xA1, 0x31, 0x11), ChipPreset("FM25F01B", 131_072L))
        put(Triple(0xA1, 0x31, 0x12), ChipPreset("FM25F02A", 262_144L))
        put(Triple(0xA1, 0x28, 0x12), ChipPreset("FM25W02", 262_144L))
        put(Triple(0xA1, 0x31, 0x13), ChipPreset("FM25F04A", 524_288L))
        put(Triple(0xA1, 0x40, 0x13), ChipPreset("FM25Q04", 524_288L))
        put(Triple(0xA1, 0x28, 0x13), ChipPreset("FM25W04", 524_288L))
        put(Triple(0xA1, 0x40, 0x14), ChipPreset("FM25Q08", 1_048_576L))
        put(Triple(0xA1, 0x40, 0x15), ChipPreset("FM25Q16", 2_097_152L))
        put(Triple(0xA1, 0x28, 0x15), ChipPreset("FM25W16", 2_097_152L))
        put(Triple(0xA1, 0x28, 0x16), ChipPreset("FM25W32", 4_194_304L))
        put(Triple(0xA1, 0x40, 0x16), ChipPreset("FS25Q32", 4_194_304L))
        put(Triple(0xA1, 0x40, 0x17), ChipPreset("FM25Q64", 8_388_608L))
        put(Triple(0xA1, 0x28, 0x17), ChipPreset("FM25W64", 8_388_608L))
        put(Triple(0xA1, 0x40, 0x18), ChipPreset("FM25Q128", 16_777_216L))
        put(Triple(0xA1, 0x28, 0x18), ChipPreset("FM25W128", 16_777_216L))
        // --- Giantec (0xC4) ---
        put(Triple(0xC4, 0x40, 0x10), ChipPreset("GT25D05C", 65_536L))
        put(Triple(0xC4, 0x60, 0x10), ChipPreset("GT25D05E", 65_536L))
        put(Triple(0xC4, 0x40, 0x11), ChipPreset("GT25D10C", 131_072L))
        put(Triple(0xC4, 0x60, 0x11), ChipPreset("GT25D10E", 131_072L))
        put(Triple(0xC4, 0x40, 0x12), ChipPreset("GT25D20C", 262_144L))
        put(Triple(0xC4, 0x60, 0x12), ChipPreset("GT25D20E", 262_144L))
        put(Triple(0xC4, 0x40, 0x13), ChipPreset("GT25Q40C", 524_288L))
        put(Triple(0xC4, 0x60, 0x14), ChipPreset("GT25Q80A", 1_048_576L))
        put(Triple(0xC4, 0x60, 0x15), ChipPreset("GT25Q16A", 2_097_152L))
        put(Triple(0xC4, 0x60, 0x16), ChipPreset("GT25Q32A-H", 4_194_304L))
        put(Triple(0xC4, 0x60, 0x17), ChipPreset("GT25Q64A-S", 8_388_608L))
        // --- GigaDevice (0xC8) ---
        put(Triple(0xC8, 0x40, 0x10), ChipPreset("GD25D05C", 65_536L))
        put(Triple(0xC8, 0x60, 0x10), ChipPreset("GD25LD05C(1.8V)", 65_536L))
        put(Triple(0xC8, 0x64, 0x10), ChipPreset("GD25WD05C", 65_536L))
        put(Triple(0xC8, 0x40, 0x11), ChipPreset("GD25D10C", 131_072L))
        put(Triple(0xC8, 0x60, 0x11), ChipPreset("GD25LD10C(1.8V)", 131_072L))
        put(Triple(0xC8, 0x64, 0x11), ChipPreset("GD25WD10C", 131_072L))
        put(Triple(0xC8, 0x60, 0x12), ChipPreset("GD25LD20C(1.8V)", 262_144L))
        put(Triple(0xC8, 0x40, 0x12), ChipPreset("GD25Q20C", 262_144L))
        put(Triple(0xC8, 0x42, 0x12), ChipPreset("GD25VQ21B", 262_144L))
        put(Triple(0xC8, 0x64, 0x12), ChipPreset("GD25WD20E", 262_144L))
        put(Triple(0xC8, 0x65, 0x12), ChipPreset("GD25WQ20E", 262_144L))
        put(Triple(0xC8, 0x30, 0x13), ChipPreset("GD25D40", 524_288L))
        put(Triple(0xC8, 0x20, 0x13), ChipPreset("GD25F40", 524_288L))
        put(Triple(0xC8, 0x60, 0x13), ChipPreset("GD25LD40C(1.8V)", 524_288L))
        put(Triple(0xC8, 0x40, 0x13), ChipPreset("GD25Q40C", 524_288L))
        put(Triple(0xC8, 0x42, 0x13), ChipPreset("GD25VQ40C", 524_288L))
        put(Triple(0xC8, 0x64, 0x13), ChipPreset("GD25WD40E", 524_288L))
        put(Triple(0xC8, 0x65, 0x13), ChipPreset("GD25WQ40E", 524_288L))
        put(Triple(0xC8, 0x30, 0x14), ChipPreset("GD25D80", 1_048_576L))
        put(Triple(0xC8, 0x20, 0x14), ChipPreset("GD25F80", 1_048_576L))
        put(Triple(0xC8, 0x60, 0x14), ChipPreset("GD25LQ80C", 1_048_576L))
        put(Triple(0xC8, 0x40, 0x14), ChipPreset("GD25Q80C", 1_048_576L))
        put(Triple(0xC8, 0x42, 0x14), ChipPreset("GD25VQ80C", 1_048_576L))
        put(Triple(0xC8, 0x64, 0x14), ChipPreset("GD25WD80C", 1_048_576L))
        put(Triple(0xC8, 0x65, 0x14), ChipPreset("GD25WQ80E", 1_048_576L))
        put(Triple(0xC8, 0x60, 0x15), ChipPreset("GD25LQ16C", 2_097_152L))
        put(Triple(0xC8, 0x40, 0x15), ChipPreset("GD25Q16C", 2_097_152L))
        put(Triple(0xC8, 0x42, 0x15), ChipPreset("GD25VQ16C", 2_097_152L))
        put(Triple(0xC8, 0x65, 0x15), ChipPreset("GD25WQ16E", 2_097_152L))
        put(Triple(0xC8, 0x60, 0x16), ChipPreset("GD25LQ32E", 4_194_304L))
        put(Triple(0xC8, 0x40, 0x16), ChipPreset("GD25Q32", 4_194_304L))
        put(Triple(0xC8, 0x65, 0x16), ChipPreset("GD25WQ32E", 4_194_304L))
        put(Triple(0xC8, 0x60, 0x17), ChipPreset("GD25LQ64E(1.8V)", 8_388_608L))
        put(Triple(0xC8, 0x40, 0x17), ChipPreset("GD25Q64CSIG", 8_388_608L))
        put(Triple(0xC8, 0x65, 0x17), ChipPreset("GD25WQ64E", 8_388_608L))
        put(Triple(0xC8, 0x60, 0x18), ChipPreset("GD25LQ128(1.8V)", 16_777_216L))
        put(Triple(0xC8, 0x40, 0x18), ChipPreset("GD25Q128CSIG", 16_777_216L))
        put(Triple(0xC8, 0x65, 0x18), ChipPreset("GD25WQ128E", 16_777_216L))
        put(Triple(0xC8, 0x60, 0x19), ChipPreset("GD25LQ256D(1.8V)", 33_554_432L))
        put(Triple(0xC8, 0x66, 0x19), ChipPreset("GD25LT256E(1.8V)", 33_554_432L))
        put(Triple(0xC8, 0x40, 0x19), ChipPreset("GD25Q256CSIG", 33_554_432L))
        put(Triple(0xC8, 0x65, 0x19), ChipPreset("GD25WB256E", 33_554_432L))
        put(Triple(0xC8, 0x47, 0x1A), ChipPreset("GD25B512ME", 67_108_864L))
        put(Triple(0xC8, 0x67, 0x1A), ChipPreset("GD25LB512ME(1.8V)", 67_108_864L))
        put(Triple(0xC8, 0x60, 0x1A), ChipPreset("GD25LR512MF(1.8V)", 67_108_864L))
        put(Triple(0xC8, 0x66, 0x1A), ChipPreset("GD25LT512ME(1.8V)", 67_108_864L))
        // --- ISSI (0x9D) ---
        put(Triple(0x9D, 0x70, 0x10), ChipPreset("IS25WP512E(1.8V)", 65_536L))
        put(Triple(0x9D, 0x40, 0x11), ChipPreset("IS25LQ010", 131_072L))
        put(Triple(0x9D, 0x70, 0x11), ChipPreset("IS25WP010E(1.8V)", 131_072L))
        put(Triple(0x9D, 0x40, 0x12), ChipPreset("IS25LQ020", 262_144L))
        put(Triple(0x9D, 0x70, 0x12), ChipPreset("IS25WP020E(1.8V)", 262_144L))
        put(Triple(0x9D, 0x11, 0x52), ChipPreset("IS25WQ020(1.8V)", 262_144L))
        put(Triple(0x9D, 0x40, 0x13), ChipPreset("IS25LP040E", 524_288L))
        put(Triple(0x9D, 0x70, 0x13), ChipPreset("IS25WP040D(1.8V)", 524_288L))
        put(Triple(0x9D, 0x12, 0x53), ChipPreset("IS25WQ040(1.8V)", 524_288L))
        put(Triple(0x9D, 0x60, 0x14), ChipPreset("IS25LP080D", 1_048_576L))
        put(Triple(0x9D, 0x70, 0x14), ChipPreset("IS25WP080D(1.8V)", 1_048_576L))
        put(Triple(0x9D, 0x13, 0x54), ChipPreset("IS25WQ080(1.8V)", 1_048_576L))
        put(Triple(0x9D, 0x60, 0x15), ChipPreset("IS25LP016D", 2_097_152L))
        put(Triple(0x9D, 0x70, 0x15), ChipPreset("IS25WP016D(1.8V)", 2_097_152L))
        put(Triple(0x9D, 0x60, 0x16), ChipPreset("IS25LP032D", 4_194_304L))
        put(Triple(0x9D, 0x70, 0x16), ChipPreset("IS25WP032D(1.8V)", 4_194_304L))
        put(Triple(0x9D, 0x60, 0x17), ChipPreset("IS25LP064D", 8_388_608L))
        put(Triple(0x9D, 0x70, 0x17), ChipPreset("IS25WP064D(1.8V)", 8_388_608L))
        put(Triple(0x9D, 0x60, 0x18), ChipPreset("IS25LP128F", 16_777_216L))
        put(Triple(0x9D, 0x70, 0x18), ChipPreset("IS25WP128F(1.8V)", 16_777_216L))
        put(Triple(0x9D, 0x60, 0x19), ChipPreset("IS25LP256D", 33_554_432L))
        put(Triple(0x9D, 0x70, 0x19), ChipPreset("IS25WP256D(1.8V)", 33_554_432L))
        put(Triple(0x9D, 0x60, 0x1A), ChipPreset("IS25LP512D", 67_108_864L))
        put(Triple(0x9D, 0x70, 0x1A), ChipPreset("IS25WP512D(1.8V)", 67_108_864L))
        // --- Macronix (0xC2) ---
        put(Triple(0xC2, 0x22, 0x10), ChipPreset("MX25L5121E", 65_536L))
        put(Triple(0xC2, 0x20, 0x10), ChipPreset("MX25L512E", 65_536L))
        put(Triple(0xC2, 0x28, 0x10), ChipPreset("MX25R512F", 65_536L))
        put(Triple(0xC2, 0x25, 0x30), ChipPreset("MX25U5121E(1.8V)", 65_536L))
        put(Triple(0xC2, 0x20, 0x11), ChipPreset("MX25L1006E", 131_072L))
        put(Triple(0xC2, 0x22, 0x11), ChipPreset("MX25L1021E", 131_072L))
        put(Triple(0xC2, 0x28, 0x11), ChipPreset("MX25R1035F", 131_072L))
        put(Triple(0xC2, 0x25, 0x31), ChipPreset("MX25U1001E(1.8V)", 131_072L))
        put(Triple(0xC2, 0x20, 0x12), ChipPreset("MX25L2005", 262_144L))
        put(Triple(0xC2, 0x28, 0x12), ChipPreset("MX25R2035F", 262_144L))
        put(Triple(0xC2, 0x25, 0x32), ChipPreset("MX25U2035F(1.8V)", 262_144L))
        put(Triple(0xC2, 0x20, 0x13), ChipPreset("MX25L4005A", 524_288L))
        put(Triple(0xC2, 0x28, 0x13), ChipPreset("MX25R4035F", 524_288L))
        put(Triple(0xC2, 0x25, 0x33), ChipPreset("MX25U4035F(1.8V)", 524_288L))
        put(Triple(0xC2, 0x20, 0x14), ChipPreset("MX25L8005M", 1_048_576L))
        put(Triple(0xC2, 0x28, 0x14), ChipPreset("MX25R8035F", 1_048_576L))
        put(Triple(0xC2, 0x25, 0x34), ChipPreset("MX25U80356(1.8V)", 1_048_576L))
        put(Triple(0xC2, 0x23, 0x14), ChipPreset("MX25V8035F", 1_048_576L))
        put(Triple(0xC2, 0x20, 0x15), ChipPreset("MX25L1605D", 2_097_152L))
        put(Triple(0xC2, 0x24, 0x15), ChipPreset("MX25L1633E", 2_097_152L))
        put(Triple(0xC2, 0x25, 0x15), ChipPreset("MX25L1635E", 2_097_152L))
        put(Triple(0xC2, 0x26, 0x15), ChipPreset("MX25L1655D", 2_097_152L))
        put(Triple(0xC2, 0x28, 0x15), ChipPreset("MX25R1635F", 2_097_152L))
        put(Triple(0xC2, 0x25, 0x35), ChipPreset("MX25U1632F(1.8V)", 2_097_152L))
        put(Triple(0xC2, 0x20, 0x16), ChipPreset("MX25L3205D", 4_194_304L))
        put(Triple(0xC2, 0x5E, 0x16), ChipPreset("MX25L3225D", 4_194_304L))
        put(Triple(0xC2, 0x9E, 0x16), ChipPreset("MX25L3255D", 4_194_304L))
        put(Triple(0xC2, 0x28, 0x16), ChipPreset("MX25R3235F", 4_194_304L))
        put(Triple(0xC2, 0x25, 0x36), ChipPreset("MX25U3232F(1.8V)", 4_194_304L))
        put(Triple(0xC2, 0x20, 0x17), ChipPreset("MX25L6405D", 8_388_608L))
        put(Triple(0xC2, 0x26, 0x17), ChipPreset("MX25L6455E", 8_388_608L))
        put(Triple(0xC2, 0x28, 0x17), ChipPreset("MX25R6435F", 8_388_608L))
        put(Triple(0xC2, 0x25, 0x37), ChipPreset("MX25U6432F(1.8V)", 8_388_608L))
        put(Triple(0xC2, 0x20, 0x18), ChipPreset("MX25L12805D", 16_777_216L))
        put(Triple(0xC2, 0x26, 0x18), ChipPreset("MX25L12855E", 16_777_216L))
        put(Triple(0xC2, 0x25, 0x38), ChipPreset("MX25U12832F(1.8V)", 16_777_216L))
        put(Triple(0xC2, 0x20, 0x19), ChipPreset("MX25L25635E", 33_554_432L))
        put(Triple(0xC2, 0x26, 0x19), ChipPreset("MX25L25655E", 33_554_432L))
        put(Triple(0xC2, 0x25, 0x39), ChipPreset("MX25U25643G(1.8V)", 33_554_432L))
        put(Triple(0xC2, 0x20, 0x1A), ChipPreset("MX25L51245G", 67_108_864L))
        put(Triple(0xC2, 0x25, 0x3A), ChipPreset("MX25U51245G(1.8V)", 67_108_864L))
        // --- Nantronix (0xD5) ---
        put(Triple(0xD5, 0x30, 0x14), ChipPreset("N25S80", 1_048_576L))
        put(Triple(0xD5, 0x30, 0x16), ChipPreset("N25S32", 4_194_304L))
        // --- PCT/SST (0xBF) ---
        put(Triple(0xBF, 0x49, 0x00), ChipPreset("PCT25VF010A", 131_072L))
        put(Triple(0xBF, 0x25, 0x8C), ChipPreset("PCT25VF020B", 262_144L))
        put(Triple(0xBF, 0x25, 0x8D), ChipPreset("PCT25VF040B", 524_288L))
        put(Triple(0xBF, 0x25, 0x8E), ChipPreset("PCT25VF080B", 1_048_576L))
        put(Triple(0xBF, 0x25, 0x41), ChipPreset("PCT25VF016B", 2_097_152L))
        put(Triple(0xBF, 0x26, 0x01), ChipPreset("PCT26VF016", 2_097_152L))
        put(Triple(0xBF, 0x25, 0x4A), ChipPreset("PCT25VF032B", 4_194_304L))
        put(Triple(0xBF, 0x26, 0x02), ChipPreset("PCT26VF032", 4_194_304L))
        put(Triple(0xBF, 0x25, 0x4B), ChipPreset("PCT25VF064C", 8_388_608L))
        // --- Paragon (0xE0) ---
        put(Triple(0xE0, 0x40, 0x14), ChipPreset("PN25F08", 1_048_576L))
        put(Triple(0xE0, 0x40, 0x15), ChipPreset("PN25F16", 2_097_152L))
        put(Triple(0xE0, 0x40, 0x16), ChipPreset("PN25F32", 4_194_304L))
        put(Triple(0xE0, 0x40, 0x17), ChipPreset("PN25F64", 8_388_608L))
        put(Triple(0xE0, 0x40, 0x18), ChipPreset("PN25F128", 16_777_216L))
        // --- Pflash (0x7F) ---
        put(Triple(0x7F, 0x9D, 0x20), ChipPreset("Pm25LQ512B", 65_536L))
        put(Triple(0x7F, 0x9D, 0x21), ChipPreset("Pm25LQ010B", 131_072L))
        put(Triple(0x7F, 0x9D, 0x7C), ChipPreset("Pm25LV010", 131_072L))
        put(Triple(0x7F, 0x9D, 0x22), ChipPreset("Pm25LD020", 262_144L))
        put(Triple(0x7F, 0x9D, 0x42), ChipPreset("Pm25LQ020B", 262_144L))
        put(Triple(0x7F, 0x9D, 0x7D), ChipPreset("Pm25LV020", 262_144L))
        put(Triple(0x7F, 0x9D, 0x7E), ChipPreset("Pm25LQ040B / Pm25LV040", 524_288L))
        put(Triple(0x7F, 0x9D, 0x45), ChipPreset("PM25LQ016", 2_097_152L))
        put(Triple(0x7F, 0x9D, 0x46), ChipPreset("PM25LQ032", 4_194_304L))
        put(Triple(0x7F, 0x9D, 0x47), ChipPreset("PM25LQ064", 8_388_608L))
        put(Triple(0x7F, 0x9D, 0x48), ChipPreset("PM25LQ128", 16_777_216L))
        // --- Puya (0x85) ---
        put(Triple(0x85, 0x00, 0x10), ChipPreset("P25Q06H", 65_536L))
        put(Triple(0x85, 0x60, 0x11), ChipPreset("P25Q10H", 131_072L))
        put(Triple(0x85, 0x40, 0x11), ChipPreset("P25Q11H", 131_072L))
        put(Triple(0x85, 0x60, 0x12), ChipPreset("P25Q20H", 262_144L))
        put(Triple(0x85, 0x40, 0x12), ChipPreset("P25Q21H", 262_144L))
        put(Triple(0x85, 0x20, 0x13), ChipPreset("P25Q40H", 524_288L))
        put(Triple(0x85, 0x60, 0x13), ChipPreset("P25Q40H", 524_288L))
        put(Triple(0x85, 0x60, 0x14), ChipPreset("P25Q80H", 1_048_576L))
        put(Triple(0x85, 0x60, 0x15), ChipPreset("P25Q16H", 2_097_152L))
        put(Triple(0x85, 0x60, 0x16), ChipPreset("P25Q32H", 4_194_304L))
        put(Triple(0x85, 0x60, 0x17), ChipPreset("P25Q64H", 8_388_608L))
        put(Triple(0x85, 0x60, 0x18), ChipPreset("P25Q128H", 16_777_216L))
        put(Triple(0x85, 0x20, 0x18), ChipPreset("PY25Q128HA", 16_777_216L))
        put(Triple(0x85, 0x20, 0x19), ChipPreset("PY25Q256HB", 33_554_432L))
        put(Triple(0x85, 0x20, 0x1A), ChipPreset("PY25Q512HB", 67_108_864L))
        // --- Spansion/Cypress (0x01) ---
        put(Triple(0x01, 0x40, 0x13), ChipPreset("S25FL204K", 524_288L))
        put(Triple(0x01, 0x40, 0x14), ChipPreset("S25FL208K", 1_048_576L))
        put(Triple(0x01, 0x02, 0x14), ChipPreset("FL016AIF / S25FL016P", 2_097_152L))
        put(Triple(0x01, 0x40, 0x15), ChipPreset("S25FL116K", 2_097_152L))
        put(Triple(0x01, 0x02, 0x15), ChipPreset("S25FL032P", 4_194_304L))
        put(Triple(0x01, 0x40, 0x16), ChipPreset("S25FL132K", 4_194_304L))
        put(Triple(0x01, 0x02, 0x16), ChipPreset("FL064AIF / S25FL064P", 8_388_608L))
        put(Triple(0x01, 0x40, 0x17), ChipPreset("S25FL164K", 8_388_608L))
        put(Triple(0x01, 0x20, 0x18), ChipPreset("S25FL128P / S25FL129P", 16_777_216L))
        put(Triple(0x01, 0x02, 0x19), ChipPreset("S25FL256S", 33_554_432L))
        // --- Ucundata (0xB3) ---
        put(Triple(0xB3, 0x60, 0x10), ChipPreset("UC25HQ05", 65_536L))
        put(Triple(0xB3, 0x60, 0x11), ChipPreset("UC25HQ10", 131_072L))
        put(Triple(0xB3, 0x60, 0x12), ChipPreset("UC25HQ20", 262_144L))
        put(Triple(0xB3, 0x60, 0x13), ChipPreset("UC25HQ40", 524_288L))
        put(Triple(0xB3, 0x60, 0x14), ChipPreset("UC25HQ80", 1_048_576L))
        put(Triple(0xB3, 0x60, 0x15), ChipPreset("UC25HQ16", 2_097_152L))
        put(Triple(0xB3, 0x60, 0x16), ChipPreset("UC25HQ32", 4_194_304L))
        put(Triple(0xB3, 0x60, 0x17), ChipPreset("UC25HQ64", 8_388_608L))
        put(Triple(0xB3, 0x40, 0x18), ChipPreset("UC25IQ128", 16_777_216L))
        // --- Winbond (0xEF) ---
        put(Triple(0xEF, 0x40, 0x10), ChipPreset("W25Q05CL", 65_536L))
        put(Triple(0xEF, 0x30, 0x10), ChipPreset("W25X05", 65_536L))
        put(Triple(0xEF, 0x40, 0x11), ChipPreset("W25Q10CL", 131_072L))
        put(Triple(0xEF, 0x60, 0x11), ChipPreset("W25Q10EW(1.8V)", 131_072L))
        put(Triple(0xEF, 0x30, 0x11), ChipPreset("W25X10", 131_072L))
        put(Triple(0xEF, 0x50, 0x12), ChipPreset("W25Q20BW(1.8V)", 262_144L))
        put(Triple(0xEF, 0x40, 0x12), ChipPreset("W25Q20CL", 262_144L))
        put(Triple(0xEF, 0x60, 0x12), ChipPreset("W25Q20EW(1.8V)", 262_144L))
        put(Triple(0xEF, 0x30, 0x12), ChipPreset("W25X20", 262_144L))
        put(Triple(0xEF, 0x40, 0x13), ChipPreset("W25Q40BV", 524_288L))
        put(Triple(0xEF, 0x50, 0x13), ChipPreset("W25Q40BW(1.8V)", 524_288L))
        put(Triple(0xEF, 0x60, 0x13), ChipPreset("W25Q40EW(1.8V)", 524_288L))
        put(Triple(0xEF, 0x30, 0x13), ChipPreset("W25X40", 524_288L))
        put(Triple(0xEF, 0x20, 0x14), ChipPreset("W25P80", 1_048_576L))
        put(Triple(0xEF, 0x50, 0x14), ChipPreset("W25Q80", 1_048_576L))
        put(Triple(0xEF, 0x40, 0x14), ChipPreset("W25Q80BL", 1_048_576L))
        put(Triple(0xEF, 0x60, 0x14), ChipPreset("W25Q80EW(1.8V)", 1_048_576L))
        put(Triple(0xEF, 0x30, 0x14), ChipPreset("W25X80", 1_048_576L))
        put(Triple(0xEF, 0x20, 0x15), ChipPreset("W25P16", 2_097_152L))
        put(Triple(0xEF, 0x40, 0x15), ChipPreset("W25Q16DV", 2_097_152L))
        put(Triple(0xEF, 0x60, 0x15), ChipPreset("W25Q16JW(1.8V)", 2_097_152L))
        put(Triple(0xEF, 0x30, 0x15), ChipPreset("W25X16", 2_097_152L))
        put(Triple(0xEF, 0x20, 0x16), ChipPreset("W25P32", 4_194_304L))
        put(Triple(0xEF, 0x40, 0x16), ChipPreset("W25Q32BV", 4_194_304L))
        put(Triple(0xEF, 0x60, 0x16), ChipPreset("W25Q32FW(1.8V)", 4_194_304L))
        put(Triple(0xEF, 0x80, 0x16), ChipPreset("W25Q32JW(1.8V)", 4_194_304L))
        put(Triple(0xEF, 0x30, 0x16), ChipPreset("W25X32VS", 4_194_304L))
        put(Triple(0xEF, 0x20, 0x17), ChipPreset("W25P64", 8_388_608L))
        put(Triple(0xEF, 0x40, 0x17), ChipPreset("W25Q64BV", 8_388_608L))
        put(Triple(0xEF, 0x60, 0x17), ChipPreset("W25Q64DW(1.8V)", 8_388_608L))
        put(Triple(0xEF, 0x30, 0x17), ChipPreset("W25X64", 8_388_608L))
        put(Triple(0xEF, 0x40, 0x18), ChipPreset("W25Q128BV", 16_777_216L))
        put(Triple(0xEF, 0x60, 0x18), ChipPreset("W25Q128FW(1.8V)", 16_777_216L))
        put(Triple(0xEF, 0x40, 0x19), ChipPreset("W25Q256FV", 33_554_432L))
        put(Triple(0xEF, 0x60, 0x19), ChipPreset("W25Q256JW(1.8V)", 33_554_432L))
        put(Triple(0xEF, 0x71, 0x19), ChipPreset("W25M512JV", 67_108_864L))
        put(Triple(0xEF, 0x61, 0x19), ChipPreset("W25M512JW(1.8V)", 67_108_864L))
        put(Triple(0xEF, 0x70, 0x20), ChipPreset("W25Q512JV", 67_108_864L))
        put(Triple(0xEF, 0x80, 0x20), ChipPreset("W25Q512NW(1.8V)", 67_108_864L))
        // --- XMC (0x20) ---
        put(Triple(0x20, 0x20, 0x11), ChipPreset("M25P10", 131_072L))
        put(Triple(0x20, 0x40, 0x11), ChipPreset("XM25QH10B", 131_072L))
        put(Triple(0x20, 0x20, 0x12), ChipPreset("M25P20", 262_144L))
        put(Triple(0x20, 0x40, 0x12), ChipPreset("XM25QH20B", 262_144L))
        put(Triple(0x20, 0x20, 0x13), ChipPreset("M25P40", 524_288L))
        put(Triple(0x20, 0x40, 0x13), ChipPreset("XM25QH40B", 524_288L))
        put(Triple(0x20, 0x50, 0x13), ChipPreset("XM25QU41B(1.8V)", 524_288L))
        put(Triple(0x20, 0x20, 0x14), ChipPreset("M25P80", 1_048_576L))
        put(Triple(0x20, 0x40, 0x14), ChipPreset("XM25QH80B", 1_048_576L))
        put(Triple(0x20, 0x50, 0x14), ChipPreset("XM25QU80B(1.8V)", 1_048_576L))
        put(Triple(0x20, 0x20, 0x15), ChipPreset("M25P016", 2_097_152L))
        put(Triple(0x20, 0xBB, 0x15), ChipPreset("N25Q016A11E(1.8V)", 2_097_152L))
        put(Triple(0x20, 0x40, 0x15), ChipPreset("XM25QH16C", 2_097_152L))
        put(Triple(0x20, 0x50, 0x15), ChipPreset("XM25QU16C(1.8V)", 2_097_152L))
        put(Triple(0x20, 0x42, 0x15), ChipPreset("XM25QW16C", 2_097_152L))
        put(Triple(0x20, 0x20, 0x16), ChipPreset("M25P32", 4_194_304L))
        put(Triple(0x20, 0xBA, 0x16), ChipPreset("N25Q032A", 4_194_304L))
        put(Triple(0x20, 0xBB, 0x16), ChipPreset("N25Q032A(1.8V)", 4_194_304L))
        put(Triple(0x20, 0x50, 0x16), ChipPreset("XM25LU32C(1.8V)", 4_194_304L))
        put(Triple(0x20, 0x70, 0x16), ChipPreset("XM25QH32A", 4_194_304L))
        put(Triple(0x20, 0x40, 0x16), ChipPreset("XM25QH32B", 4_194_304L))
        put(Triple(0x20, 0x42, 0x16), ChipPreset("XM25QW32C", 4_194_304L))
        put(Triple(0x20, 0x20, 0x17), ChipPreset("M25P64", 8_388_608L))
        put(Triple(0x20, 0xBA, 0x17), ChipPreset("N25Q064A / MT25QL64AB", 8_388_608L))
        put(Triple(0x20, 0xBB, 0x17), ChipPreset("N25Q064A(1.8V) / MT25QU64AB(1.8V)", 8_388_608L))
        put(Triple(0x20, 0x41, 0x17), ChipPreset("XM25LU64C(1.8V)", 8_388_608L))
        put(Triple(0x20, 0x70, 0x17), ChipPreset("XM25QH64A", 8_388_608L))
        put(Triple(0x20, 0x40, 0x17), ChipPreset("XM25QH64C", 8_388_608L))
        put(Triple(0x20, 0x42, 0x17), ChipPreset("XM25QW64C", 8_388_608L))
        put(Triple(0x20, 0x20, 0x18), ChipPreset("M25P128", 16_777_216L))
        put(Triple(0x20, 0xBA, 0x18), ChipPreset("N25Q128A / MT25QL128AB", 16_777_216L))
        put(Triple(0x20, 0xBB, 0x18), ChipPreset("N25Q128A(1.8V) / MT25QU128AB(1.8V)", 16_777_216L))
        put(Triple(0x20, 0x41, 0x18), ChipPreset("XM25LU128C(1.8V)", 16_777_216L))
        put(Triple(0x20, 0x70, 0x18), ChipPreset("XM25QH128A", 16_777_216L))
        put(Triple(0x20, 0x40, 0x18), ChipPreset("XM25QH128C", 16_777_216L))
        put(Triple(0x20, 0x42, 0x18), ChipPreset("XM25QW128C", 16_777_216L))
        put(Triple(0x20, 0xBB, 0x19), ChipPreset("MT25QU256AB(1.8V)", 33_554_432L))
        put(Triple(0x20, 0xBA, 0x19), ChipPreset("N25Q256A / MT25QL256AB", 33_554_432L))
        put(Triple(0x20, 0x40, 0x19), ChipPreset("XM25QH256C", 33_554_432L))
        put(Triple(0x20, 0x41, 0x19), ChipPreset("XM25QU256C(1.8V)", 33_554_432L))
        put(Triple(0x20, 0x42, 0x19), ChipPreset("XM25QW256C", 33_554_432L))
        put(Triple(0x20, 0xBA, 0x20), ChipPreset("MT25QL512AB", 67_108_864L))
        put(Triple(0x20, 0xBB, 0x20), ChipPreset("MT25QU512AB(1.8V)", 67_108_864L))
        put(Triple(0x20, 0x40, 0x20), ChipPreset("XM25QH512C", 67_108_864L))
        put(Triple(0x20, 0x41, 0x20), ChipPreset("XM25QU512C(1.8V)", 67_108_864L))
        put(Triple(0x20, 0x42, 0x20), ChipPreset("XM25QW512C", 67_108_864L))
        // --- XTX (0x0B) ---
        put(Triple(0x0B, 0x40, 0x12), ChipPreset("XT25F02E", 262_144L))
        put(Triple(0x0B, 0x60, 0x12), ChipPreset("XT25W02E", 262_144L))
        put(Triple(0x0B, 0x40, 0x13), ChipPreset("XT25F04D", 524_288L))
        put(Triple(0x0B, 0x60, 0x13), ChipPreset("XT25W04D", 524_288L))
        put(Triple(0x0B, 0x40, 0x14), ChipPreset("XT25F08B", 1_048_576L))
        put(Triple(0x0B, 0x60, 0x14), ChipPreset("XT25Q08D(1.8V)", 1_048_576L))
        put(Triple(0x0B, 0x65, 0x14), ChipPreset("XT25W08F", 1_048_576L))
        put(Triple(0x0B, 0x40, 0x15), ChipPreset("XT25F16B", 2_097_152L))
        put(Triple(0x0B, 0x60, 0x15), ChipPreset("XT25Q16D(1.8V)", 2_097_152L))
        put(Triple(0x0B, 0x65, 0x15), ChipPreset("XT25W16F", 2_097_152L))
        put(Triple(0x0B, 0x40, 0x16), ChipPreset("XT25F32F", 4_194_304L))
        put(Triple(0x0B, 0x65, 0x16), ChipPreset("XT25W32F", 4_194_304L))
        put(Triple(0x0B, 0x40, 0x17), ChipPreset("XT25F64F", 8_388_608L))
        put(Triple(0x0B, 0x60, 0x17), ChipPreset("XT25Q64D(1.8V)", 8_388_608L))
        put(Triple(0x0B, 0x60, 0x18), ChipPreset("XT25F128D(1.8V)", 16_777_216L))
        put(Triple(0x0B, 0x40, 0x18), ChipPreset("XT25F128F", 16_777_216L))
        put(Triple(0x0B, 0x40, 0x19), ChipPreset("XT25F256B", 33_554_432L))
        put(Triple(0x0B, 0x60, 0x1A), ChipPreset("XT25Q512F(1.8V)", 67_108_864L))
        // --- Yuchuang (0xD8) ---
        put(Triple(0xD8, 0x40, 0x13), ChipPreset("YC25Q40", 524_288L))
        put(Triple(0xD8, 0x40, 0x14), ChipPreset("YC25Q80", 1_048_576L))
        put(Triple(0xD8, 0x40, 0x15), ChipPreset("YC25Q16", 2_097_152L))
        put(Triple(0xD8, 0x40, 0x16), ChipPreset("YC25Q32", 4_194_304L))
        put(Triple(0xD8, 0x40, 0x17), ChipPreset("YC25Q64", 8_388_608L))
        put(Triple(0xD8, 0x40, 0x18), ChipPreset("YC25Q128", 16_777_216L))
        // --- Zbit (0x5E) ---
        put(Triple(0x5E, 0x32, 0x11), ChipPreset("ZB25D10A", 131_072L))
        put(Triple(0x5E, 0x10, 0x11), ChipPreset("ZB25LD10A(1.8V)", 131_072L))
        put(Triple(0x5E, 0x32, 0x12), ChipPreset("ZB25D20A", 262_144L))
        put(Triple(0x5E, 0x10, 0x12), ChipPreset("ZB25LD20A(1.8V)", 262_144L))
        put(Triple(0x5E, 0x60, 0x12), ChipPreset("ZB25VQ20A", 262_144L))
        put(Triple(0x5E, 0x32, 0x13), ChipPreset("ZB25D40B", 524_288L))
        put(Triple(0x5E, 0x10, 0x13), ChipPreset("ZB25LD40B(1.8V)", 524_288L))
        put(Triple(0x5E, 0x60, 0x13), ChipPreset("ZB25VQ40A", 524_288L))
        put(Triple(0x5E, 0x32, 0x14), ChipPreset("ZB25D80B", 1_048_576L))
        put(Triple(0x5E, 0x10, 0x14), ChipPreset("ZB25LD80(1.8V)", 1_048_576L))
        put(Triple(0x5E, 0x60, 0x14), ChipPreset("ZB25VQ80A", 1_048_576L))
        put(Triple(0x5E, 0x50, 0x15), ChipPreset("ZB25LQ16(1.8V)", 2_097_152L))
        put(Triple(0x5E, 0x40, 0x15), ChipPreset("ZB25VQ16", 2_097_152L))
        put(Triple(0x5E, 0x60, 0x15), ChipPreset("ZB25VQ16A", 2_097_152L))
        put(Triple(0x5E, 0x34, 0x15), ChipPreset("ZB25WQ16A", 2_097_152L))
        put(Triple(0x5E, 0x50, 0x16), ChipPreset("ZB25LQ32(1.8V)", 4_194_304L))
        put(Triple(0x5E, 0x40, 0x16), ChipPreset("ZB25VQ32", 4_194_304L))
        put(Triple(0x5E, 0x50, 0x17), ChipPreset("ZB25LQ64(1.8V)", 8_388_608L))
        put(Triple(0x5E, 0x40, 0x17), ChipPreset("ZB25VQ64", 8_388_608L))
        put(Triple(0x5E, 0x50, 0x18), ChipPreset("ZB25LQ128(1.8V)", 16_777_216L))
        put(Triple(0x5E, 0x40, 0x18), ChipPreset("ZB25VQ128", 16_777_216L))
        put(Triple(0x5E, 0x40, 0x19), ChipPreset("ZB25Q256A", 33_554_432L))
        // --- Zetta (0xBA) ---
        put(Triple(0xBA, 0x20, 0x12), ChipPreset("ZD25D20", 262_144L))
        put(Triple(0xBA, 0x60, 0x12), ChipPreset("ZD25WD20B", 262_144L))
        put(Triple(0xBA, 0x20, 0x13), ChipPreset("ZD25D40", 524_288L))
        put(Triple(0xBA, 0x60, 0x13), ChipPreset("ZD25WD40B", 524_288L))
        put(Triple(0xBA, 0x20, 0x14), ChipPreset("ZD25D80", 1_048_576L))
        put(Triple(0xBA, 0x60, 0x14), ChipPreset("ZD25Q80C", 1_048_576L))
        put(Triple(0xBA, 0x40, 0x14), ChipPreset("ZD25WQ80C", 1_048_576L))
        put(Triple(0xBA, 0x60, 0x15), ChipPreset("ZD25Q16B", 2_097_152L))
        put(Triple(0xBA, 0x60, 0x16), ChipPreset("ZD25Q32C", 4_194_304L))
        put(Triple(0xBA, 0x43, 0x17), ChipPreset("ZD25LQ64(1.8V)", 8_388_608L))
        put(Triple(0xBA, 0x32, 0x17), ChipPreset("ZD25Q64B", 8_388_608L))
        put(Triple(0xBA, 0x42, 0x18), ChipPreset("ZD25LQ128(1.8V)", 16_777_216L))
        put(Triple(0xBA, 0x40, 0x18), ChipPreset("ZD25Q128C", 16_777_216L))
        // --- onsemi (0x62) ---
        put(Triple(0x62, 0x06, 0x12), ChipPreset("LE25U20AMB", 262_144L))
        put(Triple(0x62, 0x06, 0x13), ChipPreset("LE25U40CMC", 524_288L))
    }

    /** Chip picker presets - every exact part above, sorted by size then name. */
    val COMMON_CHIPS: List<ChipPreset> = EXACT_CHIPS.values
        .distinctBy { it.name }
        .sortedWith(compareBy({ it.sizeBytes }, { it.name }))

    /**
     * Identifies a chip from its 3-byte JEDEC ID (manufacturer, memory type, capacity code).
     *
     * Checks [EXACT_CHIPS] first, unconditionally - before any sanity check on the capacity byte
     * - because 51 of its real, verified entries have a capacityCode outside the "8..30 looks
     * like a plausible size" range the generic fallback below uses (see [EXACT_CHIPS]'s doc).
     * Rejecting those before ever checking the exact-match table would silently turn "detect"
     * into "fail to detect" for every one of those chips.
     *
     * Only falls through to the generic 2^capacityCode guess - and only then applies the sanity
     * range check - for a manufacturer/memType/capacityCode combination this table has never
     * seen at all. Returns null only in that generic path, when the capacity byte isn't a
     * plausible size either (i.e. no chip answered, or the read failed and came back all-0x00 /
     * all-0xFF).
     */
    fun identify(manufacturer: Int, memoryType: Int, capacityCode: Int): ChipPreset? {
        EXACT_CHIPS[Triple(manufacturer, memoryType, capacityCode)]?.let { return it }
        if (capacityCode !in 8..30) return null // 256B..1GB is the sane range for an unknown part
        val sizeBytes = 1L shl capacityCode
        val mfgName = manufacturerName(manufacturer)
        return ChipPreset("$mfgName SPI NOR (generic)", sizeBytes)
    }

    /** Public manufacturer-byte -> vendor name lookup, for display purposes (showing the detected
     *  chip's manufacturer as its own field rather than only folded into the chip name string).
     *  See the note on 0x20 in [MANUFACTURERS] for this lookup's one known blind spot. */
    fun manufacturerName(manufacturer: Int): String =
        MANUFACTURERS[manufacturer] ?: "Unknown mfg 0x%02X".format(manufacturer)
}
