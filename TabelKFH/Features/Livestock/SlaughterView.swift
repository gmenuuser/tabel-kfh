import SwiftUI
import SwiftData

/// «Записать забой»: species, category (with what is left), heads, date, reason, optional weights,
/// «СОХРАНИТЬ ЗАБОЙ» → «Будет списано: 2 головы КРС. Продолжить?».
struct SlaughterView: View {
    @Environment(\.modelContext) private var context
    @Query private var categories: [LivestockCategory]
    @Query private var operations: [LivestockOperation]
    @State private var species: Species = .cattle
    @State private var draft = LivestockDraft(kind: .slaughter, date: AppClock.now())
    @State private var confirming = false
    @State private var showErrors = false
    @State private var saved: String?
    @State private var message: String?

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]
    private static let quickHeads = ["1", "2", "3", "5"]

    private var shown: [LivestockCategory] {
        categories.filter { $0.species == species }.sorted { ($0.sortOrder, $0.name) < ($1.sortOrder, $1.name) }
    }

    private var category: LivestockCategory? { categories.first { $0.id == draft.categoryId } }

    var body: some View {
        let counts = HerdCounts(categories: categories, operations: operations)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                caption("1. Вид животного")
                HStack(spacing: 8) {
                    ForEach(Species.allCases, id: \.self) { s in
                        Button {
                            species = s
                            draft.categoryId = nil
                        } label: {
                            VStack(spacing: 2) {
                                Text(s.title).font(.headline)
                                Text("\(counts.heads(s))").font(.caption.monospacedDigit())
                            }
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .foregroundStyle(species == s ? .white : LivestockTheme.color(s))
                            .background(species == s ? LivestockTheme.color(s) : Theme.card,
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Вид \(s.title)")
                    }
                }

                caption("2. Категория")
                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(shown) { c in
                        let selected = draft.categoryId == c.id
                        Button { draft.categoryId = c.id } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(c.name).font(.headline).lineLimit(1).minimumScaleFactor(0.8)
                                Text("есть \(LivestockText.heads(counts.heads(c)))").font(.subheadline).opacity(0.85)
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
                            .foregroundStyle(selected ? .white : .primary)
                            .background(selected ? LivestockTheme.color(species) : Theme.card,
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                        .buttonStyle(.plain)
                        .disabled(counts.heads(c) == 0)
                        .opacity(counts.heads(c) == 0 ? 0.5 : 1)
                        .accessibilityLabel("Категория \(c.name)")
                    }
                }

                caption("3. Сколько голов")
                HStack {
                    TextField("0", text: $draft.headsText)
                        .keyboardType(.numberPad)
                        .font(.system(size: 34, weight: .bold).monospacedDigit())
                        .accessibilityIdentifier("slaughterHeads")
                    ForEach(Self.quickHeads, id: \.self) { h in
                        Button(h) { draft.headsText = h }
                            .font(.headline)
                            .frame(width: 44, height: 44)
                            .background(Theme.background, in: Circle())
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                caption("4. Дата забоя")
                DatePicker("Дата", selection: $draft.date)
                    .padding(12)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                caption("5. Причина")
                HStack(spacing: 8) {
                    ForEach(SlaughterReason.allCases, id: \.self) { r in
                        Button(r.title) { draft.reason = r }
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .foregroundStyle(draft.reason == r ? .white : LivestockTheme.slaughter)
                            .background(draft.reason == r ? LivestockTheme.slaughter : Theme.card,
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .buttonStyle(.plain)
                    }
                }

                DisclosureGroup("Вес и комментарий (необязательно)") {
                    VStack(spacing: 10) {
                        weight("Общий живой вес, кг", $draft.liveWeightText)
                        weight("Вес после забоя, кг", $draft.carcassWeightText)
                        weight("Вес мяса, кг", $draft.meatWeightText)
                        TextField("Комментарий", text: $draft.comment, axis: .vertical)
                            .lineLimit(1...3)
                            .textFieldStyle(.roundedBorder)
                    }
                    .padding(.top, 8)
                }
                .padding(12)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                if showErrors && !draft.isValid {
                    ForEach(draft.validationErrors, id: \.self) {
                        Label($0, systemImage: "exclamationmark.circle").foregroundStyle(.red).font(.subheadline)
                    }
                }

                Button("СОХРАНИТЬ ЗАБОЙ") {
                    showErrors = true
                    if draft.isValid { confirming = true }
                }
                .buttonStyle(BigButtonStyle(color: LivestockTheme.slaughter))
                if let saved {
                    Label(saved, systemImage: "checkmark.circle.fill").foregroundStyle(Theme.primary)
                }
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .keyboardDoneButton()
        .background(Theme.background)
        .navigationTitle("Записать забой")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(question, isPresented: $confirming, titleVisibility: .visible) {
            Button("Продолжить", role: .destructive) { save() }
            Button("Отмена", role: .cancel) {}
        }
        .alert("Не получилось", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(message ?? "") }
    }

    private var question: String {
        "Будет списано: \(LivestockText.heads(draft.heads ?? 0)) \(species.title)"
            + (category.map { " (\($0.name))" } ?? "") + ". Продолжить?"
    }

    private func caption(_ s: String) -> some View {
        Text(s).font(.headline).foregroundStyle(.secondary)
    }

    private func weight(_ title: String, _ text: Binding<String>) -> some View {
        HStack {
            Text(title).font(.subheadline)
            TextField("—", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 120)
        }
    }

    private func save() {
        do {
            let o = try LivestockService(context: context).save(draft)
            let name = category?.name ?? ""
            saved = "Записано: забой \(LivestockText.heads(o.heads)) \(species.title) (\(name))"
            var next = LivestockDraft(kind: .slaughter, date: AppClock.now())
            next.reason = draft.reason
            draft = next
            showErrors = false
        } catch {
            message = error.localizedDescription
        }
    }
}
