package ru.kfh.core.export

import java.io.ByteArrayOutputStream

/**
 * A minimal zip archive writer (port of `ZipWriter.swift`): STORE method (no compression), UTF-8 names (flag bit 11),
 * every entry dated 1980-01-01 00:00 so equal input gives equal bytes.
 */
class ZipWriter {
    private val body = ByteArrayOutputStream()
    private val directory = ByteArrayOutputStream()
    private var count = 0

    fun add(path: String, data: ByteArray) {
        val name = path.toByteArray(Charsets.UTF_8)
        val crc = crc32(data)
        val offset = body.size()

        body.le32(0x04034B50)
        body.le16(VERSION)
        body.le16(UTF8_FLAG)
        body.le16(0) // STORE
        body.le16(DOS_TIME)
        body.le16(DOS_DATE)
        body.le32(crc)
        body.le32(data.size.toLong())
        body.le32(data.size.toLong())
        body.le16(name.size)
        body.le16(0) // extra
        body.write(name)
        body.write(data)

        directory.le32(0x02014B50)
        directory.le16(VERSION) // made by
        directory.le16(VERSION) // needed
        directory.le16(UTF8_FLAG)
        directory.le16(0)
        directory.le16(DOS_TIME)
        directory.le16(DOS_DATE)
        directory.le32(crc)
        directory.le32(data.size.toLong())
        directory.le32(data.size.toLong())
        directory.le16(name.size)
        directory.le16(0) // extra
        directory.le16(0) // comment
        directory.le16(0) // disk
        directory.le16(0) // internal attributes
        directory.le32(0) // external attributes
        directory.le32(offset.toLong())
        directory.write(name)
        count += 1
    }

    fun finish(): ByteArray {
        val out = ByteArrayOutputStream(body.size() + directory.size() + 22)
        body.writeTo(out)
        directory.writeTo(out)
        out.le32(0x06054B50)
        out.le16(0) // this disk
        out.le16(0) // disk with the directory
        out.le16(count)
        out.le16(count)
        out.le32(directory.size().toLong())
        out.le32(body.size().toLong())
        out.le16(0) // comment
        return out.toByteArray()
    }

    companion object {
        private const val UTF8_FLAG = 0x0800
        private const val VERSION = 20
        private const val DOS_TIME = 0
        /** (1980 − 1980) << 9 | 1 << 5 | 1. */
        private const val DOS_DATE = 0x0021

        private val crcTable: LongArray = LongArray(256) { n ->
            var c = n.toLong()
            repeat(8) { c = if (c and 1L == 1L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
            c
        }

        /** CRC-32 (IEEE 802.3), as zip stores it. */
        fun crc32(d: ByteArray): Long {
            var c = 0xFFFFFFFFL
            for (byte in d) c = crcTable[((c xor (byte.toLong() and 0xFF)) and 0xFF).toInt()] xor (c ushr 8)
            return c xor 0xFFFFFFFFL
        }

        private fun ByteArrayOutputStream.le16(v: Int) {
            write(v and 0xFF)
            write((v ushr 8) and 0xFF)
        }

        private fun ByteArrayOutputStream.le32(v: Long) {
            write((v and 0xFF).toInt())
            write(((v ushr 8) and 0xFF).toInt())
            write(((v ushr 16) and 0xFF).toInt())
            write(((v ushr 24) and 0xFF).toInt())
        }
    }
}
