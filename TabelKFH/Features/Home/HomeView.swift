import SwiftUI
import SwiftData
import UIKit

/// «Табель работников»: everyone who works today with the «Пришёл» / «Ушёл» button right in the list;
/// employees, the calendar and the monthly timesheet are in «Ещё».
struct HomeView: View {
    enum More: Hashable { case employees, calendar, timesheet }

    @Environment(\.modelContext) private var context
    @State private var model: HomeViewModel?
    @State private var more: More?

    var body: some View {
        Group {
            if let model {
                HomeContent(model: model)
            } else {
                Color.clear
            }
        }
        .navigationTitle("Табель работников")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { more = .employees } label: { Label("Сотрудники и история", systemImage: "person.2") }
                    Button { more = .calendar } label: { Label("Календарь", systemImage: "calendar") }
                    Button { more = .timesheet } label: { Label("Табель за месяц", systemImage: "tablecells") }
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
            case .timesheet: TimesheetView()
            }
        }
        .onAppear {
            if model == nil { model = HomeViewModel(context: context) }
            model?.reload()
        }
    }
}

private struct EditorTarget: Identifiable {
    let employee: Employee
    let day: Date
    var id: UUID { employee.id }
}

private struct HomeContent: View {
    @Bindable var model: HomeViewModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var editing: EditorTarget?
    @State private var addingEmployee = false

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                header
                if model.rows.isEmpty {
                    Text("Сегодня нет работающих сотрудников.")
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.vertical, 24)
                        .frame(maxWidth: .infinity)
                }
                ForEach(model.rows) { row in
                    EmployeeDayCard(
                        row: row,
                        isBusy: model.isBusy(row.employee),
                        onPrimary: { model.tapPrimary(row.employee) },
                        onEdit: { editing = EditorTarget(employee: row.employee, day: row.editorDay) },
                        onEditStale: {
                            if let day = row.staleOpenDay {
                                editing = EditorTarget(employee: row.employee, day: day)
                            }
                        })
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
        .refreshable { model.reload() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.reload() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
            model.reload()
        }
        .confirmationDialog(model.confirmation?.message ?? "",
                            isPresented: confirmationShown,
                            titleVisibility: .visible,
                            presenting: model.confirmation) { c in
            Button(c.confirmTitle) { model.confirm(c) }
            Button("Отмена", role: .cancel) {}
        }
        .alert("Не получилось", isPresented: errorShown) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
        .sheet(isPresented: $addingEmployee, onDismiss: { model.reload() }) {
            NavigationStack { EmployeeFormView(employee: nil) }
        }
        .sheet(item: $editing, onDismiss: { model.reload() }) { target in
            NavigationStack {
                RecordEditorView(employee: target.employee, day: target.day)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Отмена") { editing = nil }
                        }
                    }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.farmName)
                .font(.title2.bold())
                .foregroundStyle(Theme.primary)
            Text(model.dateTitle)
                .font(.headline)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 16) { counters }
                VStack(alignment: .leading, spacing: 4) { counters }
            }
            .font(.subheadline.weight(.medium))
        }
        .card()
    }

    @ViewBuilder
    private var counters: some View {
        Text(model.employeesTitle).foregroundStyle(.primary)
        Text(model.atWorkTitle).foregroundStyle(Theme.primary)
        Text(model.absentTitle).foregroundStyle(Theme.accent)
    }

    private var confirmationShown: Binding<Bool> {
        Binding(get: { model.confirmation != nil },
                set: { if !$0 { model.confirmation = nil } })
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { model.errorMessage != nil },
                set: { if !$0 { model.errorMessage = nil } })
    }
}
