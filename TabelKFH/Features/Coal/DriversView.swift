import SwiftUI
import SwiftData

/// «Водители»: hired drivers with what the farm owes each of them.
struct DriversView: View {

    @Query private var driverRows: [Driver]
    @Query private var sales: [CoalSale]
    @Query private var payments: [DriverPayment]
    @State private var adding = false
    @State private var search = ""
    @State private var showArchived = false
    @State private var deleting: Driver?
    @State private var message: String?
    @Environment(\.modelContext) private var context

    private var drivers: [Driver] {
        driverRows.filter { $0.isArchived == showArchived && DriverService.matches($0, search) }.sorted {
            $0.fullName.compare($1.fullName, locale: Locale(identifier: "ru_RU")) == .orderedAscending
        }
    }

    private var totalDebt: Int {
        driverRows.reduce(0) { $0 + DriverBalance(driverId: $1.id, sales: sales, payments: payments).debt }
    }

    var body: some View {
        List {
            Section {
                CoalMoneyRow(label: "Общий долг перед водителями", amount: totalDebt, bold: true, color: CoalTheme.hired)
                Picker("Показать", selection: $showArchived) {
                    Text("Работают").tag(false)
                    Text("Архив").tag(true)
                }
                .pickerStyle(.segmented)
            }
            Section {
                if drivers.isEmpty {
                    Text(showArchived ? "В архиве никого нет." : "Водителей пока нет. Нажмите «Добавить водителя».")
                        .foregroundStyle(.secondary)
                }
                ForEach(drivers) { d in
                    NavigationLink {
                        DriverDetailView(driver: d)
                    } label: {
                        DriverRow(driver: d, balance: DriverBalance(driverId: d.id, sales: sales, payments: payments))
                    }
                    .swipeActions {
                        Button("Удалить", role: .destructive) { deleting = d }
                            .onlyIfEditable(.driver)
                    }
                }
            }
            Section {
                Button {
                    adding = true
                } label: {
                    Label("Добавить водителя", systemImage: "person.badge.plus")
                }
                .onlyIfEditable(.driver)
            }
        }
        .searchable(text: $search, prompt: "Поиск по фамилии")
        .navigationTitle("Водители")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Добавить водителя")
                    .onlyIfEditable(.driver)
            }
        }
        .sheet(isPresented: $adding) {
            NavigationStack { DriverFormView(driver: nil) }
        }
        .confirmationDialog("Удалить водителя?", isPresented: Binding(get: { deleting != nil },
                                                                       set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { d in
            Button("Удалить", role: .destructive) {
                do { try DriverService(context: context).delete(d) } catch { message = error.localizedDescription }
            }
            Button("Отмена", role: .cancel) {}
        } message: { d in
            Text(d.fullName)
        }
        .coalErrorAlert($message)
    }
}

private struct DriverRow: View {
    let driver: Driver
    let balance: DriverBalance

    var body: some View {
        HStack(spacing: 12) {
            Text(driver.letter)
                .font(.headline)
                .frame(width: 40, height: 40)
                .background(CoalTheme.hired.opacity(0.15), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(driver.fullName).font(.headline)
                Text(CoalText.deliveries(balance.deliveries)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(CoalMath.rubles(balance.debt))
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(balance.debt > 0 ? CoalTheme.hired : .secondary)
                Text("долг").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

/// A driver: contacts, earned / paid / debt, «Выплатить водителю», deliveries and payments.
struct DriverDetailView: View {
    @Bindable var driver: Driver

    @Environment(\.modelContext) private var context
    @Query(sort: \CoalSale.saleDate, order: .reverse) private var allSales: [CoalSale]
    @Query(sort: \DriverPayment.paymentDate, order: .reverse) private var allPayments: [DriverPayment]
    @State private var editing = false
    @State private var paying = false
    @State private var deletingPayment: DriverPayment?
    @State private var message: String?

    private var sales: [CoalSale] { allSales.filter { $0.vehicleType == .hired && $0.driverId == driver.id } }
    private var payments: [DriverPayment] { allPayments.filter { $0.driverId == driver.id } }
    private var balance: DriverBalance { DriverBalance(driverId: driver.id, sales: allSales, payments: allPayments) }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(driver.fullName).font(.title3.bold())
                    if !driver.phone.isEmpty {
                        Link(driver.phone, destination: URL(string: "tel:\(driver.phone.filter { $0.isNumber || $0 == "+" })")
                             ?? URL(string: "tel:")!)
                    }
                    if !driver.comment.isEmpty { Text(driver.comment).foregroundStyle(.secondary) }
                    if driver.isArchived { Text("В архиве").foregroundStyle(Theme.accent) }
                }
            }
            Section("Расчёты") {
                LabeledContent("Доставок", value: "\(balance.deliveries)")
                CoalMoneyRow(label: "Заработано", amount: balance.earned)
                CoalMoneyRow(label: "Выплачено", amount: balance.paid)
                CoalMoneyRow(label: "Текущий долг", amount: balance.debt, bold: true, color: CoalTheme.hired)
                Button {
                    paying = true
                } label: {
                    Label("Выплатить водителю", systemImage: "banknote")
                }
                .disabled(balance.debt <= 0)
                .editable(.driverPayment)
            }
            Section("История доставок") {
                if sales.isEmpty { Text("Доставок нет").foregroundStyle(.secondary) }
                ForEach(sales) { s in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(CoalMath.tonsText(s.kilograms)) · \(s.locationNameSnapshot)")
                            Text(CoalText.dateTime(s.saleDate)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(CoalMath.rubles(s.driverPaymentAmount)).font(.body.monospacedDigit())
                    }
                }
            }
            Section("Выплаты") {
                if payments.isEmpty { Text("Выплат не было").foregroundStyle(.secondary) }
                ForEach(payments) { p in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(CoalText.dateTime(p.paymentDate))
                            if !p.comment.isEmpty { Text(p.comment).font(.caption).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        Text(CoalMath.rubles(p.amount)).font(.body.monospacedDigit())
                    }
                    .swipeActions {
                        Button("Удалить", role: .destructive) { deletingPayment = p }
                            .onlyIfEditable(.driverPayment)
                    }
                }
            }
            Section {
                Group {
                    if driver.isArchived {
                        Button("Вернуть из архива") { run { try DriverService(context: context).restore(driver) } }
                    } else {
                        Button("Убрать в архив") { run { try DriverService(context: context).archive(driver) } }
                    }
                }
                .editable(.driver)
            } footer: {
                Text("Архив скрывает водителя из выбора в новой продаже; его доставки, выплаты и долг сохраняются. "
                     + "Удалить (смахнуть влево в списке) можно только водителя без доставок и выплат.")
            }
        }
        .navigationTitle(driver.lastName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) { Button("Изменить") { editing = true }.onlyIfEditable(.driver) }
        }
        .sheet(isPresented: $editing) {
            NavigationStack { DriverFormView(driver: driver) }
        }
        .sheet(isPresented: $paying) {
            NavigationStack { DriverPaymentFormView(driver: driver, debt: balance.debt) }
        }
        .confirmationDialog("Удалить выплату?", isPresented: Binding(get: { deletingPayment != nil },
                                                                      set: { if !$0 { deletingPayment = nil } }),
                            titleVisibility: .visible, presenting: deletingPayment) { p in
            Button("Удалить", role: .destructive) { run { try DriverPaymentService(context: context).delete(p) } }
            Button("Отмена", role: .cancel) {}
        } message: { p in
            Text("\(CoalMath.rubles(p.amount)) от \(CoalText.date(p.paymentDate)). Долг перед водителем увеличится.")
        }
        .coalErrorAlert($message)
    }

