import SwiftUI

/// «Копии на сервере» (owner): the farm's copies on the server; «Создать копию», «Проверить» (the checksum) and
/// «Восстановить» (every device of the farm gets the copy's data) behind a warning.
struct ServerBackupsView: View {
    @Environment(SessionStore.self) private var session
    @State private var backups: [ServerBackupDTO] = []
    @State private var loading = true
    @State private var problem: String?
    @State private var busy = false
    @State private var note: String?
    @State private var restoring: ServerBackupDTO?

    var body: some View {
        List {
            Section {
                Button {
                    create()
                } label: {
                    Label("Создать копию", systemImage: "plus.circle")
                }
                .disabled(busy)
            } footer: {
                Text("Копия всех данных хозяйства хранится на сервере. Сервер и сам делает копии каждую ночь.")
            }

            Section("Копии") {
                if loading && backups.isEmpty {
                    ProgressView()
                } else if let problem {
                    Text(problem).foregroundStyle(.secondary)
                } else if backups.isEmpty {
                    Text("Копий пока нет.").foregroundStyle(.secondary)
                }
                ForEach(backups) { b in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(OwnerText.moment(b.createdAt)).font(.headline)
                        Text("Записей: \(b.records)").font(.subheadline).foregroundStyle(.secondary)
                        HStack {
                            Button("Проверить") { verify(b) }
                                .buttonStyle(.bordered)
                            Spacer()
                            Button("Восстановить", role: .destructive) { restoring = b }
                                .buttonStyle(.bordered)
                        }
                        .disabled(busy)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .navigationTitle("Копии на сервере")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
        .alert("Восстановить копию?", isPresented: Binding(get: { restoring != nil }, set: { if !$0 { restoring = nil } }),
               presenting: restoring) { b in
            Button("Восстановить", role: .destructive) { restore(b) }
            Button("Отмена", role: .cancel) {}
        } message: { b in
            Text("Данные хозяйства на сервере и на всех устройствах станут такими, как в копии от \(OwnerText.moment(b.createdAt)). Всё, что внесли после неё, будет удалено. Отменить это нельзя — при сомнении сначала создайте новую копию.")
        }
        .alert(note ?? "", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            backups = try await session.send("GET", "/backups")
            problem = nil
        } catch {
            problem = "Список не загружен: \(error.localizedDescription)"
        }
    }

    private func run(_ request: @escaping () async throws -> String) {
        busy = true
        Task {
            defer { busy = false }
            do {
                note = try await request()
            } catch {
                note = error.localizedDescription
            }
            await load()
        }
    }

    private func create() {
        run {
            let _: OKAnswer = try await session.send("POST", "/backups")
            return "Копия на сервере создана"
        }
    }

    private func verify(_ b: ServerBackupDTO) {
        run {
            let answer: OKAnswer = try await session.send("GET", "/backups/\(b.id)/verify")
            return answer.ok == true ? "Копия цела" : "Копия повреждена"
        }
    }

    private func restore(_ b: ServerBackupDTO) {
        run {
            let _: OKAnswer = try await session.send("POST", "/backups/\(b.id)/restore", body: ConfirmBody())
            // Every record came back as a new version: this phone takes them all again.
            await session.resyncFromStart()
            return "Данные восстановлены из копии"
        }
    }
}
