import SwiftUI
import SwiftData

/// «Новая продажа» (or editing a saved one): tons, village, truck, driver, the amounts recomputed on every
/// change, «СОХРАНИТЬ ПРОДАЖУ».
struct CoalSaleFormView: View {
    /// nil = a new sale.
    let sale: CoalSale?

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query(filter: #Predicate<DeliveryLocation> { $0.isActive == true }) private var locationRows: [DeliveryLocation]
    @Query(filter: #Predicate<Driver> { $0.isArchived == false }) private var driverRows: [Driver]
    @State private var draft: CoalSaleDraft?
    @State private var search = ""
    @State private var addingDriver = false
    @State private var showErrors = false
    @State private var confirmingDelete = false
    @State private var message: String?
    @State private var isSaving = false

    private static let quickTons = ["0,5", "1", "1,5", "2", "2,5", "3"]
    private let twoColumns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    private var locations: [DeliveryLocation] {
        locationRows.sorted { $0.name.compare($1.name, locale: Locale(identifier: "ru_RU")) == .orderedAscending }
    }

    private var drivers: [Driver] {
        driverRows.filter { DriverService.matches($0, search) }.sorted {
            $0.fullName.compare($1.fullName, locale: Locale(identifier: "ru_RU")) == .orderedAscending
        }
    }

    var body: some View {
        Group {
            if let binding = Binding($draft) {
                form(binding)
            } else {
                Color.clear
            }
        }
        .navigationTitle(sale == nil ? "Новая продажа" : "Продажа")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
        }
        .keyboardDoneButton()
        .onAppear {
            guard draft == nil else { return }
            if let sale {
                draft = CoalSaleDraft(sale)
            } else {
                CoalSettingsService(context: context).current()
                draft = CoalSaleService(context: context).newDraft()
            }
        }
        .sheet(isPresented: $addingDriver) {
            NavigationStack {
                DriverFormView(driver: nil) { newDriver in draft?.choose(newDriver) }
            }
        }
        .confirmationDialog("Удалить продажу?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Удалить", role: .destructive) { deleteSale() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Продажа исчезнет из итогов дня и из расчётов с водителем.")
        }
        .coalErrorAlert($message)
    }

    private func form(_ d: Binding<CoalSaleDraft>) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                tonsSection(d)
                locationSection(d)
                vehicleSection(d)
                if d.wrappedValue.vehicle == .hired { driverSection(d) }
                summaryCard(d)
                VStack(alignment: .leading, spacing: 6) {
                    sectionTitle("Комментарий")
                    TextField("Например, покупатель или адрес", text: d.comment, axis: .vertical)
                        .lineLimit(1...4)
                        .padding(12)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                if sale != nil {
                    DatePicker("Дата и время", selection: d.saleDate)
                        .padding(12)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                if showErrors && !d.wrappedValue.isValid {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(d.wrappedValue.validationErrors, id: \.self) { e in
                            Label(e, systemImage: "exclamationmark.circle").foregroundStyle(.red)
                        }
                    }
                    .font(.subheadline)
                }

                Button("СОХРАНИТЬ ПРОДАЖУ") { save(d.wrappedValue) }
                    .buttonStyle(BigButtonStyle())
                    .disabled(isSaving)

                if sale != nil {
                    Button("Удалить продажу", role: .destructive) { confirmingDelete = true }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.background)
    }

    // MARK: Sections

    private func sectionTitle(_ s: String) -> some View {
        Text(s).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
    }

    private func tonsSection(_ d: Binding<CoalSaleDraft>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("Количество угля, тонн")
            HStack {
                TextField("Например, 1,5", text: d.tonsText)
                    .keyboardType(.decimalPad)
                    .font(.title2.monospacedDigit().bold())
                    .accessibilityIdentifier("tonsField")
                Text("т").font(.title3).foregroundStyle(.secondary)
            }
            .padding(12)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.quickTons, id: \.self) { t in
                        let selected = d.wrappedValue.kilograms == CoalMath.parseTons(t)
                        Button("\(t) т") { d.wrappedValue.tonsText = t }
                            .font(.headline)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .foregroundStyle(selected ? .white : Theme.primary)
                            .background(selected ? Theme.primary : Theme.card, in: Capsule())
                            .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func locationSection(_ d: Binding<CoalSaleDraft>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("Населённый пункт")
            if locations.isEmpty && d.wrappedValue.locationName.isEmpty {
                Text("Добавьте населённые пункты на вкладке «Цены».").foregroundStyle(.secondary)
            }
            LazyVGrid(columns: twoColumns, spacing: 10) {
                // A saved sale's village that was removed from the list stays choosable as it was.
                if !d.wrappedValue.locationName.isEmpty,
                   !locations.contains(where: { $0.name == d.wrappedValue.locationName }) {
                    choiceTile(title: d.wrappedValue.locationName,
                               subtitle: "доставка " + CoalMath.rubles(d.wrappedValue.listDeliveryPrice),
                               selected: true, color: Theme.primary) {}
                }
                ForEach(locations) { l in
                    choiceTile(title: l.name, subtitle: "доставка " + CoalMath.rubles(l.deliveryPriceKopecks),
                               selected: d.wrappedValue.locationName == l.name, color: Theme.primary) {
                        d.wrappedValue.choose(l)
                    }
                }
            }
        }
    }

    private func vehicleSection(_ d: Binding<CoalSaleDraft>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("Машина")
            HStack(spacing: 10) {
                choiceTile(title: "Наёмная", subtitle: "водителю начисляется доставка",
                           selected: d.wrappedValue.vehicle == .hired, color: CoalTheme.hired) {
                    d.wrappedValue.vehicle = .hired
                }
                choiceTile(title: "Собственная КФХ", subtitle: "доставка — доход хозяйства",
                           selected: d.wrappedValue.vehicle == .own, color: CoalTheme.own) {
                    d.wrappedValue.vehicle = .own
                }
            }
        }
    }

