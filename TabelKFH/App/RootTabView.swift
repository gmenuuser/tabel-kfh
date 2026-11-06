import SwiftUI

/// Bottom navigation: Главная, Сотрудники, Календарь, Табель, Настройки.
struct RootTabView: View {
    enum Tab: Hashable { case home, employees, calendar, timesheet, settings }

    @State private var tab: Tab = .home

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeView() }
                .tabItem { Label("Главная", systemImage: "house") }
                .tag(Tab.home)
            NavigationStack { EmployeesView() }
                .tabItem { Label("Сотрудники", systemImage: "person.2") }
                .tag(Tab.employees)
            NavigationStack { CalendarView() }
                .tabItem { Label("Календарь", systemImage: "calendar") }
                .tag(Tab.calendar)
            NavigationStack { TabPlaceholder(title: "Табель") }
                .tabItem { Label("Табель", systemImage: "tablecells") }
                .tag(Tab.timesheet)
            NavigationStack { TabPlaceholder(title: "Настройки") }
                .tabItem { Label("Настройки", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .tint(Theme.primary)
    }
}

/// Temporary tab body; each task replaces one with the real screen.
private struct TabPlaceholder: View {
    let title: String
    var body: some View {
        Text(title)
            .font(.title2)
            .foregroundStyle(.secondary)
            .navigationTitle(title)
    }
}
