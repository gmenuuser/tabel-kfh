import SwiftUI
import SwiftData
import UIKit

/// Colours of the hours: 0 grey, 4 yellow, 8 green, 12 blue.
enum HoursStyle {
    static func color(_ h: Int) -> Color {
        switch h {
        case 0: return Color(uiColor: .systemGray)
        case 4: return Color(uiColor: UIColor { $0.userInterfaceStyle == .dark
            ? UIColor(red: 0.85, green: 0.70, blue: 0.10, alpha: 1) : UIColor(red: 0.80, green: 0.60, blue: 0.0, alpha: 1) })
        case 8: return Theme.primary
        default: return CoalTheme.hired
        }
    }

    /// The light fill of a cell of the month table.
    static func fill(_ h: Int) -> Color { color(h).opacity(0.25) }

    static func title(_ h: Int) -> String { "\(h) ч" }
}

/// «Табель работников»: the chosen day (Вчера / Сегодня / Завтра or any date) and everyone who works then,
/// each with four buttons: 0 ч, 4 ч, 8 ч, 12 ч. No mark until one is pressed.
struct HomeView: View {
    enum More: Hashable { case timesheet, employees, archive }

    struct Row: Identifiable {
        let employee: Employee
        let hours: Int?
        var id: UUID { employee.id }
    }

    @Environment(\.modelContext) private var context
    @Environment(\.scenePhase) private var scenePhase
    @State private var day = Calendar.farm.dayStart(AppClock.now())
    @State private var rows: [Row] = []
    @State private var search = ""
    @State private var more: More?
    @State private var addingEmployee = false
    @State private var message: String?

    private var calendar: Calendar { .farm }
    private var today: Date { calendar.dayStart(AppClock.now()) }
    private var service: HoursService { HoursService(context: context) }

    private var shown: [Row] {
        let q = search.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return rows }
        return rows.filter { $0.employee.fullName.range(of: q, options: .caseInsensitive, locale: Locale(identifier: "ru_RU")) != nil }
    }

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
                    if rows.count > 5 {
                        TextField("Поиск по фамилии", text: $search)
                            .padding(12)
                            .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
                ForEach(shown) { row in
                    EmployeeHoursRow(row: row) { tap($0, for: row.employee) }
                        .editable(.hoursMark)
                }
                Button {
                    addingEmployee = true
                } label: {
                    Label("Добавить сотрудника", systemImage: "plus")
                }
                .buttonStyle(BigButtonStyle(color: Theme.primary))
                .accessibilityLabel("Добавить сотрудника")
                .padding(.top, 4)
                .onlyIfEditable(.employee)
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.background)
        .navigationTitle("Табель работников")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { more = .timesheet } label: { Label("Табель за месяц", systemImage: "tablecells") }
                    Button { more = .employees } label: { Label("Сотрудники и история", systemImage: "person.2") }
                    Button { more = .archive } label: { Label("Старые отметки (архив)", systemImage: "archivebox") }
                } label: {
                    Label("Ещё", systemImage: "ellipsis.circle")
                }
                .accessibilityLabel("Ещё")
            }
        }
        .navigationDestination(item: $more) { m in
            switch m {
            case .timesheet: MonthTimesheetView()
            case .employees: EmployeesView()
            case .archive: OldMarksView()
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
        let marked = rows.filter { $0.hours != nil }
        let hours = marked.reduce(0) { $0 + ($1.hours ?? 0) }
        let unmarked = rows.count - marked.count
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Отмечено: \(marked.count) из \(rows.count)").font(.headline)
                Spacer()
                Text("Часов: \(hours)").font(.headline.monospacedDigit()).foregroundStyle(Theme.primary)
            }
            if unmarked > 0 {
                Button {
                    markAll()
                } label: {
                    Label("Всем неотмеченным — 8 ч", systemImage: "checkmark.circle")
                        .frame(maxWidth: .infinity, minHeight: 40)
                }
                .buttonStyle(.bordered)
                .tint(Theme.primary)
                .onlyIfEditable(.hoursMark)
            }
        }
        .card()
    }

    // MARK: Actions

    private func reload() {
        let people = EmployeeService(context: context).active(on: day)
        let s = service
        rows = people.map { Row(employee: $0, hours: s.hours(of: $0, on: day)) }
    }

    private func tap(_ hours: Int, for e: Employee) {
        do { try service.tap(hours, for: e, on: day) } catch { message = error.localizedDescription }
        reload()
    }

    private func markAll() {
        do { try service.markUnmarked(rows.map(\.employee), hours: 8, on: day) } catch { message = error.localizedDescription }
        reload()
    }
}

