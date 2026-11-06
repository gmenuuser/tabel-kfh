import SwiftUI
import SwiftData

@main
struct TabelKFHApp: App {
    private let container: ModelContainer

    init() {
        do {
            container = try makeModelContainer()
        } catch {
            fatalError("Не удалось открыть базу данных: \(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environment(\.locale, Locale(identifier: "ru_RU"))
        }
        .modelContainer(container)
    }
}
