import SwiftUI
import SwiftData

/// «История»: sales of any day or month with search and the truck filter, the month's report and export,
/// closed days, payments to drivers.
struct CoalHistoryView: View {

    enum Mode: Hashable { case sales, days, payments }
    enum Period: Hashable { case day, month }
    enum VehicleFilter: Hashable { case all, hired, own }

    @Environment(\.modelContext) private var context
    @Query(sort: \CoalSale.saleDate, order: .reverse) private var allSales: [CoalSale]
    @Query(sort: \DriverPayment.paymentDate, order: .reverse) private var allPayments: [DriverPayment]
    @Query(sort: \CoalWorkDay.date, order: .reverse) private var workDays: [CoalWorkDay]
    @State private var mode: Mode = .sales
    @State private var period: Period = .day
    @State private var day = Calendar.farm.dayStart(AppClock.now())
    @State private var month = Calendar.farm.component(.month, from: AppClock.now())
    @State private var year = Calendar.farm.component(.year, from: AppClock.now())
    @State private var pickingMonth = false
    @State private var search = ""
    @State private var vehicle: VehicleFilter = .all
    @State private var editing: CoalSale?

    private var range: (Date, Date) {
        let calendar = Calendar.farm
        switch period {
        case .day:
            return (day, calendar.date(byAdding: .day, value: 1, to: day) ?? day)
        case .month:
            let start = calendar.date(day: 1, month: month, year: year)
            return (start, calendar.date(byAdding: .month, value: 1, to: start) ?? start)
        }
    }

    private var filteredSales: [CoalSale] {
        let (start, end) = range
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return allSales.filter { s in
            guard s.saleDate >= start && s.saleDate < end else { return false }
            switch vehicle {
            case .all: break
            case .hired: guard s.vehicleType == .hired else { return false }
            case .own: guard s.vehicleType == .own else { return false }
            }
            guard !query.isEmpty else { return true }
            return [s.driverNameSnapshot, s.locationNameSnapshot].contains {
                $0.range(of: query, options: [.caseInsensitive], locale: Locale(identifier: "ru_RU")) != nil
            }
        }
    }

    private var monthReport: CoalReport? {
        _ = (allSales.count, allPayments.count)
        return try? CoalDayService(context: context).monthReport(month: month, year: year)
    }

