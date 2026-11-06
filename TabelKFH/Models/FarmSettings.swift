import Foundation
import SwiftData

/// Farm details printed on timesheets. One object per store.
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
