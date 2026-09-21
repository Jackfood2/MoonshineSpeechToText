package com.whisperkeyboard

import java.io.File
import java.io.RandomAccessFile

class PcmWavWriter(
    private val file: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1
) {

    private val raf =
        RandomAccessFile(file, "rw")

    private var dataBytes = 0L

    init {
        writeHeader(0)
    }

    @Synchronized
    fun write(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size
    ) {
        raf.seek(44 + dataBytes)
        raf.write(bytes, offset, length)
        dataBytes += length
    }

    @Synchronized
    fun close() {
        writeHeader(dataBytes)
        raf.close()
    }

    private fun writeHeader(dataSize: Long) {

        raf.seek(0)

        raf.writeBytes("RIFF")
        writeIntLE(
            (36 + dataSize).toInt()
        )

        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")

        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(channels)

        writeIntLE(sampleRate)

        val byteRate =
            sampleRate * channels * 2

        writeIntLE(byteRate)
        writeShortLE(channels * 2)
        writeShortLE(16)

        raf.writeBytes("data")
        writeIntLE(dataSize.toInt())
    }

    private fun writeIntLE(value: Int) {
        raf.write(
            byteArrayOf(
                value.toByte(),
                (value shr 8).toByte(),
                (value shr 16).toByte(),
                (value shr 24).toByte()
            )
        )
    }

    private fun writeShortLE(value: Int) {
        raf.write(
            byteArrayOf(
                value.toByte(),
                (value shr 8).toByte()
            )
        )
    }
}
