import Foundation
import CryptoKit

/// The same ids as the site (`web/src/lib/ids.ts`, `stableId`) for records that must be one per farm or one per
/// person and day: two devices that write such a record offline create the same row, not two.
/// First 16 bytes of SHA-256(UTF-8 name) with version nibble 8 and variant 10; UUIDs inside names are lowercase.
enum StableId {
    static func make(_ name: String) -> UUID {
        var b = Array(SHA256.hash(data: Data(name.utf8)).prefix(16))
        b[6] = (b[6] & 0x0f) | 0x80
        b[8] = (b[8] & 0x3f) | 0x80
        return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                           b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
    }

    /// «YYYY-MM-DD» of the moment on the farm calendar.
    static func dayKey(_ day: Date, calendar: Calendar = .farm) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: day)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func hoursMark(employeeId: UUID, day: Date, calendar: Calendar = .farm) -> UUID {
        make("hoursMark:\(employeeId.uuidString.lowercased()):\(dayKey(day, calendar: calendar))")
    }

    static func coalWorkDay(_ day: Date, calendar: Calendar = .farm) -> UUID {
        make("coalWorkDay:\(dayKey(day, calendar: calendar))")
    }

    static func deliveryLocation(_ name: String) -> UUID { make("deliveryLocation:\(name)") }

    /// `species` is the site's identifier, equal to `Species.rawValue` («cattle», «goats», «pigs»).
    static func livestockCategory(species: String, name: String) -> UUID { make("livestockCategory:\(species):\(name)") }

    static let coalSettings = make("coalSettings")
    static let farmSettings = make("farmSettings")
    static let livestockSettings = make("livestockSettings")
}
