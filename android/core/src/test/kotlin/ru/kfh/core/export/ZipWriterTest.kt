package ru.kfh.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of ZipWriterTests.swift. */
class ZipWriterTest {
    @Test fun crc32OfTheStandardCheckString() {
        assertEquals(0xCBF43926L, ZipWriter.crc32("123456789".toByteArray()))
        assertEquals(0L, ZipWriter.crc32(ByteArray(0)))
    }

    @Test fun archiveStartsWithALocalHeaderAndEndsWithTheDirectoryRecord() {
        val zip = ZipWriter()
        zip.add("a.txt", "A".toByteArray())
        zip.add("b/b.txt", "BB".toByteArray())
        zip.add("c.txt", ByteArray(0))
        val data = zip.finish()

        assertArrayEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04), data.copyOfRange(0, 4))
        val end = data.copyOfRange(data.size - 22, data.size)
        assertArrayEquals(byteArrayOf(0x50, 0x4B, 0x05, 0x06), end.copyOfRange(0, 4))
        assertEquals("entries on this disk", 3, (end[8].toInt() and 0xFF) or ((end[9].toInt() and 0xFF) shl 8))
        assertEquals("entries in total", 3, (end[10].toInt() and 0xFF) or ((end[11].toInt() and 0xFF) shl 8))
        assertEquals(3, MiniZipReader(data).statedCount)
    }

    @Test fun readerGetsBackTheExactBytesAndUTF8Names() {
        val text = "Табель учёта рабочего времени\n".toByteArray()
        val binary = ByteArray(1000) { (it % 256).toByte() }
        val zip = ZipWriter()
        zip.add("Табель/лист.txt", text)
        zip.add("bin.dat", binary)
        zip.add("empty", ByteArray(0))

        val reader = MiniZipReader(zip.finish())
        assertEquals(listOf("Табель/лист.txt", "bin.dat", "empty"), reader.entries.map { it.name })
        assertArrayEquals(text, reader.data("Табель/лист.txt"))
        assertArrayEquals(binary, reader.data("bin.dat"))
        assertArrayEquals(ByteArray(0), reader.data("empty"))
        for (entry in reader.entries) {
            assertEquals("UTF-8 flag on ${entry.name}", 0x0800, entry.flags and 0x0800)
        }
    }
}
