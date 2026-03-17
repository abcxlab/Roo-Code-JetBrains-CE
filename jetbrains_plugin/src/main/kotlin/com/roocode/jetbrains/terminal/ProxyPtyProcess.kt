// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.terminal

import com.pty4j.PtyProcess
import com.intellij.openapi.diagnostic.Logger
import com.pty4j.WinSize
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * ProxyPtyProcess callback interface
 * Simplified version, only provides raw data callback
 */
interface ProxyPtyProcessCallback {
    /**
     * Raw data callback
     * @param data Raw string data
     * @param streamType Stream type (STDOUT/STDERR)
     */
    fun onRawData(data: String, streamType: String)
}

/**
 * ProxyPtyProcess implementation
 * Intercepts input/output stream operations and provides raw data callback
 */
class ProxyPtyProcess(
    private val originalProcess: PtyProcess,
    private val charset: Charset,
    private val callback: ProxyPtyProcessCallback? = null
) : PtyProcess() {

    // Create proxy input stream (process standard output)
    private val proxyInputStream: ProxyInputStream = ProxyInputStream(
        originalProcess.inputStream,
        "STDOUT",
        charset,
        callback
    )

    // Create proxy error stream (process error output)
    private val proxyErrorStream: ProxyInputStream = ProxyInputStream(
        originalProcess.errorStream,
        "STDERR",
        charset,
        callback
    )

    // Override methods that require special handling
    override fun getInputStream(): java.io.InputStream = proxyInputStream
    override fun getErrorStream(): java.io.InputStream = proxyErrorStream
    override fun getOutputStream(): java.io.OutputStream = originalProcess.outputStream
    
    // Delegate all other methods to the original process
    override fun isAlive(): Boolean = originalProcess.isAlive()
    override fun pid(): Long = originalProcess.pid()
    override fun exitValue(): Int = originalProcess.exitValue()
    override fun waitFor(): Int = originalProcess.waitFor()
    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
        originalProcess.waitFor(timeout, unit)
    override fun destroy() = originalProcess.destroy()
    override fun destroyForcibly(): Process = originalProcess.destroyForcibly()
    override fun info(): ProcessHandle.Info = originalProcess.info()
    override fun children(): java.util.stream.Stream<ProcessHandle> = originalProcess.children()
    override fun descendants(): java.util.stream.Stream<ProcessHandle> = originalProcess.descendants()
    override fun setWinSize(winSize: WinSize) = originalProcess.setWinSize(winSize)
    override fun toHandle(): ProcessHandle = originalProcess.toHandle()
    override fun onExit(): java.util.concurrent.CompletableFuture<Process> = originalProcess.onExit()
    
    // PtyProcess specific methods
    override fun getWinSize(): WinSize = originalProcess.winSize
    override fun isConsoleMode(): Boolean = originalProcess.isConsoleMode
}

/**
 * Proxy InputStream implementation
 * Intercepts read operations and provides raw data callback with stateful decoding
 */
class ProxyInputStream(
    private val originalStream: java.io.InputStream,
    private val streamType: String,
    private val charset: Charset,
    private val callback: ProxyPtyProcessCallback?
) : java.io.InputStream() {

    private val logger = Logger.getInstance(ProxyInputStream::class.java)

    // Stateful decoder to handle multi-byte characters split across reads
    private val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    // Buffers for decoding
    private val byteBuffer = ByteBuffer.allocate(16384) // 16KB buffer
    private val charBuffer = CharBuffer.allocate(16384)

    override fun read(): Int {
        val result = originalStream.read()
        if (result != -1 && callback != null) {
            processBytes(byteArrayOf(result.toByte()), 0, 1)
        }
        return result
    }
    
    override fun read(b: ByteArray): Int {
        val result = originalStream.read(b)
        if (result > 0 && callback != null) {
            processBytes(b, 0, result)
        }
        return result
    }
    
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val result = originalStream.read(b, off, len)
        if (result > 0 && callback != null) {
            processBytes(b, off, result)
        }
        return result
    }

    /**
     * Process bytes using stateful decoder to handle multi-byte character boundaries
     */
    private fun processBytes(b: ByteArray, off: Int, len: Int) {
        try {
            var currentOff = off
            val end = off + len

            while (currentOff < end) {
                val remainingInInput = end - currentOff
                val spaceInByteBuf = byteBuffer.remaining()
                
                if (spaceInByteBuf == 0) {
                    // This shouldn't happen with 16KB buffer unless we have a very long invalid sequence
                    // but let's be safe and clear it if it's full
                    byteBuffer.clear()
                }

                val toPut = minOf(remainingInInput, byteBuffer.remaining())
                byteBuffer.put(b, currentOff, toPut)
                currentOff += toPut

                byteBuffer.flip()
                
                // Decode bytes to chars. false means we expect more data.
                val decodeResult = decoder.decode(byteBuffer, charBuffer, false)
                
                charBuffer.flip()
                if (charBuffer.hasRemaining()) {
                    callback?.onRawData(charBuffer.toString(), streamType)
                }
                
                charBuffer.clear()
                byteBuffer.compact() // Keep remaining bytes that couldn't be decoded yet
            }
        } catch (e: Exception) {
            logger.error("Error decoding terminal output stream ($streamType) with charset $charset", e)
            // Fallback: try to send as much as possible if decoding fails
            try {
                callback?.onRawData(String(b, off, len, charset), streamType)
            } catch (inner: Exception) {
                // Last resort
            }
        }
    }
    
    // Delegate other methods to the original stream
    override fun available(): Int = originalStream.available()
    override fun close() {
        decoder.reset()
        originalStream.close()
    }
    override fun mark(readlimit: Int) = originalStream.mark(readlimit)
    override fun reset() {
        decoder.reset()
        originalStream.reset()
    }
    override fun markSupported(): Boolean = originalStream.markSupported()
}
