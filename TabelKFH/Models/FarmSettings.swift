import Foundation
import SwiftData

/// Farm details printed on timesheets. One object per store, see `FarmSettingsStore`.
@Model
final class FarmSettings {
    var farmName: String = "КФХ Зубахин"
    var managerName: String = ""
    var responsiblePerson: String = ""
    var address: String = ""
    var phone: String = ""
    var extraInfo: String = ""

    init(farmName: String = "КФХ Зубахин",
         managerName: String = "",
         responsiblePerson: String = "",
         address: String = "",
         phone: String = "",
         extraInfo: String = "") {
        self.farmName = farmName
        self.managerName = managerName
        self.responsiblePerson = responsiblePerson
        self.address = address
        self.phone = phone
        self.extraInfo = extraInfo
    }
}

extension FarmSettings {
    /// The details printed on timesheets.
    var info: FarmInfo {
        FarmInfo(farmName: farmName, managerName: managerName, responsiblePerson: responsiblePerson,
                 address: address, phone: phone, extraInfo: extraInfo)
    }

    func apply(_ i: FarmInfo) {
        farmName = i.farmName
        managerName = i.managerName
        responsiblePerson = i.responsiblePerson
        address = i.address
        phone = i.phone
        extraInfo = i.extraInfo
    }
}

/// The store keeps exactly one FarmSettings row.
enum FarmSettingsStore {
    /// The settings row; created with the defaults («КФХ Зубахин») and saved when the store has none.
    @discardableResult
    static func current(in context: ModelContext) -> FarmSettings {
        var descriptor = FetchDescriptor<FarmSettings>()
        descriptor.fetchLimit = 1
        if let existing = (try? context.fetch(descriptor))?.first { return existing }
        let settings = FarmSettings()
        context.insert(settings)
        try? context.syncedSave()
        return settings
    }
}
