/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.ch341

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * Raw USB transport for the CH341A chip running in its "parallel/EPP" (a.k.a. programmer) mode -
 * the mode used by the ubiquitous black/green "CH341A" USB EEPROM/SPI-flash programmer boards.
 *
 * This is a completely different USB personality from the CH340/CH341 *serial* mode that
 * [com.androprogx.app.UsbSerialManager] / usb-serial-for-android already talk to elsewhere in
 * this app: in programmer mode the chip enumerates at VID 0x1A86 / PID 0x5512 with a single
 * vendor-specific interface (no CDC-ACM at all), so it needs its own permission flow, its own
 * open/claim-interface dance, and its own bulk-transfer plumbing rather than a serial port.
 * (A CH340/CH341 wired for plain UART mode - the boards this app already bridges - enumerates as
 * PID 0x7523 and never shows up here; the two modes are mutually exclusive per physical chip.)
 *
 * This class only knows how to find the device, get permission, open it, and move raw bytes over
 * bulk endpoints 0x02 (OUT) / 0x82 (IN). The actual CH341A command protocol (SPI stream, I2C
 * stream, UIO stream) lives in [Ch341Spi] and [Ch341I2c], ported from flashrom's ch341a_spi.c and
 * IMSProg's ch34x_i2c.c (GPLv2/GPLv3 - see NOTICE.md at the repo root).
 */
