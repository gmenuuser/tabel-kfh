import SwiftUI
import UIKit

extension View {
    /// «Готово» above the keyboard, which hides it: the phone pad has no return key, and a long form
    /// otherwise keeps the keyboard over its lower fields.
    func keyboardDoneButton() -> some View {
        toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Готово") {
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                                    to: nil, from: nil, for: nil)
                }
                .bold()
            }
        }
    }
}
