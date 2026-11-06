import XCTest
@testable import TabelKFH

/// Test-side reader of a STORE zip: walks the central directory and reads each local entry.
struct MiniZipReader {
    struct Entry {
        let name: String
        let flags: Int
        let data: Data
    }

    let entries: [Entry]
    /// Entry count stated by the end-of-central-directory record.
    let statedCount: Int

    init(_ data: Data) throws {
        let b = [UInt8](data)
        func u16(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 }
        func u32(_ o: Int) -> Int { u16(o) | u16(o + 2) << 16 }
        struct Bad: Error {}

        let eocd = b.count - 22
        guard eocd >= 0, u32(eocd) == 0x0605_4B50 else { throw Bad() }
        statedCount = u16(eocd + 10)
        var offset = u32(eocd + 16)
        var result: [Entry] = []
        for _ in 0..<statedCount {
            guard offset + 46 <= b.count, u32(offset) == 0x0201_4B50 else { throw Bad() }
            let flags = u16(offset + 8)
            let method = u16(offset + 10)
            let size = u32(offset + 20)
            let nameLength = u16(offset + 28), extraLength = u16(offset + 30), commentLength = u16(offset + 32)
            let local = u32(offset + 42)
            guard method == 0, let name = String(bytes: b[(offset + 46)..<(offset + 46 + nameLength)], encoding: .utf8),
                  u32(local) == 0x0403_4B50 else { throw Bad() }
            let start = local + 30 + u16(local + 26) + u16(local + 28)
            guard start + size <= b.count else { throw Bad() }
            result.append(Entry(name: name, flags: flags, data: Data(b[start..<(start + size)])))
            offset += 46 + nameLength + extraLength + commentLength
        }
        entries = result
    }

    func data(_ name: String) -> Data? { entries.first { $0.name == name }?.data }
}

final class ZipWriterTests: XCTestCase {
    func testCRC32OfTheStandardCheckString() {
        XCTAssertEqual(ZipWriter.crc32(Data("123456789".utf8)), 0xCBF4_3926)
        XCTAssertEqual(ZipWriter.crc32(Data()), 0)
    }

    func testArchiveStartsWithALocalHeaderAndEndsWithTheDirectoryRecord() throws {
        var zip = ZipWriter()
        zip.add(path: "a.txt", data: Data("A".utf8))
        zip.add(path: "b/b.txt", data: Data("BB".utf8))
        zip.add(path: "c.txt", data: Data())
        let data = zip.finish()

        XCTAssertEqual([UInt8](data.prefix(4)), [0x50, 0x4B, 0x03, 0x04])
        let end = [UInt8](data.suffix(22))
        XCTAssertEqual(Array(end.prefix(4)), [0x50, 0x4B, 0x05, 0x06])
        XCTAssertEqual(Int(end[8]) | Int(end[9]) << 8, 3, "entries on this disk")
        XCTAssertEqual(Int(end[10]) | Int(end[11]) << 8, 3, "entries in total")
        XCTAssertEqual(try MiniZipReader(data).statedCount, 3)
    }

    func testReaderGetsBackTheExactBytesAndUTF8Names() throws {
        let text = Data("Табель учёта рабочего времени\n".utf8)
        let binary = Data((0..<1000).map { UInt8($0 % 256) })
        var zip = ZipWriter()
        zip.add(path: "Табель/лист.txt", data: text)
        zip.add(path: "bin.dat", data: binary)
        zip.add(path: "empty", data: Data())

        let reader = try MiniZipReader(zip.finish())
        XCTAssertEqual(reader.entries.map(\.name), ["Табель/лист.txt", "bin.dat", "empty"])
        XCTAssertEqual(reader.data("Табель/лист.txt"), text)
        XCTAssertEqual(reader.data("bin.dat"), binary)
        XCTAssertEqual(reader.data("empty"), Data())
        for entry in reader.entries {
            XCTAssertEqual(entry.flags & 0x0800, 0x0800, "UTF-8 flag on \(entry.name)")
        }
    }
}
