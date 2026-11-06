import SwiftUI
import SwiftData

/// Bottom navigation: Главная, Сотрудники, Календарь, Табель, Настройки.
struct RootTabView: View {
    enum Tab: Hashable { case home, employees, calendar, timesheet, settings }

    @Environment(\.modelContext) private var context
    @State private var tab: Tab = .home
    /// Bumped after a restore from a backup: the other tabs are built again and read the restored data.
    @State private var dataGeneration = 0

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView() }
                .id(dataGeneration)
                .tabItem { Label("Главная", systemImage: "house") }
                .tag(Tab.home)
            NavigationStack { EmployeesView() }
                .id(dataGeneration)
                .tabItem { Label("Сотрудники", systemImage: "person.2") }
                .tag(Tab.employees)
            NavigationStack { CalendarView() }
                .id(dataGeneration)
                .tabItem { Label("Календарь", systemImage: "calendar") }
                .tag(Tab.calendar)
            NavigationStack { TimesheetView() }
                .id(dataGeneration)
                .tabItem { Label("Табель", systemImage: "tablecells") }
                .tag(Tab.timesheet)
            NavigationStack { SettingsView { dataGeneration += 1 } }
                .tabItem { Label("Настройки", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .tint(Theme.primary)
        .onAppear {
            // The store keeps exactly one settings row; it is made on the first launch.
            FarmSettingsStore.current(in: context)
        }
    }
}
