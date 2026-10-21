/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.androprogx.app.R

/**
 * An editable hex+ASCII grid over a mutable byte buffer - offset | hex bytes | ASCII, styled as a
 * dedicated "hex matrix" viewport (its own background/text colors, from @color/hex_* - see
 * colors.xml / values-night/colors.xml) rather than picking up the surrounding card's surface
 * color; printable-ASCII bytes are highlighted in a brighter tone, non-printable ones dimmed, in
 * both light and dark mode. Both the hex cells and the ASCII cells are
 * individually tappable: [onByteTapped] fires for a hex cell (caller shows a 2-digit hex entry
 * dialog), [onAsciiTapped] fires for an ASCII cell (caller shows a single-character entry dialog);
 * either way the caller commits the edit via [setByte].
 *
 * [bytesPerRow] is fixed for the lifetime of one adapter instance (see [BYTES_PER_ROW] - this app
 * always uses 16, matching the reference CH341A Programmer design). Every hex/ASCII cell is laid
 * out with `width=0dp` + `layout_weight` (see [HEX_CELL_WEIGHT]/[ASCII_CELL_WEIGHT]) inside its
 * row's hex/ASCII container - see [OFFSET_COLUMN_WIDTH_DP]/[HEX_SECTION_WEIGHT]/[ASCII_SECTION_WEIGHT]
 * for how the row itself divides its width between the offset column and those two containers.
 * Because weighted children always exactly fill the space their container is given regardless of
 * their own measured/intrinsic width, the whole row always exactly fills the RecyclerView's width
 * with zero leftover and can never need horizontal scrolling, on any screen size - unlike an
 * approach that measures text and picks a fixed dp per cell, which only fits the device it was
 * tuned against. [HexHeaderView] (the static "Address / 00 01 .. 0F / ASCII" caption row) uses the
 * exact same weights so its columns line up with the scrolling data below it.
 *
 * Rows are only ever built into views for what's on screen (RecyclerView recycling), so this
 * stays smooth over a multi-megabyte SPI flash buffer - creating per-byte TextViews happens once
 * per recycled row holder (~20-30 total on a typical screen), not once per row.
 */
