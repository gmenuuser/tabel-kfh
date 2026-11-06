import SwiftUI
import SwiftData

/// Edits one employee's mark for one day (any past day): status, times, break, manual total, comment.
/// Used from Home, Calendar and the employee's history. Dismisses itself after saving or deleting.
struct RecordEditorView: View {
    let employee: Employee
    let day: Date

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss

    @State private var loaded = false
    @State private var existing: AttendanceRecord?
    @State private var status: DayStatus = .present
    @State private var hasArrival = true
    @State private var arrivalClock: Date
    @State private var hasDeparture = true
    @State private var departureClock: Date
    @State private var departureNextDay = false
    @State private var breakMinutes: Int
    @State private var manualOn = false
    @State private var manualHours = 0
    @State private var manualMinutes = 0
    @State private var comment = ""
    @State private var errorMessage: String?
    @State private var confirmDelete = false

    private let calendar = Calendar.farm

    init(employee: Employee, day: Date) {
        self.employee = employee
        let start = Calendar.farm.dayStart(day)
        self.day = start
        _arrivalClock = State(initialValue: WorkTimeCalculator.clock(DateComponents(hour: 8, minute: 0), on: start))
        _departureClock = State(initialValue: WorkTimeCalculator.clock(DateComponents(hour: 17, minute: 0), on: start))
        _breakMinutes = State(initialValue: employee.defaultBreakMinutes)
    }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    EmployeeAvatar(employee: employee, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(employee.fullName).font(.headline)
                        Text(Self.dayFormatter.string(from: day))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Picker("Статус", selection: $status) {
                    ForEach(DayStatus.allCases, id: \.self) { s in
                        Text("\(s.rawValue) — \(s.title)").tag(s)
                    }
                }
            }

            Section {
                Toggle("Время прихода", isOn: $hasArrival)
                if hasArrival {
                    DatePicker("Приход", selection: arrivalBinding, displayedComponents: .hourAndMinute)
                    Toggle("Время ухода", isOn: $hasDeparture)
                    if hasDeparture {
                        DatePicker("Уход", selection: departureBinding, displayedComponents: .hourAndMinute)
                        Toggle("Уход на следующий день", isOn: $departureNextDay)
                    }
                }
                Stepper("Перерыв: \(breakMinutes) мин", value: $breakMinutes, in: 0...720, step: 5)
            } header: {
                Text("Время")
            } footer: {
                if status != .present {
                    Text("Время учитывается только для статуса «Явка».")
                }
            }
            .disabled(status != .present)

            if status == .present {
                Section {
                    Toggle("Исправить итог", isOn: $manualOn)
                    if manualOn {
                        Stepper("Часы: \(manualHours)", value: $manualHours, in: 0...24)
                        Stepper("Минуты: \(manualMinutes)", value: $manualMinutes, in: 0...59)
                    }
                    LabeledContent("Отработано", value: workedText)
                } header: {
                    Text("Итог")
                }
            }

            if !warnings.isEmpty {
                Section {
                    ForEach(warnings, id: \.text) { warning in
                        Label(warning.text, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(Theme.accent)
                    }
                }
            }

            Section {
                TextField("Комментарий", text: $comment, axis: .vertical)
                    .lineLimit(1...4)
            } header: {
                Text("Комментарий")
            }

            if existing != nil {
                Section {
                    Button("Удалить отметку", role: .destructive) { confirmDelete = true }
                }
            }
        }
        .navigationTitle("Отметка")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Сохранить", action: save).bold()
            }
        }
        .onAppear(perform: load)
        .confirmationDialog("Удалить отметку за \(Self.dayFormatter.string(from: day))?",
                            isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Удалить", role: .destructive, action: delete)
            Button("Отмена", role: .cancel) {}
        }
        .alert("Не получилось", isPresented: errorShown) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    // MARK: Resolved values

    private var arrival: Date? {
        guard status == .present, hasArrival else { return nil }
        return WorkTimeCalculator.clock(clockParts(arrivalClock), on: day)
    }

    private var departure: Date? {
        guard arrival != nil, hasDeparture else { return nil }
        let base = departureNextDay ? (calendar.date(byAdding: .day, value: 1, to: day) ?? day) : day
        return WorkTimeCalculator.clock(clockParts(departureClock), on: base)
    }

    private var warnings: [WorkWarning] {
        WorkTimeCalculator.warnings(status: status, arrival: arrival, departure: departure,
                                    breakMinutes: breakMinutes, now: Date())
    }

    private var workedText: String {
        if manualOn { return HomeViewModel.durationText(manualHours * 60 + manualMinutes) }
        guard let arrival, let departure else { return "—" }
        return HomeViewModel.durationText(
            WorkTimeCalculator.workedMinutes(arrival: arrival, departure: departure, breakMinutes: breakMinutes))
    }

    private func clockParts(_ date: Date) -> DateComponents {
        calendar.dateComponents([.hour, .minute], from: date)
    }

    private func minuteOfDay(_ date: Date) -> Int {
        let parts = clockParts(date)
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }

    // Time picker bindings: after the owner changes a time, «Уход на следующий день»
    // turns on by itself when the departure clock is earlier than the arrival clock.
    private var arrivalBinding: Binding<Date> {
        Binding(get: { arrivalClock }, set: { arrivalClock = $0; updateNextDay() })
    }

    private var departureBinding: Binding<Date> {
        Binding(get: { departureClock }, set: { departureClock = $0; updateNextDay() })
    }

    private func updateNextDay() {
        departureNextDay = minuteOfDay(departureClock) < minuteOfDay(arrivalClock)
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })
    }

    // MARK: Actions

    private func load() {
        guard !loaded else { return }
        let service = AttendanceService(context: context)
        if let r = service.record(for: employee, day: day) {
            existing = r
            status = r.status
            breakMinutes = r.breakMinutes
            comment = r.comment
            if r.status == .present {
                hasArrival = r.arrivalTime != nil
                hasDeparture = r.departureTime != nil
                if let a = r.arrivalTime { arrivalClock = a }
                if let d = r.departureTime {
                    departureClock = d
                    departureNextDay = calendar.dayStart(d) > day
                }
                if let manual = r.manualWorkedMinutes {
                    manualOn = true
                    manualHours = manual / 60
                    manualMinutes = manual % 60
                }
            }
        }
        loaded = true
    }

    private func save() {
        let service = AttendanceService(context: context)
        do {
            try service.save(employee: employee, day: day, status: status,
                             arrival: arrival, departure: departure, breakMinutes: breakMinutes,
                             manualMinutes: status == .present && manualOn ? manualHours * 60 + manualMinutes : nil,
                             comment: comment.trimmingCharacters(in: .whitespacesAndNewlines))
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func delete() {
        guard let existing else { return }
        do {
            try AttendanceService(context: context).delete(existing)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// «6 ноября 2026, пятница».
    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar.farm
        f.locale = Locale(identifier: "ru_RU")
        f.timeZone = Calendar.farm.timeZone
        f.dateFormat = "d MMMM y, EEEE"
        return f
    }()
}
