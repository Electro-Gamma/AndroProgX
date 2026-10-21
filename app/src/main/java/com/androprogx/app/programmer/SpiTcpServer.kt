/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.programmer

import com.androprogx.app.ch341.Ch341Spi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Exposes whichever [Ch341Spi] bus [busProvider] currently returns over a small length-prefixed
 * TCP protocol - the SPI counterpart of [I2cTcpServer], letting a client anywhere on the network
 * run SPI transactions (chip ID, status register, bulk flash reads/writes) against the CH341A
 * without touching USB directly. Deliberately mirrors [I2cTcpServer]'s wire format and this
 * class's own structure closely - a client already speaking one protocol only has to change the
 * request shape (no 7-bit device address; SPI has no addressing, CS is the only "which chip"
 * signal, and this app only ever drives one) to speak the other.
 *
 * Wire protocol - one request/response pair per exchange, all multi-byte integers big-endian:
 * ```
 * Request:  [opcode:u8][writeLen:u16][writeData...][readLen:u16]
 * Response: [status:u8][dataLen:u16][data...]
 * ```
 *  - [OP_TRANSFER] (0x01): one CS-bracketed transaction with [writeLen] bytes and [readLen] bytes
 *    of reply built into the same packet sequence - see [Ch341Spi.transfer]. Fine for opcode-only
 *    or opcode+tiny-payload commands (JEDEC ID, a status register read, WREN/erase) that fit in a
 *    single 31-byte CH341A packet.
 *  - [OP_WRITE_THEN_READ] (0x02): [writeLen] bytes clocked out as one burst, then [readLen] bytes
 *    clocked in as a separate burst, same CS pulse - see [Ch341Spi.writeThenRead]. Required (not
 *    just preferred) for a real multi-byte address followed by a substantial read - see that
 *    function's doc for why combining the two in one packet sequence silently breaks bulk reads
 *    on real hardware even though the low-level command technically allows it. A client doing a
 *    flash content dump over this protocol should reach for this opcode, not [OP_TRANSFER].
 *
 * `status` in the response is 0 = OK, 1 = SPI/USB failure (bus not connected, or the transaction
 * itself failed), 2 = bad request (unrecognized opcode). `dataLen`/`data` are present only when
 * [readLen] was nonzero.
 *
 * Every transaction, across every connected client, is serialized through [mutex] - same reason
 * as [I2cTcpServer]: the CH341A can only run one transaction at a time.
 */
class SpiTcpServer(private val busProvider: () -> Ch341Spi?) {

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var acceptJob: Job? = null
    private val mutex = Mutex()

    var onClientConnected: ((String) -> Unit)? = null
    var onClientDisconnected: ((String) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    val isRunning: Boolean get() = serverSocket != null

    fun start(port: Int) {
        stop()
        val socket = ServerSocket(port)
        serverSocket = socket
        acceptJob = scope.launch {
            try {
                while (isActive) {
                    val client = socket.accept()
                    client.tcpNoDelay = true
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) onError?.invoke(e)
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) {
        val remote = socket.inetAddress?.hostAddress ?: "unknown"
        onClientConnected?.invoke(remote)
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        try {
            while (true) {
                val opcode = input.readUnsignedByte()
                val writeLen = input.readUnsignedShort()
                val writeData = ByteArray(writeLen)
                input.readFully(writeData)
                val readLen = input.readUnsignedShort()

                if (opcode != OP_TRANSFER && opcode != OP_WRITE_THEN_READ) {
                    output.writeByte(STATUS_BAD_REQUEST)
                    output.writeShort(0)
                    output.flush()
                    continue
                }

                val response = executeTransaction(opcode, writeData, readLen)
                if (response == null) {
                    output.writeByte(STATUS_SPI_ERROR)
                    output.writeShort(0)
                } else {
                    output.writeByte(STATUS_OK)
                    output.writeShort(response.size)
                    output.write(response)
                }
                output.flush()
            }
        } catch (_: EOFException) {
            // client closed its side - normal end of session
        } catch (_: IOException) {
            // socket reset/closed - normal disconnect, nothing to surface
        } catch (e: Exception) {
            onError?.invoke(e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            onClientDisconnected?.invoke(remote)
        }
    }

    private suspend fun executeTransaction(opcode: Int, writeData: ByteArray, readLen: Int): ByteArray? =
        mutex.withLock {
            val bus = busProvider() ?: return@withLock null
            when (opcode) {
                OP_TRANSFER -> bus.transfer(writeData, readLen)
                OP_WRITE_THEN_READ -> bus.writeThenRead(writeData, readLen)
                else -> null
            }
        }

    companion object {
        const val OP_TRANSFER = 0x01
        const val OP_WRITE_THEN_READ = 0x02

        private const val STATUS_OK = 0
        private const val STATUS_SPI_ERROR = 1
        private const val STATUS_BAD_REQUEST = 2

        const val DEFAULT_PORT = 3335
    }
}
