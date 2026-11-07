import SwiftUI

/// «Конфликты» (owner): the same thing was changed on two devices; for each, both versions and the choice
/// «Взять с сервера» / «Взять с устройства» (`POST /conflicts/:id/resolve`; the device's version may come from a
/// helper's phone, not this one). Nothing is lost until then.
struct ConflictsView: View {
    @Environment(SessionStore.self) private var session
    @State private var conflicts: [ConflictDTO] = []
    @State private var loading = true
    @State private var problem: String?
    @State private var busy = false
    @State private var note: String?

    var body: some View {
        List {
            Section {
                if loading && conflicts.isEmpty {
                    ProgressView()
                } else if let problem {
                    Text(problem).foregroundStyle(.secondary)
                } else if conflicts.isEmpty {
                    Text("Конфликтов нет.").foregroundStyle(.secondary)
                }
                ForEach(conflicts) { c in
                    VStack(alignment: .leading, spacing: 10) {
                        Text("\(RefusedChanges.title(entityName: c.entity)) · \(OwnerText.field(c))").font(.headline)
                        versionRow("На сервере", OwnerText.value(c.serverValue))
                        versionRow("С устройства", OwnerText.value(c.clientValue))
                        HStack {
                            Button("Взять с сервера") { resolve(c, "server") }
                                .buttonStyle(.bordered)
                            Spacer()
                            Button("Взять с устройства") { resolve(c, "client") }
                                .buttonStyle(.borderedProminent)
                                .tint(Theme.primary)
                        }
                        .disabled(busy)
                    }
                    .padding(.vertical, 4)
                }
            } footer: {
                Text("Одно и то же изменили на двух устройствах. Ничего не потеряно: обе версии сохранены, пока вы не выберете.")
            }
        }
        .navigationTitle("Конфликты")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
        .alert(note ?? "", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func versionRow(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body)
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            let list: ConflictList = try await session.send("GET", "/conflicts")
            conflicts = list.conflicts
            problem = nil
        } catch {
            problem = "Список не загружен: \(error.localizedDescription)"
        }
    }

    private func resolve(_ c: ConflictDTO, _ choice: String) {
        busy = true
        Task {
            defer { busy = false }
            do {
                let _: OKAnswer = try await session.send("POST", "/conflicts/\(c.id)/resolve", body: ResolveBody(choice: choice))
                // The chosen version comes to this phone with the sync; the count on the main screen too.
                await session.syncNow()
            } catch {
                note = error.localizedDescription
            }
            await load()
        }
    }

    private struct ConflictList: Decodable {
        let conflicts: [ConflictDTO]
    }
}
