import SwiftUI
import UIKit

/// One employee's card on the Home screen: who, today's status and times, one big button.
struct EmployeeDayCard: View {
    let row: HomeViewModel.Row
    let isBusy: Bool
    let onPrimary: () -> Void
    let onEdit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                EmployeeAvatar(employee: row.employee, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.employee.fullName)
                        .font(.headline)
                        .fixedSize(horizontal: false, vertical: true)
                    if !row.employee.position.isEmpty {
                        Text(row.employee.position)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 8)
                Button(action: onEdit) {
                    Image(systemName: "square.and.pencil")
                        .font(.title3)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Изменить отметку")
            }

            Text(row.statusText)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(statusColor)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(statusColor.opacity(0.15), in: Capsule())

            HStack(alignment: .top) {
                info("Приход", row.arrivalText)
                info("Уход", row.departureText)
                info("Часы", row.hoursText)
            }

            primaryButton
        }
        .card()
    }

    @ViewBuilder
    private var primaryButton: some View {
        switch row.action {
        case .markArrival, .status:
            Button("Пришёл", action: onPrimary)
                .buttonStyle(BigButtonStyle(color: Theme.primary))
                .disabled(isBusy)
                .opacity(isBusy ? 0.5 : 1)
        case .markDeparture:
            Button("Ушёл", action: onPrimary)
                .buttonStyle(BigButtonStyle(color: Theme.accent))
                .disabled(isBusy)
                .opacity(isBusy ? 0.5 : 1)
        case .closed(let minutes):
            Button("\(HomeViewModel.durationText(minutes)) · Изменить", action: onEdit)
                .buttonStyle(BigButtonStyle(color: Color(uiColor: .systemGray)))
        }
    }

    private var statusColor: Color {
        switch row.action {
        case .markDeparture: return Theme.primary
        case .closed: return Theme.primary
        case .status: return Theme.accent
        case .markArrival: return .secondary
        }
    }

    private func info(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.body.monospacedDigit())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

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
