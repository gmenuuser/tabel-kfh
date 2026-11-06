import SwiftUI

@main
struct TabelKFHApp: App {
    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environment(\.locale, Locale(identifier: "ru_RU"))
        }
    }
}
