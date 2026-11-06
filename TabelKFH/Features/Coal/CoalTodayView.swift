import SwiftUI
import SwiftData

/// «Продажи за сегодня»: today's figures, the sales, «Новая продажа» and «Завершить рабочий день».
struct CoalTodayView: View {
    let onClose: () -> Void

    @Environment(\.modelContext) private var context
    @Environment(\.scenePhase) private var scenePhase
    @Query(sort: \CoalSale.saleDate) private var allSales: [CoalSale]
    @Query private var workDays: [CoalWorkDay]
    @State private var today = Calendar.farm.dayStart(AppClock.now())
    @State private var addingSale = false
    @State private var editing: CoalSale?
    @State private var deleting: CoalSale?
    @State private var closing = false
    @State private var confirmingReopen = false
    @State private var message: String?

    private var sales: [CoalSale] {
        let end = Calendar.farm.date(byAdding: .day, value: 1, to: today) ?? today
        return allSales.filter { $0.saleDate >= today && $0.saleDate < end }
    }

    private var workDay: CoalWorkDay? { workDays.first { $0.date == today } }
    private var isClosed: Bool { workDay?.isClosed == true }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(CoalText.longDate(today).capitalizedFirst)
                        .font(.headline)
                    CoalTotalsHeader(totals: CoalTotals(sales))
                }
                .card()

                if isClosed, let workDay {
                    closedBanner(workDay)
                } else {
                    Button {
                        addingSale = true
                    } label: {
                        Label("Новая продажа", systemImage: "plus.circle.fill")
                    }
                    .buttonStyle(BigButtonStyle())
                }

                if sales.isEmpty {
                    Text("Сегодня продаж пока нет.")
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 24)
                } else {
                    ForEach(sales) { sale in
                        Button {
                            if isClosed {
                                message = CoalError.dayClosed(today).errorDescription
                            } else {
                                editing = sale
                            }
                        } label: {
                            CoalSaleCard(sale: sale)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            if !isClosed {
                                Button("Изменить") { editing = sale }
                                Button("Удалить", role: .destructive) { deleting = sale }
                            }
                        }
                    }
                }

                if !isClosed {
                    Button {
                        closing = true
                    } label: {
                        Label("ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ", systemImage: "checkmark.seal")
                    }
                    .buttonStyle(BigButtonStyle(color: Theme.accent))
                    .padding(.top, 8)
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Продажи за сегодня")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { CoalCloseButton(onClose: onClose) }
        .onAppear { today = Calendar.farm.dayStart(AppClock.now()) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { today = Calendar.farm.dayStart(AppClock.now()) }
        }
        .sheet(isPresented: $addingSale) {
            NavigationStack { CoalSaleFormView(sale: nil) }
        }
        .sheet(item: $editing) { sale in
            NavigationStack { CoalSaleFormView(sale: sale) }
        }
        .sheet(isPresented: $closing) {
            NavigationStack { CloseDayView(day: today) }
        }
        .confirmationDialog("Удалить продажу?", isPresented: Binding(get: { deleting != nil },
                                                                      set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { sale in
            Button("Удалить", role: .destructive) { delete(sale) }
            Button("Отмена", role: .cancel) {}
        } message: { sale in
            Text("\(CoalMath.tonsText(sale.kilograms)), \(sale.locationNameSnapshot), \(CoalMath.rubles(sale.totalAmount))")
        }
        .confirmationDialog("Открыть день для редактирования?", isPresented: $confirmingReopen,
                            titleVisibility: .visible) {
            Button("Открыть день") { reopen() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Продажи дня снова можно будет менять. Итоги и долги пересчитаются; завершите день ещё раз после правок.")
        }
        .coalErrorAlert($message)
    }

    private func closedBanner(_ day: CoalWorkDay) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Рабочий день завершён", systemImage: "checkmark.seal.fill")
                .font(.headline)
                .foregroundStyle(Theme.primary)
            if let closedAt = day.closedAt {
                Text("Закрыт в \(CoalText.time(closedAt)). Продажи дня заблокированы от случайных изменений.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Button("Открыть день для редактирования") { confirmingReopen = true }
                .buttonStyle(BigButtonStyle(color: Theme.accent))
        }
        .card()
    }

    private func delete(_ sale: CoalSale) {
        do {
            try CoalSaleService(context: context).delete(sale)
        } catch {
            message = error.localizedDescription
        }
    }

    private func reopen() {
        do {
            try CoalDayService(context: context).reopen(today)
        } catch {
            message = error.localizedDescription
        }
    }
}

/// «Завершить рабочий день»: the day's figures, then a confirmation. Nothing is paid to anyone.
struct CloseDayView: View {
    let day: Date

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var report: CoalReport?
    @State private var confirming = false
    @State private var message: String?

    var body: some View {
        ScrollView {
            if let report {
                VStack(alignment: .leading, spacing: 14) {
                    Text(CoalText.longDate(day).capitalizedFirst).font(.headline)
                    VStack(alignment: .leading, spacing: 10) {
                        CoalMoneyRow(label: "Общая выручка", amount: report.totals.revenue, bold: true,
                                     color: Theme.primary)
                        HStack {
                            Text("Продано угля").font(.subheadline)
                            Spacer()
                            Text(CoalMath.tonsText(report.totals.kilograms)).font(.body.monospacedDigit())
                        }
                        CoalMoneyRow(label: "К выплате наёмным водителям за день", amount: report.totals.driverPayments,
                                     color: CoalTheme.hired)
                        CoalMoneyRow(label: "Доход от собственной машины", amount: report.totals.ownVehicleDeliveryRevenue,
                                     color: CoalTheme.own)
                        Divider()
                        CoalMoneyRow(label: "Остаток после выплат водителям", amount: report.totals.remainderAfterDrivers,
                                     bold: true)
                        Text(CoalReportLayout.remainderNote).font(.caption).foregroundStyle(.secondary)
                    }
                    .card()

                    if !report.drivers.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Долг перед водителями после дня").font(.headline)
                            ForEach(report.drivers) { d in
                                CoalMoneyRow(label: d.name, amount: d.debtAfter)
                            }
                            Text("Завершение дня не выплачивает деньги: долг останется, пока вы не запишете выплату.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .card()
                    }

                    Button("Подтвердить завершение дня") { confirming = true }
                        .buttonStyle(BigButtonStyle(color: Theme.accent))
                }
                .padding(16)
            }
        }
        .navigationTitle("Завершить рабочий день")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
        }
        .onAppear { load() }
        .confirmationDialog("Завершить рабочий день?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Завершить день") { close() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Итоги будут зафиксированы, продажи дня заблокированы. Выплаты водителям не делаются.")
        }
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
