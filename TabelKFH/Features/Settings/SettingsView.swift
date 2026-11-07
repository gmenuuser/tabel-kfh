import SwiftUI
import SwiftData
import UIKit

/// Настройки: the farm details printed on timesheets, the sync (state, refused changes, conflicts), the owner's
/// helpers, devices and server backups, a copy to a file, «Выйти», the app version.
struct SettingsView: View {
    /// Called after a restore replaced the data, so the other tabs read it again.
    var onRestore: () -> Void = {}

    @Environment(\.modelContext) private var context
    /// The stored row. A role that may not change it gets a blank row not stored (`FarmSettingsStore`) until `/me`
    /// or the sync stores the farm's: the stored one is shown as soon as it exists.
    @Query private var rows: [FarmSettings]
    @State private var settings: FarmSettings?

    var body: some View {
        Group {
            if let settings = rows.first ?? settings {
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
    @Environment(SessionStore.self) private var session
    @Query(filter: #Predicate<SyncOp> { $0.state == "error" }, sort: \SyncOp.seq) private var refused: [SyncOp]
    @State private var backup = BackupFlow()
    @State private var farmName = ""
    @State private var managerName = ""
    @State private var farmLoaded = false
    @State private var savingFarm = false
    @State private var note: String?
    @State private var askingSignOut = false
    @State private var deleting: SyncOp?

    var body: some View {
        Form {
            farmSection
            syncSection
            if !refused.isEmpty { refusedSection }

            if Roles.modules(role: session.role ?? "").contains(.coal) {
                Section {
                    NavigationLink {
                        CoalPricesView()
                    } label: {
                        Label("Цена угля и населённые пункты", systemImage: "rublesign.circle")
                    }
                    NavigationLink {
                        DriversView()
                    } label: {
                        Label("Водители", systemImage: "person.2")
                    }
                } header: {
                    Text("Продажа угля")
                } footer: {
                    Text("Новые водители и населённые пункты сразу появляются кнопками в продаже угля.")
                }
            }

            if session.isOwner {
                Section {
                    NavigationLink { HelpersView() } label: { Label("Помощники", systemImage: "person.badge.key") }
                        .accessibilityIdentifier("openHelpers")
                    NavigationLink { DevicesView() } label: { Label("Устройства", systemImage: "iphone") }
                        .accessibilityIdentifier("openDevices")
                    NavigationLink { ServerBackupsView() } label: {
                        Label("Копии на сервере", systemImage: "externaldrive.badge.checkmark")
                    }
                    .accessibilityIdentifier("openServerBackups")
                } header: {
                    Text("Владелец")
                } footer: {
                    Text("Помощники входят по логину и паролю и видят только свой раздел.")
                }
            }

            BackupView(flow: backup)

            Section {
                Button("Выйти", role: .destructive) { askingSignOut = true }
                    .accessibilityIdentifier("signOut")
            } footer: {
                if let farm = session.farm, let role = session.role {
                    Text("Вы вошли в «\(farm.name)»: \(Roles.title(role).lowercased()). При выходе данные хозяйства удаляются с этого телефона; на сервере они остаются.")
                }
            }

            Section("О приложении") {
                LabeledContent("Табель КФХ", value: Self.versionText)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .keyboardDoneButton()
        .backupPresenters(backup, onRestore: onRestore)
        .onChange(of: settings.info) { _, _ in
            try? context.syncedSave()
        }
        .onAppear { loadFarm(force: false) }
        .onChange(of: session.farm) { _, _ in loadFarm(force: true) }
        .alert(signOutQuestion, isPresented: $askingSignOut) {
            Button("Выйти", role: .destructive) { Task { await session.signOut(force: false) } }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Данные хозяйства будут удалены с этого телефона. На сервере они сохранятся; войти можно снова.")
        }
        .alert("Удалить изменение?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
               presenting: deleting) { op in
            Button("Удалить", role: .destructive) { delete(op) }
            Button("Отмена", role: .cancel) {}
        } message: { _ in
            Text("Сервер его не принял. Изменение будет удалено с телефона, а данные с сервера загрузятся заново.")
        }
        .alert(note ?? "", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    // MARK: Farm

    private var farmSection: some View {
        Section {
            if session.isOwner {
                field("Название КФХ", text: $farmName)
                field("ФИО руководителя", text: $managerName, content: .name)
                Button("Сохранить название и руководителя", action: saveFarm)
                    .disabled(savingFarm || !farmChanged
                              || farmName.trimmingCharacters(in: .whitespaces).isEmpty)
            } else {
                LabeledContent("Название КФХ", value: settings.farmName)
                LabeledContent("Руководитель", value: settings.managerName.isEmpty ? "—" : settings.managerName)
            }
            Group {
                field("Ответственное лицо", text: $settings.responsiblePerson, content: .name)
                field("Адрес", text: $settings.address, content: .fullStreetAddress)
                field("Телефон", text: $settings.phone, content: .telephoneNumber, keyboard: .phonePad)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Дополнительная информация").font(.caption).foregroundStyle(.secondary)
                    TextField("Например, ИНН или ОГРН", text: $settings.extraInfo, axis: .vertical)
                        .lineLimit(2...5)
                }
            }
            .editable(.farmSettings)
        } header: {
            Text("Хозяйство")
        } footer: {
            Text(session.isOwner
                 ? "Эти данные печатаются в шапке и подписях табеля. Название и руководитель — общие для всех устройств хозяйства."
                 : "Эти данные печатаются в шапке и подписях табеля. Название и руководителя меняет владелец.")
        }
    }

    private var farmChanged: Bool {
        farmName.trimmingCharacters(in: .whitespaces) != (session.farm?.name ?? settings.farmName)
            || managerName.trimmingCharacters(in: .whitespaces) != (session.farm?.managerName ?? settings.managerName)
    }

    private func loadFarm(force: Bool) {
        guard force || !farmLoaded else { return }
        farmLoaded = true
        farmName = session.farm?.name ?? settings.farmName
        managerName = session.farm?.managerName ?? settings.managerName
    }

    /// `PATCH /farm` (owner only), then `/me` brings the names to this phone.
    private func saveFarm() {
        savingFarm = true
        let name = farmName.trimmingCharacters(in: .whitespaces)
        let manager = managerName.trimmingCharacters(in: .whitespaces)
        Task {
            defer { savingFarm = false }
            do {
                try await session.updateFarm(name: name, managerName: manager)
                note = "Сохранено"
            } catch {
                note = "Не сохранено: \(error.localizedDescription)"
            }
        }
    }

    // MARK: Sync

    private var syncSection: some View {
        let status = session.syncStatus
        let role = session.role ?? ""
        return Section {
            Label(SyncStatusText.line(status, role: role),
                  systemImage: SyncStatusText.isProblem(status) ? "exclamationmark.triangle.fill" : "checkmark.icloud")
                .foregroundStyle(SyncStatusText.isProblem(status) ? Theme.accent : Color.primary)
            if let error = status.lastError {
                Text(error).font(.footnote).foregroundStyle(.secondary)
            }
            if let last = status.lastSyncAt {
                LabeledContent("Последняя синхронизация", value: OwnerText.moment(last))
            }
            if status.conflicts > 0 {
                if session.isOwner {
                    NavigationLink { ConflictsView() } label: {
                        Label("Конфликты: \(status.conflicts) — выбрать версию", systemImage: "arrow.triangle.branch")
                    }
                } else {
                    Text("Конфликты: \(status.conflicts) — обратитесь к владельцу")
                        .foregroundStyle(Theme.accent)
                }
            }
            Button(SyncStatusText.action) { Task { await session.syncNow() } }
                .disabled(status.syncing)
        } header: {
            Text("Синхронизация")
        } footer: {
            Text("Изменения сохраняются на телефоне сразу и уходят на сервер, когда есть связь.")
        }
    }

    private var refusedSection: some View {
        Section {
            ForEach(refused) { op in
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(RefusedChanges.title(op)).font(.headline)
                        Text(op.errorText.isEmpty ? "Сервер не принял изменение" : op.errorText)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text(OwnerText.moment(op.createdAt)).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Удалить", role: .destructive) { deleting = op }
                        .buttonStyle(.borderless)
                }
            }
        } header: {
            Text("Не принятые изменения")
        } footer: {
            Text("Сервер не принял эти изменения (например, не хватает прав или данные неполные). Удалите изменение — данные с сервера загрузятся заново. Если непонятно, почему, обратитесь к владельцу.")
        }
    }

    private func delete(_ op: SyncOp) {
        Task {
            do { try await session.deleteRefused(op) } catch { note = "Не удалось удалить: \(error.localizedDescription)" }
        }
    }

    private var signOutQuestion: String {
        let n = session.pendingChanges, r = session.refusedChanges
        var parts: [String] = []
        if n > 0 {
            parts.append("\(n) \(BackupService.plural(n, "изменение", "изменения", "изменений")) ещё не "
                + BackupService.plural(n, "отправлено", "отправлены", "отправлены") + ".")
        }
        if r > 0 {
            parts.append("\(r) \(BackupService.plural(r, "изменение", "изменения", "изменений")) сервер не принял — "
                + "при выходе они удалятся.")
        }
        return parts.isEmpty ? "Выйти из хозяйства?" : (parts + ["Выйти всё равно?"]).joined(separator: " ")
    }

    // MARK: Parts

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
