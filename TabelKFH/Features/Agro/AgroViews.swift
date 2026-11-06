import SwiftUI
import SwiftData
import UIKit

/// «Агрономические калькуляторы»: eight large buttons and the history.
struct AgroHomeView: View {
    enum Target: Hashable { case calculator(AgroKind), history }

    @Query private var saved: [AgroCalculation]
    @State private var target: Target?

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(AgroKind.allCases, id: \.self) { kind in
                        Button { target = .calculator(kind) } label: {
                            VStack(alignment: .leading, spacing: 10) {
                                Image(systemName: kind.icon).font(.title2.weight(.semibold))
                                Text(kind.title).font(.headline).multilineTextAlignment(.leading)
                                    .lineLimit(3).minimumScaleFactor(0.8)
                                Spacer(minLength: 0)
                            }
                            .foregroundStyle(.white)
                            .padding(14)
                            .frame(maxWidth: .infinity, minHeight: 116, alignment: .topLeading)
                            .background(Theme.primary, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(kind.title)
                        .accessibilityIdentifier("agro-\(kind.rawValue)")
                    }
                }
                Button { target = .history } label: {
                    Label("История расчётов (\(saved.count))", systemImage: "clock.arrow.circlepath")
                }
                .buttonStyle(BigButtonStyle(color: Theme.accent))
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Агрономические калькуляторы")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $target) { t in
            switch t {
            case .calculator(let kind): AgroCalculatorView(kind: kind, saved: nil)
            case .history: AgroHistoryView()
            }
        }
    }
}

/// One calculator: the fields with their units, the result right below, «Сохранить», «Очистить», copy and share.
/// A new calculation's inputs are kept as a draft when the screen is left; a saved one is corrected in place.
struct AgroCalculatorView: View {
    let kind: AgroKind
    /// Opened from the history: «Сохранить» corrects it.
    let saved: AgroCalculation?

    @Environment(\.modelContext) private var context
    @State private var inputs: [String: String] = [:]
    @State private var title = ""
    @State private var loaded = false
    @State private var note: String?
    @State private var message: String?

    private var result: AgroResult { AgroEngine.compute(kind, inputs) }
    private var draftKey: String { "agroDraft.\(kind.rawValue)" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(kind.choices, id: \.key) { choice in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(choice.title).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                        Picker(choice.title, selection: binding(choice.key, default: choice.defaultValue)) {
                            ForEach(choice.options, id: \.value) { Text($0.title).tag($0.value) }
                        }
                        .pickerStyle(.segmented)
                    }
                }
                if kind == .area { plots }
                ForEach(kind.fields(inputs), id: \.key) { field in
                    inputRow(field)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Поле или комментарий").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    TextField("Например, поле у реки", text: $title)
                        .padding(12)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                resultBlock
                HStack(spacing: 10) {
                    Button(saved == nil ? "Сохранить" : "Сохранить изменения", action: save)
                        .buttonStyle(BigButtonStyle())
                        .disabled(!result.isComplete)
                    Button("Очистить", action: clear)
                        .buttonStyle(BigButtonStyle(color: Color(uiColor: .systemGray)))
                }
                if saved != nil {
                    Button("Сохранить как новый расчёт") { save(asNew: true) }
                        .frame(maxWidth: .infinity)
                        .disabled(!result.isComplete)
                }
                if let note { Label(note, systemImage: "checkmark.circle.fill").foregroundStyle(Theme.primary) }
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .keyboardDoneButton()
        .background(Theme.background)
        .navigationTitle(kind.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if result.isComplete {
                    ShareLink(item: shareText) { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel("Поделиться")
                }
            }
        }
        .onAppear(perform: load)
        .onChange(of: inputs) { _, _ in storeDraft() }
        .onChange(of: title) { _, _ in storeDraft() }
        .alert("Не получилось", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(message ?? "") }
    }

    // MARK: Parts

