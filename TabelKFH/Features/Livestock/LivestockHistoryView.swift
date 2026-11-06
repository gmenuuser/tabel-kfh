import SwiftUI
import SwiftData

/// «История операций»: every movement with filters by species, category, type and period; a tap opens it
/// for correction or deletion.
struct LivestockHistoryView: View {
    /// Opened from a category's «История»: only that category at first.
    var categoryId: UUID?

    @Query(sort: \LivestockOperation.date, order: .reverse) private var operations: [LivestockOperation]
    @Query private var categories: [LivestockCategory]
    @Query private var slaughters: [SlaughterRecord]
    @State private var species: Species?
    @State private var category: UUID?
    @State private var kind: LivestockOperationKind?
    @State private var period: LivestockPeriod?
    @State private var day = AppClock.now()
    @State private var editing: LivestockOperation?
    @State private var loaded = false

    private var range: (Date, Date)? {
        guard let period else { return nil }
        let component: Calendar.Component = period == .day ? .day : period == .month ? .month : .year
        guard let i = Calendar.farm.dateInterval(of: component, for: day) else { return nil }
        return (i.start, i.end)
    }

    private var filtered: [LivestockOperation] {
        let byId = Dictionary(categories.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        return operations.filter { o in
            if let category, o.categoryId != category && o.targetCategoryId != category { return false }
            if let species, byId[o.categoryId]?.species != species { return false }
            if let kind, o.kind != kind { return false }
            if let r = range, !(o.date >= r.0 && o.date < r.1) { return false }
            return true
        }
    }

    var body: some View {
        let byId = Dictionary(categories.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let details = Dictionary(slaughters.map { ($0.operationId, $0) }, uniquingKeysWith: { a, _ in a })
        List {
            Section("Отбор") {
                Picker("Вид", selection: $species) {
                    Text("Все").tag(Species?.none)
                    ForEach(Species.allCases, id: \.self) { Text($0.title).tag(Optional($0)) }
                }
                if let species {
                    Picker("Категория", selection: $category) {
                        Text("Все").tag(UUID?.none)
                        ForEach(categories.filter { $0.species == species }.sorted { $0.sortOrder < $1.sortOrder }) {
                            Text($0.name).tag(Optional($0.id))
                        }
                    }
                }
                Picker("Операция", selection: $kind) {
                    Text("Все").tag(LivestockOperationKind?.none)
                    ForEach(LivestockOperationKind.allCases, id: \.self) { Text($0.title).tag(Optional($0)) }
                }
                Picker("Период", selection: $period) {
                    Text("Всё время").tag(LivestockPeriod?.none)
                    Text("День").tag(Optional(LivestockPeriod.day))
                    Text("Месяц").tag(Optional(LivestockPeriod.month))
                    Text("Год").tag(Optional(LivestockPeriod.year))
                }
                if period != nil {
                    DatePicker("Дата", selection: $day, displayedComponents: .date)
                }
            }
            Section("Операции (\(filtered.count))") {
                if filtered.isEmpty { Text("Операций нет").foregroundStyle(.secondary) }
                ForEach(filtered) { o in
                    Button { editing = o } label: {
                        OperationRow(operation: o, category: byId[o.categoryId],
                                     target: o.targetCategoryId.flatMap { byId[$0] }, slaughter: details[o.id])
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .navigationTitle("История операций")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            if let categoryId, let c = categories.first(where: { $0.id == categoryId }) {
                species = c.species
                category = categoryId
            }
        }
        .onChange(of: species) { _, _ in
            if let category, categories.first(where: { $0.id == category })?.species != species { self.category = nil }
        }
        .sheet(item: $editing) { o in
            NavigationStack {
                OperationFormView(operation: o, initial: LivestockDraft(kind: o.kind, date: o.date, categoryId: o.categoryId))
            }
        }
    }
}

/// Date, species and category, type, ± heads, weights, comment.
struct OperationRow: View {
    let operation: LivestockOperation
    let category: LivestockCategory?
    let target: LivestockCategory?
    let slaughter: SlaughterRecord?

    private var sign: String { operation.kind.isAddition ? "+" : operation.kind == .transfer ? "→" : "−" }

    private var color: Color {
        if operation.kind == .slaughter { return LivestockTheme.slaughter }
        if operation.kind.isAddition { return Theme.primary }
        return operation.kind == .transfer ? CoalTheme.hired : Theme.accent
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(operation.kind.title).font(.headline).foregroundStyle(color)
                Text("\(category?.species.title ?? "") · \(category?.name ?? "?")"
                     + (target.map { " → \($0.name)" } ?? ""))
                    .font(.subheadline)
                Text(CoalText.dateTime(operation.date)).font(.caption).foregroundStyle(.secondary)
                if let s = slaughter {
                    let parts = [s.reason.title,
                                 s.liveWeightGrams.map { "живой \(LivestockText.kilograms($0))" },
                                 s.carcassWeightGrams.map { "после забоя \(LivestockText.kilograms($0))" },
                                 s.meatWeightGrams.map { "мясо \(LivestockText.kilograms($0))" }].compactMap { $0 }
                    Text(parts.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                }
                if !operation.comment.isEmpty {
                    Text(operation.comment).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text("\(sign)\(operation.heads)")
                .font(.title3.bold().monospacedDigit())
                .foregroundStyle(color)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

/// «Отчёты по скоту»: остаток поголовья, отчёт по забою, движение поголовья — on screen, PDF and Excel.
struct LivestockReportsView: View {
    enum Report: Hashable { case herd, slaughter, movement }

    @Environment(\.modelContext) private var context
    @Query private var operations: [LivestockOperation]
    @Query private var slaughters: [SlaughterRecord]
    @Query private var categories: [LivestockCategory]
    @State private var report: Report = .herd
    @State private var period: LivestockPeriod = .month
    @State private var day = AppClock.now()

    private var document: CoalReportDocument? {
        _ = (operations.count, slaughters.count, categories.count)
        let service = LivestockService(context: context)
        let farm = FarmSettingsStore.current(in: context).info
        let now = AppClock.now()
        switch report {
        case .herd:
            let end = Calendar.farm.date(byAdding: .day, value: 1, to: Calendar.farm.dayStart(day)) ?? day
            let balances = LivestockLedger.balances(operations.map(HerdMove.init), before: end)
            guard let cats = try? service.categories() else { return nil }
            let lines = cats.map { LivestockReportLayout.HerdLine(species: $0.species, name: $0.name, heads: balances[$0.id] ?? 0) }
            return LivestockReportLayout.herd(lines, on: day, farm: farm, createdAt: now)
        case .slaughter:
            let (start, end) = service.period(period, containing: day)
            guard let summary = try? service.slaughterSummary(from: start, to: end) else { return nil }
            let byId = Dictionary(categories.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            let details = Dictionary(slaughters.map { ($0.operationId, $0) }, uniquingKeysWith: { a, _ in a })
            let lines = operations.filter { $0.kind == .slaughter && $0.date >= start && $0.date < end }
                .sorted { $0.date < $1.date }
                .map { o in
                    LivestockReportLayout.SlaughterLine(
                        date: o.date, species: byId[o.categoryId]?.species ?? .cattle,
                        category: byId[o.categoryId]?.name ?? "?", heads: o.heads,
                        reason: details[o.id]?.reason.title ?? "", liveGrams: details[o.id]?.liveWeightGrams,
                        meatGrams: details[o.id]?.meatWeightGrams, comment: o.comment)
                }
            return LivestockReportLayout.slaughter(summary, lines: lines, period: period, start: start, farm: farm,
                                                   createdAt: now)
        case .movement:
            let (start, end) = service.period(period, containing: day)
            guard let rows = try? service.movement(from: start, to: end) else { return nil }
            return LivestockReportLayout.movement(rows, period: period, start: start, farm: farm, createdAt: now)
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 12) {
                    Picker("Отчёт", selection: $report) {
                        Text("Остаток").tag(Report.herd)
                        Text("Забой").tag(Report.slaughter)
                        Text("Движение").tag(Report.movement)
                    }
                    .pickerStyle(.segmented)
                    if report != .herd {
                        Picker("Период", selection: $period) {
                            Text("День").tag(LivestockPeriod.day)
                            Text("Месяц").tag(LivestockPeriod.month)
                            Text("Год").tag(LivestockPeriod.year)
                        }
                        .pickerStyle(.segmented)
                    }
                    DatePicker(report == .herd ? "На дату" : "Дата в периоде", selection: $day, displayedComponents: .date)
                        .font(.headline)
                }
                .card()
                if let document {
                    CoalExportActions(document: document)
                    ReportPreview(document: document)
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Отчёты по скоту")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The report's blocks on screen, simply: headings, figures and rows.
struct ReportPreview: View {
    let document: CoalReportDocument

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(document.blocks.enumerated()), id: \.offset) { _, block in
                switch block {
                case .title(let s): Text(s).font(.title3.bold())
                case .boldText(let s): Text(s).font(.headline)
                case .text(let s): Text(s)
                case .note(let s): Text(s).font(.caption).foregroundStyle(.secondary)
                case .heading(let s): Text(s).font(.headline).foregroundStyle(Theme.primary).padding(.top, 6)
                case .pairs(let pairs):
                    ForEach(Array(pairs.enumerated()), id: \.offset) { _, p in
                        HStack {
                            Text(p.label).font(p.bold ? .subheadline.bold() : .subheadline)
                            Spacer()
                            Text(p.value.text).font(.subheadline.monospacedDigit()).bold(p.bold)
                        }
                    }
                case .table(let t):
                    ScrollView(.horizontal, showsIndicators: false) {
                        Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 4) {
                            GridRow {
                                ForEach(Array(t.columns.enumerated()), id: \.offset) { _, c in
                                    Text(c.title).font(.caption.bold()).foregroundStyle(.secondary)
                                }
                            }
                            ForEach(Array((t.rows + (t.total.map { [$0] } ?? [])).enumerated()), id: \.offset) { i, row in
                                GridRow {
                                    ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                                        Text(cell.text).font(.footnote.monospacedDigit())
                                            .bold(t.total != nil && i == t.rows.count)
                                    }
                                }
                            }
                        }
                    }
                case .signature: EmptyView()
                }
            }
        }
        .card()
    }
}
