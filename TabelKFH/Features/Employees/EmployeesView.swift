import SwiftUI
import SwiftData

/// Сотрудники: working and archived employees, search by ФИО, add, archive, restore.
struct EmployeesView: View {
    enum Segment: Hashable { case working, archive }

    @Environment(\.modelContext) private var context
    @Query private var employees: [Employee]
    @State private var segment: Segment = .working
    @State private var search = ""
    @State private var adding = false
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                Picker("Список", selection: $segment) {
                    Text("Работают").tag(Segment.working)
                    Text("Архив").tag(Segment.archive)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }

            if shown.isEmpty {
                Text(emptyText)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
                    .padding(.vertical, 24)
                    .listRowBackground(Color.clear)
            }

            ForEach(shown) { e in
                NavigationLink {
                    EmployeeHistoryView(employee: e)
                } label: {
                    EmployeeRow(employee: e)
                }
                .swipeActions(edge: .trailing) {
                    if e.isArchived {
                        Button("Вернуть") { run { try service.restore(e) } }
                            .tint(Theme.primary)
                    } else {
                        Button("В архив") { run { try service.archive(e) } }
                            .tint(Theme.accent)
                    }
                }
            }
        }
        .navigationTitle("Сотрудники")
        .searchable(text: $search, prompt: "Поиск по ФИО")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Добавить сотрудника")
            }
        }
        .sheet(isPresented: $adding) {
            NavigationStack { EmployeeFormView(employee: nil) }
        }
        .alert("Не получилось", isPresented: errorShown) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var service: EmployeeService { EmployeeService(context: context) }

    private var shown: [Employee] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let list = employees.filter { e in
            e.isArchived == (segment == .archive)
                && (query.isEmpty || e.fullName.localizedCaseInsensitiveContains(query))
        }
        return EmployeeService.sortedByName(list)
    }

    private var emptyText: String {
        if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "Никого не нашлось" }
        return segment == .archive
            ? "В архиве пусто"
            : "Сотрудников пока нет. Нажмите «+», чтобы добавить."
    }

    private func run(_ action: () throws -> Void) {
        do { try action() } catch { errorMessage = error.localizedDescription }
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })
    }
}

private struct EmployeeRow: View {
    let employee: Employee

    var body: some View {
        HStack(spacing: 12) {
            EmployeeAvatar(employee: employee, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(employee.fullName)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                if !employee.position.isEmpty {
                    Text(employee.position)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if let note = employmentNote {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(Theme.accent)
                }
            }
        }
        .padding(.vertical, 4)
    }

    /// «Принят с 10.11.2026» / «Уволен 05.11.2026» when not employed today.
    private var employmentNote: String? {
        let calendar = Calendar.farm
        let today = calendar.dayStart(AppClock.now())
        if calendar.dayStart(employee.hireDate) > today {
            return "Принят с \(EmployeeFormatting.date(employee.hireDate))"
        }
        if let dismissal = employee.dismissalDate, calendar.dayStart(dismissal) < today {
            return "Уволен \(EmployeeFormatting.date(dismissal))"
        }
        return nil
    }
}

enum EmployeeFormatting {
    /// «05.11.2026».
    static func date(_ d: Date) -> String { dateFormatter.string(from: d) }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar.farm
        f.locale = Locale(identifier: "ru_RU")
        f.timeZone = Calendar.farm.timeZone
        f.dateFormat = "dd.MM.yyyy"
        return f
    }()
}
