import SwiftUI
import SwiftData

@main
struct TabelKFHApp: App {
    private let container: ModelContainer
    /// The device's sign-in (`-uitest`: the owner, offline, no sync; `-uitest-role`, `-uitest-signedout`).
    @State private var session: SessionStore
    /// Only under `-uitest -uitest-dark`: the UI tests take dark-mode screenshots.
    private let forcedScheme: ColorScheme?

    init() {
        // `-uitest` (UI tests): an empty in-memory store, a fixed clock and time zone; a normal launch is unchanged.
        let arguments = ProcessInfo.processInfo.arguments
        let uiTest = arguments.contains("-uitest")
        // The farm's day keys use the zone stored on the first launch (UI tests: a fixed zone).
        Calendar.useFarmTimeZone(FarmTimeZone().launchZone(uiTest: uiTest))
        if uiTest {
            AppClock.startAtFixedMoment()
            // Calculator drafts of an earlier UI test run would change the screenshots.
            for key in UserDefaults.standard.dictionaryRepresentation().keys where key.hasPrefix("agroDraft.") {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }
        forcedScheme = uiTest && arguments.contains("-uitest-dark") ? .dark : nil
        do {
            container = try makeModelContainer(inMemory: uiTest)
        } catch {
            fatalError("Не удалось открыть базу данных: \(error)")
        }
        // Every write goes through `syncedSave()` (store and send queue in one save); an autosave would store a
        // change without queueing it.
        let mainContainer = container
        let store = MainActor.assumeIsolated {
            mainContainer.mainContext.autosaveEnabled = false
            guard uiTest else { return SessionStore(container: mainContainer) }
            // `-uitest-signedout`: the sign-in screen; `-uitest-role timesheet`: a helper's screens.
            if arguments.contains("-uitest-signedout") { return SessionStore.uiTest(container: mainContainer, role: nil) }
            if let i = arguments.firstIndex(of: "-uitest-role"), i + 1 < arguments.count {
                return SessionStore.uiTest(container: mainContainer, role: arguments[i + 1])
            }
            return SessionStore.uiTest(container: mainContainer)
        }
        _session = State(initialValue: store)
    }

    var body: some Scene {
        WindowGroup {
            AppRootView()
                .environment(session)
                .environment(\.locale, Locale(identifier: "ru_RU"))
                .preferredColorScheme(forcedScheme)
        }
        .modelContainer(container)
    }
}
