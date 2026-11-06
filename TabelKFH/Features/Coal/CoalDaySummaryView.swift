import SwiftUI
import SwiftData

/// «Итоги рабочего дня»: the table of the day's sales, the totals А–Е, «Взаиморасчёты с водителями» with
/// «Выплатить водителю», the export, and closing or reopening the day. Any day can be chosen.
struct CoalDaySummaryView: View {
    /// The day shown first; today when nil.
    var initialDay: Date?

    @Environment(\.modelContext) private var context
    // Watched so the report is built again after any change of sales, payments or days.
    @Query private var sales: [CoalSale]
    @Query private var payments: [DriverPayment]
    @Query private var workDays: [CoalWorkDay]
    @Query private var drivers: [Driver]
    @State private var day = Calendar.farm.dayStart(AppClock.now())
    @State private var loaded = false
    @State private var closing = false
    @State private var confirmingReopen = false
    @State private var paying: Driver?
    @State private var message: String?

    private var report: CoalReport? {
        _ = (sales.count, payments.count, workDays.map(\.isClosed), drivers.count)
        return try? CoalDayService(context: context).dayReport(day)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                DatePicker("День", selection: Binding(get: { day }, set: { day = Calendar.farm.dayStart($0) }),
                           displayedComponents: .date)
                    .font(.headline)
                    .card()
                if let report {
                    CoalReportContent(report: report) { settlement in
                        paying = drivers.first { $0.id == settlement.driverId }
                    }
                    dayActions(report)
                    CoalExportActions(document: CoalReportLayout.document(report))
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Итоги рабочего дня")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            if let initialDay { day = Calendar.farm.dayStart(initialDay) }
        }
        .sheet(isPresented: $closing) {
            NavigationStack { CloseDayView(day: day) }
        }
        .sheet(item: $paying) { driver in
            NavigationStack {
                DriverPaymentFormView(driver: driver,
                                      debt: DriverBalance(driverId: driver.id, sales: sales, payments: payments).debt)
            }
        }
        .confirmationDialog("Открыть день для редактирования?", isPresented: $confirmingReopen,
                            titleVisibility: .visible) {
            Button("Открыть день") {
                do { try CoalDayService(context: context).reopen(day) } catch { message = error.localizedDescription }
            }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Продажи дня снова можно будет менять. Отчёт и долги пересчитаются.")
        }
        .coalErrorAlert($message)
    }

    @ViewBuilder
    private func dayActions(_ report: CoalReport) -> some View {
        if let closedAt = report.closedAt {
            VStack(alignment: .leading, spacing: 8) {
                Label("День завершён \(CoalText.dateTime(closedAt))", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(Theme.primary)
                Button("Открыть день для редактирования") { confirmingReopen = true }
                    .buttonStyle(BigButtonStyle(color: Theme.accent))
            }
            .card()
        } else {
            Button {
                closing = true
            } label: {
                Label("ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ", systemImage: "checkmark.seal")
            }
            .buttonStyle(BigButtonStyle(color: Theme.accent))
        }
    }
}

/// The body of a day or month report on screen: sales table, totals, drivers, villages (month), payments.
struct CoalReportContent: View {
    let report: CoalReport
    /// «Выплатить» next to a driver who is owed money; nil hides the buttons.
    var onPay: ((DriverSettlement) -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Продажи").font(.headline)
                if report.rows.isEmpty {
                    Text("Продаж нет.").foregroundStyle(.secondary)
                } else {
                    CoalSalesTable(report: report)
                }
            }
            .card()

            VStack(alignment: .leading, spacing: 8) {
                Text("Финансовые итоги").font(.headline)
                let t = report.totals
                CoalMoneyRow(label: "А. Общая выручка хозяйства", amount: t.revenue, bold: true, color: Theme.primary)
                CoalMoneyRow(label: "Б. Стоимость проданного угля", amount: t.coalAmount)
                CoalMoneyRow(label: "В. Общая стоимость доставки", amount: t.deliveryAmount)
                CoalMoneyRow(label: "Г. Выплаты наёмным водителям", amount: t.driverPayments, color: CoalTheme.hired)
                CoalMoneyRow(label: "Д. Доход от собственной машины", amount: t.ownVehicleDeliveryRevenue,
                             color: CoalTheme.own)
                Divider()
                CoalMoneyRow(label: "Е. Остаток после выплат водителям", amount: t.remainderAfterDrivers, bold: true)
                Text(CoalReportLayout.remainderNote).font(.caption).foregroundStyle(.secondary)
                HStack {
                    Text("Продано угля").font(.subheadline)
                    Spacer()
                    Text("\(CoalMath.tonsText(t.kilograms)), \(CoalText.sales(t.salesCount))")
                        .font(.subheadline.monospacedDigit())
                }
            }
            .card()