    private func binding(_ key: String, default value: String = "") -> Binding<String> {
        Binding(get: { inputs[key] ?? value }, set: { inputs[key] = $0; note = nil })
    }

    private func inputRow(_ f: AgroField) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(f.title + (f.optional ? " (необязательно)" : ""))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
            HStack {
                TextField("0", text: binding(f.key))
                    .keyboardType(.decimalPad)
                    .font(.title2.bold().monospacedDigit())
                    .accessibilityIdentifier("field-\(f.key)")
                Text(f.unit).font(.title3).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }

    private var plots: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(1...AgroPlots.count(inputs), id: \.self) { i in
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Участок \(i)").font(.headline)
                        Spacer()
                        if i == AgroPlots.count(inputs) && i > 1 {
                            Button("Убрать", role: .destructive) { removePlot(i) }.font(.subheadline)
                        }
                    }
                    HStack(spacing: 8) {
                        plotField("Длина", AgroPlots.key(i, "length"), "м")
                        Text("×").font(.title3)
                        plotField("Ширина", AgroPlots.key(i, "width"), "м")
                    }
                    plotField("или площадь части", AgroPlots.key(i, "area"), "м²")
                }
                .card()
            }
            Button {
                inputs["plots"] = "\(AgroPlots.count(inputs) + 1)"
            } label: {
                Label("Добавить участок", systemImage: "plus").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
        }
    }

    private func plotField(_ title: String, _ key: String, _ unit: String) -> some View {
        HStack {
            TextField(title, text: binding(key))
                .keyboardType(.decimalPad)
                .font(.title3.monospacedDigit())
                .accessibilityIdentifier("field-\(key)")
            Text(unit).foregroundStyle(.secondary)
        }
        .padding(10)
        .background(Theme.background, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var resultBlock: some View {
        let r = result
        return VStack(alignment: .leading, spacing: 10) {
            Text("Результат").font(.headline)
            if !r.errors.isEmpty {
                ForEach(r.errors, id: \.self) { Label($0, systemImage: "exclamationmark.circle").foregroundStyle(.red) }
            } else if !r.missing.isEmpty {
                Text("Заполните: " + r.missing.joined(separator: ", ")).foregroundStyle(.secondary)
            }
            ForEach(Array(r.lines.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .firstTextBaseline) {
                    Text(line.title).font(line.main ? .headline : .body)
                    Spacer(minLength: 8)
                    Text(line.text)
                        .font(line.main ? .title2.bold().monospacedDigit() : .body.monospacedDigit())
                        .foregroundStyle(line.main ? Theme.primary : .primary)
                        .multilineTextAlignment(.trailing)
                }
                .accessibilityElement(children: .combine)
            }
            ForEach(r.warnings, id: \.self) {
                Label($0, systemImage: "info.circle").font(.footnote).foregroundStyle(Theme.accent)
            }
            if r.isComplete {
                Button {
                    UIPasteboard.general.string = shareText
                    note = "Результат скопирован"
                } label: { Label("Копировать результат", systemImage: "doc.on.doc") }
                    .font(.subheadline)
            }
        }
        .card()
        .accessibilityIdentifier("agroResult")
    }

    private var shareText: String { AgroEngine.shareText(kind, title: title, inputs: inputs, result: result) }

    // MARK: Actions

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let saved {
            inputs = saved.inputs
            title = saved.title
        } else if let data = UserDefaults.standard.data(forKey: draftKey),
                  let draft = try? JSONDecoder().decode([String: String].self, from: data) {
            title = draft["__title"] ?? ""
            inputs = draft.filter { $0.key != "__title" }
        }
    }

    /// The draft of a new calculation survives leaving the screen (per-device convenience only).
    private func storeDraft() {
        guard saved == nil, loaded else { return }
        var draft = inputs
        draft["__title"] = title
        UserDefaults.standard.set(AgroService.encode(draft), forKey: draftKey)
    }

    private func clear() {
        let keep = inputs.filter { key, _ in kind.choices.contains { $0.key == key } }
        inputs = keep
        title = ""
        note = nil
        if saved == nil { UserDefaults.standard.removeObject(forKey: draftKey) }
    }

    private func removePlot(_ i: Int) {
        for part in ["length", "width", "area"] { inputs[AgroPlots.key(i, part)] = nil }
        inputs["plots"] = "\(max(1, i - 1))"
    }

    private func save() { save(asNew: false) }

    private func save(asNew: Bool) {
        do {
            try AgroService(context: context).save(kind, inputs: inputs, title: title,
                                                   over: asNew ? nil : saved)
            note = saved == nil || asNew ? "Расчёт сохранён в истории" : "Изменения сохранены"
            if saved == nil { UserDefaults.standard.removeObject(forKey: draftKey) }
        } catch {
            message = error.localizedDescription
        }
    }
}

