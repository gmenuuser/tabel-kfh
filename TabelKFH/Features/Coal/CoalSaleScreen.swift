import SwiftUI
import SwiftData

/// «Продажа угля»: one screen for the whole day. The price and today's figures on top, the new sale form
/// (tons, village, truck), the calculation, «СОХРАНИТЬ ПРОДАЖУ», today's sales, «ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ».
struct CoalSaleScreen: View {
    enum More: Hashable { case summary, history, drivers, prices }

    @Environment(\.modelContext) private var context
    @Environment(\.scenePhase) private var scenePhase
    @Query(sort: \CoalSale.saleDate, order: .reverse) private var allSales: [CoalSale]
    @Query private var workDays: [CoalWorkDay]
    @Query(filter: #Predicate<DeliveryLocation> { $0.isActive == true }) private var locationRows: [DeliveryLocation]
    @Query(filter: #Predicate<Driver> { $0.isArchived == false }) private var driverRows: [Driver]
    @Query private var settingsRows: [CoalSettings]
    @State private var today = Calendar.farm.dayStart(AppClock.now())
    @State private var draft: CoalSaleDraft?
    @State private var showErrors = false
    @State private var savedNote: String?
    @State private var addingDriver = false
    @State private var editing: CoalSale?
    @State private var cancelling: CoalSale?
    @State private var closing = false
    @State private var confirmingReopen = false
    @State private var more: More?
    @State private var message: String?

    private static let quickTons = ["0,5", "1", "1,5", "2", "2,5", "3"]
    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    private var todaySales: [CoalSale] {
        let end = Calendar.farm.date(byAdding: .day, value: 1, to: today) ?? today
        return allSales.filter { $0.saleDate >= today && $0.saleDate < end }
    }

    private var isClosed: Bool { workDays.contains { $0.date == today && $0.isClosed } }
    private var closedAt: Date? { workDays.first { $0.date == today && $0.isClosed }?.closedAt }
    private var pricePerTon: Int { settingsRows.first?.pricePerTonKopecks ?? CoalDefaults.pricePerTonKopecks }

    private var locations: [DeliveryLocation] {
        locationRows.sorted { $0.name.compare($1.name, locale: Locale(identifier: "ru_RU")) == .orderedAscending }
    }

    private var drivers: [Driver] {
        driverRows.sorted { $0.fullName.compare($1.fullName, locale: Locale(identifier: "ru_RU")) == .orderedAscending }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if isClosed {
                    closedBanner
                } else if let binding = Binding($draft) {
                    form(binding)
                }
                salesList
                if !isClosed {
                    Button {
                        closing = true
                    } label: {
                        Label("ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ", systemImage: "checkmark.seal")
                    }
                    .buttonStyle(BigButtonStyle(color: Theme.accent))
                    .padding(.top, 4)
                }
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.background)
        .navigationTitle("Продажа угля")
        .navigationBarTitleDisplayMode(.inline)
        .keyboardDoneButton()
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { more = .summary } label: { Label("Итоги дня и выплаты", systemImage: "list.bullet.rectangle") }
                    Button { more = .history } label: { Label("История продаж", systemImage: "clock.arrow.circlepath") }
                    Button { more = .drivers } label: { Label("Водители", systemImage: "person.2") }
                    Button { more = .prices } label: { Label("Цены и населённые пункты", systemImage: "rublesign.circle") }
                } label: {
                    Label("Ещё", systemImage: "ellipsis.circle")
                }
                .accessibilityLabel("Ещё")
            }
        }
        .navigationDestination(item: $more) { m in
            switch m {
            case .summary: CoalDaySummaryView(initialDay: today)
            case .history: CoalHistoryView()
            case .drivers: DriversView()
            case .prices: CoalPricesView()
            }
        }
        .onAppear {
            CoalSettingsService(context: context).current()
            refreshDay()
            if draft == nil { draft = CoalSaleService(context: context).newDraft() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refreshDay() }
        }
        .onChange(of: pricePerTon) { _, price in
            // A new price set in «Цены» applies to the sale being typed in.
            draft?.pricePerTon = price
        }
        .sheet(isPresented: $addingDriver) {
            NavigationStack {
                DriverFormView(driver: nil) { newDriver in draft?.choose(newDriver) }
            }
        }
        .sheet(item: $editing) { sale in
            NavigationStack { CoalSaleFormView(sale: sale) }
        }
        .sheet(isPresented: $closing) {
            NavigationStack { CloseDayView(day: today) }
        }
        .confirmationDialog("Отменить продажу?", isPresented: Binding(get: { cancelling != nil },
                                                                       set: { if !$0 { cancelling = nil } }),
                            titleVisibility: .visible, presenting: cancelling) { sale in
            Button("Отменить продажу", role: .destructive) { cancel(sale) }
            Button("Не отменять", role: .cancel) {}
        } message: { sale in
            Text("\(CoalMath.tonsText(sale.kilograms)), \(sale.locationNameSnapshot), \(CoalMath.rubles(sale.totalAmount))")
        }
        .confirmationDialog("Открыть день для исправлений?", isPresented: $confirmingReopen, titleVisibility: .visible) {
            Button("Открыть день") { reopen() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Продажи дня снова можно будет менять. После исправлений завершите день ещё раз.")
        }
        .coalErrorAlert($message)
    }

    // MARK: Top

    private var header: some View {
        let t = CoalTotals(todaySales)
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("Цена угля").font(.headline)
                Spacer()
                Text("\(CoalMath.rubles(pricePerTon)) за тонну").font(.headline.monospacedDigit())
            }
            HStack(spacing: 8) {
                CoalStatTile(title: "Продаж сегодня", value: "\(t.salesCount)")
                CoalStatTile(title: "Выручка за день", value: CoalMath.rubles(t.revenue), color: Theme.primary)
            }
        }
        .card()
    }

    private var closedBanner: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Рабочий день завершён", systemImage: "checkmark.seal.fill")
                .font(.headline)
                .foregroundStyle(Theme.primary)
            if let closedAt {
                Text("Закрыт в \(CoalText.time(closedAt)). Новые продажи и исправления — после открытия дня.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Button("Открыть день для исправлений") { confirmingReopen = true }
                .buttonStyle(BigButtonStyle(color: Theme.accent))
        }
        .card()
    }

    // MARK: Form

    private func form(_ d: Binding<CoalSaleDraft>) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Новая продажа").font(.title2.bold())

            VStack(alignment: .leading, spacing: 8) {
                caption("1. Сколько тонн")
                HStack {
                    TextField("0", text: d.tonsText)
                        .keyboardType(.decimalPad)
                        .font(.system(size: 36, weight: .bold).monospacedDigit())
                        .accessibilityIdentifier("tonsField")
                    Text("т").font(.title).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Self.quickTons, id: \.self) { t in
                            let selected = d.wrappedValue.kilograms == CoalMath.parseTons(t)
                            Button("\(t) т") { d.wrappedValue.tonsText = t }
                                .font(.headline)
                                .padding(.horizontal, 16)
                                .frame(minHeight: 46)
                                .foregroundStyle(selected ? .white : Theme.primary)
                                .background(selected ? Theme.primary : Theme.card, in: Capsule())
                                .buttonStyle(.plain)
                        }
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                caption("2. Куда везти")
                if locations.isEmpty {
                    Text("Добавьте населённые пункты: «Ещё» → «Цены и населённые пункты».").foregroundStyle(.secondary)
                }
                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(locations) { l in
                        tile(title: l.name, subtitle: CoalMath.rubles(l.deliveryPriceKopecks),
                             selected: d.wrappedValue.locationName == l.name, color: Theme.primary,
                             id: "location-\(l.name)") {
                            d.wrappedValue.choose(l)
                        }
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                caption("3. Чья машина")
                LazyVGrid(columns: columns, spacing: 10) {
                    tile(title: "СВОЯ МАШИНА", subtitle: "без оплаты водителю",
                         selected: d.wrappedValue.vehicle == .own, color: CoalTheme.own, id: "vehicle-own") {
                        d.wrappedValue.vehicle = .own
                        d.wrappedValue.driverId = nil
                        d.wrappedValue.driverName = ""
                    }
                    ForEach(drivers) { driver in
                        DriverChoiceButton(driver: driver,
                                           isSelected: d.wrappedValue.vehicle == .hired && d.wrappedValue.driverId == driver.id) {
                            d.wrappedValue.choose(driver)
                        }
                    }
                    Button {
                        addingDriver = true
                    } label: {
                        Label("Водитель", systemImage: "plus")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 58)
                            .foregroundStyle(Theme.primary)
                            .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Добавить водителя")
                }
            }

            calculation(d.wrappedValue)

            if showErrors, !d.wrappedValue.isValid {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(errors(d.wrappedValue), id: \.self) { e in
                        Label(e, systemImage: "exclamationmark.circle").foregroundStyle(.red)
                    }
                }
                .font(.subheadline)
            }

            Button("СОХРАНИТЬ ПРОДАЖУ") { save(d.wrappedValue) }
                .buttonStyle(BigButtonStyle())
            if let savedNote {
                Label(savedNote, systemImage: "checkmark.circle.fill")
                    .foregroundStyle(Theme.primary)
                    .font(.subheadline)
            }
        }
    }

    private func calculation(_ v: CoalSaleDraft) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("Уголь: \(CoalMath.tonsText(v.kilograms ?? 0)) × \(CoalMath.rubles(v.pricePerTon))")
                Spacer()
                Text(CoalMath.rubles(v.coalAmount)).monospacedDigit()
            }
            HStack(alignment: .firstTextBaseline) {
                Text(v.locationName.isEmpty ? "Доставка" : "Доставка: \(v.locationName)")
                Spacer()
                Text(CoalMath.rubles(v.deliveryPrice)).monospacedDigit()
            }
            if v.vehicle == .hired, v.driverId != nil {
                HStack(alignment: .firstTextBaseline) {
                    Text("Заработок водителя \(v.driverName)")
                    Spacer()
                    Text(CoalMath.rubles(v.driverPayment)).monospacedDigit()
                }
                .font(.subheadline)
                .foregroundStyle(CoalTheme.hired)
            }
            Divider()
            HStack(alignment: .firstTextBaseline) {
                Text("ИТОГО").font(.title3.bold())
                Spacer()
                Text(CoalMath.rubles(v.total))
                    .font(.largeTitle.bold().monospacedDigit())
                    .foregroundStyle(Theme.primary)
                    .accessibilityIdentifier("saleTotal")
            }
        }
        .font(.body)
        .card()
    }

    // MARK: Today's sales

    @ViewBuilder
    private var salesList: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Продажи за сегодня").font(.title2.bold())
            if todaySales.isEmpty {
                Text("Сегодня продаж пока нет.").foregroundStyle(.secondary)
            }
            ForEach(todaySales) { sale in
                Button {
                    if isClosed { message = CoalError.dayClosed(today).errorDescription } else { editing = sale }
                } label: {
                    CoalSaleCard(sale: sale)
                }
                .buttonStyle(.plain)
                .contextMenu {
                    if !isClosed {
                        Button("Изменить") { editing = sale }
                        Button("Отменить продажу", role: .destructive) { cancelling = sale }
                    }
                }
            }
            if !todaySales.isEmpty && !isClosed {
                Text("Нажмите на продажу, чтобы исправить или отменить её.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: Pieces

    private func caption(_ s: String) -> some View {
        Text(s).font(.headline).foregroundStyle(.secondary)
    }

    private func tile(title: String, subtitle: String, selected: Bool, color: Color, id: String,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(2).minimumScaleFactor(0.8)
                Text(subtitle).font(.subheadline).opacity(0.85).lineLimit(1)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .foregroundStyle(selected ? .white : .primary)
            .background(selected ? color : Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(color.opacity(selected ? 0 : 0.35), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier(id)
    }

    /// The draft's messages in the words of this screen (the truck is chosen with one row of buttons).
    private func errors(_ d: CoalSaleDraft) -> [String] {
        d.validationErrors.map {
            switch $0 {
            case "Выберите водителя наёмной машины": return "Выберите машину: свою или водителя"
            case "Укажите оплату водителю, например 1000": return "Выберите, куда везти"
            default: return $0
            }
        }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
    }

    // MARK: Actions

    private func refreshDay() {
        let now = Calendar.farm.dayStart(AppClock.now())
        if now != today {
            today = now
            draft = CoalSaleService(context: context).newDraft()
        }
    }

    private func save(_ d: CoalSaleDraft) {
        showErrors = true
        guard d.isValid else { return }
        do {
            var toSave = d
            toSave.saleDate = AppClock.now()
            let sale = try CoalSaleService(context: context).save(toSave)
            // A clean form for the next sale.
            draft = CoalSaleService(context: context).newDraft()
            showErrors = false
            savedNote = "Сохранено: \(CoalMath.tonsText(sale.kilograms)), \(sale.locationNameSnapshot), \(CoalMath.rubles(sale.totalAmount))"
        } catch {
            message = error.localizedDescription
        }
    }

    private func cancel(_ sale: CoalSale) {
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