/// One person: photo, ФИО, the day's hours and the four buttons. Tapping the selected button again removes the mark.
struct EmployeeHoursRow: View {
    let row: HomeView.Row
    let onTap: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                EmployeeAvatar(employee: row.employee, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.employee.fullName).font(.headline).lineLimit(2)
                    Text(row.hours.map { "Отмечено: \($0) ч" } ?? "Не отмечен")
                        .font(.subheadline)
                        .foregroundStyle(row.hours == nil ? Color.secondary : HoursStyle.color(row.hours ?? 0))
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                ForEach(WorkHours.options, id: \.self) { h in
                    let selected = row.hours == h
                    Button { onTap(h) } label: {
                        Text(HoursStyle.title(h))
                            .font(.headline.monospacedDigit())
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .foregroundStyle(selected ? .white : HoursStyle.color(h))
                            .background(selected ? HoursStyle.color(h) : Theme.background,
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(HoursStyle.color(h).opacity(selected ? 0 : 0.5), lineWidth: 1.5))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(h) ч \(row.employee.lastName)")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                    .accessibilityIdentifier("hours-\(h)-\(row.employee.lastName)")
                }
            }
        }
        .card()
    }
}

/// «Старые отметки (архив)»: the status codes kept from before the timesheet of hours, read only.
struct OldMarksView: View {
    @Environment(\.modelContext) private var context
    @State private var month = Calendar.farm.component(.month, from: AppClock.now())
    @State private var year = Calendar.farm.component(.year, from: AppClock.now())
    @State private var pickingMonth = false
    @State private var lines: [(name: String, days: String)] = []
    @State private var total = 0

    var body: some View {
        List {
            Section {
                Button { pickingMonth = true } label: {
                    LabeledContent("Месяц", value: Calendar.farm.monthTitle(month: month, year: year))
                }
                Text("Здесь видны отметки старого формата (Я, В, Б…). Они сохранены как история и в табель часов не входят.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Отметки (\(total))") {
                if lines.isEmpty { Text("В этом месяце старых отметок нет").foregroundStyle(.secondary) }
                ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(l.name).font(.headline)
                        Text(l.days).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Старые отметки")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: reload)
        .onChange(of: month) { _, _ in reload() }
        .onChange(of: year) { _, _ in reload() }
        .sheet(isPresented: $pickingMonth) {
            MonthYearPicker(month: month, year: year) { m, y in month = m; year = y }
                .presentationDetents([.medium])
        }
    }

    private func reload() {
        let calendar = Calendar.farm
        let start = calendar.date(day: 1, month: month, year: year)
        let end = calendar.date(byAdding: .month, value: 1, to: start) ?? start
        let records = (try? context.fetch(FetchDescriptor<AttendanceRecord>(
            predicate: #Predicate { $0.day >= start && $0.day < end }, sortBy: [SortDescriptor(\.day)]))) ?? []
        let employees = (try? context.fetch(FetchDescriptor<Employee>())) ?? []
        let names = Dictionary(employees.map { ($0.id, $0.fullName) }, uniquingKeysWith: { a, _ in a })
        let grouped = Dictionary(grouping: records, by: \.employeeId)
        let built: [(name: String, days: String)] = grouped.map { id, rs in
            (name: names[id] ?? "Сотрудник удалён",
             days: rs.map { "\(calendar.component(.day, from: $0.day)) — \($0.status.rawValue)" }.joined(separator: ", "))
        }
        lines = built.sorted { $0.name < $1.name }
        total = records.count
    }
}