/// «История расчётов»: search, the calculator and the period; open, correct, delete; PDF and Excel of the list.
struct AgroHistoryView: View {
    @Environment(\.modelContext) private var context
    @Query(sort: \AgroCalculation.createdAt, order: .reverse) private var all: [AgroCalculation]
    @State private var search = ""
    @State private var kind: AgroKind?
    @State private var period: LivestockPeriod?
    @State private var day = AppClock.now()
    @State private var opened: AgroCalculation?
    @State private var deleting: AgroCalculation?

    private var items: [AgroCalculation] {
        _ = all.count
        var start: Date?, end: Date?
        if let period {
            let component: Calendar.Component = period == .day ? .day : period == .month ? .month : .year
            let i = Calendar.farm.dateInterval(of: component, for: day)
            start = i?.start
            end = i?.end
        }
        return (try? AgroService(context: context).history(kind: kind, search: search, from: start, to: end)) ?? []
    }

    var body: some View {
        let shown = items
        List {
            Section {
                Picker("Калькулятор", selection: $kind) {
                    Text("Все").tag(AgroKind?.none)
                    ForEach(AgroKind.allCases, id: \.self) { Text($0.title).tag(Optional($0)) }
                }
                Picker("Период", selection: $period) {
                    Text("Всё время").tag(LivestockPeriod?.none)
                    Text("День").tag(Optional(LivestockPeriod.day))
                    Text("Месяц").tag(Optional(LivestockPeriod.month))
                    Text("Год").tag(Optional(LivestockPeriod.year))
                }
                if period != nil { DatePicker("Дата", selection: $day, displayedComponents: .date) }
            }
            Section("Расчёты (\(shown.count))") {
                if shown.isEmpty { Text("Сохранённых расчётов нет").foregroundStyle(.secondary) }
                ForEach(shown) { c in
                    Button { opened = c } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack {
                                Text(c.kind.title).font(.headline)
                                Spacer()
                                Text(CoalText.dateTime(c.createdAt)).font(.caption).foregroundStyle(.secondary)
                            }
                            if !c.title.isEmpty { Text(c.title).font(.subheadline) }
                            if let main = c.lines.first(where: \.main) {
                                Text("\(main.title): \(main.text)").font(.subheadline.bold()).foregroundStyle(Theme.primary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .swipeActions { Button("Удалить", role: .destructive) { deleting = c } }
                }
            }
            Section {
                CoalExportActions(document: AgroReportLayout.history(shown, farm: FarmSettingsStore.current(in: context).info,
                                                                     createdAt: AppClock.now()))
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
        }
        .searchable(text: $search, prompt: "Поиск по полю или комментарию")
        .navigationTitle("История расчётов")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $opened) { c in AgroCalculatorView(kind: c.kind, saved: c) }
        .confirmationDialog("Удалить расчёт?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { c in
            Button("Удалить", role: .destructive) { try? AgroService(context: context).delete(c) }
            Button("Отмена", role: .cancel) {}
        } message: { c in
            Text("\(c.kind.title), \(CoalText.dateTime(c.createdAt))")
        }
    }
}