            VStack(alignment: .leading, spacing: 10) {
                Text("Взаиморасчёты с водителями").font(.headline)
                if report.drivers.isEmpty {
                    Text("Начислений наёмным водителям нет.").foregroundStyle(.secondary)
                }
                ForEach(report.drivers) { d in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(d.name).font(.subheadline.bold())
                            Spacer()
                            Text(CoalText.deliveries(d.deliveries)).font(.caption).foregroundStyle(.secondary)
                        }
                        if d.debtBefore != 0 { CoalMoneyRow(label: "Долг на начало", amount: d.debtBefore) }
                        CoalMoneyRow(label: "Начислено", amount: d.accrued)
                        if d.paid != 0 { CoalMoneyRow(label: "Выплачено", amount: d.paid) }
                        CoalMoneyRow(label: "К выплате", amount: d.debtAfter, bold: true, color: CoalTheme.hired)
                        if let onPay, d.debtAfter > 0 {
                            Button("Выплатить водителю") { onPay(d) }
                                .buttonStyle(.bordered)
                                .tint(CoalTheme.hired)
                        }
                    }
                    Divider()
                }
                if !report.drivers.isEmpty {
                    CoalMoneyRow(label: "ИТОГО К ВЫПЛАТЕ", amount: report.totalDebtAfter, bold: true, color: CoalTheme.hired)
                    Text("Начисленное не считается выплаченным: долг уменьшают только записанные выплаты.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .card()

            if report.kind == .month && !report.locations.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Итоги по населённым пунктам").font(.headline)
                    ForEach(report.locations, id: \.name) { l in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(l.name).font(.subheadline.bold())
                                Text("\(CoalText.sales(l.salesCount)), \(CoalMath.tonsText(l.kilograms))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(CoalMath.rubles(l.revenue)).font(.body.monospacedDigit())
                        }
                    }
                }
                .card()
            }

            if !report.payments.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Выплаты водителям").font(.headline)
                    ForEach(Array(report.payments.enumerated()), id: \.offset) { _, p in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(p.driver).font(.subheadline)
                                Text(CoalText.dateTime(p.date) + (p.comment.isEmpty ? "" : " · \(p.comment)"))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(CoalMath.rubles(p.amount)).font(.body.monospacedDigit())
                        }
                    }
                }
                .card()
            }
        }
    }
}

/// №, время, тонн, пункт, машина, водитель, уголь, доставка, итого — scrolls sideways on a phone.
struct CoalSalesTable: View {
    let report: CoalReport

    private var titles: [String] {
        ["№", report.kind == .day ? "Время" : "Дата", "Тонн", "Населённый пункт", "Машина", "Водитель", "Уголь",
         "Доставка", "Итого"]
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: true) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                GridRow {
                    ForEach(titles, id: \.self) { Text($0).font(.caption.bold()).foregroundStyle(.secondary) }
                }
                Divider()
                ForEach(report.rows, id: \.id) { r in
                    GridRow {
                        Text("\(r.number)")
                        Text(report.kind == .day ? CoalText.time(r.date) : CoalText.dateTime(r.date))
                        Text(CoalMath.tons(r.kilograms)).gridColumnAlignment(.trailing)
                        Text(r.location)
                        Text(r.vehicle.shortTitle).foregroundStyle(CoalTheme.color(r.vehicle))
                        Text(r.driver.isEmpty ? "—" : r.driver)
                        Text(CoalMath.rublesNumber(r.coalAmount)).gridColumnAlignment(.trailing)
                        Text(CoalMath.rublesNumber(r.deliveryPrice)).gridColumnAlignment(.trailing)
                        Text(CoalMath.rublesNumber(r.totalAmount) + (r.listTotal == nil ? "" : "*"))
                            .bold()
                            .gridColumnAlignment(.trailing)
                    }
                    .font(.footnote.monospacedDigit())
                }
                Divider()
                GridRow {
                    Text("Итого").bold()
                    Text("")
                    Text(CoalMath.tons(report.totals.kilograms)).bold()
                    Text(CoalText.sales(report.totals.salesCount))
                    Text("")
                    Text("")
                    Text(CoalMath.rublesNumber(report.totals.coalAmount)).bold()
                    Text(CoalMath.rublesNumber(report.totals.deliveryAmount)).bold()
                    Text(CoalMath.rublesNumber(report.totals.revenue)).bold()
                }
                .font(.footnote.monospacedDigit())
            }
            .padding(.bottom, 6)
        }
    }
}
