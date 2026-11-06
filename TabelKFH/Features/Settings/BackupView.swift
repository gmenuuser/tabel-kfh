import SwiftUI
import SwiftData
import UniformTypeIdentifiers

/// «Резервная копия»: save all the data to a file in «Файлы», or replace the data with a saved copy
/// after showing what the copy holds.
struct BackupView: View {
    let onRestore: () -> Void

    @Environment(\.modelContext) private var context
    @State private var backup: ExportedFile?
    @State private var backupName = ""
    @State private var isExporting = false
    @State private var isImporting = false
    @State private var pending: PendingRestore?
    /// Shown once the confirmation sheet has closed.
    @State private var restoreResult: String?
    @State private var message: String?

    struct PendingRestore: Identifiable {
        let id = UUID()
        let data: Data
        let summary: String
    }

    var body: some View {
        Section {
            Button("Создать резервную копию", action: makeBackup)
                .fileExporter(isPresented: $isExporting, document: backup, contentType: .json,
                              defaultFilename: backupName) { result in
                    if case .failure(let error) = result, !error.isCancellation {
                        message = "Не удалось сохранить копию: \(error.localizedDescription)"
                    } else if case .success = result {
                        message = "Резервная копия сохранена"
                    }
                }
            Button("Восстановить из копии") { isImporting = true }
                .fileImporter(isPresented: $isImporting, allowedContentTypes: [.json, .data]) { result in
                    switch result {
                    case .success(let url): prepareRestore(from: url)
                    case .failure(let error):
                        if !error.isCancellation { message = "Не удалось открыть файл: \(error.localizedDescription)" }
                    }
                }
        } header: {
            Text("Резервная копия")
        } footer: {
            Text("Копия содержит сотрудников с архивом, отметки, версии табелей и настройки. Храните её в «Файлах».")
        }
        .sheet(item: $pending, onDismiss: {
            if let restoreResult {
                message = restoreResult
                self.restoreResult = nil
            }
        }) { p in
            RestoreConfirmation(summary: p.summary) {
                restore(p.data)
            }
            .presentationDetents([.medium])
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func makeBackup() {
        do {
            let data = try BackupService(context: context).makeBackup()
            backup = ExportedFile(data: data, contentType: .json)
            backupName = (BackupService.fileName(on: AppClock.now()) as NSString).deletingPathExtension
            isExporting = true
        } catch {
            message = "Не удалось создать копию: \(error.localizedDescription)"
        }
    }

    /// Reads the chosen file and asks before replacing anything.
    private func prepareRestore(from url: URL) {
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
                                                                                     createdAt: s.createdAt))
        } catch {
            message = error.localizedDescription
        }
    }

    private func restore(_ data: Data) {
        do {
            try BackupService(context: context).restore(data)
            restoreResult = "Данные восстановлены из копии"
            onRestore()
        } catch {
            restoreResult = "Данные не изменены. \(error.localizedDescription)"
        }
        pending = nil
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
