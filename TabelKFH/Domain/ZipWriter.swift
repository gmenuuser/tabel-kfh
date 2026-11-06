import Foundation

/// A minimal zip archive writer: STORE method (no compression), UTF-8 names (flag bit 11),
/// every entry dated 1980-01-01 00:00 so equal input gives equal bytes.
struct ZipWriter {
    private var body = Data()
    private var directory = Data()
    private var count = 0

    private static let utf8Flag: UInt16 = 0x0800
    private static let version: UInt16 = 20
    private static let dosTime: UInt16 = 0
    /// (1980 − 1980) << 9 | 1 << 5 | 1.
    private static let dosDate: UInt16 = 0x0021

    mutating func add(path: String, data: Data) {
        let name = Data(path.utf8)
        let crc = Self.crc32(data)
        let offset = UInt32(body.count)

        body.append(le32: 0x0403_4B50)
        body.append(le16: Self.version)
        body.append(le16: Self.utf8Flag)
        body.append(le16: 0) // STORE
        body.append(le16: Self.dosTime)
        body.append(le16: Self.dosDate)
        body.append(le32: crc)
        body.append(le32: UInt32(data.count))
        body.append(le32: UInt32(data.count))
        body.append(le16: UInt16(name.count))
        body.append(le16: 0) // extra
        body.append(name)
        body.append(data)

        directory.append(le32: 0x0201_4B50)
        directory.append(le16: Self.version) // made by
        directory.append(le16: Self.version) // needed
        directory.append(le16: Self.utf8Flag)
        directory.append(le16: 0)
        directory.append(le16: Self.dosTime)
        directory.append(le16: Self.dosDate)
        directory.append(le32: crc)
        directory.append(le32: UInt32(data.count))
        directory.append(le32: UInt32(data.count))
        directory.append(le16: UInt16(name.count))
        directory.append(le16: 0) // extra
        directory.append(le16: 0) // comment
        directory.append(le16: 0) // disk
        directory.append(le16: 0) // internal attributes
        directory.append(le32: 0) // external attributes
        directory.append(le32: offset)
        directory.append(name)
        count += 1
    }

    func finish() -> Data {
        var out = body
        out.append(directory)
        out.append(le32: 0x0605_4B50)
        out.append(le16: 0) // this disk
        out.append(le16: 0) // disk with the directory
        out.append(le16: UInt16(count))
        out.append(le16: UInt16(count))
        out.append(le32: UInt32(directory.count))
        out.append(le32: UInt32(body.count))
        out.append(le16: 0) // comment
        return out
    }

    private static let crcTable: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 == 1 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    /// CRC-32 (IEEE 802.3), as zip stores it.
    static func crc32(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for byte in d { c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func append(le16 v: UInt16) {
        append(contentsOf: [UInt8(v & 0xFF), UInt8(v >> 8)])
    }

    mutating func append(le32 v: UInt32) {
        append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)])
    }
}
