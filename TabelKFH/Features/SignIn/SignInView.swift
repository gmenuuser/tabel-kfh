import SwiftUI
import SwiftData

/// «Табель КФХ»: «Почта или логин», «Пароль», «Войти». The farm is created on the site; helpers get their login
/// from the owner. When the phone still has its own data, signing in first offers to save a copy to «Файлы»: the
/// sign-in replaces it with the farm's data from the server.
struct SignInView: View {
    @Environment(SessionStore.self) private var session
    @Environment(\.modelContext) private var context

    @State private var login = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var askBackup = false
    /// The copy question was answered on this screen.
    @State private var backupAnswered = false
    @State private var backup = BackupFlow()
    @FocusState private var focus: Field?

    private enum Field { case login, password }

    private var canSubmit: Bool {
        !busy && !login.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty
    }

    var body: some View {
        @Bindable var backup = backup
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Табель КФХ")
                    .font(.largeTitle.bold())
                    .foregroundStyle(Theme.primary)
                    .padding(.top, 40)
                if let notice = session.signOutNotice {
                    Label(notice, systemImage: "lock.fill")
                        .foregroundStyle(Theme.accent)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("signOutNotice")
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Почта или логин").font(.subheadline).foregroundStyle(.secondary)
                    TextField("Почта или логин", text: $login)
                        .textContentType(.username)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.next)
                        .focused($focus, equals: .login)
                        .onSubmit { focus = .password }
                        .padding(12)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .accessibilityIdentifier("signInLogin")
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Пароль").font(.subheadline).foregroundStyle(.secondary)
                    SecureField("Пароль", text: $password)
                        .textContentType(.password)
                        .submitLabel(.go)
                        .focused($focus, equals: .password)
                        .onSubmit(submit)
                        .padding(12)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .accessibilityIdentifier("signInPassword")
                }
                if let error {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
                VStack(spacing: 8) {
                    Button(action: submit) {
                        if busy { ProgressView().tint(.white) } else { Text("Войти") }
                    }
                    .buttonStyle(BigButtonStyle())
                    .disabled(!canSubmit)
                    .opacity(canSubmit || busy ? 1 : 0.5)
                    .accessibilityIdentifier("signInButton")
                    Text("Логин выдаёт владелец хозяйства")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 20)
        }
        .background(Theme.background)
        .scrollDismissesKeyboard(.interactively)
        .alert("Сохранить копию данных?", isPresented: $askBackup) {
            Button("Сохранить копию") {
                backupAnswered = true
                Task {
                    // The file dialog opens once the alert has gone.
                    try? await Task.sleep(nanoseconds: 400_000_000)
                    backup.makeBackup(context: context)
                    if let message = backup.message {
                        error = message
                        backup.message = nil
                        backupAnswered = false
                    }
                }
            }
            Button("Не нужно", role: .destructive) {
                backupAnswered = true
                signIn()
            }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("На телефоне есть свои данные. После входа они будут заменены данными хозяйства с сервера. Можно сохранить копию в «Файлы».")
        }
        .fileExporter(isPresented: $backup.isExporting, document: backup.backup, contentType: .json,
                      defaultFilename: backup.backupName) { result in
            switch result {
            case .success:
                signIn()
            case .failure(let e):
                // Not saved: no sign-in yet, the question comes again with «Войти».
                backupAnswered = false
                if !e.isCancellation { error = "Не удалось сохранить копию: \(e.localizedDescription)" }
            }
        }
    }

    private func submit() {
        guard canSubmit else { return }
        error = nil
        if !backupAnswered && session.hasLocalData {
            askBackup = true
        } else {
            signIn()
        }
    }

    private func signIn() {
        busy = true
        focus = nil
        Task {
            do {
                try await session.signIn(login: login.trimmingCharacters(in: .whitespaces), password: password)
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }
}

/// The first download after signing in.
struct LoadingDataView: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView().controlSize(.large)
            Text("Загрузка данных…").font(.title3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
    }
}
