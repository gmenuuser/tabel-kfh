import SwiftUI
import SwiftData

@main
struct TabelKFHApp: App {
    private let container: ModelContainer
    /// Only under `-uitest -uitest-dark`: the UI tests take dark-mode screenshots.
    private let forcedScheme: ColorScheme?

    init() {
        // `-uitest` (UI tests): an empty in-memory store, a fixed clock and time zone; a normal launch is unchanged.
        let arguments = ProcessInfo.processInfo.arguments
        let uiTest = arguments.contains("-uitest")
        // The farm's day keys use the zone stored on the first launch (UI tests: a fixed zone).
        Calendar.useFarmTimeZone(FarmTimeZone().launchZone(uiTest: uiTest))
        if uiTest { AppClock.startAtFixedMoment() }
        forcedScheme = uiTest && arguments.contains("-uitest-dark") ? .dark : nil
        do {
            container = try makeModelContainer(inMemory: uiTest)
        } catch {
            fatalError("Не удалось открыть базу данных: \(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environment(\.locale, Locale(identifier: "ru_RU"))
                .preferredColorScheme(forcedScheme)
        }
        .modelContainer(container)
    }
}
