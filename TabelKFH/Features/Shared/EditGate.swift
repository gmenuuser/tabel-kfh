import SwiftUI

/// The controls that change data follow the role (`SessionStore.canEdit`): inactive or hidden for a role that may
/// not change `entity` (the viewer, a helper in another part), so no screen can queue a change the server refuses.
private struct EditableModifier: ViewModifier {
    @Environment(SessionStore.self) private var session
    let entity: SyncEntity
    let hide: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if session.canEdit(entity) {
            content
        } else if !hide {
            content.disabled(true)
        }
    }
}

extension View {
    /// Inactive unless the role may change `entity`.
    func editable(_ entity: SyncEntity) -> some View {
        modifier(EditableModifier(entity: entity, hide: false))
    }

    /// Shown only when the role may change `entity`.
    func onlyIfEditable(_ entity: SyncEntity) -> some View {
        modifier(EditableModifier(entity: entity, hide: true))
    }
}

/// «Только просмотр»: a note on a screen the role reads but does not change.
struct ReadOnlyNote: View {
    var body: some View {
        Label("Только просмотр: изменять данные может владелец или помощник с нужной ролью.", systemImage: "eye")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}
