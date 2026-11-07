import SwiftUI

/// «Устройства» (owner): every phone and browser signed in to the farm; «Отозвать доступ» for a lost one (its data
/// is wiped there at its next sync).
struct DevicesView: View {
    @Environment(SessionStore.self) private var session
    @State private var devices: [DeviceDTO] = []
    @State private var loading = true
    @State private var problem: String?
    @State private var note: String?
    @State private var revoking: DeviceDTO?

    var body: some View {
        List {
            Section {
                if loading && devices.isEmpty {
                    ProgressView()
                } else if let problem {
                    Text(problem).foregroundStyle(.secondary)
                }
                ForEach(devices) { d in
                    HStack(alignment: .center, spacing: 12) {
                        Image(systemName: d.platform == "ios" ? "iphone" : "globe")
                            .font(.title3)
                            .foregroundStyle(d.revoked ? Color.secondary : Theme.primary)
                            .frame(width: 28)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.current ? "\(d.title) (это устройство)" : d.title).font(.headline)
                            if !d.who.isEmpty { Text(d.who).font(.subheadline).foregroundStyle(.secondary) }
                            if d.revoked {
                                Text("Доступ отозван").font(.caption).foregroundStyle(Theme.accent)
                            } else if let seen = d.lastSeenAt {
                                Text("Был в сети: \(OwnerText.moment(seen))").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer(minLength: 0)
                        if !d.revoked && !d.current {
                            Button("Отозвать", role: .destructive) { revoking = d }
                                .buttonStyle(.bordered)
                        }
                    }
                    .padding(.vertical, 2)
                }
            } footer: {
                Text("Отзовите доступ потерянного телефона: он больше не получит данные хозяйства, а при следующей связи данные на нём удалятся.")
            }
        }
        .navigationTitle("Устройства")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
        .alert("Отозвать доступ этого устройства?", isPresented: Binding(get: { revoking != nil },
                                                                         set: { if !$0 { revoking = nil } }),
               presenting: revoking) { d in
            Button("Отозвать доступ", role: .destructive) { revoke(d) }
            Button("Отмена", role: .cancel) {}
        } message: { d in
            Text("\(d.title)\(d.who.isEmpty ? "" : " · \(d.who)") выйдет из хозяйства. Войти снова можно по логину и паролю.")
        }
        .alert(note ?? "", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            devices = try await session.send("GET", "/devices")
            problem = nil
        } catch {
            problem = "Список не загружен: \(error.localizedDescription)"
        }
    }

    private func revoke(_ d: DeviceDTO) {
        Task {
            do {
                let _: OKAnswer = try await session.send("POST", "/devices/\(d.id)/revoke")
                note = "Доступ отозван"
            } catch {
                note = error.localizedDescription
            }
            await load()
        }
    }
}