    private func run(_ action: () throws -> Void) {
        do { try action() } catch { message = error.localizedDescription }
    }
}

/// Add or edit a driver: фамилия (required), имя, отчество, телефон, комментарий.
struct DriverFormView: View {
    let driver: Driver?
    /// Called with the new driver after adding one.
    var onAdded: (Driver) -> Void = { _ in }

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var draft = DriverDraft()
    @State private var loaded = false
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                TextField("Фамилия", text: $draft.lastName).textContentType(.familyName)
                TextField("Имя", text: $draft.firstName).textContentType(.givenName)
                TextField("Отчество", text: $draft.middleName).textContentType(.middleName)
            } footer: {
                if !draft.isValid { Text("Фамилия обязательна: по ней строятся кнопки выбора водителя.") }
            }
            Section {
                TextField("Телефон", text: $draft.phone)
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                TextField("Комментарий", text: $draft.comment, axis: .vertical).lineLimit(1...4)
            }
        }
        .navigationTitle(driver == nil ? "Новый водитель" : "Водитель")
        .navigationBarTitleDisplayMode(.inline)
        .keyboardDoneButton()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Сохранить", action: save).disabled(!draft.isValid).editable(.driver)
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            if let driver { draft = DriverDraft(driver) }
        }
        .coalErrorAlert($message)
    }

    private func save() {
        do {
            let service = DriverService(context: context)
            if let driver {
                try service.update(driver, with: draft)
            } else {
                onAdded(try service.add(draft))
            }
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }
}

/// «Выплатить водителю»: amount (the whole debt by default), date, comment.
struct DriverPaymentFormView: View {
    let driver: Driver
    let debt: Int

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var amountText = ""
    @State private var date = AppClock.now()
    @State private var comment = ""
    @State private var paymentId = UUID()
    @State private var message: String?

    private var amount: Int? { CoalMath.parseRubles(amountText) }

    var body: some View {
        Form {
            Section {
                LabeledContent("Водитель", value: driver.fullName)
                CoalMoneyRow(label: "Долг сейчас", amount: debt, bold: true, color: CoalTheme.hired)
            }
            Section {
                RublesField(title: "Сумма выплаты", text: $amountText)
                    .accessibilityIdentifier("paymentAmount")
                DatePicker("Дата выплаты", selection: $date)
                TextField("Комментарий", text: $comment)
            } footer: {
                if let amount, amount > 0, amount <= debt {
                    Text("Останется долг: \(CoalMath.rubles(debt - amount)). Он перейдёт на следующие дни.")
                }
            }
        }
        .navigationTitle("Выплата водителю")
        .navigationBarTitleDisplayMode(.inline)
        .keyboardDoneButton()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Выплатить", action: pay).disabled((amount ?? 0) <= 0).editable(.driverPayment)
            }
        }
        .onAppear {
            if amountText.isEmpty { amountText = CoalMath.rublesField(max(0, debt)) }
        }
        .coalErrorAlert($message)
    }

    private func pay() {
        guard let amount else { return }
        do {
            try DriverPaymentService(context: context).pay(driver, amount: amount, date: date, comment: comment,
                                                           id: paymentId)
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }
}