    private func driverSection(_ d: Binding<CoalSaleDraft>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("Водитель")
            if driverRows.count > 6 {
                TextField("Поиск по фамилии", text: $search)
                    .textFieldStyle(.roundedBorder)
            }
            LazyVGrid(columns: twoColumns, spacing: 10) {
                ForEach(drivers) { driver in
                    DriverChoiceButton(driver: driver, isSelected: d.wrappedValue.driverId == driver.id) {
                        d.wrappedValue.choose(driver)
                    }
                }
            }
            if let id = d.wrappedValue.driverId, !driverRows.contains(where: { $0.id == id }) {
                Text("Выбран: \(d.wrappedValue.driverName) (в архиве)").font(.subheadline).foregroundStyle(.secondary)
            }
            Button {
                addingDriver = true
            } label: {
                Label("Добавить водителя", systemImage: "person.badge.plus")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            HStack {
                Text("Оплата водителю за доставку")
                Spacer()
                RublesField(title: "1000", text: d.driverPaymentText)
                    .frame(maxWidth: 140)
            }
            .padding(12)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }

    private func summaryCard(_ d: Binding<CoalSaleDraft>) -> some View {
        let v = d.wrappedValue
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Уголь")
                Spacer()
                Text("\(CoalMath.tonsText(v.kilograms ?? 0)) × \(CoalMath.rubles(v.pricePerTon))")
                    .foregroundStyle(.secondary)
                    .font(.subheadline.monospacedDigit())
            }
            CoalMoneyRow(label: "Стоимость угля", amount: v.coalAmount)
            CoalMoneyRow(label: "Стоимость доставки", amount: v.deliveryPrice)
            Divider()
            HStack(alignment: .firstTextBaseline) {
                Text("ИТОГО К ОПЛАТЕ").font(.headline)
                Spacer()
                Text(CoalMath.rubles(v.total))
                    .font(.title.bold().monospacedDigit())
                    .foregroundStyle(Theme.primary)
                    .accessibilityIdentifier("saleTotal")
            }
            if v.isManual && v.isAdjusted {
                Text("По прайсу: \(CoalMath.rubles(v.listTotal))").font(.caption).foregroundStyle(Theme.accent)
            }
            HStack(spacing: 6) {
                Image(systemName: v.vehicle == .hired ? "truck.box" : "house")
                if v.vehicle == .hired {
                    Text(v.driverId == nil ? "Водитель не выбран" : "Водитель: \(v.driverName)")
                    Spacer()
                    Text("к выплате \(CoalMath.rubles(v.driverPayment))").font(.subheadline.monospacedDigit())
                } else {
                    Text("Собственная машина КФХ — выплат водителю нет")
                }
            }
            .font(.subheadline)
            .foregroundStyle(CoalTheme.color(v.vehicle))

            Toggle("Изменить цену вручную", isOn: Binding(
                get: { d.wrappedValue.isManual },
                set: { on in
                    if on { d.wrappedValue.startManual() } else { d.wrappedValue.isManual = false }
                }))
            if v.isManual {
                HStack {
                    Text("Уголь, факт")
                    Spacer()
                    RublesField(title: "0", text: d.manualCoalText).frame(maxWidth: 160)
                }
                HStack {
                    Text("Доставка, факт")
                    Spacer()
                    RublesField(title: "0", text: d.manualDeliveryText).frame(maxWidth: 160)
                }
            }
        }
        .card()
    }

    private func choiceTile(title: String, subtitle: String, selected: Bool, color: Color,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(2).minimumScaleFactor(0.8)
                Text(subtitle).font(.caption).opacity(0.85).lineLimit(2)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .foregroundStyle(selected ? .white : .primary)
            .background(selected ? color : Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(color.opacity(selected ? 0 : 0.35), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Actions

    private func save(_ d: CoalSaleDraft) {
        showErrors = true
        guard d.isValid, !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        do {
            let service = CoalSaleService(context: context)
            if let sale {
                try service.update(sale, with: d)
            } else {
                try service.save(d)
            }
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }

    private func deleteSale() {
        guard let sale else { return }
        do {
            try CoalSaleService(context: context).delete(sale)
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }
}
