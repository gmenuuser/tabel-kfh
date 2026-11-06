import SwiftUI
import SwiftData

/// Colours of the coal module: hired trucks blue, the farm's own truck green.
enum CoalTheme {
    static let hired = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.42, green: 0.64, blue: 1.0, alpha: 1)
            : UIColor(red: 0.10, green: 0.38, blue: 0.80, alpha: 1)
    })
    static let own = Theme.primary

    static func color(_ v: VehicleType) -> Color { v == .hired ? hired : own }
}

/// A small figure with its caption, for the tops of the screens.
struct CoalStatTile: View {
    let title: String
    let value: String
    var color: Color = .primary

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.headline.monospacedDigit())
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Theme.background.opacity(0.6), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

/// The figures of a set of sales: count, tons, revenue, hired and own deliveries.
struct CoalTotalsHeader: View {
    let totals: CoalTotals

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                CoalStatTile(title: "Продаж", value: "\(totals.salesCount)")
                CoalStatTile(title: "Продано, т", value: CoalMath.tons(totals.kilograms))
            }
            CoalStatTile(title: "Общая выручка", value: CoalMath.rubles(totals.revenue), color: Theme.primary)
            HStack(spacing: 8) {
                CoalStatTile(title: "Наёмными машинами", value: "\(totals.hiredDeliveries)", color: CoalTheme.hired)
                CoalStatTile(title: "Собственной машиной", value: "\(totals.ownDeliveries)", color: CoalTheme.own)
            }
        }
    }
}

/// One sale: time, tons, village, truck, driver, total; the colour strip tells the truck.
struct CoalSaleCard: View {
    let sale: CoalSale
    var showsDate = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RoundedRectangle(cornerRadius: 3)
                .fill(CoalTheme.color(sale.vehicleType))
                .frame(width: 6)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(showsDate ? CoalText.dateTime(sale.saleDate) : CoalText.time(sale.saleDate))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(CoalMath.rubles(sale.totalAmount))
                        .font(.headline.monospacedDigit())
                }
                Text("\(CoalMath.tonsText(sale.kilograms)) · \(sale.locationNameSnapshot)")
                    .font(.headline)
                HStack(spacing: 6) {
                    Image(systemName: sale.vehicleType == .hired ? "truck.box" : "house")
                    Text(sale.vehicleType == .hired
                         ? "Наёмная: \(sale.driverNameSnapshot)"
                         : "Собственная машина КФХ")
                }
                .font(.subheadline)
                .foregroundStyle(CoalTheme.color(sale.vehicleType))
                if sale.isPriceAdjusted {
                    Text("Цена изменена вручную, по прайсу \(CoalMath.rubles(sale.listTotal))")
                        .font(.caption)
                        .foregroundStyle(Theme.accent)
                }
                if !sale.comment.isEmpty {
                    Text(sale.comment).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .card()
        .accessibilityElement(children: .combine)
    }
}

/// A large round letter and the surname: «[М] Мунгалов», for one-handed choosing.
struct DriverChoiceButton: View {
    let driver: Driver
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(driver.letter)
                    .font(.title3.bold())
                    .frame(width: 40, height: 40)
                    .background(isSelected ? Color.white.opacity(0.25) : CoalTheme.hired.opacity(0.15),
                                in: Circle())
                Text(driver.lastName)
                    .font(.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, minHeight: 58)
            .foregroundStyle(isSelected ? .white : .primary)
            .background(isSelected ? CoalTheme.hired : Theme.card,
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(CoalTheme.hired.opacity(isSelected ? 0 : 0.35), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(driver.letter) \(driver.lastName)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// A rubles text field: digits with «,», the ₽ sign after it.
struct RublesField: View {
    let title: String
    @Binding var text: String

    var body: some View {
        HStack {
            TextField(title, text: $text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .font(.body.monospacedDigit())
            Text("₽").foregroundStyle(.secondary)
        }
    }
}

/// A line of the financial totals: label left, amount right.
struct CoalMoneyRow: View {
    let label: String
    let amount: Int
    var bold = false
    var color: Color = .primary

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(bold ? .subheadline.bold() : .subheadline)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Text(CoalMath.rubles(amount))
                .font(bold ? .headline.monospacedDigit() : .body.monospacedDigit())
                .foregroundStyle(color)
        }
    }
}

/// Shows a refusal of a service as an alert.
struct CoalErrorAlert: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.alert("Не получилось", isPresented: Binding(get: { message != nil },
                                                           set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
    }
}

extension View {
    func coalErrorAlert(_ message: Binding<String?>) -> some View { modifier(CoalErrorAlert(message: message)) }
}
