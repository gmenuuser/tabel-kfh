import SwiftUI

/// «Помощники» (owner): who else signs in to the farm, with their role; add a helper (login, password, role),
/// change the role, set a new password, switch a helper off. As the site's settings page.
struct HelpersView: View {
    @Environment(SessionStore.self) private var session
    @State private var members: [MemberDTO] = []
    @State private var loading = true
    @State private var problem: String?
    @State private var note: String?
    @State private var login = ""
    @State private var password = ""
    @State private var role = "timesheet"
    @State private var busy = false
    @State private var newPasswordFor: MemberDTO?
    @State private var newPassword = ""
    @State private var removing: MemberDTO?

    private var helpers: [MemberDTO] { members.filter { $0.role != "owner" } }

    var body: some View {
        Form {
            Section {
                if loading && members.isEmpty {
                    ProgressView()
                } else if let problem {
                    Text(problem).foregroundStyle(.secondary)
                } else if helpers.isEmpty {
                    Text("Помощников пока нет.").foregroundStyle(.secondary)
                }
                ForEach(helpers) { m in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(m.displayName).font(.headline)
                        Picker("Роль", selection: Binding(get: { m.role }, set: { changeRole(m, to: $0) })) {
                            ForEach(Roles.helperRoles, id: \.self) { Text(Roles.title($0)).tag($0) }
                        }
                        .disabled(busy)
                        HStack {
                            Button("Новый пароль") {
                                newPassword = ""
                                newPasswordFor = m
                            }
                            .buttonStyle(.bordered)
                            Spacer()
                            Button("Отключить", role: .destructive) { removing = m }
                                .buttonStyle(.bordered)
                        }
                        .disabled(busy)
                    }
                    .padding(.vertical, 4)
                }
            } header: {
                Text("Помощники")
            } footer: {
                Text("Помощник войдёт на iPhone или на сайте kfh.cubely.ru по логину и паролю. После смены роли или пароля ему нужно войти заново.")
            }

            Section {
                TextField("Логин", text: $login)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.username)
                SecureField("Пароль (не короче 8 символов)", text: $password)
                    .textContentType(.newPassword)
                Picker("Роль", selection: $role) {
                    ForEach(Roles.helperRoles, id: \.self) { Text(Roles.title($0)).tag($0) }
                }
                Button("Добавить помощника", action: add)
                    .disabled(busy || login.trimmingCharacters(in: .whitespaces).isEmpty || password.count < 8)
            } header: {
                Text("Новый помощник")
            } footer: {
                Text("«Только табель», «Только уголь», «Только скот» — помощник видит и меняет только свой раздел. «Только просмотр» — видит всё, ничего не меняет. «Управляющий» — всё, кроме этих настроек владельца.")
            }
        }
        .navigationTitle("Помощники")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
        .alert("Новый пароль", isPresented: Binding(get: { newPasswordFor != nil },
                                                    set: { if !$0 { newPasswordFor = nil } }),
               presenting: newPasswordFor) { m in
            SecureField("Не короче 8 символов", text: $newPassword)
            Button("Сохранить") { setPassword(m) }
            Button("Отмена", role: .cancel) {}
        } message: { m in
            Text("Для \(m.displayName). Его устройства выйдут из хозяйства, он войдёт с новым паролем.")
        }
        .alert("Отключить помощника?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
               presenting: removing) { m in
            Button("Отключить", role: .destructive) { remove(m) }
            Button("Отмена", role: .cancel) {}
        } message: { m in
            Text("\(m.displayName) больше не сможет войти в хозяйство, его устройства выйдут. Данные хозяйства остаются.")
        }
        .alert(note ?? "", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            members = try await session.send("GET", "/members")
            problem = nil
        } catch {
            problem = "Список не загружен: \(error.localizedDescription)"
        }
    }

    /// Runs a request, then the list again; the server's text when it fails.
    private func act(_ done: String, _ request: @escaping () async throws -> Void) {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await request()
                note = done
            } catch {
                note = error.localizedDescription
            }
            await load()
        }
    }

    private func add() {
        let body = NewMember(login: login.trimmingCharacters(in: .whitespaces), password: password, role: role)
        act("Помощник добавлен") {
            let _: OKAnswer = try await session.send("POST", "/members", body: body)
            login = ""
            password = ""
            role = "timesheet"
        }
    }

    private func changeRole(_ m: MemberDTO, to newRole: String) {
        guard newRole != m.role else { return }
        act("Роль изменена") {
            let _: OKAnswer = try await session.send("PATCH", "/members/\(m.id)", body: RolePatch(role: newRole))
        }
    }

    private func setPassword(_ m: MemberDTO) {
        let value = newPassword
        guard value.count >= 8 else {
            note = "Пароль — не короче 8 символов"
            return
        }
        act("Пароль изменён") {
            let _: OKAnswer = try await session.send("POST", "/members/\(m.id)/password", body: PasswordBody(password: value))
        }
    }

    private func remove(_ m: MemberDTO) {
        act("Помощник отключён") {
            let _: OKAnswer = try await session.send("DELETE", "/members/\(m.id)")
        }
    }
}
