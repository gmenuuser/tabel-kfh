import Foundation

/// The farm's time zone, stored on the first launch (UserDefaults `farmTimeZone`). Day keys are midnights
/// in this zone, so a record keeps its day when the phone's zone changes (travel, a new phone);
/// a restore adopts the zone of the backup.
struct FarmTimeZone {
    static let key = "farmTimeZone"
    /// The UI tests' zone, so their fixed clock shows the same day on any runner.
    static let uiTestIdentifier = "Europe/Moscow"

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The stored zone; nil before the first launch.
    var stored: TimeZone? {
        defaults.string(forKey: Self.key).flatMap(TimeZone.init(identifier:))
    }

    /// The zone of this launch: the fixed test zone under `-uitest` (nothing stored), otherwise the stored zone,
    /// storing `device` first when none is stored yet.
    @discardableResult
    func launchZone(uiTest: Bool, device: TimeZone = .current) -> TimeZone {
        if uiTest { return TimeZone(identifier: Self.uiTestIdentifier) ?? device }
        if let stored { return stored }
        adopt(device)
        return device
    }

    func adopt(_ zone: TimeZone) {
        defaults.set(zone.identifier, forKey: Self.key)
    }

    /// After a restore: stores `zone` and makes `Calendar.farm` use it.
    static func adoptInApp(_ zone: TimeZone) {
        FarmTimeZone().adopt(zone)
        Calendar.useFarmTimeZone(zone)
    }
}
