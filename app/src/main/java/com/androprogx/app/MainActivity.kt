/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.androprogx.app.databinding.ActivityMainBinding
import com.androprogx.app.display.OledCompositor
import com.androprogx.app.eeprom.I2cEeprom
import com.androprogx.app.eeprom.SpiFlashDatabase
import com.androprogx.app.programmer.ProgrammerController
import com.androprogx.app.programmer.ProgrammerMode
import com.androprogx.app.ui.HexEditorAdapter
import com.androprogx.app.util.HexUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CH341A programmer app - I2C EEPROM (24Cxx) read/write, SPI NOR flash (25xx) read/erase/write,
 * and a small SSD1306 I2C OLED display driver (including a clock, a phone-stats dashboard, a
 * custom TTF/OTF font, and a multi-image animation loop), all over the CH341A's USB
 * "parallel/EPP" (programmer) mode. Also exposes an I2C-over-TCP server so another device on the
 * network can run I2C transactions against whatever's wired to the CH341A.
 *
 * This talks to the CH341A's programmer-mode USB personality via [Ch341UsbDevice] - see that
 * class's doc for why it needs its own connect/open/claim-interface flow rather than a standard
 * serial port.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val progBinding get() = binding.includeProgrammer

    private val programmerController by lazy { ProgrammerController(applicationContext) }
    private var currentProgMode = ProgrammerMode.I2C_EEPROM
    private var progConnectedDevice: UsbDevice? = null

    /** The four pages the "Mode" selector (repeated - always in sync - at the top of whichever
     *  page is currently showing) switches between. TCP Server has no [ProgrammerMode] of its
     *  own: I2C-over-TCP and (once implemented) SPI-over-TCP both need a live connection, and the
     *  I2C one specifically just needs the I2C bus opened - exactly what [ProgrammerMode.I2C_EEPROM]
     *  already does in [ProgrammerController.connect] - so this page reuses that connection
     *  rather than inventing a fifth thing for the controller to know how to open. */
    private enum class UiPage(val progMode: ProgrammerMode) {
        EEPROM(ProgrammerMode.I2C_EEPROM),
        SPI(ProgrammerMode.SPI_FLASH),
        DISPLAY(ProgrammerMode.SSD1306_DISPLAY),
        TCP_SERVER(ProgrammerMode.I2C_EEPROM)
    }

    private val uiPageLabels = arrayOf("I2C EEPROM (24Cxx)", "SPI Flash (25xx)", "SSD1306 OLED Display", "TCP Server")
    private val uiPageValues = arrayOf(UiPage.EEPROM, UiPage.SPI, UiPage.DISPLAY, UiPage.TCP_SERVER)
    private var currentUiPage = UiPage.EEPROM

    private val eepromChipLabels = I2cEeprom.COMMON_CHIPS.map { it.name } + "Custom (enter size below)"
    private var selectedEepromChip: I2cEeprom.Chip = I2cEeprom.COMMON_CHIPS.first()

    private val spiChipLabels = SpiFlashDatabase.COMMON_CHIPS.map { it.name } + "Custom (enter size below)"
    /** Null = "Custom" is selected, so the size comes from [ActivityMainBinding]'s etSpiSizeKb field instead. */
    private var selectedSpiChipSize: Long? = SpiFlashDatabase.COMMON_CHIPS.firstOrNull()?.sizeBytes

    private val oledSizeLabels = arrayOf("128 x 64", "128 x 32", "96 x 16", "64 x 32")
    private val oledSizeValues = arrayOf(128 to 64, 128 to 32, 96 to 16, 64 to 32)
    private var selectedOledSize = oledSizeValues.first()

    /** Which of the clock/stats/animation continuous-redraw loops (if any) is currently running -
     *  mirrors [ProgrammerController.isContinuousRunning] but also tracks *which one*, so the
     *  right button gets put back to its "Start" label when another one supersedes it. */
    private enum class ContinuousMode { CLOCK, STATS, ANIMATION }
    private var activeContinuousMode: ContinuousMode? = null
    private var animFrameUris: List<Uri> = emptyList()

    /** Live, editable buffers backing each panel's embedded hex editor - populated by Read, Load
     *  File, Erase, or Write Random, edited in place by tapping a byte or ASCII character, and
     *  what Write/Save File act on. Seeded with [PLACEHOLDER_HEX_BYTES] bytes of 0xFF (erased/
     *  uninitialized-memory convention) so the grid is never blank on first launch, before any
     *  real Read has happened - matching the reference CH341A Programmer design, which always
     *  shows a populated hex dump. */
    private val eepromHexAdapter = HexEditorAdapter(defaultHexPlaceholder(), HexEditorAdapter.BYTES_PER_ROW,
        onByteTapped = { offset -> onEepromByteTapped(offset) },
        onAsciiTapped = { offset -> onEepromAsciiTapped(offset) })
    private val spiHexAdapter = HexEditorAdapter(defaultHexPlaceholder(), HexEditorAdapter.BYTES_PER_ROW,
        onByteTapped = { offset -> onSpiByteTapped(offset) },
        onAsciiTapped = { offset -> onSpiAsciiTapped(offset) })

    /** Hex Compare tab: "what we last read from the chip" / "what we last loaded from a file",
     *  tracked independently from the live, editable buffer above (which either one can overwrite)
     *  so the two can still be compared byte-for-byte after either one changes - see
     *  [refreshEepromCompare]/[refreshSpiCompare]. Null until a Read/Erase/Write Random (chip) or
     *  Load File (file) has actually happened once. */
    private var eepromChipSnapshot: ByteArray? = null
    private var eepromChipSnapshotLabel: String = "-"
    private var eepromFileSnapshot: ByteArray? = null
    private var eepromFileSnapshotLabel: String = "-"

    private var spiChipSnapshot: ByteArray? = null
    private var spiChipSnapshotLabel: String = "-"
    private var spiFileSnapshot: ByteArray? = null
    private var spiFileSnapshotLabel: String = "-"

    /** Read-only, diff-highlighted adapters backing the Hex Compare tab's two RecyclerViews per
     *  panel - no tap handling, since the compare view isn't itself editable. */
    private val eepromCompareChipAdapter = HexEditorAdapter(ByteArray(0), HexEditorAdapter.BYTES_PER_ROW, {}, {})
    private val eepromCompareFileAdapter = HexEditorAdapter(ByteArray(0), HexEditorAdapter.BYTES_PER_ROW, {}, {})
    private val spiCompareChipAdapter = HexEditorAdapter(ByteArray(0), HexEditorAdapter.BYTES_PER_ROW, {}, {})
    private val spiCompareFileAdapter = HexEditorAdapter(ByteArray(0), HexEditorAdapter.BYTES_PER_ROW, {}, {})

    /** Which segmented-tab pane ("Hex Dump" or "Hex Compare") is currently showing, per panel - see
     *  [switchEepromHexTab]/[switchSpiHexTab]. */
    private enum class HexTab { DUMP, COMPARE }
    private var eepromHexTab = HexTab.DUMP
    private var spiHexTab = HexTab.DUMP

    // ---- Storage Access Framework pickers for dump files --------------------

    private val eepromSaveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) writeBytesToUri(uri, eepromHexAdapter.currentData(), "EEPROM dump")
        }

    private val eepromOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readBytesFromUri(uri) { bytes -> onEepromFileLoaded(bytes, queryDisplayName(uri)) }
        }

    private val spiSaveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) writeBytesToUri(uri, spiHexAdapter.currentData(), "SPI flash dump")
        }

    private val spiOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readBytesFromUri(uri) { bytes -> onSpiFileLoaded(bytes, queryDisplayName(uri)) }
        }

    private val oledImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onOledImageChosen(uri)
        }

    private val fontPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFontChosen(uri)
        }

    private val animFramesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onAnimFramesChosen(uris)
        }

    /** Android 13+ only shows a foreground service's notification once this is granted - see
     *  [ensureNotificationPermission]. The service itself still starts either way (see
     *  [ProgrammerService]'s class doc), so this is best-effort and its result is never checked. */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpEdgeToEdgeInsets()
        setupProgrammerUi()
    }

    /** minSdk 26 through targetSdk 35 all draw edge-to-edge by default (mandatory from API 35 on,
     *  opt-in before that) - meaning our own content, not the OS, is responsible for keeping the
     *  header's title/subtitle text out from behind the status bar's clock/icons, and the log
     *  panel's content out from behind the gesture/3-button nav bar. The header/log *backgrounds*
     *  still deliberately extend up/down behind those system bars for a seamless look - only
     *  their content gets pushed in by the matching inset, via padding (not margin, so a tap near
     *  the very top of the header still lands inside it). */
    private var systemBarsBottomInset = 0
    private var logHeaderBaseBottom = 0
    private var logBodyBaseBottom = 0

    private fun setUpEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false

        val header = binding.headerBar
        val headerBaseTop = header.paddingTop
        logHeaderBaseBottom = progBinding.llProgLogHeader.paddingBottom
        logBodyBaseBottom = progBinding.scrollProgLog.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(header.paddingLeft, headerBaseTop + bars.top, header.paddingRight, header.paddingBottom)
            systemBarsBottomInset = bars.bottom
            applyLogBottomInset()
            insets
        }
    }

    /**
     * Keeps the Log panel's bottom edge clear of the system navigation bar. The padding has to
     * move between [llProgLogHeader]/[scrollProgLog] depending on which one is currently the
     * bottom-most visible view, since only one of them shows at a time (see [setupLogToggle]) -
     * otherwise, collapsed, the header - and its toggle chevron - ends up rendered underneath the
     * nav bar and becomes unreachable.
     */
    private fun applyLogBottomInset() {
        val p = progBinding
        val header = p.llProgLogHeader
        val log = p.scrollProgLog
        header.setPadding(
            header.paddingLeft, header.paddingTop, header.paddingRight,
            logHeaderBaseBottom + if (progLogExpanded) 0 else systemBarsBottomInset
        )
        log.setPadding(
            log.paddingLeft, log.paddingTop, log.paddingRight,
            logBodyBaseBottom + if (progLogExpanded) systemBarsBottomInset else 0
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Plain synchronous close (not the suspend disconnect()) - the activity is on its way
        // out, so there's no coroutine scope left to safely launch into.
        programmerController.stopI2cTcpServer()
        programmerController.stopSpiTcpServer()
        programmerController.usbDevice.close()
        ProgrammerService.stop(this)
    }

    // ==================== CH341A: I2C EEPROM / SPI flash / SSD1306 ====================

    private fun setupProgrammerUi() {
        val p = progBinding

        setupLogToggle()

        val modeBoxes = listOf(p.spinnerEepromMode, p.spinnerSpiMode, p.spinnerDisplayMode, p.spinnerTcpServerMode)
        modeBoxes.forEach { box -> box.setOnClickListener { showModePicker(box) } }
        syncModeSpinners()

        p.spinnerEepromChip.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, eepromChipLabels)
        p.spinnerEepromChip.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustom = position !in I2cEeprom.COMMON_CHIPS.indices
                p.etEepromCustomSize.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (isCustom) {
                    p.tvEepromInfo.text = "Enter the size in bytes above - a 2-byte-address, 32-byte-page chip is assumed."
                    p.tvEepromInfoChipValue.text = eepromChipLabels[position]
                    p.tvEepromInfoManufacturerValue.text = "-"
                    p.tvEepromInfoAddressValue.text = "0x50"
                    p.tvEepromInfoCapacityValue.text = "-"
                    p.tvEepromInfoPageSizeValue.text = "32 B"
                    p.tvEepromInfoWordAddrValue.text = "2 bytes"
                    p.tvEepromInfoVccValue.text = "3.3 V"
                } else {
                    selectedEepromChip = I2cEeprom.COMMON_CHIPS[position]
                    val chip = selectedEepromChip
                    p.tvEepromInfo.text = "${chip.totalBytes} bytes total, ${chip.pageSize}-byte write pages."
                    p.tvEepromInfoChipValue.text = chip.name
                    p.tvEepromInfoManufacturerValue.text = chip.manufacturer
                    p.tvEepromInfoAddressValue.text = "0x%02X".format(chip.baseAddr7)
                    p.tvEepromInfoCapacityValue.text = formatSize(chip.totalBytes.toLong())
                    p.tvEepromInfoPageSizeValue.text = "${chip.pageSize} B"
                    p.tvEepromInfoWordAddrValue.text = if (chip.wordAddressBytes == 1) "1 byte" else "2 bytes"
                    p.tvEepromInfoVccValue.text = "${chip.vccVolts} V"
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        p.spinnerOledSize.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, oledSizeLabels)
        p.spinnerOledSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedOledSize = oledSizeValues[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        p.spinnerSpiChip.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spiChipLabels)
        p.spinnerSpiChip.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustom = position !in SpiFlashDatabase.COMMON_CHIPS.indices
                if (isCustom) {
                    selectedSpiChipSize = null
                    applySpiChipSelectionUi(isCustom = true, chip = null)
                } else {
                    val chip = SpiFlashDatabase.COMMON_CHIPS[position]
                    selectedSpiChipSize = chip.sizeBytes
                    p.etSpiSizeKb.setText((chip.sizeBytes / 1024).toString())
                    applySpiChipSelectionUi(isCustom = false, chip = chip)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        p.btnProgConnect.setOnClickListener { onProgConnectClicked() }
        p.btnEepromRead.setOnClickListener { onEepromReadClicked() }
        p.btnEepromWrite.setOnClickListener { onEepromWriteClicked() }
        p.btnEepromErase.setOnClickListener { onEepromEraseClicked() }
        p.btnEepromVerify.setOnClickListener { onEepromVerifyClicked() }
        p.btnEepromDump.setOnClickListener { onEepromDumpClicked() }
        p.btnEepromRandom.setOnClickListener { onEepromRandomClicked() }
        p.btnEepromWriteText.setOnClickListener { onEepromWriteTextClicked() }
        p.btnEepromLoadFile.setOnClickListener { onEepromLoadFileClicked() }
        p.btnEepromSaveFile.setOnClickListener { onEepromSaveFileClicked() }
        p.btnSpiRead.setOnClickListener { onSpiReadClicked() }
        p.btnSpiErase.setOnClickListener { onSpiEraseClicked() }
        p.btnSpiWrite.setOnClickListener { onSpiWriteClicked() }
        p.btnSpiVerify.setOnClickListener { onSpiVerifyClicked() }
        p.btnSpiDump.setOnClickListener { onSpiDumpClicked() }
        p.btnSpiRandom.setOnClickListener { onSpiRandomClicked() }
        p.btnSpiWriteText.setOnClickListener { onSpiWriteTextClicked() }
        p.btnSpiLoadFile.setOnClickListener { onSpiLoadFileClicked() }
        p.btnSpiSaveFile.setOnClickListener { onSpiSaveFileClicked() }
        p.btnOledInit.setOnClickListener { onOledInitClicked() }
        p.btnOledTest.setOnClickListener { onOledTestClicked() }
        p.btnOledClear.setOnClickListener { onOledClearClicked() }
        p.btnOledDrawText.setOnClickListener { onOledDrawTextClicked() }
        p.btnOledLoadImage.setOnClickListener { onOledLoadImageClicked() }

        val fontLabels = listOf(getString(R.string.prog_font_builtin)) +
            ProgrammerController.BUNDLED_FONTS.map { it.displayName } +
            getString(R.string.prog_font_choose_phone)
        p.spinnerFont.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fontLabels)
        p.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> {
                        programmerController.clearCustomFont()
                        updateFontStatus()
                        progLog("Reverted to the built-in pixel font")
                    }
                    in 1..ProgrammerController.BUNDLED_FONTS.size -> {
                        val font = ProgrammerController.BUNDLED_FONTS[position - 1]
                        val ok = programmerController.loadBundledFont(this@MainActivity, font.assetPath, font.displayName)
                        updateFontStatus()
                        if (ok) progLog("Font loaded: ${font.displayName}") else progLog("Failed to load ${font.displayName} from assets", LogLevel.WARN)
                    }
                    else -> fontPickerLauncher.launch(arrayOf("*/*"))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        p.btnClockToggle.setOnClickListener { onClockToggleClicked() }
        p.btnStatsToggle.setOnClickListener { onStatsToggleClicked() }
        p.btnAnimChoose.setOnClickListener { onAnimChooseClicked() }
        p.btnAnimToggle.setOnClickListener { onAnimToggleClicked() }

        p.btnI2cTcpToggle.setOnClickListener { onI2cTcpToggleClicked() }
        p.btnSpiTcpToggle.setOnClickListener { onSpiTcpToggleClicked() }

        p.rvEepromHex.layoutManager = LinearLayoutManager(this)
        p.rvEepromHex.adapter = eepromHexAdapter
        p.rvEepromHex.setHasFixedSize(true)

        p.rvSpiHex.layoutManager = LinearLayoutManager(this)
        p.rvSpiHex.adapter = spiHexAdapter
        p.rvSpiHex.setHasFixedSize(true)

        p.rvEepromCompareChip.layoutManager = LinearLayoutManager(this)
        p.rvEepromCompareChip.adapter = eepromCompareChipAdapter
        p.rvEepromCompareFile.layoutManager = LinearLayoutManager(this)
        p.rvEepromCompareFile.adapter = eepromCompareFileAdapter
        setupCompareScrollSync(p.rvEepromCompareChip, p.rvEepromCompareFile)

        p.rvSpiCompareChip.layoutManager = LinearLayoutManager(this)
        p.rvSpiCompareChip.adapter = spiCompareChipAdapter
        p.rvSpiCompareFile.layoutManager = LinearLayoutManager(this)
        p.rvSpiCompareFile.adapter = spiCompareFileAdapter
        setupCompareScrollSync(p.rvSpiCompareChip, p.rvSpiCompareFile)

        p.tabEepromHexDump.setOnClickListener { switchEepromHexTab(HexTab.DUMP) }
        p.tabEepromHexCompare.setOnClickListener { switchEepromHexTab(HexTab.COMPARE) }
        p.tabSpiHexDump.setOnClickListener { switchSpiHexTab(HexTab.DUMP) }
        p.tabSpiHexCompare.setOnClickListener { switchSpiHexTab(HexTab.COMPARE) }
        switchEepromHexTab(HexTab.DUMP)
        switchSpiHexTab(HexTab.DUMP)

        p.cbEepromCompareAscii.setOnCheckedChangeListener { _, checked ->
            eepromCompareChipAdapter.setShowAscii(checked)
            eepromCompareFileAdapter.setShowAscii(checked)
            p.includeEepromCompareChipHeader.tvHexHeaderAscii.visibility = if (checked) View.VISIBLE else View.GONE
            p.includeEepromCompareFileHeader.tvHexHeaderAscii.visibility = if (checked) View.VISIBLE else View.GONE
        }
        p.cbSpiCompareAscii.setOnCheckedChangeListener { _, checked ->
            spiCompareChipAdapter.setShowAscii(checked)
            spiCompareFileAdapter.setShowAscii(checked)
            p.includeSpiCompareChipHeader.tvHexHeaderAscii.visibility = if (checked) View.VISIBLE else View.GONE
            p.includeSpiCompareFileHeader.tvHexHeaderAscii.visibility = if (checked) View.VISIBLE else View.GONE
        }

        p.btnEepromFind.setOnClickListener {
            showFindReplaceDialog(eepromHexAdapter, p.rvEepromHex) {
                switchEepromHexTab(HexTab.DUMP)
                refreshEepromHexInfo("edited via Find/Replace")
            }
        }
        p.btnSpiFind.setOnClickListener {
            showFindReplaceDialog(spiHexAdapter, p.rvSpiHex) {
                switchSpiHexTab(HexTab.DUMP)
                refreshSpiHexInfo("edited via Find/Replace")
            }
        }

        p.btnSpiChipDetectBox.setOnClickListener { onSpiDetectClicked() }

        p.tvEepromInfo.text = "${selectedEepromChip.totalBytes} bytes total, ${selectedEepromChip.pageSize}-byte write pages."
        p.tvEepromInfoChipValue.text = selectedEepromChip.name
        refreshEepromHexInfo("placeholder")
        refreshSpiHexInfo("placeholder")
        updateProgConnectionUi(false)
    }

    /** Shows [page]'s panel and hides the other three (including the just-relocated TCP Server
     *  one), and updates [currentProgMode] to whatever connection [page] actually needs - see
     *  [UiPage]'s doc for why TCP Server reuses I2C EEPROM's. Called from whichever of the four
     *  Mode rows the person just picked from; [syncModeSpinners] then reflects the new page's
     *  label on the other three, so every page's Mode row always shows the same current value. */
    private fun switchToPage(page: UiPage) {
        if (currentUiPage == page) return
        currentUiPage = page
        currentProgMode = page.progMode
        progBinding.llI2cPanel.visibility = if (page == UiPage.EEPROM) View.VISIBLE else View.GONE
        progBinding.llSpiPanel.visibility = if (page == UiPage.SPI) View.VISIBLE else View.GONE
        progBinding.llDisplayPanel.visibility = if (page == UiPage.DISPLAY) View.VISIBLE else View.GONE
        progBinding.llTcpServerPanel.visibility = if (page == UiPage.TCP_SERVER) View.VISIBLE else View.GONE
        syncModeSpinners()
    }

    private fun syncModeSpinners() {
        val label = uiPageLabels[uiPageValues.indexOf(currentUiPage)]
        progBinding.tvEepromModeValue.text = label
        progBinding.tvSpiModeValue.text = label
        progBinding.tvDisplayModeValue.text = label
        progBinding.tvTcpServerModeValue.text = label
    }

    /** The Mode row (deliberately a plain clickable row rather than a real [android.widget.Spinner]
     *  - a Spinner's own text rendering can't be restyled down to match the compact Chip Detect
     *  row it sits next to) opens this small popup of the same four pages listed in [uiPageLabels]. */
    private fun showModePicker(anchor: View) {
        val popup = PopupMenu(this, anchor)
        uiPageLabels.forEachIndexed { index, label -> popup.menu.add(0, index, index, label) }
        popup.setOnMenuItemClickListener { item ->
            switchToPage(uiPageValues[item.itemId])
            true
        }
        popup.show()
    }

    // ---- connect / disconnect ----------------------------------------------

    private fun updateProgConnectionUi(connected: Boolean) {
        progBinding.tvProgStatus.text = getString(if (connected) R.string.prog_status_connected else R.string.prog_status_disconnected)
        val statusColor = getColor(if (connected) R.color.status_ok else R.color.status_error)
        progBinding.tvProgStatus.setTextColor(statusColor)
        progBinding.vProgStatusDot.backgroundTintList = ColorStateList.valueOf(statusColor)
        progBinding.btnProgConnect.text = getString(if (connected) R.string.prog_disconnect else R.string.prog_connect)
        listOf(progBinding.spinnerEepromMode, progBinding.spinnerSpiMode, progBinding.spinnerDisplayMode, progBinding.spinnerTcpServerMode).forEach { box ->
            box.isEnabled = !connected
            box.alpha = if (connected) 0.55f else 1f
        }
        if (!connected) {
            // disconnect() always stops both TCP servers too - reflect that here.
            progBinding.tvI2cTcpStatus.text = getString(R.string.prog_i2ctcp_not_running)
            progBinding.tvI2cTcpToggleLabel.text = getString(R.string.prog_i2ctcp_start)
            progBinding.tvSpiTcpStatus.text = getString(R.string.prog_i2ctcp_not_running)
            progBinding.tvSpiTcpToggleLabel.text = getString(R.string.prog_i2ctcp_start)
            // ...and any running clock/stats/animation loop - reset that UI state too.
            activeContinuousMode = null
            resetContinuousButtons()
        }
    }

    private fun onProgConnectClicked() {
        if (programmerController.isConnected) {
            lifecycleScope.launch {
                programmerController.disconnect(currentProgMode)
                progConnectedDevice = null
                updateProgConnectionUi(false)
                progLog("Disconnected")
                ProgrammerService.stop(this@MainActivity)
            }
            return
        }

        val dev = programmerController.findDevice()
        if (dev == null) {
            progLog("No CH341A programmer found - plug it in (VID 1A86 / PID 5512) and tap Connect again", LogLevel.ERROR)
            return
        }

        ensureNotificationPermission()
        if (programmerController.hasPermission(dev)) {
            connectToProgrammerDevice(dev)
        } else {
            programmerController.requestPermission(dev) { granted ->
                if (granted) connectToProgrammerDevice(dev) else progLog("USB permission denied", LogLevel.ERROR)
            }
        }
    }

    /** Requests the runtime `POST_NOTIFICATIONS` permission on Android 13+ if not already granted,
     *  so [ProgrammerService]'s persistent notification can actually show. Fire-and-forget - the
     *  connect flow doesn't wait for an answer, since the service works either way (see that
     *  class's doc); this only affects whether its notification is visible. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun connectToProgrammerDevice(dev: UsbDevice) {
        lifecycleScope.launch {
            val ok = programmerController.connect(dev, currentProgMode)
            if (ok) {
                progConnectedDevice = dev
                updateProgConnectionUi(true)
                val deviceLabel = programmerController.usbDevice.deviceLabel()
                progLog("Connected: $deviceLabel")
                programmerController.usbDevice.endpointInfo?.let { progLog(it) }
                ProgrammerService.start(this@MainActivity, getString(R.string.prog_service_connected, deviceLabel))
            } else {
                progLog("Failed to open/initialize the CH341A", LogLevel.ERROR)
            }
        }
    }

    // ---- I2C EEPROM ---------------------------------------------------------

    /** Reads the chip spinner, or builds a Chip from the custom-size field. Logs and returns null if invalid. */
    private fun currentEepromChip(): I2cEeprom.Chip? {
        val pos = progBinding.spinnerEepromChip.selectedItemPosition
        if (pos in I2cEeprom.COMMON_CHIPS.indices) return I2cEeprom.COMMON_CHIPS[pos]
        val size = progBinding.etEepromCustomSize.text.toString().toIntOrNull()
        if (size == null || size <= 0) {
            progLog("Enter a valid custom EEPROM size in bytes first", LogLevel.WARN)
            return null
        }
        progLog(
            "Custom chip: assuming 2-byte word addressing / 32-byte pages (typical for 32Kbit+ " +
                "EEPROMs) - pick the matching preset instead if your chip is smaller than that"
        )
        return I2cEeprom.Chip("Custom ($size B)", size, pageSize = 32, algorithm = 0x02)
    }

    private fun onEepromReadClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val chip = currentEepromChip() ?: return
        lifecycleScope.launch {
            setProgProgress(0)
            eepromHexAdapter.beginLiveFill(chip.totalBytes)
            progLog("Reading ${chip.name}...")
            val data = programmerController.readEeprom(
                chip,
                onProgress = { done, total ->
                    runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
                },
                onChunk = { offset, chunk ->
                    runOnUiThread { eepromHexAdapter.appendChunk(offset, chunk) }
                }
            )
            setProgProgress(null)
            if (data != null) {
                eepromChipSnapshot = data.copyOf()
                eepromChipSnapshotLabel = chip.name
                refreshEepromHexInfo("from chip")
                if (eepromHexTab == HexTab.COMPARE) refreshEepromCompare()
                progLog("Read ${data.size} bytes")
            } else {
                progLog("EEPROM read failed - check wiring and that a chip is present", LogLevel.ERROR)
            }
        }
    }

    private fun onEepromWriteClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val chip = currentEepromChip() ?: return
        val live = eepromHexAdapter.currentData()
        if (live.isEmpty()) {
            progLog("Nothing to write - tap Read or Load File first", LogLevel.WARN)
            return
        }
        // Snapshot rather than write the adapter's live array directly - it stays editable by the
        // user (tapping a byte) while this write is streaming, and the two shouldn't race.
        val data = if (live.size > chip.totalBytes) {
            progLog("Hex editor has ${live.size} B, larger than ${chip.name} (${chip.totalBytes} B) - truncating", LogLevel.WARN)
            live.copyOf(chip.totalBytes).also { eepromHexAdapter.replaceData(it) }
        } else {
            live.copyOf()
        }
        styledDialog("Write to chip?", DialogBadge.CONFIRM)
            .setMessage("Writes ${data.size} bytes to ${chip.name}. This overwrites the chip's existing data. Continue?")
            .setPositiveButton("Write") { _, _ -> startEepromWrite(chip, data) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startEepromWrite(chip: I2cEeprom.Chip, data: ByteArray) {
        lifecycleScope.launch {
            setProgProgress(0)
            progLog("Writing ${data.size} bytes to ${chip.name}...")
            val ok = programmerController.writeEeprom(chip, data) { done, total ->
                runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
            }
            setProgProgress(null)
            if (ok) progLog("EEPROM write complete") else progLog("EEPROM write failed", LogLevel.ERROR)
        }
    }

    private fun onEepromLoadFileClicked() {
        progLog("Choose a file to load into the hex editor...")
        eepromOpenLauncher.launch(arrayOf("*/*"))
    }

    private fun onEepromFileLoaded(bytes: ByteArray, displayName: String?) {
        eepromHexAdapter.replaceData(bytes)
        eepromFileSnapshot = bytes.copyOf()
        eepromFileSnapshotLabel = displayName ?: "Loaded file"
        refreshEepromHexInfo("from file")
        if (eepromHexTab == HexTab.COMPARE) refreshEepromCompare()
        progLog("Loaded ${bytes.size} bytes into the hex editor")
    }

    private fun onEepromSaveFileClicked() {
        val data = eepromHexAdapter.currentData()
        if (data.isEmpty()) {
            progLog("Nothing to save - tap Read or Load File first", LogLevel.WARN)
            return
        }
        eepromSaveLauncher.launch(suggestedFileName("eeprom_dump", data.size))
    }

    private fun onEepromEraseClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val chip = currentEepromChip() ?: return
        styledDialog("Erase ${chip.name}?", DialogBadge.CONFIRM)
            .setMessage("Fills all ${chip.totalBytes} bytes with 0xFF. This cannot be undone. Continue?")
            .setPositiveButton("Erase") { _, _ ->
                lifecycleScope.launch {
                    setProgProgress(0)
                    progLog("Erasing ${chip.name}...")
                    val ok = programmerController.eraseEeprom(chip) { done, total ->
                        runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
                    }
                    setProgProgress(null)
                    if (ok) progLog("Erase complete") else progLog("Erase failed", LogLevel.ERROR)
                    if (ok) {
                        eepromHexAdapter.replaceData(ByteArray(chip.totalBytes) { 0xFF.toByte() })
                        eepromChipSnapshot = eepromHexAdapter.currentData().copyOf()
                        eepromChipSnapshotLabel = chip.name
                        refreshEepromHexInfo("from chip")
                        if (eepromHexTab == HexTab.COMPARE) refreshEepromCompare()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onEepromRandomClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val chip = currentEepromChip() ?: return
        styledDialog("Write random data to ${chip.name}?", DialogBadge.CONFIRM)
            .setMessage("Overwrites all ${chip.totalBytes} bytes with random data. This cannot be undone. Continue?")
            .setPositiveButton("Write Random") { _, _ ->
                val randomData = ByteArray(chip.totalBytes)
                Random.nextBytes(randomData)
                lifecycleScope.launch {
                    setProgProgress(0)
                    progLog("Writing random data to ${chip.name}...")
                    val ok = programmerController.writeEeprom(chip, randomData) { done, total ->
                        runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
                    }
                    setProgProgress(null)
                    if (ok) progLog("Random write complete") else progLog("Random write failed", LogLevel.ERROR)
                    if (ok) {
                        eepromHexAdapter.replaceData(randomData)
                        eepromChipSnapshot = randomData.copyOf()
                        eepromChipSnapshotLabel = chip.name
                        refreshEepromHexInfo("from chip")
                        if (eepromHexTab == HexTab.COMPARE) refreshEepromCompare()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Reads the whole chip and compares it byte-by-byte against the hex editor's current buffer
     * (the "source" - whatever was last Read/Loaded/edited there, i.e. what Write would send) -
     * without disturbing that buffer, so the comparison is against exactly what was on screen when
     * Verify was tapped. Mismatches are shown two ways: a PASS/FAIL dialog via [showVerifyResult]
     * with the first few offsets, and a live diff highlight over [eepromHexAdapter] itself (same
     * red-highlight mechanism the Hex Compare tab uses - see [HexEditorAdapter.setDiffAgainst]).
     */
    private fun onEepromVerifyClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val chip = currentEepromChip() ?: return
        val source = eepromHexAdapter.currentData()
        if (source.isEmpty()) {
            progLog("Nothing to verify against - tap Read or Load File first", LogLevel.WARN)
            return
        }
        val sourceSnapshot = source.copyOf()
        lifecycleScope.launch {
            setProgProgress(0)
            progLog("Verifying ${chip.name} against the hex editor buffer...")
            val chipData = programmerController.readEeprom(chip, onProgress = { done, total ->
                runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
            })
            setProgProgress(null)
            if (chipData == null) {
                progLog("Verify failed - could not read the chip", LogLevel.ERROR)
                return@launch
            }
            showVerifyResult(chip.name, sourceSnapshot, chipData, eepromHexAdapter)
        }
    }

    /**
     * Reads the complete chip (same live-filling read as [onEepromReadClicked]) and, on success,
     * immediately prompts where to save it - a one-tap "read + save" shortcut, reusing
     * [eepromSaveLauncher] so the save itself goes through the exact same SAF flow as Save File.
     */
    private fun onEepromDumpClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val chip = currentEepromChip() ?: return
        lifecycleScope.launch {
            setProgProgress(0)
            eepromHexAdapter.beginLiveFill(chip.totalBytes)
            progLog("Dumping ${chip.name}...")
            val data = programmerController.readEeprom(
                chip,
                onProgress = { done, total -> runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) } },
                onChunk = { offset, chunk -> runOnUiThread { eepromHexAdapter.appendChunk(offset, chunk) } }
            )
            setProgProgress(null)
            if (data == null) {
                progLog("Dump failed - check wiring and that a chip is present", LogLevel.ERROR)
                return@launch
            }
            eepromChipSnapshot = data.copyOf()
            eepromChipSnapshotLabel = chip.name
            refreshEepromHexInfo("from chip")
            if (eepromHexTab == HexTab.COMPARE) refreshEepromCompare()
            progLog("Read ${data.size} bytes - choose where to save")
            eepromSaveLauncher.launch(suggestedFileName("eeprom_dump", data.size))
        }
    }

    /**
     * Shared by the EEPROM and SPI panels' Verify button: computes the byte-by-byte diff between
     * [source] (the buffer before the read) and [chipData] (what just came back from the chip),
     * logs and shows a PASS/FAIL dialog with the first few mismatches, and - PASS or FAIL - paints
     * [adapter] with the diff so every differing byte is visible directly in the hex grid (cleared
     * again by the next edit/Read/Load, per [HexEditorAdapter.setByte]/`replaceData`/`beginLiveFill`).
     */
    private fun showVerifyResult(label: String, source: ByteArray, chipData: ByteArray, adapter: HexEditorAdapter) {
        adapter.setDiffAgainst(chipData)
        val length = minOf(source.size, chipData.size)
        val mismatches = mutableListOf<Int>()
        for (i in 0 until length) {
            if (source[i] != chipData[i]) mismatches.add(i)
        }
        val sizeMismatch = source.size != chipData.size
        if (mismatches.isEmpty() && !sizeMismatch) {
            progLog("Verify PASS - $label matches the buffer (${source.size} bytes)")
            styledDialog("Verify: PASS", DialogBadge.PASS)
                .setMessage("All ${source.size} bytes on $label match the hex editor buffer.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val detail = StringBuilder()
        if (sizeMismatch) {
            detail.append("Buffer is ${source.size} B, chip read ${chipData.size} B.\n\n")
        }
        val shown = mismatches.take(10)
        shown.forEach { off ->
            detail.append(
                "%s: buffer %02X, chip %02X\n".format(
                    HexUtils.fmtAddr(off), source[off].toInt() and 0xFF, chipData[off].toInt() and 0xFF
                )
            )
        }
        if (mismatches.size > shown.size) detail.append("...and ${mismatches.size - shown.size} more")
        progLog("Verify FAIL - ${mismatches.size} mismatch(es) against $label", LogLevel.ERROR)
        styledDialog("Verify: FAIL", DialogBadge.FAIL)
            .setMessage("${mismatches.size} byte(s) differ out of ${maxOf(source.size, chipData.size)}.\n\n$detail")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun onEepromByteTapped(offset: Int) {
        showByteEditDialog(eepromHexAdapter, offset) { refreshEepromHexInfo("edited, not written yet") }
    }

    private fun onEepromAsciiTapped(offset: Int) {
        showAsciiEditDialog(eepromHexAdapter, offset) { refreshEepromHexInfo("edited, not written yet") }
    }

    /** Which icon a [styledDialog] shows in its badge - see that function's doc. */
    private enum class DialogBadge { CONFIRM, EDIT, PASS, FAIL }

    /**
     * Builds a [MaterialAlertDialogBuilder] styled to match the reference "CH341A Programmer"
     * dialog design exactly: a card using the `dialog_*` color tokens (colors.xml /
     * values-night/colors.xml - adapts with the rest of the app's own DayNight theme), a colored
     * circular icon badge next to a bold title (via a custom title view -
     * [R.layout.dialog_title_icon] - since a plain `setTitle()` string has no way to show one), a
     * filled positive button, and a plain-text negative button - rather than this app's own
     * [R.style.ThemeOverlay_Prog_AlertDialog].
     *
     * [badge] picks the glyph: [DialogBadge.CONFIRM] (an "i") for confirming a destructive action
     * (Erase, Write Random, Write-to-chip), [DialogBadge.EDIT] (a pencil) for the hex editor's
     * text/byte/ASCII entry prompts, [DialogBadge.PASS]/[DialogBadge.FAIL] (check / X) for a
     * Verify result - matching the dialog families already in this app.
     */
    private fun styledDialog(title: String, badge: DialogBadge): MaterialAlertDialogBuilder {
        val builder = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Prog_ConfirmDialog)
        val titleView = LayoutInflater.from(this).inflate(R.layout.dialog_title_icon, null)
        titleView.findViewById<ImageView>(R.id.ivDialogBadge).setImageResource(
            when (badge) {
                DialogBadge.CONFIRM -> R.drawable.ic_info
                DialogBadge.EDIT -> R.drawable.ic_edit
                DialogBadge.PASS -> R.drawable.ic_verify
                DialogBadge.FAIL -> R.drawable.ic_clear
            }
        )
        titleView.findViewById<TextView>(R.id.tvDialogTitle).text = title
        builder.setCustomTitle(titleView)
        return builder
    }

    private fun onEepromWriteTextClicked() {
        showWriteTextDialog(eepromHexAdapter) { refreshEepromHexInfo("text written, not written to chip yet") }
    }

    /** Opens a start-offset + multi-line plain-text entry dialog and writes the text's ASCII bytes
     *  (newlines included, as 0x0A) into [adapter]'s buffer starting at that offset - a quick way
     *  to get readable text into the buffer without editing byte-by-byte, the same idea as the OLED
     *  panel's Draw Text but targeting a hex editor instead of the display. Only edits the
     *  in-memory buffer - Write still has to be tapped separately to actually send it to the chip,
     *  same as any other hex editor edit. [onCommitted] runs after a successful write, to refresh
     *  that panel's info line. */
    private fun showWriteTextDialog(adapter: HexEditorAdapter, onCommitted: () -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()

        val offsetInput = EditText(this).apply {
            hint = "Offset, e.g. 0x1000"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("0x0000")
            setBackgroundResource(R.drawable.bg_dialog_input)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dialog_title))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.dialog_hint))
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val textInput = EditText(this).apply {
            hint = "Text to write"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setBackgroundResource(R.drawable.bg_dialog_input)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dialog_title))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.dialog_hint))
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(offsetInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                textInput,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = pad / 2 }
            )
        }

        styledDialog("Write text into hex editor", DialogBadge.EDIT)
            .setMessage("Writes the text below as ASCII bytes starting at the offset, overwriting whatever's currently there.")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val bufferSize = adapter.currentData().size
                val offset = HexUtils.parseOffset(offsetInput.text.toString())
                if (offset == null || offset !in 0 until bufferSize) {
                    progLog("Enter a valid offset between 0x0 and ${HexUtils.fmtAddr(bufferSize - 1)}", LogLevel.WARN)
                    return@setPositiveButton
                }
                val text = textInput.text.toString()
                if (text.isEmpty()) {
                    progLog("Enter some text first", LogLevel.WARN)
                    return@setPositiveButton
                }
                val available = bufferSize - offset
                val toWrite = if (text.length > available) text.substring(0, available) else text
                toWrite.forEachIndexed { i, ch -> adapter.setByte(offset + i, (ch.code and 0xFF).toByte()) }
                onCommitted()
                if (text.length > available) {
                    progLog(
                        "Wrote the first $available of ${text.length} characters at ${HexUtils.fmtAddr(offset)} - the rest didn't fit the buffer",
                        LogLevel.WARN
                    )
                } else {
                    progLog("Wrote ${toWrite.length} character(s) at ${HexUtils.fmtAddr(offset)}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshEepromHexInfo(source: String) {
        val n = eepromHexAdapter.currentData().size
        progBinding.tvEepromHexInfo.text = if (n == 0) getString(R.string.prog_hex_no_data) else "$n bytes - $source"
    }

    /** Shows the "Hex Dump" or "Hex Compare" pane and paints the segmented tab labels to match -
     *  see [setHexTabStyle]. Switching to Compare refreshes it with whatever's been read/loaded
     *  so far (see [refreshEepromCompare]) since either can have changed while it wasn't visible. */
    private fun switchEepromHexTab(tab: HexTab) {
        eepromHexTab = tab
        val p = progBinding
        p.llEepromHexDumpView.visibility = if (tab == HexTab.DUMP) View.VISIBLE else View.GONE
        p.llEepromHexCompareView.visibility = if (tab == HexTab.COMPARE) View.VISIBLE else View.GONE
        setHexTabStyle(p.tabEepromHexDump, tab == HexTab.DUMP)
        setHexTabStyle(p.tabEepromHexCompare, tab == HexTab.COMPARE)
        if (tab == HexTab.COMPARE) refreshEepromCompare()
    }

    /** Paints one segmented-tab label active (filled accent background, white text) or inactive
     *  (transparent, secondary text) - the two states [tabEepromHexDump]/[tabEepromHexCompare] and
     *  their SPI counterparts started out with in panel_programmer.xml, now driven at runtime so
     *  either tab can become the active one. */
    private fun setHexTabStyle(tab: TextView, active: Boolean) {
        if (active) {
            tab.setBackgroundResource(R.drawable.bg_input_box)
            tab.backgroundTintList = ColorStateList.valueOf(getColor(R.color.bridge_accent))
            tab.setTextColor(getColor(android.R.color.white))
        } else {
            tab.background = null
            tab.setTextColor(getColor(R.color.bridge_text_secondary))
        }
    }

    /**
     * Refreshes the EEPROM panel's Hex Compare tab: CRC32 + name badges and the two diff-
     * highlighted hex tables for [eepromChipSnapshot] vs. [eepromFileSnapshot]. Either side shows
     * a hint instead of its table until that snapshot actually exists (first Read/Erase/Write
     * Random for the chip side, first Load File for the file side).
     */
    private fun refreshEepromCompare() {
        val p = progBinding
        val chip = eepromChipSnapshot
        val file = eepromFileSnapshot

        p.rvEepromCompareChip.visibility = if (chip != null) View.VISIBLE else View.GONE
        p.tvEepromCompareChipHint.visibility = if (chip == null) View.VISIBLE else View.GONE
        p.tvEepromCompareChipCrc.text = if (chip != null) HexUtils.crc32Hex(chip) else getString(R.string.compare_value_placeholder)
        p.tvEepromCompareChipName.text = if (chip != null) eepromChipSnapshotLabel else ""

        p.rvEepromCompareFile.visibility = if (file != null) View.VISIBLE else View.GONE
        p.tvEepromCompareFileHint.visibility = if (file == null) View.VISIBLE else View.GONE
        p.tvEepromCompareFileCrc.text = if (file != null) HexUtils.crc32Hex(file) else getString(R.string.compare_value_placeholder)
        p.tvEepromCompareFileName.text = if (file != null) eepromFileSnapshotLabel else ""

        eepromCompareChipAdapter.replaceData(chip ?: ByteArray(0))
        eepromCompareChipAdapter.setDiffAgainst(file)
        eepromCompareFileAdapter.replaceData(file ?: ByteArray(0))
        eepromCompareFileAdapter.setDiffAgainst(chip)
    }

    // ---- SPI flash ------------------------------------------------------------

    /** Size in bytes implied by the chip spinner, or the manual KB field when "Custom" is selected. */
    private fun currentSpiSizeBytes(): Long {
        selectedSpiChipSize?.let { return it }
        val kb = progBinding.etSpiSizeKb.text.toString().toIntOrNull() ?: 0
        return kb.toLong() * 1024
    }

    /** Shows/hides the manual Size(KB)/Page size(B) row and updates [ActivityMainBinding.
     *  includeProgrammer]'s tvSpiInfo hint - called both from the chip spinner's own selection
     *  and from [onSpiDetectClicked], so a chip picked either way ends up in the same state:
     *  known chip -> row hidden, summary shown (its size/page are already in the Chip
     *  Information card above, so repeating them here was just clutter); "Custom" -> row shown so
     *  there's somewhere to type them, since there's no database entry to derive either from. */
    private fun applySpiChipSelectionUi(isCustom: Boolean, chip: SpiFlashDatabase.ChipPreset?) {
        val p = progBinding
        p.llSpiCustomSize.visibility = if (isCustom) View.VISIBLE else View.GONE
        p.tvSpiInfo.text = when {
            isCustom -> "Enter the size in KB and page size in bytes above."
            chip != null -> "${formatSize(chip.sizeBytes)} total, 256-byte write pages."
            else -> ""
        }
    }

    private fun onSpiDetectClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        lifecycleScope.launch {
            val detected = programmerController.detectSpiChip()
            if (detected == null) {
                val detail = programmerController.usbDevice.lastError
                progLog(
                    "Failed to read JEDEC ID - check wiring/chip" + (if (detail != null) " [$detail]" else ""),
                    LogLevel.ERROR
                )
                return@launch
            }
            val (id, chip) = detected
            progBinding.tvSpiInfoChipValue.text = chip.name
            progBinding.tvSpiInfoCapacityValue.text = formatSize(chip.sizeBytes)
            progBinding.tvSpiInfoManufacturerValue.text = SpiFlashDatabase.manufacturerName(id.manufacturer)
            progBinding.tvSpiInfoJedecValue.text = "%02X %02X %02X".format(id.manufacturer, id.memoryType, id.capacityCode)
            // Both universal SPI NOR conventions (this app's own SpiFlash class assumes the same
            // values elsewhere: a 256B page for write() and a fixed 4096B granularity for
            // eraseSector4k()) rather than per-chip data - this app's chip database only tracks
            // name+size, not the page/erase-block table IMSProg's own IMSProg.Dat carries.
            progBinding.tvSpiInfoPageSizeValue.text =
                (progBinding.etSpiPageSize.text.toString().toIntOrNull() ?: 256).let { "$it B" }
            progBinding.tvSpiInfoSectorSizeValue.text = "4 KB"
            programmerController.configureSpiAddressing(chip.sizeBytes)
            val index = SpiFlashDatabase.COMMON_CHIPS.indexOfFirst { it.name == chip.name }
            if (index >= 0) {
                progBinding.spinnerSpiChip.setSelection(index)
                applySpiChipSelectionUi(isCustom = false, chip = chip)
            } else {
                // Not one of the exact-named parts - fall back to "Custom" and fill in the decoded size.
                progBinding.spinnerSpiChip.setSelection(spiChipLabels.size - 1)
                selectedSpiChipSize = null
                progBinding.etSpiSizeKb.setText((chip.sizeBytes / 1024).toString())
                applySpiChipSelectionUi(isCustom = true, chip = null)
            }
            progLog("Detected: ${chip.name}")
        }
    }

    private fun onSpiRandomClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val sizeBytes = currentSpiSizeBytes()
        if (sizeBytes <= 0) {
            progLog("Pick a chip or enter a valid size in KB", LogLevel.WARN)
            return
        }
        val pageSize = progBinding.etSpiPageSize.text.toString().toIntOrNull() ?: 256
        styledDialog("Write random data?", DialogBadge.CONFIRM)
            .setMessage("Erases and overwrites ${formatSize(sizeBytes)} with random data. This cannot be undone. Continue?")
            .setPositiveButton("Write Random") { _, _ ->
                val randomData = ByteArray(sizeBytes.toInt())
                Random.nextBytes(randomData)
                lifecycleScope.launch {
                    programmerController.configureSpiAddressing(sizeBytes)
                    setProgProgress(0)
                    progLog("Writing random data to SPI flash...")
                    val ok = programmerController.writeSpiFlash(randomData, pageSize) { done, total ->
                        runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
                    }
                    setProgProgress(null)
                    if (ok) progLog("Random write complete") else progLog("Random write failed", LogLevel.ERROR)
                    if (ok) {
                        spiHexAdapter.replaceData(randomData)
                        spiChipSnapshot = randomData.copyOf()
                        spiChipSnapshotLabel = progBinding.spinnerSpiChip.selectedItem?.toString() ?: "SPI flash"
                        refreshSpiHexInfo("from chip")
                        if (spiHexTab == HexTab.COMPARE) refreshSpiCompare()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** SPI counterpart of [onEepromVerifyClicked] - same "read chip, diff against the buffer that
     *  was on screen before the read" approach, via the shared [showVerifyResult]. */
    private fun onSpiVerifyClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val sizeKb = progBinding.etSpiSizeKb.text.toString().toIntOrNull()
        if (sizeKb == null || sizeKb <= 0) {
            progLog("Enter a valid size in KB", LogLevel.WARN)
            return
        }
        val source = spiHexAdapter.currentData()
        if (source.isEmpty()) {
            progLog("Nothing to verify against - tap Read or Load File first", LogLevel.WARN)
            return
        }
        val sourceSnapshot = source.copyOf()
        val length = sizeKb * 1024
        lifecycleScope.launch {
            programmerController.configureSpiAddressing(length.toLong())
            setProgProgress(0)
            progLog("Verifying SPI flash against the hex editor buffer...")
            val chipData = programmerController.readSpiFlash(length, onProgress = { done, total ->
                runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
            })
            setProgProgress(null)
            if (chipData == null) {
                val detail = programmerController.usbDevice.lastError
                progLog("Verify failed - could not read the chip" + (if (detail != null) " [$detail]" else ""), LogLevel.ERROR)
                return@launch
            }
            showVerifyResult("SPI flash", sourceSnapshot, chipData, spiHexAdapter)
        }
    }

    /** SPI counterpart of [onEepromDumpClicked] - reads the configured size, then hands off to
     *  [spiSaveLauncher] to save it, same as [onSpiReadClicked] followed by Save File in one tap. */
    private fun onSpiDumpClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val sizeKb = progBinding.etSpiSizeKb.text.toString().toIntOrNull()
        if (sizeKb == null || sizeKb <= 0) {
            progLog("Enter a valid size in KB", LogLevel.WARN)
            return
        }
        val length = sizeKb * 1024
        lifecycleScope.launch {
            programmerController.configureSpiAddressing(length.toLong())
            setProgProgress(0)
            spiHexAdapter.beginLiveFill(length)
            progLog("Dumping $sizeKb KB from SPI flash...")
            val data = programmerController.readSpiFlash(
                length,
                onProgress = { done, total -> runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) } },
                onChunk = { offset, chunk -> runOnUiThread { spiHexAdapter.appendChunk(offset, chunk) } }
            )
            setProgProgress(null)
            if (data == null) {
                val detail = programmerController.usbDevice.lastError
                progLog("Dump failed - check wiring and that a chip is present" + (if (detail != null) " [$detail]" else ""), LogLevel.ERROR)
                return@launch
            }
            spiChipSnapshot = data.copyOf()
            spiChipSnapshotLabel = progBinding.spinnerSpiChip.selectedItem?.toString() ?: "SPI flash"
            refreshSpiHexInfo("from chip")
            if (spiHexTab == HexTab.COMPARE) refreshSpiCompare()
            progLog("Read ${data.size} bytes - choose where to save")
            spiSaveLauncher.launch(suggestedFileName("spi_flash_dump", data.size))
        }
    }

    private fun onSpiReadClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val sizeKb = progBinding.etSpiSizeKb.text.toString().toIntOrNull()
        if (sizeKb == null || sizeKb <= 0) {
            progLog("Enter a valid size in KB", LogLevel.WARN)
            return
        }
        val length = sizeKb * 1024
        lifecycleScope.launch {
            programmerController.configureSpiAddressing(length.toLong())
            setProgProgress(0)
            spiHexAdapter.beginLiveFill(length)
            progLog("Reading $sizeKb KB from SPI flash...")
            val data = programmerController.readSpiFlash(
                length,
                onProgress = { done, total ->
                    runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
                },
                onChunk = { offset, chunk ->
                    runOnUiThread { spiHexAdapter.appendChunk(offset, chunk) }
                }
            )
            setProgProgress(null)
            if (data != null) {
                spiChipSnapshot = data.copyOf()
                spiChipSnapshotLabel = progBinding.spinnerSpiChip.selectedItem?.toString() ?: "SPI flash"
                refreshSpiHexInfo("from chip")
                if (spiHexTab == HexTab.COMPARE) refreshSpiCompare()
                progLog("Read ${data.size} bytes")
            } else {
                val detail = programmerController.usbDevice.lastError
                progLog(
                    "SPI flash read failed - check wiring and that a chip is present" +
                        (if (detail != null) " [$detail]" else ""),
                    LogLevel.ERROR
                )
            }
        }
    }

    private fun onSpiEraseClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val sizeBytes = currentSpiSizeBytes()
        styledDialog("Erase entire SPI flash chip?", DialogBadge.CONFIRM)
            .setMessage("This wipes the whole chip and cannot be undone. Continue?")
            .setPositiveButton("Erase") { _, _ ->
                lifecycleScope.launch {
                    programmerController.configureSpiAddressing(sizeBytes)
                    setProgBusy("erasing")
                    progLog("Erasing SPI flash chip - this can take a while...")
                    val ok = programmerController.eraseSpiFlashChip()
                    setProgProgress(null)
                    if (ok) progLog("Chip erase complete") else progLog("Chip erase failed", LogLevel.ERROR)
                    if (ok && sizeBytes > 0) {
                        spiHexAdapter.replaceData(ByteArray(sizeBytes.toInt()) { 0xFF.toByte() })
                        spiChipSnapshot = spiHexAdapter.currentData().copyOf()
                        spiChipSnapshotLabel = progBinding.spinnerSpiChip.selectedItem?.toString() ?: "SPI flash"
                        refreshSpiHexInfo("from chip")
                        if (spiHexTab == HexTab.COMPARE) refreshSpiCompare()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onSpiWriteClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val live = spiHexAdapter.currentData()
        if (live.isEmpty()) {
            progLog("Nothing to write - tap Read or Load File first", LogLevel.WARN)
            return
        }
        val data = live.copyOf() // snapshot - the adapter's live array stays editable during the write
        val pageSize = progBinding.etSpiPageSize.text.toString().toIntOrNull() ?: 256
        styledDialog("Write to chip?", DialogBadge.CONFIRM)
            .setMessage("Writes ${data.size} bytes to SPI flash (page size $pageSize B). This overwrites the chip's existing data. Continue?")
            .setPositiveButton("Write") { _, _ -> startSpiWrite(data, pageSize) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSpiWrite(data: ByteArray, pageSize: Int) {
        lifecycleScope.launch {
            programmerController.configureSpiAddressing(maxOf(currentSpiSizeBytes(), data.size.toLong()))
            setProgProgress(0)
            progLog("Writing ${data.size} bytes to SPI flash (page size $pageSize B)...")
            val ok = programmerController.writeSpiFlash(data, pageSize) { done, total ->
                runOnUiThread { setProgProgress(if (total > 0) done * 100 / total else 100) }
            }
            setProgProgress(null)
            if (ok) progLog("SPI flash write complete") else progLog("SPI flash write failed", LogLevel.ERROR)
        }
    }

    private fun onSpiLoadFileClicked() {
        progLog("Choose a file to load into the hex editor...")
        spiOpenLauncher.launch(arrayOf("*/*"))
    }

    private fun onSpiFileLoaded(bytes: ByteArray, displayName: String?) {
        spiHexAdapter.replaceData(bytes)
        spiFileSnapshot = bytes.copyOf()
        spiFileSnapshotLabel = displayName ?: "Loaded file"
        refreshSpiHexInfo("from file")
        if (spiHexTab == HexTab.COMPARE) refreshSpiCompare()
        progLog("Loaded ${bytes.size} bytes into the hex editor")
    }

    private fun onSpiSaveFileClicked() {
        val data = spiHexAdapter.currentData()
        if (data.isEmpty()) {
            progLog("Nothing to save - tap Read or Load File first", LogLevel.WARN)
            return
        }
        spiSaveLauncher.launch(suggestedFileName("spi_flash_dump", data.size))
    }

    private fun onSpiByteTapped(offset: Int) {
        showByteEditDialog(spiHexAdapter, offset) { refreshSpiHexInfo("edited, not written yet") }
    }

    private fun onSpiAsciiTapped(offset: Int) {
        showAsciiEditDialog(spiHexAdapter, offset) { refreshSpiHexInfo("edited, not written yet") }
    }

    private fun onSpiWriteTextClicked() {
        showWriteTextDialog(spiHexAdapter) { refreshSpiHexInfo("text written, not written to chip yet") }
    }

    private fun refreshSpiHexInfo(source: String) {
        val n = spiHexAdapter.currentData().size
        progBinding.tvSpiHexInfo.text = if (n == 0) getString(R.string.prog_hex_no_data) else "$n bytes - $source"
    }

    /** SPI counterpart of [switchEepromHexTab]. */
    private fun switchSpiHexTab(tab: HexTab) {
        spiHexTab = tab
        val p = progBinding
        p.llSpiHexDumpView.visibility = if (tab == HexTab.DUMP) View.VISIBLE else View.GONE
        p.llSpiHexCompareView.visibility = if (tab == HexTab.COMPARE) View.VISIBLE else View.GONE
        setHexTabStyle(p.tabSpiHexDump, tab == HexTab.DUMP)
        setHexTabStyle(p.tabSpiHexCompare, tab == HexTab.COMPARE)
        if (tab == HexTab.COMPARE) refreshSpiCompare()
    }

    /** SPI counterpart of [refreshEepromCompare]. */
    private fun refreshSpiCompare() {
        val p = progBinding
        val chip = spiChipSnapshot
        val file = spiFileSnapshot

        p.rvSpiCompareChip.visibility = if (chip != null) View.VISIBLE else View.GONE
        p.tvSpiCompareChipHint.visibility = if (chip == null) View.VISIBLE else View.GONE
        p.tvSpiCompareChipCrc.text = if (chip != null) HexUtils.crc32Hex(chip) else getString(R.string.compare_value_placeholder)
        p.tvSpiCompareChipName.text = if (chip != null) spiChipSnapshotLabel else ""

        p.rvSpiCompareFile.visibility = if (file != null) View.VISIBLE else View.GONE
        p.tvSpiCompareFileHint.visibility = if (file == null) View.VISIBLE else View.GONE
        p.tvSpiCompareFileCrc.text = if (file != null) HexUtils.crc32Hex(file) else getString(R.string.compare_value_placeholder)
        p.tvSpiCompareFileName.text = if (file != null) spiFileSnapshotLabel else ""

        spiCompareChipAdapter.replaceData(chip ?: ByteArray(0))
        spiCompareChipAdapter.setDiffAgainst(file)
        spiCompareFileAdapter.replaceData(file ?: ByteArray(0))
        spiCompareFileAdapter.setDiffAgainst(chip)
    }

    /**
     * Best-effort proportional scroll sync between the Hex Compare tab's two RecyclerViews (the
     * chip and file buffers are generally a different length) - scrolling either one scrolls the
     * other to the matching fraction of its own range. `suppressSync` stops the mirrored
     * [RecyclerView.scrollBy] call on one side from re-triggering the listener on the other.
     */
    private fun setupCompareScrollSync(a: RecyclerView, b: RecyclerView) {
        var suppressSync = false
        fun mirror(source: RecyclerView, target: RecyclerView) {
            if (suppressSync) return
            val sourceRange = source.computeVerticalScrollRange() - source.computeVerticalScrollExtent()
            val targetRange = target.computeVerticalScrollRange() - target.computeVerticalScrollExtent()
            if (sourceRange <= 0 || targetRange <= 0) return
            val fraction = source.computeVerticalScrollOffset().toFloat() / sourceRange
            val targetOffset = (fraction * targetRange).toInt()
            suppressSync = true
            target.scrollBy(0, targetOffset - target.computeVerticalScrollOffset())
            suppressSync = false
        }
        a.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = mirror(a, b)
        })
        b.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = mirror(b, a)
        })
    }

    // ---- SSD1306 OLED -----------------------------------------------------------

    private fun parseOledAddress(): Int =
        progBinding.etOledAddress.text.toString().trim()
            .removePrefix("0x").removePrefix("0X")
            .toIntOrNull(16) ?: 0x3C

    private fun onOledInitClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val (w, h) = selectedOledSize
        val addr = parseOledAddress()
        lifecycleScope.launch {
            val ok = programmerController.initDisplay(addr, w, h)
            if (ok) {
                progLog("SSD1306 initialized (${w}x$h @ 0x${addr.toString(16)})")
            } else {
                progLog("SSD1306 init failed - check address/wiring", LogLevel.ERROR)
            }
        }
    }

    private fun onOledTestClicked() {
        lifecycleScope.launch {
            val ok = programmerController.displayTestPattern()
            if (ok) progLog("Test pattern sent") else progLog("Init the display first", LogLevel.WARN)
        }
    }

    private fun onOledClearClicked() {
        lifecycleScope.launch {
            val ok = programmerController.displayClear()
            if (ok) progLog("Display cleared") else progLog("Init the display first", LogLevel.WARN)
        }
    }

    private fun onOledDrawTextClicked() {
        val text = progBinding.etOledText.text.toString()
        lifecycleScope.launch {
            val ok = if (programmerController.customFontTypeface != null) {
                programmerController.displayCustomText(text)
            } else {
                programmerController.displayText(text)
            }
            if (ok) progLog("Text drawn") else progLog("Init the display first", LogLevel.WARN)
        }
    }

    private fun onOledLoadImageClicked() {
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        progLog("Choose an image to display...")
        oledImageLauncher.launch(arrayOf("image/*"))
    }

    private fun onOledImageChosen(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) {
                null
            }
            if (bitmap == null) {
                progLog("Failed to decode the selected image", LogLevel.ERROR)
                return@launch
            }
            val ok = programmerController.displayBitmap(bitmap)
            bitmap.recycle()
            if (ok) progLog("Image drawn (scaled + thresholded to 1-bit)") else progLog("Init the display first", LogLevel.WARN)
        }
    }

    // ---- Custom font (Draw Text / the Clock's date can use a loaded TTF/OTF) ----

    private fun updateFontStatus() {
        progBinding.tvFontStatus.text = programmerController.customFontName?.let { "Using: $it" }
            ?: getString(R.string.prog_font_none)
    }

    private fun onFontChosen(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { programmerController.loadCustomFont(this@MainActivity, uri) }
            updateFontStatus()
            if (ok) {
                progLog("Font loaded: ${programmerController.customFontName} - Draw Text and the Clock will use it")
            } else {
                progLog("That file doesn't look like a valid font", LogLevel.WARN)
            }
        }
    }

    // ---- Clock / Stats / Animate - continuous redraw loops -----------------------

    private fun resetContinuousButtons() {
        progBinding.btnClockToggle.text = getString(R.string.prog_clock_start)
        progBinding.btnStatsToggle.text = getString(R.string.prog_stats_start)
        progBinding.btnAnimToggle.text = getString(R.string.prog_anim_start)
    }

    private fun onClockToggleClicked() {
        if (activeContinuousMode == ContinuousMode.CLOCK) {
            programmerController.stopContinuous()
            activeContinuousMode = null
            progBinding.btnClockToggle.text = getString(R.string.prog_clock_start)
            progLog("Clock stopped")
            return
        }
        if (!programmerController.isConnected) {
            progLog("Connect in SSD1306 Display mode first", LogLevel.WARN)
            return
        }
        resetContinuousButtons()
        activeContinuousMode = ContinuousMode.CLOCK
        progBinding.btnClockToggle.text = getString(R.string.prog_clock_stop)
        progLog("Clock started")
        programmerController.startClock(intervalMs = 1000L) { error ->
            runOnUiThread {
                progBinding.btnClockToggle.text = getString(R.string.prog_clock_start)
                if (activeContinuousMode == ContinuousMode.CLOCK) activeContinuousMode = null
                if (error != null) progLog(error, LogLevel.ERROR)
            }
        }
    }

    private fun onStatsToggleClicked() {
        if (activeContinuousMode == ContinuousMode.STATS) {
            programmerController.stopContinuous()
            activeContinuousMode = null
            progBinding.btnStatsToggle.text = getString(R.string.prog_stats_start)
            progLog("Stats stopped")
            return
        }
        if (!programmerController.isConnected) {
            progLog("Connect in SSD1306 Display mode first", LogLevel.WARN)
            return
        }
        resetContinuousButtons()
        activeContinuousMode = ContinuousMode.STATS
        progBinding.btnStatsToggle.text = getString(R.string.prog_stats_stop)
        progLog("Stats started")
        programmerController.startStats(applicationContext, intervalMs = 1000L) { error ->
            runOnUiThread {
                progBinding.btnStatsToggle.text = getString(R.string.prog_stats_start)
                if (activeContinuousMode == ContinuousMode.STATS) activeContinuousMode = null
                if (error != null) progLog(error, LogLevel.ERROR)
            }
        }
    }

    private fun onAnimChooseClicked() {
        animFramesLauncher.launch(arrayOf("image/*"))
    }

    private fun onAnimFramesChosen(uris: List<Uri>) {
        animFrameUris = sortUrisByDisplayName(uris)
        progBinding.tvAnimStatus.text = "${uris.size} frame(s) selected"
        progLog("${uris.size} animation frame(s) selected")
    }

    private fun sortUrisByDisplayName(uris: List<Uri>): List<Uri> = uris.sortedBy { uri ->
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else uri.toString()
            } ?: uri.toString()
        } catch (e: Exception) {
            uri.toString()
        }
    }

    /** The picked file's display name (for the Hex Compare tab's "File data" label), or null if it
     *  can't be resolved - callers fall back to a generic label in that case. */
    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun onAnimToggleClicked() {
        if (activeContinuousMode == ContinuousMode.ANIMATION) {
            programmerController.stopContinuous()
            activeContinuousMode = null
            progBinding.btnAnimToggle.text = getString(R.string.prog_anim_start)
            progLog("Animation stopped")
            return
        }
        if (!programmerController.isConnected) {
            progLog("Connect in SSD1306 Display mode first", LogLevel.WARN)
            return
        }
        if (animFrameUris.isEmpty()) {
            progLog("Choose frames first", LogLevel.WARN)
            return
        }
        val delayMs = progBinding.etAnimDelay.text.toString().toLongOrNull()
        if (delayMs == null || delayMs <= 0) {
            progLog("Enter a valid frame delay in ms", LogLevel.WARN)
            return
        }
        resetContinuousButtons()
        activeContinuousMode = ContinuousMode.ANIMATION
        progBinding.btnAnimToggle.text = getString(R.string.prog_anim_stop)
        progLog("Loading ${animFrameUris.size} frame(s)...")
        lifecycleScope.launch {
            val frames = withContext(Dispatchers.IO) {
                animFrameUris.mapNotNull { uri ->
                    try {
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            if (frames.isEmpty() || activeContinuousMode != ContinuousMode.ANIMATION) {
                if (frames.isEmpty()) {
                    progLog("Couldn't decode any of the selected frames", LogLevel.ERROR)
                } else {
                    frames.forEach { it.recycle() } // superseded by another mode while decoding
                }
                if (activeContinuousMode == ContinuousMode.ANIMATION) {
                    activeContinuousMode = null
                    progBinding.btnAnimToggle.text = getString(R.string.prog_anim_start)
                }
                return@launch
            }
            progLog("Animating ${frames.size} frame(s) every ${delayMs}ms")
            programmerController.startAnimation(frames, delayMs) { error ->
                runOnUiThread {
                    progBinding.btnAnimToggle.text = getString(R.string.prog_anim_start)
                    if (activeContinuousMode == ContinuousMode.ANIMATION) activeContinuousMode = null
                    if (error != null) progLog(error, LogLevel.ERROR)
                    frames.forEach { it.recycle() }
                }
            }
        }
    }

    // ---- I2C-over-TCP -----------------------------------------------------------

    private fun onI2cTcpToggleClicked() {
        if (programmerController.isI2cTcpServerRunning) {
            programmerController.stopI2cTcpServer()
            progBinding.tvI2cTcpStatus.text = getString(R.string.prog_i2ctcp_not_running)
            progBinding.tvI2cTcpToggleLabel.text = getString(R.string.prog_i2ctcp_start)
            progLog("I2C-over-TCP server stopped")
            if (programmerController.isConnected) {
                ProgrammerService.start(this, getString(R.string.prog_service_connected, programmerController.usbDevice.deviceLabel()))
            }
            return
        }
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val port = progBinding.etI2cTcpPort.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            progLog("Enter a valid port number (1-65535)", LogLevel.WARN)
            return
        }
        try {
            programmerController.startI2cTcpServer(
                port = port,
                onClientConnected = { addr -> runOnUiThread { progLog("I2C-over-TCP client connected: $addr") } },
                onClientDisconnected = { addr -> runOnUiThread { progLog("I2C-over-TCP client disconnected: $addr") } },
                onError = { e -> runOnUiThread { progLog("I2C-over-TCP error: ${e.message}", LogLevel.ERROR) } }
            )
            val address = "${OledCompositor.wifiOrCellularIpAddress(this)}:$port"
            progBinding.tvI2cTcpStatus.text = "Listening on $address"
            progBinding.tvI2cTcpToggleLabel.text = getString(R.string.prog_i2ctcp_stop)
            progLog("I2C-over-TCP server started on port $port")
            ProgrammerService.start(this, getString(R.string.prog_service_tcp_running, address))
        } catch (e: Exception) {
            progLog("Failed to start I2C-over-TCP server: ${e.message}", LogLevel.ERROR)
        }
    }

    /** SPI counterpart of [onI2cTcpToggleClicked] - same shape, just against
     *  [ProgrammerController.startSpiTcpServer]/`isSpiTcpServerRunning`. Doesn't need this page's
     *  mode check duplicated here beyond `isConnected`: unlike the I2C server, the SPI one
     *  initializes its own bus on demand - see [ProgrammerController.startSpiTcpServer]'s doc. */
    private fun onSpiTcpToggleClicked() {
        if (programmerController.isSpiTcpServerRunning) {
            programmerController.stopSpiTcpServer()
            progBinding.tvSpiTcpStatus.text = getString(R.string.prog_i2ctcp_not_running)
            progBinding.tvSpiTcpToggleLabel.text = getString(R.string.prog_i2ctcp_start)
            progLog("SPI-over-TCP server stopped")
            if (programmerController.isConnected) {
                ProgrammerService.start(this, getString(R.string.prog_service_connected, programmerController.usbDevice.deviceLabel()))
            }
            return
        }
        if (!programmerController.isConnected) {
            progLog("Connect to the CH341A first", LogLevel.WARN)
            return
        }
        val port = progBinding.etSpiTcpPort.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            progLog("Enter a valid port number (1-65535)", LogLevel.WARN)
            return
        }
        try {
            programmerController.startSpiTcpServer(
                port = port,
                onClientConnected = { addr -> runOnUiThread { progLog("SPI-over-TCP client connected: $addr") } },
                onClientDisconnected = { addr -> runOnUiThread { progLog("SPI-over-TCP client disconnected: $addr") } },
                onError = { e -> runOnUiThread { progLog("SPI-over-TCP error: ${e.message}", LogLevel.ERROR) } }
            )
            val address = "${OledCompositor.wifiOrCellularIpAddress(this)}:$port"
            progBinding.tvSpiTcpStatus.text = "Listening on $address"
            progBinding.tvSpiTcpToggleLabel.text = getString(R.string.prog_i2ctcp_stop)
            progLog("SPI-over-TCP server started on port $port")
            ProgrammerService.start(this, getString(R.string.prog_service_tcp_running, address))
        } catch (e: Exception) {
            progLog("Failed to start SPI-over-TCP server: ${e.message}", LogLevel.ERROR)
        }
    }

    // ---- file I/O (Storage Access Framework) -----------------------------------

    private fun suggestedFileName(prefix: String, sizeBytes: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_${sizeBytes}B_$stamp.bin"
    }

    private fun writeBytesToUri(uri: Uri, data: ByteArray, label: String) {
        lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    true
                }
            } catch (e: Exception) {
                false
            }
            if (ok) progLog("$label saved (${data.size} bytes)") else progLog("Failed to save $label", LogLevel.ERROR)
        }
    }

    private fun readBytesFromUri(uri: Uri, onLoaded: (ByteArray) -> Unit) {
        lifecycleScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            } catch (e: Exception) {
                null
            }
            if (bytes != null) onLoaded(bytes) else progLog("Failed to read the selected file", LogLevel.ERROR)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes >= 1024 * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    // ---- hex editor byte-edit dialogs -------------------------------------------

    /** Shows a tiny "enter a new hex byte" dialog for [offset], committing via [adapter] on Save. */
    private fun showByteEditDialog(adapter: HexEditorAdapter, offset: Int, onCommitted: () -> Unit) {
        val current = adapter.byteAt(offset) ?: return
        val input = EditText(this).apply {
            setText(String.format("%02X", current.toInt() and 0xFF))
            filters = arrayOf(InputFilter.LengthFilter(2))
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
            setBackgroundResource(R.drawable.bg_dialog_input)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dialog_title))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        styledDialog("Edit byte at offset 0x%X".format(offset), DialogBadge.EDIT)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim().toIntOrNull(16)
                if (value == null || value !in 0..255) {
                    progLog("Enter a valid 2-digit hex value (00-FF)", LogLevel.WARN)
                } else {
                    adapter.setByte(offset, value.toByte())
                    onCommitted()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shows a "type text starting here" dialog for [offset] - the ASCII-column counterpart of
     *  [showByteEditDialog], but - since tapping an ASCII character is the person placing a
     *  cursor, not picking a single byte to overwrite - it writes each typed character as
     *  consecutive ASCII bytes from [offset] onward (clamped to the buffer's end) instead of
     *  limiting input to one character. Same idea as [showWriteTextDialog], just anchored at the
     *  tapped offset instead of always starting from 0. */
    private fun showAsciiEditDialog(adapter: HexEditorAdapter, offset: Int, onCommitted: () -> Unit) {
        val remaining = adapter.currentData().size - offset
        if (remaining <= 0) return

        val input = EditText(this).apply {
            hint = "Type text..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setBackgroundResource(R.drawable.bg_dialog_input)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dialog_title))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.dialog_hint))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        styledDialog("Edit text at offset 0x%X".format(offset), DialogBadge.EDIT)
            .setMessage("Types as ASCII bytes starting at this offset, overwriting whatever's currently there.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString()
                if (text.isEmpty()) {
                    progLog("Enter some text first", LogLevel.WARN)
                    return@setPositiveButton
                }
                val toWrite = if (text.length > remaining) text.substring(0, remaining) else text
                toWrite.forEachIndexed { i, ch -> adapter.setByte(offset + i, (ch.code and 0xFF).toByte()) }
                onCommitted()
                if (text.length > remaining) {
                    progLog("Wrote the first $remaining of ${text.length} characters - the rest didn't fit the buffer", LogLevel.WARN)
                } else {
                    progLog("Wrote ${toWrite.length} character(s) starting at offset 0x%X".format(offset))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shared Find/Replace dialog for the hex editor ([R.layout.dialog_find_replace]) - operates
     * directly on [adapter]'s live buffer, highlighting matches (blue) and replacements (green)
     * right in [recyclerView] rather than only reporting a status line, and leaves that highlight
     * in place after the dialog closes so the result is still visible in the grid. [onCommitted]
     * runs after every successful Find/Replace/Replace All so the caller can switch back to the
     * Hex Dump tab and refresh its own info line - the same pattern [showByteEditDialog]/
     * [showAsciiEditDialog]/[showWriteTextDialog] already use for the hex editor's other edits.
     * Unlike those, this dialog needs four independent actions plus an options block and a
     * quick-insert grid, so - like [dialog_find_replace.xml] says - it skips [styledDialog]
     * entirely and builds its own title bar and buttons as one fully custom view.
     */
    private fun showFindReplaceDialog(adapter: HexEditorAdapter, recyclerView: RecyclerView, onCommitted: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_find_replace, null)
        val spinnerFindEncoding = view.findViewById<Spinner>(R.id.spinnerFindEncoding)
        val spinnerReplaceEncoding = view.findViewById<Spinner>(R.id.spinnerReplaceEncoding)
        val etFindQuery = view.findViewById<EditText>(R.id.etFindQuery)
        val etReplaceQuery = view.findViewById<EditText>(R.id.etReplaceQuery)
        val tvStatus = view.findViewById<TextView>(R.id.tvFindStatus)
        val cbBackwards = view.findViewById<CheckBox>(R.id.cbFindBackwards)
        val cbPrompt = view.findViewById<CheckBox>(R.id.cbFindPrompt)

        val findEncodings = arrayOf("UTF-8", "Hex", "ASCII", "Decimal")
        val replaceEncodings = arrayOf("Hex", "UTF-8", "ASCII")
        spinnerFindEncoding.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, findEncodings)
        spinnerReplaceEncoding.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, replaceEncodings)

        // Absolute offset (and length) of whatever's currently highlighted blue, if anything -
        // what "Find" advances past and what "Replace" acts on.
        var lastMatchOffset: Int? = null
        var lastMatchLength = 0

        fun resetSearchState() {
            lastMatchOffset = null
            lastMatchLength = 0
            adapter.clearHighlight()
        }
        etFindQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = resetSearchState()
        })

        fun scrollToOffset(offset: Int) {
            val row = offset / HexEditorAdapter.BYTES_PER_ROW
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(row, 0)
        }

        fun runFind() {
            val data = adapter.currentData()
            val encoding = findEncodings[spinnerFindEncoding.selectedItemPosition]
            val parsed = HexUtils.parseQueryBytes(etFindQuery.text.toString(), encoding)
            val pattern = when (parsed) {
                is HexUtils.QueryResult.Err -> { tvStatus.text = parsed.message; return }
                is HexUtils.QueryResult.Ok -> parsed.bytes
            }
            if (pattern.isEmpty() || pattern.size > data.size) {
                lastMatchOffset = null
                lastMatchLength = 0
                adapter.clearHighlight()
                tvStatus.text = "Not found"
                return
            }

            val foundAt: Int
            if (!cbBackwards.isChecked) {
                val start = lastMatchOffset?.plus(1) ?: 0
                foundAt = HexUtils.indexOfBytes(data, pattern, start)
                    .let { if (it == -1) HexUtils.indexOfBytes(data, pattern, 0) else it } // wrap to start
            } else {
                val start = lastMatchOffset?.minus(1) ?: (data.size - pattern.size)
                foundAt = HexUtils.lastIndexOfBytes(data, pattern, start)
                    .let { if (it == -1) HexUtils.lastIndexOfBytes(data, pattern, data.size - pattern.size) else it } // wrap to end
            }

            if (foundAt == -1) {
                lastMatchOffset = null
                lastMatchLength = 0
                adapter.clearHighlight()
                tvStatus.text = "Not found"
            } else {
                lastMatchOffset = foundAt
                lastMatchLength = pattern.size
                adapter.setHighlight((foundAt until foundAt + pattern.size).toSet(), emptySet())
                tvStatus.text = "Found @ " + HexUtils.fmtAddr(foundAt)
                onCommitted()
                scrollToOffset(foundAt)
            }
        }

        fun performReplace(offset: Int, length: Int, replBytes: ByteArray) {
            val writeLen = minOf(replBytes.size, length)
            for (k in 0 until writeLen) adapter.setByte(offset + k, replBytes[k])
            adapter.setHighlight(emptySet(), (offset until offset + writeLen).toSet())
            tvStatus.text = "Replaced 1 match @ " + HexUtils.fmtAddr(offset)
            lastMatchOffset = null
            lastMatchLength = 0
            onCommitted()
            scrollToOffset(offset)
        }

        fun runReplace() {
            if (lastMatchOffset == null) {
                runFind()
                if (lastMatchOffset == null) return
            }
            val replEncoding = replaceEncodings[spinnerReplaceEncoding.selectedItemPosition]
            val parsed = HexUtils.parseQueryBytes(etReplaceQuery.text.toString(), replEncoding)
            val replBytes = when (parsed) {
                is HexUtils.QueryResult.Err -> { tvStatus.text = parsed.message; return }
                is HexUtils.QueryResult.Ok -> parsed.bytes
            }
            if (replBytes.isEmpty()) {
                tvStatus.text = "Enter replacement"
                return
            }

            val offset = lastMatchOffset ?: return
            val length = lastMatchLength
            if (cbPrompt.isChecked) {
                styledDialog("Replace match?", DialogBadge.EDIT)
                    .setMessage("Replace the match at ${HexUtils.fmtAddr(offset)}?")
                    .setPositiveButton("Replace") { _, _ -> performReplace(offset, length, replBytes) }
                    .setNegativeButton("Cancel") { _, _ -> tvStatus.text = "Replace cancelled" }
                    .show()
            } else {
                performReplace(offset, length, replBytes)
            }
        }

        fun runReplaceAll() {
            val data = adapter.currentData()
            val findEncoding = findEncodings[spinnerFindEncoding.selectedItemPosition]
            val findParsed = HexUtils.parseQueryBytes(etFindQuery.text.toString(), findEncoding)
            val pattern = when (findParsed) {
                is HexUtils.QueryResult.Err -> { tvStatus.text = findParsed.message; return }
                is HexUtils.QueryResult.Ok -> findParsed.bytes
            }
            if (pattern.isEmpty() || pattern.size > data.size) {
                tvStatus.text = "Not found"
                return
            }

            val replEncoding = replaceEncodings[spinnerReplaceEncoding.selectedItemPosition]
            val replParsed = HexUtils.parseQueryBytes(etReplaceQuery.text.toString(), replEncoding)
            val replBytes = when (replParsed) {
                is HexUtils.QueryResult.Err -> { tvStatus.text = replParsed.message; return }
                is HexUtils.QueryResult.Ok -> replParsed.bytes
            }
            if (replBytes.isEmpty()) {
                tvStatus.text = "Enter replacement"
                return
            }

            val writeLen = minOf(replBytes.size, pattern.size)
            val promptEach = cbPrompt.isChecked

            // Non-overlapping replace-all, walking forward from `fromPos`. When "Prompt on
            // replace" is on, each match pauses on its own confirm dialog and this resumes from
            // that dialog's button callback instead of a plain loop.
            fun replaceRemaining(fromPos: Int, replacedSoFar: Int) {
                var pos = fromPos
                var count = replacedSoFar
                while (pos <= data.size - pattern.size) {
                    val off = HexUtils.indexOfBytes(data, pattern, pos)
                    if (off == -1) break
                    if (promptEach) {
                        styledDialog("Replace match?", DialogBadge.EDIT)
                            .setMessage("Replace the match at ${HexUtils.fmtAddr(off)}?")
                            .setPositiveButton("Replace") { _, _ ->
                                for (k in 0 until writeLen) adapter.setByte(off + k, replBytes[k])
                                adapter.setHighlight(emptySet(), (off until off + writeLen).toSet())
                                replaceRemaining(off + pattern.size, count + 1)
                            }
                            .setNegativeButton("Skip") { _, _ -> replaceRemaining(off + pattern.size, count) }
                            .show()
                        return
                    }
                    for (k in 0 until writeLen) adapter.setByte(off + k, replBytes[k])
                    pos = off + pattern.size
                    count++
                }
                lastMatchOffset = null
                lastMatchLength = 0
                tvStatus.text = if (count > 0) "Replaced $count match" + (if (count == 1) "" else "es") else "No matches found"
                onCommitted()
            }
            replaceRemaining(0, 0)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Prog_ConfirmDialog)
            .setView(view)
            .create()

        fun insertHeader(hex: String) {
            spinnerFindEncoding.setSelection(findEncodings.indexOf("Hex"))
            etFindQuery.setText(hex)
            resetSearchState()
            tvStatus.text = "Header inserted"
        }

        view.findViewById<View>(R.id.btnHeaderZip).setOnClickListener { insertHeader("50 4B 03 04") }
        view.findViewById<View>(R.id.btnHeaderPng).setOnClickListener { insertHeader("89 50 4E 47 0D 0A 1A 0A") }
        view.findViewById<View>(R.id.btnHeaderJpg).setOnClickListener { insertHeader("FF D8 FF E0") }
        view.findViewById<View>(R.id.btnHeaderGif).setOnClickListener { insertHeader("47 49 46 38 39 61") }
        view.findViewById<View>(R.id.btnHeaderTar).setOnClickListener { insertHeader("75 73 74 61 72") }
        view.findViewById<View>(R.id.btnHeaderUefi).setOnClickListener { insertHeader("4D 5A") }
        view.findViewById<View>(R.id.btnHeaderBios).setOnClickListener { insertHeader("55 AA") }
        view.findViewById<View>(R.id.btnHeaderGpt).setOnClickListener { insertHeader("45 46 49 20 50 41 52 54") }

        view.findViewById<View>(R.id.btnFindGo).setOnClickListener { runFind() }
        view.findViewById<View>(R.id.btnReplaceGo).setOnClickListener { runReplace() }
        view.findViewById<View>(R.id.btnReplaceAllGo).setOnClickListener { runReplaceAll() }
        view.findViewById<View>(R.id.btnFindDialogClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnFindDialogCancel).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ---- shared progress/log helpers -------------------------------------------

    private fun setProgProgress(percent: Int?) {
        val bar = progBinding.progressProgrammer
        val label = progBinding.tvProgPercent
        bar.isIndeterminate = false
        if (percent == null) {
            bar.visibility = View.INVISIBLE
            label.visibility = View.INVISIBLE
        } else {
            val clamped = percent.coerceIn(0, 100)
            bar.visibility = View.VISIBLE
            bar.progress = clamped
            label.visibility = View.VISIBLE
            label.text = "$clamped%"
        }
    }

    /** For long single-shot operations with no natural byte-count progress to report (chip
     *  erase: one busy-poll wait with no chunked callback) - an indeterminate spinner plus a
     *  short status word, instead of leaving the percentage stuck at a static "0%" for however
     *  long the operation actually takes. */
    private fun setProgBusy(statusWord: String) {
        val bar = progBinding.progressProgrammer
        val label = progBinding.tvProgPercent
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        label.visibility = View.VISIBLE
        label.text = statusWord
    }

    private enum class LogLevel(val tag: String) { INFO("INFO"), WARN("WARN"), ERROR("ERROR") }

    /** Whether the Log panel's body is currently showing - see [setupLogToggle]. Starts collapsed
     *  (unlike the reference HTML, which starts expanded) since the person just wants it out of
     *  the way until they tap the header. */
    private var progLogExpanded = false

    /**
     * Wires the Log header row to show/hide [ActivityMainBinding.includeProgrammer]'s log body,
     * rotating the chevron to match - the same click-to-toggle-with-a-rotating-chevron mechanism
     * as the reference HTML's `toggleLog()` (an up chevron at 0° means "expanded, tap to
     * collapse"; rotated 180° - pointing down - means "collapsed, tap to expand").
     */
    private fun setupLogToggle() {
        val p = progBinding
        p.scrollProgLog.visibility = if (progLogExpanded) View.VISIBLE else View.GONE
        p.ivProgLogChevron.rotation = if (progLogExpanded) 0f else 180f
        applyLogBottomInset()
        p.llProgLogHeader.setOnClickListener {
            progLogExpanded = !progLogExpanded
            p.scrollProgLog.visibility = if (progLogExpanded) View.VISIBLE else View.GONE
            p.ivProgLogChevron.rotation = if (progLogExpanded) 0f else 180f
            applyLogBottomInset()
            if (progLogExpanded) p.scrollProgLog.post { p.scrollProgLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Appends one colored log line - `[HH:mm:ss] LEVEL message` - matching the reference design's
     *  INFO (accent blue) / WARN (amber) / ERROR (red) log style. [level] defaults to INFO so
     *  every already-written call site keeps compiling/behaving as a plain status line; only the
     *  call sites that are genuinely a warning or a failure pass [LogLevel.WARN]/[LogLevel.ERROR]
     *  explicitly. */
    private fun progLog(message: String, level: LogLevel = LogLevel.INFO) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val color = when (level) {
            LogLevel.INFO -> getColor(R.color.bridge_accent)
            LogLevel.WARN -> getColor(R.color.status_warn)
            LogLevel.ERROR -> getColor(R.color.status_error)
        }
        val prefix = "[$time] "
        val tag = "${level.tag} "
        val line = SpannableString(prefix + tag + message + "\n")
        line.setSpan(ForegroundColorSpan(getColor(R.color.log_timestamp)), 0, prefix.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val tagStart = prefix.length
        val tagEnd = tagStart + tag.length
        line.setSpan(ForegroundColorSpan(color), tagStart, tagEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.setSpan(StyleSpan(Typeface.BOLD), tagStart, tagEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        progBinding.tvProgLog.append(line)
        progBinding.scrollProgLog.post { progBinding.scrollProgLog.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        /** Rows' worth of 0xFF ("erased flash") shown in each hex viewer before any real Read -
         *  just enough to look populated (matching the reference design) without pretending a
         *  multi-megabyte chip has actually been read; Read/Erase/Load File/Write Random all
         *  replace this via [HexEditorAdapter.replaceData] same as always. */
        private const val PLACEHOLDER_HEX_BYTES = 256

        private fun defaultHexPlaceholder(): ByteArray = ByteArray(PLACEHOLDER_HEX_BYTES) { 0xFF.toByte() }
    }
}
