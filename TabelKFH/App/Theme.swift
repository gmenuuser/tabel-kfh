import SwiftUI
import UIKit

/// Colours and shared styles: dark green primary, orange accent, light-grey cards on a white background.
enum Theme {
    static let primary = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0x4F / 255, green: 0xA3 / 255, blue: 0x6F / 255, alpha: 1)
            : UIColor(red: 0x1F / 255, green: 0x5D / 255, blue: 0x3A / 255, alpha: 1)
    })
    static let accent = Color(red: 0xE8 / 255, green: 0x89 / 255, blue: 0x2B / 255)
    static let card = Color(uiColor: .secondarySystemBackground)
    static let background = Color(uiColor: .systemBackground)
    static let cornerRadius: CGFloat = 14
}

struct CardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
    }
}

extension View {
    /// Light-grey rounded card.
    func card() -> some View { modifier(CardStyle()) }
}

/// Large full-width button for one-handed use.
struct BigButtonStyle: ButtonStyle {
    var color: Color = Theme.primary
    /// An inactive button (the viewer's forms) looks inactive.
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white.opacity(isEnabled ? 1 : 0.7))
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(color.opacity(!isEnabled ? 0.45 : (configuration.isPressed ? 0.75 : 1)),
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
    }
}
