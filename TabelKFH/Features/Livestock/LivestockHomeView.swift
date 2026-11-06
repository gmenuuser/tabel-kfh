import SwiftUI
import SwiftData

/// Colours of the livestock module: КРС green, козы blue, свиньи orange, забой red.
enum LivestockTheme {
    static let slaughter = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.93, green: 0.40, blue: 0.38, alpha: 1)
            : UIColor(red: 0.78, green: 0.16, blue: 0.16, alpha: 1)
    })
    static let module = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.70, green: 0.50, blue: 0.36, alpha: 1)
            : UIColor(red: 0.52, green: 0.33, blue: 0.20, alpha: 1)
    })

    static func color(_ s: Species) -> Color {
        switch s {
        case .cattle: return Theme.primary
        case .goats: return CoalTheme.hired
        case .pigs: return Theme.accent
        }
    }

    static func icon(_ s: Species) -> String {
        switch s {
        case .cattle: return "c.circle.fill"
        case .goats: return "k.circle.fill"
        case .pigs: return "s.circle.fill"
        }
    }
}

/// Current heads of every category and species, read from all operations.
struct HerdCounts {
    let byCategory: [UUID: Int]
    let bySpecies: [Species: Int]

    init(categories: [LivestockCategory], operations: [LivestockOperation]) {
        let b = LivestockLedger.balances(operations.map(HerdMove.init))
        byCategory = b
        var s: [Species: Int] = [:]
        for c in categories { s[c.species, default: 0] += b[c.id] ?? 0 }
        bySpecies = s
    }

    var total: Int { bySpecies.values.reduce(0, +) }
    func heads(_ c: LivestockCategory) -> Int { byCategory[c.id] ?? 0 }
    func heads(_ s: Species) -> Int { bySpecies[s] ?? 0 }
}

/// «Скот и забой»: the herd now and four large sections.
struct LivestockHomeView: View {
    enum Part: Hashable { case herd, slaughter, history, reports }

    @Environment(\.modelContext) private var context
    @Query private var categories: [LivestockCategory]
    @Query private var operations: [LivestockOperation]
    @State private var section: Part?

    var body: some View {
        let counts = HerdCounts(categories: categories, operations: operations)
        ScrollView {
            VStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Поголовье сейчас").font(.headline)
                    HStack(spacing: 8) {
                        ForEach(Species.allCases, id: \.self) { s in
                            VStack(spacing: 2) {
                                Text("\(counts.heads(s))")
                                    .font(.system(size: 34, weight: .bold).monospacedDigit())
                                    .foregroundStyle(LivestockTheme.color(s))
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.5)
                                Text(s.title).font(.subheadline.weight(.semibold))
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Theme.background, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                    HStack {
                        Text("Всего животных").font(.headline)
                        Spacer()
                        Text(LivestockText.heads(counts.total)).font(.title3.bold().monospacedDigit())
                    }
                }
                .card()
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("herdSummary")

                big("Поголовье", subtitle: "Категории, добавить и уменьшить", icon: "list.number",
                    color: Theme.primary) { section = .herd }
                big("Записать забой", subtitle: "Списать забитых животных", icon: "scissors",
                    color: LivestockTheme.slaughter) { section = .slaughter }
                big("История операций", subtitle: "Все движения, исправление и удаление", icon: "clock.arrow.circlepath",
                    color: CoalTheme.hired) { section = .history }
                big("Отчёты по скоту", subtitle: "Остаток, забой, движение — PDF и Excel", icon: "doc.text.fill",
                    color: Color(uiColor: .systemGray)) { section = .reports }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Скот и забой")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $section) { s in
            switch s {
            case .herd: HerdView()
            case .slaughter: SlaughterView()
            case .history: LivestockHistoryView()
            case .reports: LivestockReportsView()
            }
        }
        .onAppear { LivestockService(context: context).ensureDefaults() }
    }

    private func big(_ title: String, subtitle: String, icon: String, color: Color,
                     action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.title2.weight(.semibold))
                    .frame(width: 52, height: 52)
                    .background(.white.opacity(0.2), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.title3.bold())
                    Text(subtitle).font(.subheadline).opacity(0.9).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.headline)
            }
            .foregroundStyle(.white)
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 84, alignment: .leading)
            .background(color, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityAddTraits(.isButton)
    }
}