class HexEditorAdapter(
    private var data: ByteArray,
    val bytesPerRow: Int,
    private val onByteTapped: (offset: Int) -> Unit,
    private val onAsciiTapped: (offset: Int) -> Unit
) : RecyclerView.Adapter<HexEditorAdapter.RowHolder>() {

    private var offsetDigits = offsetDigitsFor(data.size)

    /** Set for the Hex Compare tab's two read-only adapters (see MainActivity's
     *  `refreshEepromCompare`/`refreshSpiCompare`), and transiently for a live hex-editor adapter
     *  right after Verify reads the chip (see `showVerifyResult`) - when set, any byte where this
     *  differs from [data] (including "the other buffer doesn't even reach this offset") is drawn
     *  with the diff highlight instead of the normal printable/non-printable color. [setByte],
     *  [replaceData] and [beginLiveFill] all clear it, since a highlight computed against the
     *  buffer's old contents stops meaning anything once those change. */
    private var diffAgainst: ByteArray? = null

    /** Backs the Hex Compare tab's "Visible ASCII areas" checkbox - hides the ASCII column
     *  entirely when false. Always true (the ASCII column always shows) for the two live editors. */
    private var showAscii: Boolean = true

    /** Absolute byte offsets currently lit up by the Find/Replace dialog - see [setHighlight]. */
    private var highlightOffsets: Set<Int> = emptySet()
    private var replacedOffsets: Set<Int> = emptySet()


    class RowHolder(
        root: LinearLayout,
        val offset: TextView,
        val hexContainer: LinearLayout,
        val asciiContainer: LinearLayout,
        val hexColor: Int,
        val asciiColor: Int,
        val hexHighlightColor: Int,
        val asciiHighlightColor: Int,
        val findBg: Int,
        val findText: Int,
        val replaceBg: Int,
        val replaceText: Int,
        val diffBg: Int,
        val diffText: Int,
        val diffAsciiText: Int,
        val rippleBgRes: Int
    ) : RecyclerView.ViewHolder(root) {
        val hexCells = mutableListOf<TextView>()
        val asciiCells = mutableListOf<TextView>()
    }

    override fun getItemCount(): Int = (data.size + bytesPerRow - 1) / bytesPerRow

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val ctx = parent.context
        val root = LayoutInflater.from(ctx).inflate(R.layout.item_hex_row, parent, false) as LinearLayout
        val offset = root.findViewById<TextView>(R.id.tvHexOffset)
        val hexContainer = root.findViewById<LinearLayout>(R.id.llHexBytes)
        val asciiContainer = root.findViewById<LinearLayout>(R.id.llHexAscii)
        val hexColor = ctx.getColor(R.color.hex_byte)
        val asciiColor = ctx.getColor(R.color.hex_ascii)
        val hexHighlightColor = ctx.getColor(R.color.hex_byte_highlight)
        val asciiHighlightColor = ctx.getColor(R.color.hex_ascii_highlight)
        val findBg = ctx.getColor(R.color.hex_find_highlight_bg)
        val findText = ctx.getColor(R.color.hex_find_highlight_text)
        val replaceBg = ctx.getColor(R.color.hex_replace_highlight_bg)
        val replaceText = ctx.getColor(R.color.hex_replace_highlight_text)
        val diffBg = ctx.getColor(R.color.hex_diff_highlight_bg)
        val diffText = ctx.getColor(R.color.hex_diff_highlight_text)
        val diffAsciiText = ctx.getColor(R.color.hex_diff_ascii_text)

        val bg = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)

        val holder = RowHolder(
            root, offset, hexContainer, asciiContainer, hexColor, asciiColor, hexHighlightColor, asciiHighlightColor,
            findBg, findText, replaceBg, replaceText, diffBg, diffText, diffAsciiText, bg.resourceId
        )

        repeat(bytesPerRow) { i ->
            val hexCell = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { lp ->
                    // A slightly wider gap before byte 8 - the classic hex-dump "8 bytes, gap, 8
                    // bytes" grouping (matches the reference design's border-l divider there).
                    if (i == 8) lp.marginStart = (GROUP_GAP_DP * ctx.resources.displayMetrics.density).toInt()
                }
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                textSize = HEX_TEXT_SIZE_SP
                setTextColor(hexColor)
                isClickable = true
                isFocusable = true
                if (bg.resourceId != 0) setBackgroundResource(bg.resourceId)
                setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val byteOffset = pos * bytesPerRow + i
                        if (byteOffset < data.size) onByteTapped(byteOffset)
                    }
                }
            }
            hexContainer.addView(hexCell)
            holder.hexCells.add(hexCell)

            val asciiCell = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                textSize = HEX_TEXT_SIZE_SP
                setTextColor(asciiColor)
                isClickable = true
                isFocusable = true
                if (bg.resourceId != 0) setBackgroundResource(bg.resourceId)
                setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val byteOffset = pos * bytesPerRow + i
                        if (byteOffset < data.size) onAsciiTapped(byteOffset)
                    }
                }
            }
            asciiContainer.addView(asciiCell)
            holder.asciiCells.add(asciiCell)
        }
        return holder
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val rowStart = position * bytesPerRow
        val rowEnd = minOf(rowStart + bytesPerRow, data.size)
        holder.offset.text = Integer.toHexString(rowStart).padStart(offsetDigits, '0').uppercase()
        holder.asciiContainer.visibility = if (showAscii) View.VISIBLE else View.GONE

        val diff = diffAgainst
        for (i in 0 until bytesPerRow) {
            val idx = rowStart + i
            val hexCell = holder.hexCells[i]
            val asciiCell = holder.asciiCells[i]
            if (idx < rowEnd) {
                val b = data[idx].toInt() and 0xFF
                val printable = b in 0x20..0x7E
                hexCell.text = String.format("%02X", b)
                hexCell.alpha = 1f
                asciiCell.text = if (printable) b.toInt().toChar().toString() else "."
                asciiCell.alpha = 1f

                val isDiff = diff != null && (idx >= diff.size || diff[idx] != data[idx])
                when {
                    isDiff -> {
                        hexCell.setTextColor(holder.diffText)
                        hexCell.setBackgroundColor(holder.diffBg)
                        asciiCell.setTextColor(holder.diffAsciiText)
                        if (holder.rippleBgRes != 0) asciiCell.setBackgroundResource(holder.rippleBgRes) else asciiCell.background = null
                    }
                    replacedOffsets.contains(idx) -> {
                        hexCell.setTextColor(holder.replaceText)
                        asciiCell.setTextColor(holder.replaceText)
                        hexCell.setBackgroundColor(holder.replaceBg)
                        asciiCell.setBackgroundColor(holder.replaceBg)
                    }
                    highlightOffsets.contains(idx) -> {
                        hexCell.setTextColor(holder.findText)
                        asciiCell.setTextColor(holder.findText)
                        hexCell.setBackgroundColor(holder.findBg)
                        asciiCell.setBackgroundColor(holder.findBg)
                    }
                    else -> {
                        hexCell.setTextColor(if (printable) holder.hexHighlightColor else holder.hexColor)
                        asciiCell.setTextColor(if (printable) holder.asciiHighlightColor else holder.asciiColor)
                        if (holder.rippleBgRes != 0) {
                            hexCell.setBackgroundResource(holder.rippleBgRes)
                            asciiCell.setBackgroundResource(holder.rippleBgRes)
                        } else {
                            hexCell.background = null
                            asciiCell.background = null
                        }
                    }
                }
            } else {
                hexCell.text = ""
                hexCell.alpha = 0.25f
                asciiCell.text = ""
                asciiCell.alpha = 0.25f
                if (holder.rippleBgRes != 0) {
                    hexCell.setBackgroundResource(holder.rippleBgRes)
                    asciiCell.setBackgroundResource(holder.rippleBgRes)
                }
            }
        }
    }

    /** Configures this adapter as one side of the Hex Compare tab: every byte where [other] is
     *  missing or differs from this adapter's own data is drawn with the diff highlight. Pass
     *  null to go back to plain printable/non-printable coloring (e.g. no counterpart loaded yet). */
    fun setDiffAgainst(other: ByteArray?) {
        diffAgainst = other
        notifyDataSetChanged()
    }

    /** Backs the Hex Compare tab's "Visible ASCII areas" checkbox. */
    fun setShowAscii(show: Boolean) {
        if (showAscii == show) return
        showAscii = show
        notifyDataSetChanged()
    }

    /** Lights up [found] (blue) and [replaced] (green) byte offsets for the Find/Replace dialog -
     *  only the rows whose highlight actually changed get redrawn, not the whole buffer, so this
     *  stays cheap even on a multi-megabyte SPI flash adapter. */
    fun setHighlight(found: Set<Int>, replaced: Set<Int>) {
        val touchedRows = (highlightOffsets + replacedOffsets + found + replaced).map { it / bytesPerRow }.toSet()
        highlightOffsets = found
        replacedOffsets = replaced
        val lastRow = itemCount - 1
        touchedRows.forEach { row -> if (row in 0..lastRow) notifyItemChanged(row) }
    }

    /** Turns off any Find/Replace highlighting - e.g. when the dialog's query field is edited. */
    fun clearHighlight() = setHighlight(emptySet(), emptySet())

    /** Commits a new value at [offset] in the underlying buffer and redraws just that one row.
     *  Also drops any [setDiffAgainst] highlight (e.g. from Verify) - it was computed against the
     *  buffer's previous contents, so it no longer means anything once a byte has changed. */
    fun setByte(offset: Int, value: Byte) {
        if (offset !in data.indices) return
        data[offset] = value
        if (diffAgainst != null) diffAgainst = null
        notifyItemChanged(offset / bytesPerRow)
    }

    fun byteAt(offset: Int): Byte? = data.getOrNull(offset)

    /** Swaps in an entirely new buffer (a fresh read, or a loaded file) and redraws everything.
     *  Also drops any stale [setDiffAgainst] highlight - existing callers that want a diff always
     *  call [setDiffAgainst] again right after this, so this is a no-op for them. */
    fun replaceData(newData: ByteArray) {
        data = newData
        diffAgainst = null
        offsetDigits = offsetDigitsFor(newData.size)
        notifyDataSetChanged()
    }

    /**
     * Starts a live-filling read: allocates the full [totalSize] buffer up front (so the
     * RecyclerView immediately shows the whole scrollable range, not just what's arrived so far)
     * filled with 0xFF - the conventional "erased" byte value - as a placeholder for the not-yet-
     * read tail, then redraws. Pair with repeated [appendChunk] calls as each piece of a chip read
     * actually arrives, matching IMSProg's own hex view, which visibly fills in as a read
     * progresses rather than only appearing once the whole thing finishes.
     */
    fun beginLiveFill(totalSize: Int) {
        data = ByteArray(totalSize) { 0xFF.toByte() }
        diffAgainst = null
        offsetDigits = offsetDigitsFor(totalSize)
        notifyDataSetChanged()
    }

    /**
     * Copies [chunk] into the buffer at [offset] (see [beginLiveFill]) and redraws just the rows
     * it touches - the live-updating counterpart to [replaceData]'s all-at-once redraw. No-ops if
     * [offset]/[chunk] would run past the end of the buffer [beginLiveFill] allocated, rather than
     * throwing, so a caller racing a resize can't crash the UI.
     */
    fun appendChunk(offset: Int, chunk: ByteArray) {
        if (offset < 0 || chunk.isEmpty() || offset + chunk.size > data.size) return
        System.arraycopy(chunk, 0, data, offset, chunk.size)
        val firstRow = offset / bytesPerRow
        val lastRow = (offset + chunk.size - 1) / bytesPerRow
        notifyItemRangeChanged(firstRow, lastRow - firstRow + 1)
    }

    /** The live buffer, edits included - what a "Write" or "Save File" action should act on. */
    fun currentData(): ByteArray = data

    companion object {
        /** Bytes shown per row, matching the reference CH341A Programmer design. Fixed (not
         *  computed from screen width) because every cell is weight-based - see the class doc. */
        const val BYTES_PER_ROW = 16

        const val HEX_TEXT_SIZE_SP = 9f
        const val GROUP_GAP_DP = 5f

        /** How the row (after the fixed-width offset column) splits its remaining width between
         *  the hex-byte section and the ASCII section - see [HexHeaderView], which must use the
         *  exact same numbers so its header columns line up with the data rows below it. */
        const val HEX_SECTION_WEIGHT = 3f
        const val ASCII_SECTION_WEIGHT = 1f

        /** Fixed width (dp) for the address/offset column, shared with [HexHeaderView]'s "Address"
         *  label so the two line up regardless of that label's own text width. */
        const val OFFSET_COLUMN_WIDTH_DP = 54

        /** Always at least 8 hex digits ("00000000"), matching the reference design's fixed-width
         *  addresses, but happy to grow for a hypothetically larger buffer than this app's real
         *  chips ever reach (SPI flash here tops out around 16 MB = 7 hex digits). */
        private fun offsetDigitsFor(size: Int) = maxOf(8, Integer.toHexString(maxOf(size - 1, 0)).length)
    }
}
