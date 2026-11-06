import SwiftUI
import SwiftData

/// Where the main screen leads.
enum MainDestination: Hashable {
    case timesheet, coal, livestock, agro, reports, settings
}

/// One main screen with three large buttons; every part opens on top of it and «Назад» returns to it.
struct AppRootView: View {
    @Environment(\.modelContext) private var context
    /// Every part is opened again from the main screen, so after a restore from a backup it reads the restored data.
    @State private var path: [MainDestination] = []

    var body: some View {
        NavigationStack(path: $path) {
            MainScreen { path.append($0) }
                .navigationDestination(for: MainDestination.self) { destination in
                    switch destination {
                    case .timesheet: HomeView()
                    case .coal: CoalSaleScreen()
                    case .livestock: LivestockHomeView()
                    case .agro: AgroHomeView()
                    case .reports: ReportsView()
                    case .settings: SettingsView()
                    }
                }
        }
        .tint(Theme.primary)
        // Date pickers show and set times on the farm's calendar and zone, like every day key.
        .environment(\.calendar, Calendar.farm)
        .environment(\.timeZone, Calendar.farm.timeZone)
        .onAppear {
            // The store keeps one settings row each; they are made on the first launch.
            FarmSettingsStore.current(in: context)
            CoalSettingsService(context: context).current()
        }
    }
}

/// Главный экран: the farm, today, a short line about the day, and «ТАБЕЛЬ РАБОТНИКОВ», «ПРОДАЖА УГЛЯ»,
/// «ОТЧЁТЫ».
struct MainScreen: View {
    let open: (MainDestination) -> Void

    @Environment(\.modelContext) private var context
    @Environment(\.scenePhase) private var scenePhase
    @Query private var sales: [CoalSale]
    @Query private var workDays: [CoalWorkDay]
    @Query private var herdOperations: [LivestockOperation]
    @State private var today = Calendar.farm.dayStart(AppClock.now())
    @State private var farmName = ""
    /// People working today and those marked Я.
    @State private var staff = 0
    @State private var atWork = 0

    private var todaySales: [CoalSale] {
        let end = Calendar.farm.date(byAdding: .day, value: 1, to: today) ?? today
        return sales.filter { $0.saleDate >= today && $0.saleDate < end }
    }

    private var dayClosed: Bool { workDays.contains { $0.date == today && $0.isClosed } }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                header
                bigButton("ТАБЕЛЬ РАБОТНИКОВ", subtitle: "Отметки за день, табель за месяц, сотрудники",
                          icon: "person.2.fill", color: Theme.primary, id: "openTimesheet") { open(.timesheet) }
                bigButton("ПРОДАЖА УГЛЯ", subtitle: "Новая продажа, продажи за сегодня, итоги дня",
                          icon: "truck.box.fill", color: Theme.accent, id: "openCoal") { open(.coal) }
                bigButton("СКОТ И ЗАБОЙ", subtitle: "Поголовье, забой, приплод, история",
                          icon: "pawprint.fill", color: LivestockTheme.module, id: "openLivestock") { open(.livestock) }
                bigButton("АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ", subtitle: "Посев, удобрения, опрыскивание, урожай и другие",
                          icon: "function", color: CoalTheme.hired, id: "openAgro") { open(.agro) }
                bigButton("ОТЧЁТЫ", subtitle: "Табель, продажи угля, выплаты водителям — PDF и Excel",
                          icon: "doc.text.fill", color: Color(uiColor: .systemGray), id: "openReports") { open(.reports) }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Главная")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { open(.settings) } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel("Настройки")
                    .accessibilityIdentifier("openSettings")
            }
        }
        .onAppear(perform: reload)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { reload() }
        }
    }

    private func reload() {
        today = Calendar.farm.dayStart(AppClock.now())
        farmName = FarmSettingsStore.current(in: context).farmName
        let people = EmployeeService(context: context).active(on: today)
        let marks = AttendanceService(context: context)
        staff = people.count
        atWork = people.filter { marks.status(of: $0, on: today) == .present }.count
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(farmName)
                .font(.largeTitle.bold())
                .foregroundStyle(Theme.primary)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            Text(CoalText.longDate(today).capitalizedFirst)
                .font(.title3.weight(.medium))
            VStack(alignment: .leading, spacing: 4) {
                Label(staff == 0 ? "Сотрудников пока нет" : "На работе: \(atWork) из \(staff)",
                      systemImage: "person.2")
                let t = CoalTotals(todaySales)
                Label(t.salesCount == 0 ? "Продаж угля сегодня нет"
                      : "Уголь: \(CoalText.sales(t.salesCount)) на \(CoalMath.rubles(t.revenue))",
                      systemImage: "truck.box")
                let herd = LivestockLedger.balances(herdOperations.map(HerdMove.init)).values.reduce(0, +)
                if herd > 0 {
                    Label("Скот: \(LivestockText.heads(herd))", systemImage: "pawprint")
                }
                if dayClosed {
                    Label("Рабочий день по углю завершён", systemImage: "checkmark.seal.fill")
                        .foregroundStyle(Theme.primary)
                }
            }
            .font(.body)
            .foregroundStyle(.secondary)
        }
        .card()
    }

    private func bigButton(_ title: String, subtitle: String, icon: String, color: Color, id: String,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.system(size: 26, weight: .semibold))
                    .frame(width: 52, height: 52)
                    .background(.white.opacity(0.2), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.title3.bold()).lineLimit(2).minimumScaleFactor(0.8)
                        .multilineTextAlignment(.leading)
                    Text(subtitle).font(.subheadline).opacity(0.9).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.headline)
            }
            .foregroundStyle(.white)
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 84, alignment: .leading)
            .background(color, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint(subtitle)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(id)
    }
}
