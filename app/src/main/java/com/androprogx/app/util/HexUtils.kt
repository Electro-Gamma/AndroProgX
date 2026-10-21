/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.util

import java.util.zip.CRC32

/**
 * Small, stateless helpers shared by the Hex Compare tab (CRC32 + byte-length formatting) and the
 * Find/Replace dialog (query parsing + byte-pattern search) - see [MainActivity]'s
 * `showFindReplaceDialog` / `refreshEepromCompare` / `refreshSpiCompare` for where these are used.
 * Kept out of [HexEditorAdapter] itself since none of this needs a live buffer or a RecyclerView.
 */
object HexUtils {

    /** 8-hex-digit CRC32 of [data], e.g. "0A1B2C3D" - matches the reference design's Compare tab. */
    fun crc32Hex(data: ByteArray): String {
        val crc = CRC32()
        crc.update(data)
        return "%08X".format(crc.value)
    }

    /** "0x" + [offset] as 8 uppercase hex digits (growing for a hypothetically larger offset),
     *  matching the Find/Replace dialog's status line ("Found @ 0x00000020"). */
    fun fmtAddr(offset: Int): String = "0x" + Integer.toHexString(offset).uppercase().padStart(8, '0')

    /** Parses a user-typed hex offset ("0x1000", "0X1000", or bare "1000") into an [Int], or null
     *  if it isn't valid hex - see the Write Text dialog's offset field. Unlike [parseQueryBytes]'s
     *  "Hex" branch this doesn't strip non-hex characters first, so a typo is rejected rather than
     *  silently reinterpreted. */
    fun parseOffset(raw: String): Int? {
        val cleaned = raw.trim().removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return cleaned.toIntOrNull(16)
    }

    /** Result of [parseQueryBytes]: either the parsed pattern, or a short reason it failed - shown
     *  directly in the Find/Replace dialog's status line either way. */
    sealed class QueryResult {
        data class Ok(val bytes: ByteArray) : QueryResult()
        data class Err(val message: String) : QueryResult()
    }

    /**
     * Parses the Find/Replace dialog's query field into raw bytes, according to whichever encoding
     * is selected in that field's own dropdown ("UTF-8", "Hex", "ASCII", "Decimal") - the same four
     * encodings (and the same parsing rules) as the reference design's HTML tool.
     */
    fun parseQueryBytes(value: String, encoding: String): QueryResult {
        if (value.isBlank()) return QueryResult.Err("Enter query")
        return when (encoding) {
            "Hex" -> {
                val cleaned = value.replace(Regex("0x", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[^0-9a-fA-F]"), "")
                if (cleaned.isEmpty()) return QueryResult.Err("Invalid hex")
                if (cleaned.length % 2 != 0) return QueryResult.Err("Invalid hex (odd digits)")
                val bytes = ByteArray(cleaned.length / 2) { i ->
                    cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                QueryResult.Ok(bytes)
            }
            "Decimal" -> {
                val parts = value.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                val out = ByteArray(parts.size)
                for ((i, p) in parts.withIndex()) {
                    val n = p.toIntOrNull() ?: return QueryResult.Err("Invalid decimal (0-255)")
                    if (n < 0 || n > 255) return QueryResult.Err("Invalid decimal (0-255)")
                    out[i] = n.toByte()
                }
                QueryResult.Ok(out)
            }
            "ASCII" -> QueryResult.Ok(ByteArray(value.length) { i -> (value[i].code and 0xFF).toByte() })
            else -> QueryResult.Ok(value.toByteArray(Charsets.UTF_8)) // "UTF-8"
        }
    }

    /** First index at/after [fromIndex] where [pattern] occurs in [haystack], or -1. No wraparound -
     *  callers that want wraparound (see the Find dialog) retry once from 0 themselves. */
    fun indexOfBytes(haystack: ByteArray, pattern: ByteArray, fromIndex: Int): Int {
        val n = haystack.size
        val m = pattern.size
        if (m == 0 || fromIndex > n - m) return -1
        var i = maxOf(0, fromIndex)
        while (i <= n - m) {
            var match = true
            var j = 0
            while (j < m) {
                if (haystack[i + j] != pattern[j]) { match = false; break }
                j++
            }
            if (match) return i
            i++
        }
        return -1
    }

    /** Backwards counterpart of [indexOfBytes] - last index at/before [fromIndex] where [pattern]
     *  occurs. No wraparound, same convention as [indexOfBytes]. */
    fun lastIndexOfBytes(haystack: ByteArray, pattern: ByteArray, fromIndex: Int): Int {
        val n = haystack.size
        val m = pattern.size
        if (m == 0) return -1
        var i = minOf(fromIndex, n - m)
        while (i >= 0) {
            var match = true
            var j = 0
            while (j < m) {
                if (haystack[i + j] != pattern[j]) { match = false; break }
                j++
            }
            if (match) return i
            i--
        }
        return -1
    }
}
