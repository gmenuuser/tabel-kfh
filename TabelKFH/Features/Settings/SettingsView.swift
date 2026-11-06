import SwiftUI
import SwiftData
import UIKit

/// Настройки: the farm details printed on timesheets (saved on change), backups, the app version.
struct SettingsView: View {
    /// Called after a restore replaced the data, so the other tabs read it again.
    var onRestore: () -> Void = {}

    @Environment(\.modelContext) private var context
    @State private var settings: FarmSettings?

    var body: some View {
        Group {
            if let settings {
                SettingsForm(settings: settings) {
                    self.settings = FarmSettingsStore.current(in: context)
                    onRestore()
                }
            } else {
                Color.clear
            }
        }
        .navigationTitle("Настройки")
        .onAppear {
            if settings == nil { settings = FarmSettingsStore.current(in: context) }
        }
    }
}

private struct SettingsForm: View {
    @Bindable var settings: FarmSettings
    let onRestore: () -> Void

    @Environment(\.modelContext) private var context
    @State private var backup = BackupFlow()

    var body: some View {
        Form {
            Section {
                field("Название КФХ", text: $settings.farmName)
                field("ФИО руководителя", text: $settings.managerName, content: .name)
                field("Ответственное лицо", text: $settings.responsiblePerson, content: .name)
                field("Адрес", text: $settings.address, content: .fullStreetAddress)
                field("Телефон", text: $settings.phone, content: .telephoneNumber, keyboard: .phonePad)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Дополнительная информация").font(.caption).foregroundStyle(.secondary)
                    TextField("Например, ИНН или ОГРН", text: $settings.extraInfo, axis: .vertical)
                        .lineLimit(2...5)
                }
            } header: {
                Text("Хозяйство")
            } footer: {
                Text("Эти данные печатаются в шапке и подписях табеля.")
            }

            BackupView(flow: backup)

            Section("О приложении") {
                LabeledContent("Табель КФХ", value: Self.versionText)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .keyboardDoneButton()
        .backupPresenters(backup, onRestore: onRestore)
        .onChange(of: settings.info) { _, _ in
            try? context.save()
        }
    }

    private func field(_ title: String, text: Binding<String>, content: UITextContentType? = nil,
                       keyboard: UIKeyboardType = .default) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            TextField(title, text: text)
                .textContentType(content)
                .keyboardType(keyboard)
        }
    }

    /// «Версия 1.0 (1)».
    private static var versionText: String {
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "Версия \(BackupService.appVersion) (\(build))"
    }
}
