import SwiftUI
import SwiftData
import Observation
import UniformTypeIdentifiers

/// The state of «Резервная копия»: save all the data to a file in «Файлы», or replace the data with a saved copy
/// after showing what the copy holds. The settings form owns it; the buttons only set flags, and the file dialogs,
/// the confirmation and the messages are attached to the form (`backupPresenters`), not to the buttons in its rows.
@MainActor
@Observable
final class BackupFlow {
    struct PendingRestore: Identifiable {
        let id = UUID()
        let data: Data
        let summary: String
    }

    var backup: ExportedFile?
    var backupName = ""
    var isExporting = false
    var isImporting = false
    var pending: PendingRestore?
    /// Shown once the confirmation sheet has closed.
    var restoreResult: String?
    var message: String?

    func makeBackup(context: ModelContext) {
        do {
            let data = try BackupService(context: context).makeBackup()
            backup = ExportedFile(data: data, contentType: .json)
            backupName = (BackupService.fileName(on: AppClock.now()) as NSString).deletingPathExtension
            isExporting = true
        } catch {
            message = "Не удалось создать копию: \(error.localizedDescription)"
        }
    }

    func exported(_ result: Result<URL, Error>) {
        switch result {
        case .success:
            message = "Резервная копия сохранена"
        case .failure(let error):
            if !error.isCancellation { message = "Не удалось сохранить копию: \(error.localizedDescription)" }
        }
    }

    func imported(_ result: Result<URL, Error>, context: ModelContext) {
        switch result {
        case .success(let url): prepareRestore(from: url, context: context)
        case .failure(let error):
            if !error.isCancellation { message = "Не удалось открыть файл: \(error.localizedDescription)" }
        }
    }

    /// Reads the chosen file and asks before replacing anything.
    private func prepareRestore(from url: URL, context: ModelContext) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            message = "Не удалось прочитать файл: \(error.localizedDescription)"
            return
        }
        do {
            let s = try BackupService(context: context).summary(of: data)
            pending = PendingRestore(data: data, summary: BackupService.summaryText(employees: s.employees,
                                                                                     records: s.records,
                                                                                     coalSales: s.coalSales,
                                                                                     createdAt: s.createdAt))
        } catch {
            message = error.localizedDescription
        }
    }

    func restore(_ data: Data, context: ModelContext, onRestore: () -> Void) {
        do {
            try BackupService(context: context).restore(data)
            restoreResult = "Данные восстановлены из копии"
            onRestore()
        } catch {
            restoreResult = "Данные не изменены. \(error.localizedDescription)"
        }
        pending = nil
    }

    func confirmationClosed() {
        if let restoreResult {
            message = restoreResult
            self.restoreResult = nil
        }
    }
}

/// The «Резервная копия» section of the settings form: a copy of the phone's data to a file. Restoring a file is
/// not offered: with the sync on, it would overwrite the farm's data on the server (the owner restores a server
/// copy instead, «Копии на сервере»).
struct BackupView: View {
    let flow: BackupFlow

    @Environment(\.modelContext) private var context

    var body: some View {
        Section {
            Button("Сохранить копию в файл") { flow.makeBackup(context: context) }
        } header: {
            Text("Копия на телефоне")
        } footer: {
            Text("Файл со всеми данными, которые есть на этом телефоне (сотрудники, отметки, уголь, скот). Храните его в «Файлах». Синхронизация — не замена копии.")
        }
    }
}

extension View {
    /// The file dialogs, the restore confirmation and the messages of `flow`, attached to the whole form.
    func backupPresenters(_ flow: BackupFlow, onRestore: @escaping () -> Void) -> some View {
        modifier(BackupPresenters(flow: flow, onRestore: onRestore))
    }
}

private struct BackupPresenters: ViewModifier {
    @Bindable var flow: BackupFlow
    let onRestore: () -> Void

    @Environment(\.modelContext) private var context

    func body(content: Content) -> some View {
        // The importer and the exporter sit on two different views: two file dialogs on one view can block
        // each other.
        VStack(spacing: 0) {
            content
                .fileImporter(isPresented: $flow.isImporting, allowedContentTypes: [.json, .data]) { result in
                    flow.imported(result, context: context)
                }
        }
        .fileExporter(isPresented: $flow.isExporting, document: flow.backup, contentType: .json,
                      defaultFilename: flow.backupName) { result in
            flow.exported(result)
        }
        .sheet(item: $flow.pending, onDismiss: { flow.confirmationClosed() }) { p in
            RestoreConfirmation(summary: p.summary) {
                flow.restore(p.data, context: context, onRestore: onRestore)
            }
            .presentationDetents([.medium])
        }
        .alert(flow.message ?? "", isPresented: Binding(get: { flow.message != nil },
                                                         set: { if !$0 { flow.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }
}

/// What the copy holds and the destructive «Заменить данные».
private struct RestoreConfirmation: View {
    let summary: String
    let onReplace: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Восстановление из копии", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(Theme.accent)
            Text(summary)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button("Заменить данные", role: .destructive, action: onReplace)
                .buttonStyle(BigButtonStyle(color: .red))
            Button("Отмена") { dismiss() }
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .padding(20)
    }
}

extension Error {
    /// The person closed the file picker.
    var isCancellation: Bool {
        (self as? CocoaError)?.code == .userCancelled
    }
}
