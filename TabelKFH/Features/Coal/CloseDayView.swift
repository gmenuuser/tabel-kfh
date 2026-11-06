import SwiftUI
import SwiftData

/// «Завершить рабочий день»: the day's figures and one button that closes the day. Nothing is paid to anyone.
struct CloseDayView: View {
    let day: Date

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var report: CoalReport?
    @State private var message: String?

    var body: some View {
        ScrollView {
            if let report {
                VStack(alignment: .leading, spacing: 14) {
                    Text(CoalText.longDate(day).capitalizedFirst).font(.headline)

                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Text("Всего продано угля").font(.body)
                            Spacer()
                            Text(CoalMath.tonsText(report.totals.kilograms)).font(.title3.bold().monospacedDigit())
                        }
                        CoalMoneyRow(label: "Общая выручка", amount: report.totals.revenue, bold: true,
                                     color: Theme.primary)
                    }
                    .card()

                    VStack(alignment: .leading, spacing: 12) {
                        if report.drivers.isEmpty {
                            Text("Наёмным водителям платить нечего.").foregroundStyle(.secondary)
                        }
                        ForEach(report.drivers) { d in
                            CoalMoneyRow(label: "\(d.name): к выплате", amount: d.debtAfter, color: CoalTheme.hired)
                        }
                        Divider()
                        CoalMoneyRow(label: "Всего выплатить наёмным водителям", amount: report.totalDebtAfter,
                                     bold: true, color: CoalTheme.hired)
                        CoalMoneyRow(label: "Доход от собственной машины", amount: report.totals.ownVehicleDeliveryRevenue,
                                     color: CoalTheme.own)
                        Divider()
                        CoalMoneyRow(label: "Остаток после выплат водителям", amount: report.totals.remainderAfterDrivers,
                                     bold: true)
                        Text("Остаток — это ещё не прибыль: стоимость угля и другие расходы здесь не вычтены.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .card()

                    Text("Деньги водителям этим не выплачиваются: долг останется, пока вы не отметите выплату.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)

                    Button("ПОДТВЕРДИТЬ ЗАВЕРШЕНИЕ ДНЯ", action: close)
                        .buttonStyle(BigButtonStyle())
                }
                .padding(16)
            }
        }
        .background(Theme.background)
        .navigationTitle("Итоги рабочего дня")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
        }
        .onAppear { load() }
        .coalErrorAlert($message)
    }

    private func load() {
        do {
            report = try CoalDayService(context: context).dayReport(day)
        } catch {
            message = error.localizedDescription
        }
    }

    private func close() {
        do {
            try CoalDayService(context: context).close(day)
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }
}

extension String {
    /// «Пятница, 6 ноября 2026».
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
