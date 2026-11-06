import SwiftUI
import UIKit

/// The employee's photo, or initials on a green circle.
struct EmployeeAvatar: View {
    let employee: Employee
    var size: CGFloat = 48

    var body: some View {
        Group {
            if let data = employee.photo, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Text(employee.initials)
                    .font(.system(size: size * 0.38, weight: .semibold))
                    .foregroundStyle(Theme.primary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Theme.primary.opacity(0.15))
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}
