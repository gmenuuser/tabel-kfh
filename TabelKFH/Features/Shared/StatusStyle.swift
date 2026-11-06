import SwiftUI
import UIKit

/// Colours and words of the day statuses, the same on every screen.
extension DayStatus {
    /// Next to the name: «Был на работе», «Выходной», «Больничный»…
    var dayTitle: String { self == .present ? "Был на работе" : title }

    /// The cell background: Я light green, В light grey, Б light red, О light blue, ОТ light yellow,
    /// ПР orange, К light purple (darker shades in the dark theme).
    var fill: Color {
        switch self {
        case .present: return Self.adaptive(light: (0.80, 0.93, 0.80), dark: (0.17, 0.38, 0.22))
        case .dayOff: return Self.adaptive(light: (0.90, 0.90, 0.91), dark: (0.30, 0.30, 0.32))
        case .sick: return Self.adaptive(light: (0.98, 0.82, 0.82), dark: (0.45, 0.20, 0.20))
        case .vacation: return Self.adaptive(light: (0.80, 0.90, 0.99), dark: (0.17, 0.32, 0.48))
        case .timeOff: return Self.adaptive(light: (0.99, 0.95, 0.72), dark: (0.45, 0.40, 0.14))
        case .absent: return Self.adaptive(light: (0.99, 0.74, 0.47), dark: (0.60, 0.35, 0.10))
        case .businessTrip: return Self.adaptive(light: (0.89, 0.84, 0.98), dark: (0.34, 0.26, 0.50))
        }
    }

    private static func adaptive(light: (Double, Double, Double), dark: (Double, Double, Double)) -> Color {
        Color(uiColor: UIColor { traits in
            let c = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: c.0, green: c.1, blue: c.2, alpha: 1)
        })
    }
}

/// A rounded chip with the status of the day; «Не отмечен» when there is none.
struct StatusChip: View {
    let status: DayStatus?

    var body: some View {
        Text(status?.dayTitle ?? "Не отмечен")
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .foregroundStyle(status == nil ? Color.secondary : Color.primary)
            .background(status?.fill ?? Color(uiColor: .tertiarySystemFill), in: Capsule())
    }
}

/// The codes and their colours, under the monthly timesheet.
struct StatusLegend: View {
    var body: some View {
        let columns = [GridItem(.adaptive(minimum: 140), spacing: 8, alignment: .leading)]
        LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            ForEach(DayStatus.allCases, id: \.self) { s in
                HStack(spacing: 6) {
                    Text(s.rawValue)
                        .font(.subheadline.bold())
                        .frame(width: 32, height: 26)
                        .background(s.fill, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    Text(s.title).font(.subheadline)
                }
            }
        }
    }
}