    var body: some View {
        List {
            Section {
                Picker("Раздел", selection: $mode) {
                    Text("Продажи").tag(Mode.sales)
                    Text("Дни").tag(Mode.days)
                    Text("Выплаты").tag(Mode.payments)
                }
                .pickerStyle(.segmented)
            }
            switch mode {
            case .sales: salesSections
            case .days: daysSection
            case .payments: paymentsSection
            }
        }
        .navigationTitle("История продаж")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $pickingMonth) {
            MonthYearPicker(month: month, year: year) { m, y in
                month = m
                year = y
            }
            .presentationDetents([.medium])
        }
        .sheet(item: $editing) { sale in
            NavigationStack { CoalSaleFormView(sale: sale) }
        }
    }

    @ViewBuilder
    private var salesSections: some View {
        Section {
            Picker("Период", selection: $period) {
                Text("День").tag(Period.day)
                Text("Месяц").tag(Period.month)
            }
            .pickerStyle(.segmented)
            if period == .day {
                DatePicker("День", selection: Binding(get: { day }, set: { day = Calendar.farm.dayStart($0) }),
                           displayedComponents: .date)
            } else {
                Button {
                    pickingMonth = true
                } label: {
                    LabeledContent("Месяц", value: Calendar.farm.monthTitle(month: month, year: year))
                }
            }
            TextField("Поиск: водитель или населённый пункт", text: $search)
            Picker("Машина", selection: $vehicle) {
                Text("Все").tag(VehicleFilter.all)
                Text("Наёмные").tag(VehicleFilter.hired)
                Text("Своя").tag(VehicleFilter.own)
            }
            .pickerStyle(.segmented)
        }

        let sales = filteredSales
        Section {
            CoalTotalsHeader(totals: CoalTotals(sales))
                .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
            if period == .day {
                NavigationLink("Итоги этого дня") { CoalDaySummaryView(initialDay: day) }
            }
        } header: {
            Text(search.isEmpty && vehicle == .all ? "Итоги" : "Итоги по отбору")
        }

        Section("Продажи (\(sales.count))") {
            if sales.isEmpty { Text("Продаж нет").foregroundStyle(.secondary) }
            ForEach(sales) { s in
                Button { editing = s } label: { CoalSaleCard(sale: s, showsDate: period == .month) }
                    .buttonStyle(.plain)
                    .editable(.coalSale)
                    .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))
                    .listRowBackground(Color.clear)
            }
        }

        if period == .month, let report = monthReport {
            Section("Отчёт за месяц") {
                CoalReportContent(report: report)
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
                    .listRowBackground(Color.clear)
                CoalExportActions(document: CoalReportLayout.document(report))
                    .listRowBackground(Color.clear)
            }
        }
    }

    private var daysSection: some View {
        Section("Завершённые рабочие дни") {
            let closed = workDays.filter(\.isClosed)
            if closed.isEmpty { Text("Завершённых дней пока нет").foregroundStyle(.secondary) }
            ForEach(closed) { d in
                NavigationLink {
                    CoalDaySummaryView(initialDay: d.date)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(CoalText.date(d.date)).font(.headline)
                            Spacer()
                            Text(CoalMath.rubles(d.totalRevenue)).font(.headline.monospacedDigit())
                        }
                        Text("\(CoalMath.tonsText(d.totalKilograms)) · водителям \(CoalMath.rubles(d.totalDriverPayments))"
                             + " · своя машина \(CoalMath.rubles(d.ownVehicleDeliveryRevenue))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if let closedAt = d.closedAt {
                            Text("Закрыт \(CoalText.dateTime(closedAt))").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private var paymentsSection: some View {
        Section("Выплаты водителям") {
            if allPayments.isEmpty { Text("Выплат пока не было").foregroundStyle(.secondary) }
            ForEach(allPayments) { p in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.driverNameSnapshot).font(.headline)
                        Text(CoalText.dateTime(p.paymentDate) + (p.comment.isEmpty ? "" : " · \(p.comment)"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(CoalMath.rubles(p.amount)).font(.body.monospacedDigit())
                }
            }
        }
    }
}

/// «Цены»: the coal price per ton and the villages with their delivery prices and drivers' pay.
struct CoalPricesView: View {

    @Environment(\.modelContext) private var context
    @Query(filter: #Predicate<DeliveryLocation> { $0.isActive == true }) private var locationRows: [DeliveryLocation]
    @State private var priceText = ""
    @State private var editing: DeliveryLocation?
    @State private var adding = false
    @State private var removing: DeliveryLocation?
    @State private var message: String?
    @State private var saved = false

    private var locations: [DeliveryLocation] {
        locationRows.sorted { $0.name.compare($1.name, locale: Locale(identifier: "ru_RU")) == .orderedAscending }
    }

    var body: some View {
        Form {
            Section {
                HStack {
                    Text("Цена угля за 1 тонну")
                    Spacer()
                    RublesField(title: "6500", text: $priceText).frame(maxWidth: 150)
                }
                Button("Сохранить цену", action: savePrice)
                    .disabled(CoalMath.parseRubles(priceText) == nil
                              || CoalMath.parseRubles(priceText) == CoalSettingsService(context: context).pricePerTon)
                if saved { Text("Цена сохранена").foregroundStyle(Theme.primary) }
            } header: {
                Text("Уголь")
            } footer: {
                Text("Новая цена действует для новых продаж. Сохранённые продажи и отчёты остаются по своим ценам.")
            }

            Section {
                ForEach(locations) { l in
                    Button { editing = l } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(l.name).foregroundStyle(.primary)
                                if let pay = l.driverPaymentKopecks {
                                    Text("водителю \(CoalMath.rubles(pay))").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Text(CoalMath.rubles(l.deliveryPriceKopecks)).foregroundStyle(.primary)
                        }
                    }
                    .swipeActions {
                        Button("Удалить", role: .destructive) { removing = l }
                            .onlyIfEditable(.deliveryLocation)
                    }
                }
                Button {
                    adding = true
                } label: {
                    Label("Добавить населённый пункт", systemImage: "plus")
                }
            } header: {
                Text("Доставка по населённым пунктам")
            } footer: {
                Text("Нажмите на пункт, чтобы изменить цену доставки или оплату водителю; смахните влево, чтобы удалить. "
                     + "По умолчанию наёмный водитель получает всю стоимость доставки.")
            }
        }
        // The viewer reads the prices; nothing here can be changed.
        .editable(.coalSettings)
        .navigationTitle("Цены и населённые пункты")
        .navigationBarTitleDisplayMode(.inline)
        .keyboardDoneButton()
        .onAppear {
            priceText = CoalMath.rublesField(CoalSettingsService(context: context).pricePerTon)
        }
        .sheet(item: $editing) { l in
            NavigationStack { LocationFormView(location: l) }
        }
        .sheet(isPresented: $adding) {
            NavigationStack { LocationFormView(location: nil) }
        }
        .confirmationDialog("Удалить населённый пункт?", isPresented: Binding(get: { removing != nil },
                                                                              set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { l in
            Button("Удалить", role: .destructive) {
                do { try CoalSettingsService(context: context).removeLocation(l) } catch { message = error.localizedDescription }
            }
            Button("Отмена", role: .cancel) {}
        } message: { l in
            Text("«\(l.name)» исчезнет из новой продажи. Прошлые продажи сохранят название и цены.")
        }
        .coalErrorAlert($message)
    }

    private func savePrice() {
        guard let kopecks = CoalMath.parseRubles(priceText) else { return }
        do {
            try CoalSettingsService(context: context).setPricePerTon(kopecks)
            saved = true
        } catch {
            message = error.localizedDescription
        }
    }
}

/// A village: name, delivery price for the buyer, the hired driver's pay (empty = the delivery price).
struct LocationFormView: View {
    let location: DeliveryLocation?

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var deliveryText = ""
    @State private var driverText = ""
    @State private var loaded = false
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                TextField("Название", text: $name)
                HStack {
                    Text("Доставка для покупателя")
                    Spacer()
                    RublesField(title: "1000", text: $deliveryText).frame(maxWidth: 140)
                }
                HStack {
                    Text("Оплата наёмному водителю")
                    Spacer()
                    RublesField(title: "как доставка", text: $driverText).frame(maxWidth: 140)
                }
            } footer: {
                Text("Оставьте оплату водителю пустой, чтобы водитель получал всю стоимость доставки.")
            }
        }
        .navigationTitle(location == nil ? "Новый пункт" : "Населённый пункт")
        .navigationBarTitleDisplayMode(.inline)
        .keyboardDoneButton()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Сохранить", action: save).editable(.deliveryLocation) }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            if let location {
                name = location.name
                deliveryText = CoalMath.rublesField(location.deliveryPriceKopecks)
                driverText = location.driverPaymentKopecks.map(CoalMath.rublesField) ?? ""
            }
        }
        .coalErrorAlert($message)
    }

    private func save() {
        guard let delivery = CoalMath.parseRubles(deliveryText) else {
            message = "Укажите стоимость доставки, например 1000"
            return
        }
        let trimmedDriver = driverText.trimmingCharacters(in: .whitespaces)
        let driverPay: Int?
        if trimmedDriver.isEmpty {
            driverPay = nil
        } else if let value = CoalMath.parseRubles(trimmedDriver) {
            driverPay = value
        } else {
            message = "Неверная оплата водителю"
            return
        }
        do {
            let service = CoalSettingsService(context: context)
            if let location {
                try service.updateLocation(location, name: name, deliveryKopecks: delivery, driverPaymentKopecks: driverPay)
            } else {
                try service.addLocation(name: name, deliveryKopecks: delivery, driverPaymentKopecks: driverPay)
            }
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }
}
