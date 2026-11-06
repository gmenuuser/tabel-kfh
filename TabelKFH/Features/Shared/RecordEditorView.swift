import SwiftUI
import SwiftData

/// One employee's mark for one day: choose the status or «Снять отметку». No times, no hours.
/// A future day takes В, Б, О, ОТ, ПР, К. Used from the calendar and the employee's history;
/// dismisses itself after a choice.
struct RecordEditorView: View {
    let employee: Employee
    let day: Date

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var current: DayStatus?
    @State private var loaded = false
    @State private var errorMessage: String?

    init(employee: Employee, day: Date) {
        self.employee = employee
        self.day = Calendar.farm.dayStart(day)
    }

    private var options: [DayStatus] { DayStatus.statuses(on: day, today: AppClock.now()) }

    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    EmployeeAvatar(employee: employee, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(employee.fullName).font(.headline)
                        Text(CoalText.longDate(day).capitalizedFirst).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                StatusChip(status: current)
            }
            Section("Отметка дня") {
                ForEach(options, id: \.self) { s in
                    Button { choose(s) } label: {
                        HStack(spacing: 12) {
                            Text(s.rawValue)
                                .font(.headline)
                                .frame(width: 40, height: 32)
                                .background(s.fill, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                            Text(s.dayTitle).foregroundStyle(.primary)
                            Spacer()
                            if current == s { Image(systemName: "checkmark").foregroundStyle(Theme.primary) }
                        }
                        .frame(minHeight: 40)
                    }
                }
                if current != nil {
                    Button("Снять отметку", role: .destructive) { choose(nil) }
                }
            }
        }
        .navigationTitle("Отметка")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            current = AttendanceService(context: context).status(of: employee, on: day)
        }
        .alert("Не получилось", isPresented: Binding(get: { errorMessage != nil },
                                                     set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func choose(_ s: DayStatus?) {
        do {
            try AttendanceService(context: context).setStatus(s, for: employee, on: day)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
