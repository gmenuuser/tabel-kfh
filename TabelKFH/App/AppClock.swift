import Foundation

/// The time the screens and services use when no clock is passed in.
/// A normal launch reads the real time; the UI tests (`-uitest`) start it at a fixed moment.
enum AppClock {
    static var now: () -> Date = Date.init

    /// 6 November 2026, 09:00 on the farm's calendar, then running on with the real seconds,
    /// so the screenshots of the UI tests show the same day every run.
    static func startAtFixedMoment() {
        let start = Calendar.farm.date(from: DateComponents(year: 2026, month: 11, day: 6, hour: 9)) ?? Date()
        let launched = Date()
        now = { start.addingTimeInterval(Date().timeIntervalSince(launched)) }
    }
}
