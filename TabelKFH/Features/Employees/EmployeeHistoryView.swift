import SwiftUI
import SwiftData

/// One employee's work history by month: each day of employment with its hours (0, 4, 8, 12) or no mark.
/// Tapping a day opens the choice of hours.
struct EmployeeHistoryView: View {
    let employee: Employee

    @Environment(\.modelContext) private var context
    @State private var month: Int
    @State private var year: Int
    @State private var records: [Date: Int] = [:]
    @State private var editingProfile = false
    @State private var confirmArchive = false
    @State private var errorMessage: String?

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
                LabeledContent("Часов за месяц", value: "\(totals.hours)")
                LabeledContent("Рабочих дней", value: "\(totals.workedDays)")
            }

            Section {
                if days.isEmpty {
                    Text("В этом месяце сотрудник не работал в хозяйстве")
                        .foregroundStyle(.secondary)
                }
                ForEach(days, id: \.self) { day in
                    NavigationLink {
                        HoursPickerView(employee: employee, day: day)
                    } label: {
                        HistoryDayRow(day: day, hours: records[day])
                    }
                }
            } header: {
                Text("Дни")
            }

            Section {
                Group {
                    if employee.isArchived {
                        Button("Вернуть из архива") { run { try EmployeeService(context: context).restore(employee) } }
                    } else {
                        Button("В архив") { confirmArchive = true }
                            .foregroundStyle(Theme.accent)
                    }
                }
                .editable(.employee)
            } footer: {
                Text("Архив только скрывает сотрудника с Главной; отметки и табели остаются.")
            }
        }
        .navigationTitle(employee.fullName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Изменить") { editingProfile = true }
                    .onlyIfEditable(.employee)
            }
        }
        .sheet(isPresented: $editingProfile, onDismiss: reload) {
            NavigationStack { EmployeeFormView(employee: employee) }
        }
        .confirmationDialog("Убрать \(employee.fullName) в архив?", isPresented: $confirmArchive,
                            titleVisibility: .visible) {
            Button("В архив", role: .destructive) { run { try EmployeeService(context: context).archive(employee) } }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Сотрудник пропадёт с Главной. Вернуть его можно здесь или во вкладке «Архив».")
        }
        .alert("Не получилось", isPresented: errorShown) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
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

    /// Only days within employment count, as in the timesheet.
    private var totals: HoursTotals {
        var t = HoursTotals()
        for day in days { if let h = records[day] { t.count(h) } }
        return t
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })
    }

    private func run(_ action: () throws -> Void) {
        do {
            try action()
        } catch {
            errorMessage = error.localizedDescription
        }
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
        let descriptor = FetchDescriptor<WorkHoursMark>(
            predicate: #Predicate { $0.employeeId == id && $0.day >= start && $0.day < end })
        let fetched = (try? context.fetch(descriptor)) ?? []
        records = Dictionary(fetched.map { (calendar.dayStart($0.day), $0.hours) }, uniquingKeysWith: { a, _ in a })
    }
}

private struct HistoryDayRow: View {
    let day: Date
    let hours: Int?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(Self.dayFormatter.string(from: day))
                .font(.subheadline.monospacedDigit())
                .frame(width: 56, alignment: .leading)
            if let hours {
                Text(HoursStyle.title(hours))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(HoursStyle.color(hours))
            } else {
                Text("Нет отметки")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    /// «6, пт».
    private static var dayFormatter: DateFormatter {
        let f = DateFormatter()
        f.calendar = Calendar.farm
        f.locale = Locale(identifier: "ru_RU")
        f.timeZone = Calendar.farm.timeZone
        f.dateFormat = "d, EE"
        return f
    }
}

/// The hours of one day for one employee: four large buttons and «Снять отметку».
struct HoursPickerView: View {
    let employee: Employee
    let day: Date

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var current: Int?
    @State private var loaded = false
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(employee.fullName).font(.title3.bold())
            Text(CoalText.longDate(day).capitalizedFirst).foregroundStyle(.secondary)
            HStack(spacing: 10) {
                ForEach(WorkHours.options, id: \.self) { h in
                    Button(HoursStyle.title(h)) { choose(h) }
                        .editable(.hoursMark)
                        .font(.title3.bold())
                        .frame(maxWidth: .infinity, minHeight: 60)
                        .foregroundStyle(current == h ? .white : HoursStyle.color(h))
                        .background(current == h ? HoursStyle.color(h) : Theme.card,
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .buttonStyle(.plain)
                }
            }
            if current != nil {
                Button("Снять отметку", role: .destructive) { choose(nil) }
                    .onlyIfEditable(.hoursMark)
            }
            Spacer()
        }
        .padding(16)
        .navigationTitle("Часы за день")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            current = HoursService(context: context).hours(of: employee, on: day)
        }
        .alert("Не получилось", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(message ?? "") }
    }

    private func choose(_ h: Int?) {
        do {
            try HoursService(context: context).set(h, for: employee, on: day)
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }
}
