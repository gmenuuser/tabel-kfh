import SwiftUI
import SwiftData

/// «Отчёты»: the monthly timesheet, coal sales and payments to drivers, each as PDF and Excel ready to print.
struct ReportsView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                row("Табель работников", subtitle: "Табель за месяц по всему хозяйству или по сотруднику",
                    icon: "tablecells", color: Theme.primary) { MonthTimesheetView() }
                row("Продажи угля", subtitle: "За день или за месяц: продажи, выручка, итоги",
                    icon: "truck.box.fill", color: Theme.accent) { CoalSalesReportView() }
                row("Выплаты водителям", subtitle: "Сколько заработал, получил и кому сколько ещё выплатить",
                    icon: "banknote.fill", color: CoalTheme.hired) { DriverPaymentsReportView() }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Отчёты")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func row<Destination: View>(_ title: String, subtitle: String, icon: String, color: Color,
                                        @ViewBuilder destination: () -> Destination) -> some View {
        NavigationLink(destination: destination()) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 52)
                    .background(color, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.title3.bold()).foregroundStyle(.primary)
                    Text(subtitle).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
            .card()
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title)
    }
}

/// Day or month, chosen with a date or a month picker.
struct ReportPeriodPicker: View {
    enum Kind: Hashable { case day, month }

    @Binding var kind: Kind
    @Binding var day: Date
    @Binding var month: Int
    @Binding var year: Int
    @State private var pickingMonth = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("Период", selection: $kind) {
                Text("За день").tag(Kind.day)
                Text("За месяц").tag(Kind.month)
            }
            .pickerStyle(.segmented)
            if kind == .day {
                DatePicker("День", selection: Binding(get: { day }, set: { day = Calendar.farm.dayStart($0) }),
                           displayedComponents: .date)
                    .font(.headline)
            } else {
                Button {
                    pickingMonth = true
                } label: {
                    HStack {
                        Text("Месяц").font(.headline).foregroundStyle(.primary)
                        Spacer()
                        Text(Calendar.farm.monthTitle(month: month, year: year)).font(.headline)
                        Image(systemName: "chevron.down").font(.caption)
                    }
                }
            }
        }
        .card()
        .sheet(isPresented: $pickingMonth) {
            MonthYearPicker(month: month, year: year) { m, y in
                month = m
                year = y
            }
            .presentationDetents([.medium])
        }
    }
}

/// «Продажи угля»: the day or month report on screen with «Скачать PDF», «Экспорт в Excel», «Распечатать».
struct CoalSalesReportView: View {
    @Environment(\.modelContext) private var context
    // Watched so the report is built again after a change.
    @Query private var sales: [CoalSale]
    @Query private var payments: [DriverPayment]
    @Query private var workDays: [CoalWorkDay]
    @State private var kind: ReportPeriodPicker.Kind = .day
    @State private var day = Calendar.farm.dayStart(AppClock.now())
    @State private var month = Calendar.farm.component(.month, from: AppClock.now())
    @State private var year = Calendar.farm.component(.year, from: AppClock.now())

    private var report: CoalReport? {
        _ = (sales.count, payments.count, workDays.map(\.isClosed))
        let service = CoalDayService(context: context)
        return try? (kind == .day ? service.dayReport(day) : service.monthReport(month: month, year: year))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ReportPeriodPicker(kind: $kind, day: $day, month: $month, year: $year)
                if let report {
                    CoalExportActions(document: CoalReportLayout.document(report))
                    CoalReportContent(report: report)
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Продажи угля")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// «Выплаты водителям»: who is owed how much now (with «Выплатить»), and the report of a day or a month.
struct DriverPaymentsReportView: View {
    @Environment(\.modelContext) private var context
    @Query private var sales: [CoalSale]
    @Query private var payments: [DriverPayment]
    @Query private var drivers: [Driver]
    @State private var kind: ReportPeriodPicker.Kind = .month
    @State private var day = Calendar.farm.dayStart(AppClock.now())
    @State private var month = Calendar.farm.component(.month, from: AppClock.now())
    @State private var year = Calendar.farm.component(.year, from: AppClock.now())
    @State private var paying: Driver?

    private struct Owed: Identifiable {
        let driver: Driver
        let balance: DriverBalance
        var id: UUID { driver.id }
    }

    /// Working drivers, and archived ones the farm still owes.
    private var owed: [Owed] {
        drivers.map { Owed(driver: $0, balance: DriverBalance(driverId: $0.id, sales: sales, payments: payments)) }
            .filter { $0.balance.debt != 0 || !$0.driver.isArchived }
            .sorted { $0.driver.fullName.compare($1.driver.fullName, locale: Locale(identifier: "ru_RU")) == .orderedAscending }
    }

    private var report: CoalReport? {
        _ = (sales.count, payments.count, drivers.count)
        let service = CoalDayService(context: context)
        return try? (kind == .day ? service.dayReport(day) : service.monthReport(month: month, year: year))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Долг перед водителями сейчас").font(.headline)
                    if owed.isEmpty { Text("Наёмных водителей пока нет.").foregroundStyle(.secondary) }
                    ForEach(owed) { row in
                        let driver = row.driver, balance = row.balance
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(driver.fullName).font(.headline)
                                Text("заработал \(CoalMath.rubles(balance.earned)), получил \(CoalMath.rubles(balance.paid))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(CoalMath.rubles(balance.debt))
                                .font(.headline.monospacedDigit())
                                .foregroundStyle(balance.debt > 0 ? CoalTheme.hired : .secondary)
                        }
                        if balance.debt > 0 {
                            Button("Выплатить \(driver.lastName)") { paying = driver }
                                .buttonStyle(BigButtonStyle(color: CoalTheme.hired))
                        }
                        Divider()
                    }
                }
                .card()

                Text("Отчёт для печати").font(.title3.bold())
                ReportPeriodPicker(kind: $kind, day: $day, month: $month, year: $year)
                if let report {
                    CoalExportActions(document: CoalReportLayout.paymentsDocument(report))
                    if !report.payments.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Выплаты за период").font(.headline)
                            ForEach(Array(report.payments.enumerated()), id: \.offset) { _, p in
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(p.driver)
                                        Text(CoalText.dateTime(p.date) + (p.comment.isEmpty ? "" : " · \(p.comment)"))
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Text(CoalMath.rubles(p.amount)).monospacedDigit()
                                }
                            }
                        }
                        .card()
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Выплаты водителям")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $paying) { driver in
            NavigationStack {
                DriverPaymentFormView(driver: driver,
                                      debt: DriverBalance(driverId: driver.id, sales: sales, payments: payments).debt)
            }
        }
    }
}
