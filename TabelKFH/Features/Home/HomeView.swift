import SwiftUI
import SwiftData

/// «Табель работников»: the chosen day (Вчера / Сегодня / Завтра or any date) and everyone who works then,
/// each with one large «Я» button and a small menu of the other statuses. No times, no hours.
struct HomeView: View {
    enum More: Hashable { case employees, calendar, timesheet }

    struct Row: Identifiable {
        let employee: Employee
        let status: DayStatus?
        var id: UUID { employee.id }
    }

    @Environment(\.modelContext) private var context
    @Environment(\.scenePhase) private var scenePhase
    @State private var day = Calendar.farm.dayStart(AppClock.now())
    @State private var rows: [Row] = []
    @State private var more: More?
    @State private var addingEmployee = false
    @State private var message: String?

    private var calendar: Calendar { .farm }
    private var today: Date { calendar.dayStart(AppClock.now()) }
    private var isFuture: Bool { day > today }
    private var service: AttendanceService { AttendanceService(context: context) }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                dateCard
                if rows.isEmpty {
                    Text("В этот день нет работающих сотрудников.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.vertical, 24)
                        .frame(maxWidth: .infinity)
                } else {
                    summary
                }
                ForEach(rows) { row in
                    EmployeeMarkRow(row: row, allowed: DayStatus.statuses(on: day, today: today),
                                    onToggle: { toggle(row.employee) },
                                    onSet: { set($0, for: row.employee) })
                }
                Button {
                    addingEmployee = true
                } label: {
                    Label("Добавить сотрудника", systemImage: "plus")
                }
                .buttonStyle(BigButtonStyle(color: Theme.primary))
                .accessibilityLabel("Добавить сотрудника")
                .padding(.top, 4)
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Табель работников")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { more = .timesheet } label: { Label("Табель за месяц", systemImage: "tablecells") }
                    Button { more = .calendar } label: { Label("Календарь", systemImage: "calendar") }
                    Button { more = .employees } label: { Label("Сотрудники и история", systemImage: "person.2") }
                } label: {
                    Label("Ещё", systemImage: "ellipsis.circle")
                }
                .accessibilityLabel("Ещё")
            }
        }
        .navigationDestination(item: $more) { m in
            switch m {
            case .employees: EmployeesView()
            case .calendar: CalendarView()
            case .timesheet: MonthTimesheetView()
            }
        }
        .onAppear(perform: reload)
        .onChange(of: day) { _, _ in reload() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { reload() }
        }
        .sheet(isPresented: $addingEmployee, onDismiss: reload) {
            NavigationStack { EmployeeFormView(employee: nil) }
        }
        .alert("Не получилось", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
    }

    // MARK: Parts

    private var dateCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(CoalText.longDate(day).capitalizedFirst)
                .font(.title2.bold())
                .foregroundStyle(Theme.primary)
            HStack(spacing: 8) {
                dayButton("Вчера", offset: -1)
                dayButton("Сегодня", offset: 0)
                dayButton("Завтра", offset: 1)
            }
            DatePicker("Другая дата", selection: Binding(get: { day }, set: { day = calendar.dayStart($0) }),
                       displayedComponents: .date)
                .font(.subheadline)
        }
        .card()
    }

    private func dayButton(_ title: String, offset: Int) -> some View {
        let target = calendar.date(byAdding: .day, value: offset, to: today) ?? today
        let selected = day == target
        return Button(title) { day = target }
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(selected ? .white : Theme.primary)
            .background(selected ? Theme.primary : Theme.background,
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var summary: some View {
        let present = rows.filter { $0.status == .present }.count
        let unmarked = rows.filter { $0.status == nil }.count
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("На работе: \(present) из \(rows.count)").font(.headline)
                Spacer()
                if unmarked > 0 {
                    Text("Не отмечены: \(unmarked)").font(.subheadline).foregroundStyle(Theme.accent)
                }
            }
            if unmarked > 0 && !isFuture {
                Button {
                    markAll()
                } label: {
                    Label("Отметить всех неотмеченных: Я", systemImage: "checkmark.circle")
                        .frame(maxWidth: .infinity, minHeight: 40)
                }
                .buttonStyle(.bordered)
                .tint(Theme.primary)
            }
        }
        .card()
    }

    // MARK: Actions

    private func reload() {
        let people = EmployeeService(context: context).active(on: day)
        let s = service
        rows = people.map { Row(employee: $0, status: s.status(of: $0, on: day)) }
    }

    private func toggle(_ e: Employee) {
        run { try service.togglePresence(e, on: day) }
    }

    private func set(_ status: DayStatus?, for e: Employee) {
        run { try service.setStatus(status, for: e, on: day) }
    }

    private func markAll() {
        run { try service.markAllPresent(rows.map(\.employee), on: day) }
    }

    private func run(_ action: () throws -> Any?) {
        do {
            _ = try action()
        } catch {
            message = error.localizedDescription
        }
        reload()
    }
}

/// One person: photo, ФИО, the day's status; the large «Я» button; «Статус» opens the other statuses.
struct EmployeeMarkRow: View {
    let row: HomeView.Row
    let allowed: [DayStatus]
    let onToggle: () -> Void
    let onSet: (DayStatus?) -> Void

    private var isPresent: Bool { row.status == .present }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                EmployeeAvatar(employee: row.employee, size: 44)
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.employee.fullName)
                        .font(.headline)
                        .lineLimit(2)
                    StatusChip(status: row.status)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 10) {
                if allowed.contains(.present) {
                    Button(action: onToggle) {
                        HStack(spacing: 8) {
                            Image(systemName: isPresent ? "checkmark.circle.fill" : "circle")
                            Text(isPresent ? "Я — был на работе" : "Отметить: Я")
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .foregroundStyle(isPresent ? .white : Theme.primary)
                        .background(isPresent ? Theme.primary : DayStatus.present.fill,
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(isPresent ? "Снять явку \(row.employee.fullName)" : "Явка \(row.employee.fullName)")
                    .accessibilityIdentifier("mark-\(row.employee.lastName)")
                }
                Menu {
                    ForEach(allowed, id: \.self) { s in
                        Button("\(s.rawValue) — \(s.title)") { onSet(s) }
                    }
                    if row.status != nil {
                        Button("Снять отметку", role: .destructive) { onSet(nil) }
                    }
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: "list.bullet")
                        Text("Статус").font(.caption.weight(.semibold))
                    }
                    .frame(width: allowed.contains(.present) ? 76 : nil, height: 54)
                    .frame(maxWidth: allowed.contains(.present) ? nil : .infinity)
                    .foregroundStyle(Theme.primary)
                    .background(Theme.background, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .accessibilityLabel("Статус \(row.employee.fullName)")
            }
        }
        .card()
    }
}
