import SwiftUI
import SwiftData

/// Where the main screen leads.
enum MainDestination: Hashable {
    case timesheet, coal, livestock, agro, reports, settings
}

/// Signed out: the sign-in screen; signing in: «Загрузка данных…»; signed in: the farm. Starts the sync on
/// launch, when the app becomes active (and every minute while it is) and when the network comes back.
struct AppRootView: View {
    @Environment(SessionStore.self) private var session
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch session.state {
            case .signedOut: SignInView()
            case .loading: LoadingDataView()
            case .signedIn: FarmRootView()
            }
        }
        .task { session.startTriggers() }
        .onChange(of: scenePhase, initial: true) { _, phase in session.scenePhaseChanged(phase) }
    }
}

/// One main screen with three large buttons; every part opens on top of it and «Назад» returns to it.
struct FarmRootView: View {
    @Environment(\.modelContext) private var context
    @Environment(SessionStore.self) private var session
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
        .safeAreaInset(edge: .top, spacing: 0) {
            // The first download did not finish (no network): the data comes with a later sync.
            if let engine = session.engine, engine.status.lastSyncAt == nil, let problem = engine.status.lastError {
                Label("Данные хозяйства ещё не загружены: \(problem)", systemImage: "icloud.slash")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Theme.card)
            }
        }
        .tint(Theme.primary)
        // Date pickers show and set times on the farm's calendar and zone, like every day key.
        .environment(\.calendar, Calendar.farm)
        .environment(\.timeZone, Calendar.farm.timeZone)
        .onAppear {
            // The store keeps one settings row each; they are made on the first launch.
            // Signed in, `FarmSettings` comes with `/me`; a role that may not change the coal settings never
            // makes them (the server would refuse the change).
            FarmSettingsStore.current(in: context)
            if session.canEdit(.coalSettings) { CoalSettingsService(context: context).current() }
        }
    }
}

/// Главный экран: the farm, today, a short line about the day, the sync line, and the big buttons of the parts the
/// role sees («ТАБЕЛЬ РАБОТНИКОВ», «ПРОДАЖА УГЛЯ», «СКОТ И ЗАБОЙ», «АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ», «ОТЧЁТЫ»).
struct MainScreen: View {
    let open: (MainDestination) -> Void

    @Environment(SessionStore.self) private var session
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
                SyncStatusLine()
                let shown = session.destinations
                if shown.isEmpty {
                    Text("Для вашей роли в хозяйстве нет доступных разделов. Обратитесь к владельцу.")
                        .foregroundStyle(.secondary)
                        .card()
                }
                if shown.contains(.timesheet) {
                    bigButton("ТАБЕЛЬ РАБОТНИКОВ", subtitle: "Отметки за день, табель за месяц, сотрудники",
                              icon: "person.2.fill", color: Theme.primary, id: "openTimesheet") { open(.timesheet) }
                }
                if shown.contains(.coal) {
                    bigButton("ПРОДАЖА УГЛЯ", subtitle: "Новая продажа, продажи за сегодня, итоги дня",
                              icon: "truck.box.fill", color: Theme.accent, id: "openCoal") { open(.coal) }
                }
                if shown.contains(.livestock) {
                    bigButton("СКОТ И ЗАБОЙ", subtitle: "Поголовье, забой, приплод, история",
                              icon: "pawprint.fill", color: LivestockTheme.module, id: "openLivestock") { open(.livestock) }
                }
                if shown.contains(.agro) {
                    bigButton("АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ", subtitle: "Посев, удобрения, опрыскивание, урожай и другие",
                              icon: "function", color: CoalTheme.hired, id: "openAgro") { open(.agro) }
                }
                if shown.contains(.reports) {
                    bigButton("ОТЧЁТЫ", subtitle: reportsSubtitle,
                              icon: "doc.text.fill", color: Color(uiColor: .systemGray), id: "openReports") { open(.reports) }
                }
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
        let marks = HoursService(context: context)
        staff = people.count
        atWork = people.filter { (marks.hours(of: $0, on: today) ?? 0) > 0 }.count
    }

    private var modules: [AppModule] { Roles.modules(role: session.role ?? "") }

    private var reportsSubtitle: String {
        var parts: [String] = []
        if modules.contains(.timesheet) { parts.append("Табель") }
        if modules.contains(.coal) { parts.append("продажи угля, выплаты водителям") }
        if modules.contains(.livestock) { parts.append("скот и забой") }
        return (parts.joined(separator: ", ") + " — PDF и Excel").capitalizedFirst
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
            if let role = session.role, role != "owner" {
                Label("Ваша роль: \(Roles.title(role).lowercased())", systemImage: "person.badge.key")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.primary)
            }
            VStack(alignment: .leading, spacing: 4) {
                if modules.contains(.timesheet) {
                    Label(staff == 0 ? "Сотрудников пока нет" : "На работе: \(atWork) из \(staff)",
                          systemImage: "person.2")
                }
                if modules.contains(.coal) {
                    let t = CoalTotals(todaySales)
                    Label(t.salesCount == 0 ? "Продаж угля сегодня нет"
                          : "Уголь: \(CoalText.sales(t.salesCount)) на \(CoalMath.rubles(t.revenue))",
                          systemImage: "truck.box")
                }
                let herd = LivestockLedger.balances(herdOperations.map(HerdMove.init)).values.reduce(0, +)
                if herd > 0 && modules.contains(.livestock) {
                    Label("Скот: \(LivestockText.heads(herd))", systemImage: "pawprint")
                }
                if dayClosed && modules.contains(.coal) {
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