/// «Поголовье»: one species at a time; every category with its heads and «Добавить», «Уменьшить», «История».
struct HerdView: View {
    @Environment(\.modelContext) private var context
    @Query private var categories: [LivestockCategory]
    @Query private var operations: [LivestockOperation]
    @State private var species: Species = .cattle
    @State private var form: OperationTarget?
    @State private var history: LivestockCategory?
    @State private var addingCategory = false
    @State private var newCategory = ""
    @State private var message: String?

    struct OperationTarget: Identifiable {
        let id = UUID()
        let draft: LivestockDraft
    }

    private var shown: [LivestockCategory] {
        categories.filter { $0.species == species }.sorted { ($0.sortOrder, $0.name) < ($1.sortOrder, $1.name) }
    }

    var body: some View {
        let counts = HerdCounts(categories: categories, operations: operations)
        ScrollView {
            VStack(spacing: 12) {
                Picker("Вид", selection: $species) {
                    ForEach(Species.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                HStack {
                    Text("Всего: \(LivestockText.heads(counts.heads(species)))").font(.title3.bold())
                        .foregroundStyle(LivestockTheme.color(species))
                    Spacer()
                }
                ForEach(shown) { c in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(c.name).font(.title3.weight(.semibold))
                            Spacer()
                            Text("\(counts.heads(c))")
                                .font(.system(size: 34, weight: .bold).monospacedDigit())
                                .foregroundStyle(LivestockTheme.color(species))
                                .accessibilityIdentifier("heads-\(c.name)")
                        }
                        HStack(spacing: 8) {
                            Button {
                                form = OperationTarget(draft: LivestockDraft(kind: .purchase, date: AppClock.now(), categoryId: c.id))
                            } label: { Label("Добавить", systemImage: "plus").frame(maxWidth: .infinity, minHeight: 44) }
                                .buttonStyle(.borderedProminent)
                                .tint(Theme.primary)
                                .accessibilityLabel("Добавить \(c.name)")
                            Button {
                                form = OperationTarget(draft: LivestockDraft(kind: .sale, date: AppClock.now(), categoryId: c.id))
                            } label: { Label("Уменьшить", systemImage: "minus").frame(maxWidth: .infinity, minHeight: 44) }
                                .buttonStyle(.bordered)
                                .tint(LivestockTheme.slaughter)
                                .disabled(counts.heads(c) == 0)
                                .accessibilityLabel("Уменьшить \(c.name)")
                            Button { history = c } label: {
                                Image(systemName: "clock.arrow.circlepath").frame(width: 30, height: 44)
                            }
                            .buttonStyle(.bordered)
                            .accessibilityLabel("История \(c.name)")
                        }
                    }
                    .card()
                    .contextMenu {
                        if !c.isBuiltIn {
                            Button("Удалить категорию", role: .destructive) { delete(c) }
                        }
                    }
                }
                Button { addingCategory = true } label: {
                    Label("Своя категория", systemImage: "plus.square.on.square").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Поголовье")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $form) { t in
            NavigationStack { OperationFormView(operation: nil, initial: t.draft) }
        }
        .navigationDestination(item: $history) { c in LivestockHistoryView(categoryId: c.id) }
        .alert("Новая категория (\(species.title))", isPresented: $addingCategory) {
            TextField("Название", text: $newCategory)
            Button("Добавить") { addCategory() }
            Button("Отмена", role: .cancel) { newCategory = "" }
        }
        .alert("Не получилось", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(message ?? "") }
    }

    private func addCategory() {
        do { try LivestockService(context: context).addCategory(newCategory, species: species) } catch {
            message = error.localizedDescription
        }
        newCategory = ""
    }

    private func delete(_ c: LivestockCategory) {
        do { try LivestockService(context: context).deleteCategory(c) } catch { message = error.localizedDescription }
    }
}
