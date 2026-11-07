import SwiftUI

/// The sync in one line on the main screen («Все данные синхронизированы», «Нет связи. Ждут отправки: N»,
/// «Ошибка синхронизации», «Конфликты: N»); a tap syncs now.
struct SyncStatusLine: View {
    @Environment(SessionStore.self) private var session

    var body: some View {
        let status = session.syncStatus
        let problem = SyncStatusText.isProblem(status)
        Button {
            Task { await session.syncNow() }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon(status))
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(problem ? Theme.accent : Theme.primary)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(SyncStatusText.line(status, role: session.role ?? ""))
                        .font(.headline)
                        .foregroundStyle(problem ? Theme.accent : Color.primary)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(SyncStatusText.action)
                        .font(.subheadline)
                        .foregroundStyle(Theme.primary)
                }
                Spacer(minLength: 0)
                if status.syncing { ProgressView() }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(status.syncing)
        .accessibilityElement(children: .combine)
        .accessibilityHint(SyncStatusText.action)
        .accessibilityIdentifier("syncStatus")
    }

    private func icon(_ s: SyncStatus) -> String {
        if s.syncing { return "arrow.triangle.2.circlepath" }
        if !s.online { return "icloud.slash" }
        if SyncStatusText.isProblem(s) { return "exclamationmark.triangle.fill" }
        if s.pending > 0 { return "icloud.and.arrow.up" }
        return "checkmark.icloud"
    }
}