class Ch341UsbDevice(private val context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    var device: UsbDevice? = null
        private set

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epOut: UsbEndpoint? = null
    private var epIn: UsbEndpoint? = null

    val isOpen: Boolean get() = connection != null

    /**
     * Set once at [open] to the OUT/IN bulk endpoint addresses and `maxPacketSize`s actually
     * found on this phone - the one number every chunking decision in [bulkOut]/[readExactly]
     * depends on, and one this app has never actually surfaced anywhere until now. Meant to be
     * logged right after a successful connect (see MainActivity's "Connected:" log line) so a bug
     * report shows it up front instead of it staying an unknown.
     */
    var endpointInfo: String? = null
        private set

    /**
     * Human-readable detail on the most recent [bulkOut]/[readExactly] failure: which call it
     * was, how many bytes had already gone through, and what the failing `bulkTransfer()` call
     * itself returned. Meant purely for surfacing in the app's on-screen log - see MainActivity's
     * SPI read/detect failure branches, which append this - so a bug report shows *where* a
     * transfer broke instead of only "read failed".
     */
    @Volatile
    var lastError: String? = null
        private set

    /** The first attached CH341A running in programmer (not UART) mode, if any. */
    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID_PROGRAMMER
        }

    fun hasPermission(dev: UsbDevice): Boolean = usbManager.hasPermission(dev)

    /**
     * Requests USB permission for [dev] and calls [callback] with the result. Mirrors
     * MainActivity's requestUsbPermission() for the serial path - same broadcast-receiver
     * dance, just against a plain [UsbDevice] instead of a usb-serial-for-android driver.
     */
    fun requestPermission(dev: UsbDevice, callback: (Boolean) -> Unit) {
        val action = "com.androprogx.app.CH341_USB_PERMISSION"
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(action).setPackage(context.packageName), mutabilityFlag
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == action) {
                    context.unregisterReceiver(this)
                    callback(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        usbManager.requestPermission(dev, permissionIntent)
    }

    /** Opens [dev], claims interface 0, and locates the bulk IN/OUT endpoints. */
    fun open(dev: UsbDevice): Boolean {
        close()
        val conn = usbManager.openDevice(dev) ?: run {
            lastError = "open: openDevice() returned null"
            return false
        }
        if (dev.interfaceCount == 0) {
            conn.close()
            lastError = "open: device reports 0 interfaces"
            return false
        }
        val intf = dev.getInterface(0)
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            lastError = "open: claimInterface() failed"
            return false
        }
        var out: UsbEndpoint? = null
        var inn: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) inn = ep
            }
        }
        if (out == null || inn == null) {
            conn.releaseInterface(intf)
            conn.close()
            lastError = "open: no bulk IN/OUT endpoint pair found (interface has ${intf.endpointCount} endpoint(s))"
            return false
        }
        device = dev
        connection = conn
        iface = intf
        epOut = out
        epIn = inn
        endpointInfo = "OUT=0x%02X maxPacketSize=%d, IN=0x%02X maxPacketSize=%d".format(
            out.address, out.maxPacketSize, inn.address, inn.maxPacketSize
        )
        return true
    }

    fun close() {
        try {
            iface?.let { connection?.releaseInterface(it) }
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        iface = null
        epOut = null
        epIn = null
        device = null
    }

    /**
     * Sends [data] as one logical bulk OUT transfer, split into [UsbEndpoint.getMaxPacketSize]
     * chunks. Handing a single large buffer straight to `bulkTransfer()` is a well-known Android
     * USB-host trap: on many devices/kernels a big enough OUT buffer gets silently short-changed
     * or rejected in one call. Chunking to the endpoint's own reported max packet size - the same
     * unit [readExactly] already uses for the IN direction - sidesteps needing to know exactly
     * where that limit sits on any given phone. Returns true only if every chunk transferred in
     * full; on any failed chunk, records [lastError] and attempts [clearHalt] before returning
     * false so one bad transfer doesn't leave the endpoint stalled (and therefore every future
     * call - including a completely unrelated, otherwise-trivial one - failing) until the app
     * reconnects.
     */
    fun bulkOut(data: ByteArray, timeoutMs: Int = USB_TIMEOUT_MS): Boolean {
        val conn = connection ?: run { lastError = "bulkOut: not connected"; return false }
        val ep = epOut ?: run { lastError = "bulkOut: no OUT endpoint"; return false }
        if (data.isEmpty()) return true
        val chunkSize = maxOf(ep.maxPacketSize, 1)
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            val n = conn.bulkTransfer(ep, data, offset, len, timeoutMs)
            if (n != len) {
                lastError = "bulkOut: chunk at offset $offset/${data.size} (requested $len) returned $n"
                clearHalt(ep)
                return false
            }
            offset += len
        }
        return true
    }

    /**
     * Reads exactly [total] bytes from the bulk IN endpoint, looping over as many physical
     * transfers as needed (the CH341A answers in chunks tied to its own internal packet framing,
     * not necessarily all at once). Returns null on error/timeout, recording [lastError] first -
     * see [bulkOut]'s doc for why a hard error or exhausting the empty-read retry budget also
     * attempts [clearHalt] before giving up, rather than just returning null and leaving the
     * endpoint however it was left.
     */
    fun readExactly(total: Int, timeoutMs: Int = USB_TIMEOUT_MS): ByteArray? {
        if (total <= 0) return ByteArray(0)
        val conn = connection ?: run { lastError = "readExactly: not connected"; return null }
        val ep = epIn ?: run { lastError = "readExactly: no IN endpoint"; return null }
        val out = ByteArray(total)
        var received = 0
        val chunkSize = maxOf(ep.maxPacketSize, 1)
        val chunkBuf = ByteArray(chunkSize)
        var emptyReads = 0
        while (received < total) {
            val want = minOf(chunkSize, total - received)
            val n = conn.bulkTransfer(ep, chunkBuf, want, timeoutMs)
            if (n < 0) {
                lastError = "readExactly: bulkTransfer returned $n after $received/$total bytes"
                clearHalt(ep)
                return null
            }
            if (n == 0) {
                emptyReads++
                if (emptyReads > 20) {
                    lastError = "readExactly: 20 consecutive empty reads after $received/$total bytes"
                    clearHalt(ep)
                    return null
                }
                continue
            }
            emptyReads = 0
            System.arraycopy(chunkBuf, 0, out, received, n)
            received += n
        }
        return out
    }

    /**
     * Concurrently pipelines a batch of small OUT packets and their expected IN replies, keeping
     * up to [PIPELINE_WINDOW] IN reads outstanding at once and immediately re-submitting the next
     * OUT packet as soon as the previous one completes - instead of [bulkOut] + [readExactly]
     * fully completing one synchronous round trip before the next packet is even built.
     *
     * This is the Android `UsbRequest`/`requestWait()` equivalent of what IMSProg's own desktop
     * driver does with libusb's async transfer API in its `usb_transfer()` (`ch341a_spi.c`):
     * submit the OUT data, and separately keep several IN reads simultaneously in flight (its own
     * driver queues up to 32) so the device's small internal reply buffer never sits undrained
     * waiting for the host to get around to asking for the next reply - see
     * [Ch341Spi.rawTransfer]'s doc for why that continuous draining matters at all, and why this
     * function's window is 4 rather than desktop's 32: a real device queried during development
     * failed partway through its *fifth* un-drained packet when everything was sent with zero
     * draining at all, so 4 outstanding requests stays under that observed threshold with margin,
     * rather than assuming a number desktop only ever tuned for an entirely different USB stack
     * (its own comment says "32 seems to produce the most stable throughput on **Windows**")
     * transfers directly to Android.
     *
     * Android's `UsbRequest`/`requestWait()` async path has a real, documented history of being
     * flakier than the synchronous `bulkTransfer()` [bulkOut]/[readExactly] use, varying across
     * Android versions and OEM builds in ways that can't be verified without the specific device
     * in hand. This is why [Ch341Spi.rawTransfer] only ever treats this as a best-effort fast
     * path and falls back to its own proven sequential per-packet transfer on any failure here,
     * rather than this being the only way a read can happen - so a `UsbRequest` quirk on any
     * given phone costs speed, not correctness.
     *
     * [outPackets] and [replySizes] must be the same length, index-for-index: `replySizes[i]` is
     * exactly how many bytes the device is expected to echo back for `outPackets[i]` (its own
     * write-phase byte count plus any read-filler bytes in that packet - see
     * [Ch341Spi.rawTransfer]). Returns the concatenation of every reply in order (still including
     * each packet's own write-phase echo bytes - the caller strips those, same as the sequential
     * path does), or null on any failure: a request that won't initialize/queue, a completion
     * that doesn't match what was expected, or exhausting [timeoutMs] waiting for one.
     */
    fun pipelinedTransfer(
        outPackets: List<ByteArray>,
        replySizes: List<Int>,
        timeoutMs: Long = USB_TIMEOUT_MS.toLong()
    ): ByteArray? {
        val conn = connection ?: run { lastError = "pipelinedTransfer: not connected"; return null }
        val out = epOut ?: run { lastError = "pipelinedTransfer: no OUT endpoint"; return null }
        val inn = epIn ?: run { lastError = "pipelinedTransfer: no IN endpoint"; return null }
        val n = outPackets.size
        if (n == 0) return ByteArray(0)
        if (n != replySizes.size) {
            lastError = "pipelinedTransfer: outPackets/replySizes length mismatch ($n/${replySizes.size})"
            return null
        }

        val totalReply = replySizes.sum()
        val result = ByteArray(totalReply)
        val offsets = IntArray(n)
        run {
            var o = 0
            for (i in 0 until n) {
                offsets[i] = o
                o += replySizes[i]
            }
        }

        val outReq = UsbRequest()
        val inReqs = Array(minOf(PIPELINE_WINDOW, n)) { UsbRequest() }
        var initFailed = false
        if (!outReq.initialize(conn, out)) {
            lastError = "pipelinedTransfer: OUT UsbRequest.initialize() failed"
            initFailed = true
        }
        if (!initFailed) {
            for (r in inReqs) {
                if (!r.initialize(conn, inn)) {
                    lastError = "pipelinedTransfer: IN UsbRequest.initialize() failed"
                    initFailed = true
                    break
                }
            }
        }
        if (initFailed) {
            outReq.close()
            for (r in inReqs) r.close()
            return null
        }

        var ok = true
        try {
            var outIdx = 0
            var outActive = false
            var inSubmitIdx = 0
            var inDoneCount = 0
            val freeInSlots = ArrayDeque<UsbRequest>()
            freeInSlots.addAll(inReqs)
            val pendingInBuffers = HashMap<UsbRequest, ByteBuffer>()

            fun submitOut(): Boolean {
                if (outActive || outIdx >= n) return true
                val pkt = outPackets[outIdx]
                val buf = ByteBuffer.allocateDirect(pkt.size)
                buf.put(pkt)
                buf.position(0)
                if (!outReq.queue(buf, pkt.size)) {
                    lastError = "pipelinedTransfer: OUT queue() failed at packet $outIdx"
                    return false
                }
                outActive = true
                return true
            }

            fun submitIn(): Boolean {
                if (inSubmitIdx >= n || freeInSlots.isEmpty()) return true
                val slot = freeInSlots.removeFirst()
                val idx = inSubmitIdx
                val len = replySizes[idx]
                val buf = ByteBuffer.allocateDirect(maxOf(len, 1))
                slot.clientData = idx
                if (!slot.queue(buf, len)) {
                    lastError = "pipelinedTransfer: IN queue() failed at packet $idx"
                    return false
                }
                pendingInBuffers[slot] = buf
                inSubmitIdx++
                return true
            }

            if (!submitOut()) ok = false
            while (ok && inSubmitIdx < n && freeInSlots.isNotEmpty()) {
                if (!submitIn()) { ok = false; break }
            }

            while (ok && (outIdx < n || inDoneCount < n)) {
                val completed = try {
                    conn.requestWait(timeoutMs)
                } catch (e: TimeoutException) {
                    lastError = "pipelinedTransfer: requestWait() timed out after $inDoneCount/$n replies"
                    null
                }
                if (completed == null) {
                    if (ok) lastError = "pipelinedTransfer: requestWait() returned null after $inDoneCount/$n replies"
                    ok = false
                    break
                }

                if (completed === outReq) {
                    outActive = false
                    outIdx++
                    if (!submitOut()) { ok = false; break }
                } else {
                    val idx = completed.clientData as? Int
                    val buf = pendingInBuffers.remove(completed)
                    if (idx == null || buf == null) {
                        lastError = "pipelinedTransfer: completed IN request had no tracked buffer/index"
                        ok = false
                        break
                    }
                    val got = buf.position()
                    if (got != replySizes[idx]) {
                        lastError = "pipelinedTransfer: packet $idx reply short ($got/${replySizes[idx]} bytes)"
                        ok = false
                        break
                    }
                    buf.rewind()
                    buf.get(result, offsets[idx], got)
                    inDoneCount++
                    freeInSlots.addLast(completed)
                    if (!submitIn()) { ok = false; break }
                }
            }
        } finally {
            outReq.close()
            for (r in inReqs) r.close()
        }

        return if (ok) result else null
    }

    /**
     * Best-effort recovery from a stalled bulk endpoint via the standard USB
     * `CLEAR_FEATURE(ENDPOINT_HALT)` control request (host-to-device, standard, recipient =
     * endpoint). A device that rejects or gets confused by a transfer can halt the endpoint at the
     * USB level; once that happens every later `bulkTransfer()` on that same endpoint fails
     * immediately - including for a totally unrelated, otherwise-trivial command - until something
     * clears it (reconnecting works only because re-claiming the interface implicitly does this).
     * Calling this after any failed transfer means a single bad transaction can't permanently wedge
     * the connection. Return value is deliberately ignored by callers - if this itself fails there's
     * nothing more to try short of a full reconnect, and callers already report the original failure.
     */
    private fun clearHalt(ep: UsbEndpoint) {
        val conn = connection ?: return
        try {
            conn.controlTransfer(0x02, 0x01, 0x00, ep.address, null, 0, USB_TIMEOUT_MS)
        } catch (_: Exception) {
        }
    }

    fun deviceLabel(): String {
        val dev = device ?: return "CH341A"
        return "CH341A programmer (VID:${dev.vendorId} PID:${dev.productId})"
    }

    companion object {
        const val VENDOR_ID = 0x1A86
        const val PRODUCT_ID_PROGRAMMER = 0x5512
        const val USB_TIMEOUT_MS = 3000

        /** See [pipelinedTransfer]'s doc for how this was picked: empirically observed to be
         *  safely below (not copied from desktop's own Windows-tuned 32) the point where a real
         *  device stopped accepting further un-drained packets. */
        const val PIPELINE_WINDOW = 4
    }
}
