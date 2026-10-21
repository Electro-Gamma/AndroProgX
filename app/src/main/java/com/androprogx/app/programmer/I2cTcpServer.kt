/*
 * Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
 * Author: Yassine Moukil ELECTRO-GAMMA
 * Third-party / ported code attributions: see NOTICE.md
 */

package com.androprogx.app.programmer

import com.androprogx.app.ch341.Ch341I2c
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
 * Exposes whichever [Ch341I2c] bus [busProvider] currently returns over a small length-prefixed
 * TCP protocol, so a client anywhere on the network - not just this app - can run I2C
 * transactions against the CH341A without touching USB directly. See the repo's
 * `ch341-bridge-termux` script for a tested client (works for both this and [SpiTcpServer]).
 *
 * Wire protocol - one request/response pair per exchange, all multi-byte integers big-endian:
 * ```
 * Request:  [opcode:u8][addr7:u8][writeLen:u16][writeData...][readLen:u16]
 * Response: [status:u8][dataLen:u16][data...]
 * ```
 *  - [OP_WRITE] (0x01): a plain write - `writeLen` bytes follow the device address; `readLen`
 *    must be 0. `writeLen` 0 is a valid "quick write" (address cycle only, no payload).
 *  - [OP_READ] (0x02): a plain read of `readLen` bytes; `writeLen` must be 0.
 *  - [OP_WRITE_THEN_READ] (0x03): `writeLen` bytes, a repeated START, then `readLen` bytes -
 *    the standard "set a register pointer, then read it back" shape most I2C peripherals use.
 *
 * `status` in the response is 0 = OK, 1 = I2C/USB failure (bus not connected, or the CH341A
 * didn't get a response), 2 = bad request (unrecognized opcode). `dataLen`/`data` are present
 * only for reads; 0-length for a bare write/quick-write.
 *
 * Every transaction, across every connected client, is serialized through [mutex]: the CH341A
 * can only run one transaction at a time, and interleaving two clients' byte streams on the wire
 * would corrupt both.
 */
class I2cTcpServer(private val busProvider: () -> Ch341I2c?) {

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
                val addr7 = input.readUnsignedByte()
                val writeLen = input.readUnsignedShort()
                val writeData = ByteArray(writeLen)
                input.readFully(writeData)
                val readLen = input.readUnsignedShort()

                if (opcode != OP_WRITE && opcode != OP_READ && opcode != OP_WRITE_THEN_READ) {
                    output.writeByte(STATUS_BAD_REQUEST)
                    output.writeShort(0)
                    output.flush()
                    continue
                }

                val response = executeTransaction(opcode, addr7, writeData, readLen)
                if (response == null) {
                    output.writeByte(STATUS_I2C_ERROR)
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

    private suspend fun executeTransaction(opcode: Int, addr7: Int, writeData: ByteArray, readLen: Int): ByteArray? =
        mutex.withLock {
            val bus = busProvider() ?: return@withLock null
            val writeAddrByte = (addr7 shl 1)
            val readAddrByte = writeAddrByte or 1
            when (opcode) {
                OP_WRITE -> {
                    val ok = bus.transaction(
                        listOf(Ch341I2c.Phase.Write(byteArrayOf(writeAddrByte.toByte()) + writeData))
                    ) != null
                    if (ok) ByteArray(0) else null
                }
                OP_READ -> bus.transaction(
                    listOf(
                        Ch341I2c.Phase.Write(byteArrayOf(readAddrByte.toByte())),
                        Ch341I2c.Phase.Read(readLen)
                    )
                )
                OP_WRITE_THEN_READ -> bus.transaction(
                    listOf(
                        Ch341I2c.Phase.Write(byteArrayOf(writeAddrByte.toByte()) + writeData),
                        Ch341I2c.Phase.RepeatStart,
                        Ch341I2c.Phase.Write(byteArrayOf(readAddrByte.toByte())),
                        Ch341I2c.Phase.Read(readLen)
                    )
                )
                else -> null
            }
        }

    companion object {
        const val OP_WRITE = 0x01
        const val OP_READ = 0x02
        const val OP_WRITE_THEN_READ = 0x03

        private const val STATUS_OK = 0
        private const val STATUS_I2C_ERROR = 1
        private const val STATUS_BAD_REQUEST = 2

        const val DEFAULT_PORT = 3334
    }
}
