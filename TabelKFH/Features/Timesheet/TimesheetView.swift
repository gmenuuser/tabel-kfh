import SwiftUI
import SwiftData

/// Табель: month, year, employee and format; «Сформировать табель» saves a new version; the chosen
/// version is shown as a table with the unfinished days above it.
struct TimesheetView: View {
    @Environment(\.modelContext) private var context
    @State private var model: TimesheetViewModel?

    var body: some View {
        Group {
            if let model {
                TimesheetContent(model: model)
            } else {
                Color.clear
            }
        }
        .navigationTitle("Табель")
        .onAppear {
            if model == nil { model = TimesheetViewModel(context: context) }
        }
    }
}

private struct TimesheetContent: View {
    @Bindable var model: TimesheetViewModel
    @State private var choices: [Employee] = []
    @State private var pickingMonth = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                monthHeader
                employeeMenu
                formatPicker
                Button("Сформировать табель") { model.generate() }
                    .buttonStyle(BigButtonStyle())
                if model.versions.isEmpty {
                    emptyState
                } else {
                    versionsList
                    if let document = model.document {
                        selectedVersion(document)
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .sheet(isPresented: $pickingMonth) {
            MonthYearPicker(month: model.month, year: model.year) { month, year in
                model.show(month: month, year: year)
            }
            .presentationDetents([.medium])
        }
        .alert("Ошибка", isPresented: Binding(get: { model.errorMessage != nil },
                                              set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
        .onAppear {
            model.reload()
            choices = model.employees()
        }
        .onChange(of: model.month) { _, _ in choices = model.employees() }
        .onChange(of: model.year) { _, _ in choices = model.employees() }
    }

    private var monthHeader: some View {
        HStack {
            Button { model.previous() } label: {
                Image(systemName: "chevron.left").font(.title3.weight(.semibold)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Предыдущий месяц")
            Spacer()
            Button { pickingMonth = true } label: {
                HStack(spacing: 6) {
                    Text(model.title).font(.title3.weight(.semibold))
                    Image(systemName: "chevron.down").font(.caption.weight(.semibold))
                }
                .foregroundStyle(.primary)
            }
            .accessibilityHint("Выбрать месяц и год")
            Spacer()
            Button { model.next() } label: {
                Image(systemName: "chevron.right").font(.title3.weight(.semibold)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Следующий месяц")
        }
        .tint(Theme.primary)
    }

    private var employeeMenu: some View {
        Menu {
            Button("Все сотрудники") { model.choose(nil) }
            ForEach(choices) { e in
                Button(e.isArchived ? "\(e.fullName) (архив)" : e.fullName) { model.choose(e) }
            }
        } label: {
            HStack {
                Image(systemName: model.employee == nil ? "person.2" : "person")
                Text(model.employee?.fullName ?? "Все сотрудники")
                    .lineLimit(1)
                Spacer()
                Image(systemName: "chevron.up.chevron.down").font(.caption)
            }
            .foregroundStyle(.primary)
            .card()
        }
    }

    private var formatPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Формат:").font(.subheadline).foregroundStyle(.secondary)
            Picker("Формат", selection: $model.format) {
                ForEach(CellFormat.allCases, id: \.self) { f in Text(f.title).tag(f) }
            }
            .pickerStyle(.segmented)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "tablecells").font(.largeTitle).foregroundStyle(.secondary)
            Text(model.emptyText)
                .font(.headline)
                .multilineTextAlignment(.center)
            Text("Нажмите «Сформировать табель», чтобы собрать его из отметок.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
    }

    private var versionsList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Версии").font(.headline)
            VStack(spacing: 0) {
                ForEach(model.versions) { s in
                    Button { model.select(s) } label: {
                        HStack {
                            Text(model.versionTitle(s)).foregroundStyle(.primary)
                            Spacer()
                            if s.id == model.selected?.id {
                                Image(systemName: "checkmark").foregroundStyle(Theme.primary)
                            }
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(s.id == model.selected?.id ? .isSelected : [])
                    if s.id != model.versions.last?.id { Divider() }
                }
            }
            .card()
        }
    }

    /// The table of the chosen version and the export actions under it.
    private func selectedVersion(_ document: TimesheetDocument) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if !document.unfinished.isEmpty {
                unfinishedWarning(document.unfinished)
            }
            TimesheetGridView(document: document)
            ExportActions(document: document)
        }
    }

    private func unfinishedWarning(_ days: [String]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Незавершённые дни — уход не отмечен, 0 часов", systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline.weight(.semibold))
            ForEach(Array(days.enumerated()), id: \.offset) { _, line in
                Text(line).font(.subheadline)
            }
        }
        .foregroundStyle(Theme.accent)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: Theme.cornerRadius,
                                                                       style: .continuous))
    }
}
