import SwiftUI
import SwiftData

/// One employee's work history by month: each day of employment with status, times, hours and comment.
/// Tapping a day opens the record editor.
struct EmployeeHistoryView: View {
    let employee: Employee

    @Environment(\.modelContext) private var context
    @State private var month: Int
    @State private var year: Int
    @State private var records: [Date: AttendanceRecord] = [:]
    @State private var editingProfile = false

    private let calendar = Calendar.farm

    init(employee: Employee) {
        self.employee = employee
        let now = Calendar.farm.dateComponents([.month, .year], from: AppClock.now())
        _month = State(initialValue: now.month ?? 1)
        _year = State(initialValue: now.year ?? 2026)
    }

    var body: some View {
        List {
            Section {
                profile
            }

            Section {
                monthSwitcher
                LabeledContent("Дней явки", value: "\(presentDays)")
                LabeledContent("Отработано", value: durationText(minutes: workedMinutes))
            }

            Section {
                if days.isEmpty {
                    Text("В этом месяце сотрудник не работал в хозяйстве")
                        .foregroundStyle(.secondary)
                }
                ForEach(days, id: \.self) { day in
                    NavigationLink {
                        RecordEditorView(employee: employee, day: day)
                    } label: {
                        HistoryDayRow(day: day, record: records[day])
                    }
                }
            } header: {
                Text("Дни")
            }
        }
        .navigationTitle(employee.fullName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Изменить") { editingProfile = true }
            }
        }
        .sheet(isPresented: $editingProfile, onDismiss: reload) {
            NavigationStack { EmployeeFormView(employee: employee) }
        }
        .onAppear(perform: reload)
        .onChange(of: month) { _, _ in reload() }
        .onChange(of: year) { _, _ in reload() }
    }

    private var profile: some View {
        HStack(spacing: 12) {
            EmployeeAvatar(employee: employee, size: 56)
            VStack(alignment: .leading, spacing: 4) {
                Text(employee.fullName).font(.headline)
                if !employee.position.isEmpty {
                    Text(employee.position).foregroundStyle(.secondary)
                }
                Text(employmentText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("График \(employee.workSchedule.title) · перерыв \(employee.defaultBreakMinutes) мин")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if employee.isArchived {
                    Text("В архиве")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                }
            }
        }
    }

    private var employmentText: String {
        var text = "Принят \(EmployeeFormatting.date(employee.hireDate))"
        if let dismissal = employee.dismissalDate {
            text += ", уволен \(EmployeeFormatting.date(dismissal))"
        }
        return text
    }

    private var monthSwitcher: some View {
        HStack {
            Button { shiftMonth(-1) } label: {
                Image(systemName: "chevron.left").frame(width: 44, height: 44)
            }
            .accessibilityLabel("Предыдущий месяц")
            Spacer()
            Text(calendar.monthTitle(month: month, year: year))
                .font(.headline)
            Spacer()
            Button { shiftMonth(1) } label: {
                Image(systemName: "chevron.right").frame(width: 44, height: 44)
            }
            .accessibilityLabel("Следующий месяц")
        }
        .buttonStyle(.borderless)
    }

    /// Days of the shown month within employment, newest first.
    private var days: [Date] {
        let hire = calendar.dayStart(employee.hireDate)
        let dismissal = employee.dismissalDate.map { calendar.dayStart($0) }
        return (1...calendar.daysInMonth(month: month, year: year)).reversed()
            .map { calendar.date(day: $0, month: month, year: year) }
            .filter { day in day >= hire && (dismissal.map { day <= $0 } ?? true) }
    }

    private var presentDays: Int {
        records.values.filter { $0.status == .present }.count
    }

    private var workedMinutes: Int {
        records.values.filter { $0.status == .present }.map { WorkTimeCalculator.effectiveMinutes($0) }.reduce(0, +)
    }

    private func shiftMonth(_ delta: Int) {
        let first = calendar.date(day: 1, month: month, year: year)
        guard let moved = calendar.date(byAdding: .month, value: delta, to: first) else { return }
        let parts = calendar.dateComponents([.month, .year], from: moved)
        month = parts.month ?? month
        year = parts.year ?? year
    }

    private func reload() {
        let id = employee.id
        let start = calendar.date(day: 1, month: month, year: year)
        let end = calendar.date(byAdding: .month, value: 1, to: start) ?? start
        let descriptor = FetchDescriptor<AttendanceRecord>(
            predicate: #Predicate { $0.employeeId == id && $0.day >= start && $0.day < end })
        let fetched = (try? context.fetch(descriptor)) ?? []
        records = Dictionary(fetched.map { (calendar.dayStart($0.day), $0) }, uniquingKeysWith: { a, _ in a })
    }
}

private struct HistoryDayRow: View {
    let day: Date
    let record: AttendanceRecord?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(Self.dayFormatter.string(from: day))
                .font(.subheadline.monospacedDigit())
                .frame(width: 56, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                if let record {
                    Text("\(record.status.rawValue) — \(record.status.title)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(record.status == .present ? Theme.primary : Theme.accent)
                    if record.status == .present {
                        Text(timesText(record))
                            .font(.subheadline.monospacedDigit())
                    }
                    if !record.comment.isEmpty {
                        Text(record.comment)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text("Нет отметки")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }

    /// «08:00–17:00 · 8 ч», «22:00–? · не закрыт», «7 ч 30 мин» (manual total without times).
    private func timesText(_ r: AttendanceRecord) -> String {
        let hasTotal = r.manualWorkedMinutes != nil || r.workedMinutes != nil
        let hours = hasTotal ? durationText(minutes: WorkTimeCalculator.effectiveMinutes(r)) : "не закрыт"
        guard let arrival = r.arrivalTime else {
            return hasTotal ? hours : "Время не указано"
        }
        let departure = r.departureTime.map { HomeViewModel.time($0) } ?? "?"
        return "\(HomeViewModel.time(arrival))–\(departure) · \(hours)"
    }

    /// «6, пт».
    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar.farm
        f.locale = Locale(identifier: "ru_RU")
        f.timeZone = Calendar.farm.timeZone
        f.dateFormat = "d, EE"
        return f
    }()
}
