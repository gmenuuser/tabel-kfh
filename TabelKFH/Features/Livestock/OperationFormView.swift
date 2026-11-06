import SwiftUI
import SwiftData

/// One operation: add (поступление, приплод, начальное поголовье), decrease (забой, продажа, падёж, перевод,
/// другое) or correct a saved one. The herd is checked before saving; a saved one can be deleted.
struct OperationFormView: View {
    /// nil = a new operation from `initial`.
    let operation: LivestockOperation?
    let initial: LivestockDraft

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query private var categories: [LivestockCategory]
    @Query private var operations: [LivestockOperation]
    @State private var draft: LivestockDraft?
    @State private var showErrors = false
    @State private var confirmingSlaughter = false
    @State private var confirmingDelete = false
    @State private var message: String?
    /// A new decrease starts without a reason: the owner must choose one.
    @State private var reasonChosen = false

    init(operation: LivestockOperation?, initial: LivestockDraft) {
        self.operation = operation
        self.initial = initial
    }

    var body: some View {
        Group {
            if let d = Binding($draft) { form(d) } else { Color.clear }
        }
        .navigationTitle(operation == nil ? (initial.kind.isAddition ? "Добавить" : "Уменьшить") : "Операция")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Сохранить") { trySave() }
            }
        }
        .keyboardDoneButton()
        .onAppear {
            guard draft == nil else { return }
            if let operation, let d = try? LivestockService(context: context).draft(for: operation) {
                draft = d
            } else {
                draft = initial
            }
        }
        .confirmationDialog(slaughterQuestion, isPresented: $confirmingSlaughter, titleVisibility: .visible) {
            Button("Продолжить") { save() }
            Button("Отмена", role: .cancel) {}
        }
        .confirmationDialog("Удалить операцию?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Удалить", role: .destructive) { deleteOperation() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Поголовье пересчитается так, как будто этой операции не было.")
        }
        .alert("Не получилось", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(message ?? "") }
    }

    private var category: LivestockCategory? { categories.first { $0.id == draft?.categoryId } }

    private var slaughterQuestion: String {
        guard let d = draft, let c = category else { return "" }
        return "Будет списано: \(LivestockText.heads(d.heads ?? 0)) \(c.species.title) (\(c.name)). Продолжить?"
    }

    private func form(_ d: Binding<LivestockDraft>) -> some View {
        let counts = HerdCounts(categories: categories, operations: operations)
        let species = category?.species ?? .cattle
        let kinds = (operation == nil ? (initial.kind.isAddition ? LivestockOperationKind.additions
                                                                  : LivestockOperationKind.decreases)
                                      : LivestockOperationKind.allCases)
        return Form {
            Section {
                if let c = category {
                    LabeledContent(c.species.title, value: c.name)
                    LabeledContent("Сейчас голов", value: "\(counts.heads(c))")
                }
                if needsReason {
                    Picker("Причина", selection: Binding<LivestockOperationKind?>(
                        get: { reasonChosen ? d.wrappedValue.kind : nil },
                        set: { if let k = $0 { d.wrappedValue.kind = k; reasonChosen = true } })) {
                        Text("Выберите").tag(LivestockOperationKind?.none)
                        ForEach(kinds, id: \.self) { Text($0.title).tag(Optional($0)) }
                    }
                    .accessibilityIdentifier("reasonPicker")
                } else {
                    Picker(initial.kind.isAddition && operation == nil ? "Как поступили" : "Тип", selection: d.kind) {
                        ForEach(kinds, id: \.self) { Text($0.title).tag($0) }
                    }
                }
                if operation != nil {
                    Picker("Категория", selection: d.categoryId) {
                        ForEach(categories.filter { $0.species == species }) { Text($0.name).tag(Optional($0.id)) }
                    }
                }
                if d.wrappedValue.kind == .transfer {
                    Picker("Перевести в", selection: d.targetCategoryId) {
                        Text("Выберите").tag(UUID?.none)
                        ForEach(categories.filter { $0.species == species && $0.id != d.wrappedValue.categoryId }) {
                            Text($0.name).tag(Optional($0.id))
                        }
                    }
                }
            }
            Section {
                HStack {
                    Text("Голов")
                    TextField("0", text: d.headsText)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .font(.title2.bold().monospacedDigit())
                        .accessibilityIdentifier("headsField")
                }
                DatePicker("Дата", selection: d.date)
                TextField("Комментарий", text: d.comment, axis: .vertical).lineLimit(1...3)
            }
            if d.wrappedValue.kind == .slaughter && !(needsReason && !reasonChosen) {
                SlaughterFields(draft: d)
            }
            if showErrors && !errors(d.wrappedValue).isEmpty {
                Section {
                    ForEach(errors(d.wrappedValue), id: \.self) {
                        Label($0, systemImage: "exclamationmark.circle").foregroundStyle(.red)
                    }
                }
            }
            Section {
                Button(operation == nil ? "СОХРАНИТЬ" : "СОХРАНИТЬ ИЗМЕНЕНИЯ") { trySave() }
                    .buttonStyle(BigButtonStyle(color: d.wrappedValue.kind == .slaughter ? LivestockTheme.slaughter : Theme.primary))
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                if operation != nil {
                    Button("Удалить операцию", role: .destructive) { confirmingDelete = true }
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
    }

    /// A new «Уменьшить»: the reason is not preselected.
    private var needsReason: Bool { operation == nil && !initial.kind.isAddition }

    private func errors(_ d: LivestockDraft) -> [String] {
        (needsReason && !reasonChosen ? ["Выберите причину уменьшения"] : []) + d.validationErrors
    }

    private func trySave() {
        showErrors = true
        guard let d = draft, errors(d).isEmpty else { return }
        if d.kind == .slaughter { confirmingSlaughter = true } else { save() }
    }

    private func save() {
        guard let d = draft else { return }
        do {
            let service = LivestockService(context: context)
            if let operation { try service.update(operation, with: d) } else { try service.save(d) }
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }

    private func deleteOperation() {
        guard let operation else { return }
        do {
            try LivestockService(context: context).delete(operation)
            dismiss()
        } catch {
            message = error.localizedDescription
        }
    }
}

/// Reason and the optional weights of a slaughter.
struct SlaughterFields: View {
    @Binding var draft: LivestockDraft

    var body: some View {
        Section {
            Picker("Причина забоя", selection: $draft.reason) {
                ForEach(SlaughterReason.allCases, id: \.self) { Text($0.title).tag($0) }
            }
            weight("Живой вес, кг", $draft.liveWeightText)
            weight("Вес после забоя, кг", $draft.carcassWeightText)
            weight("Вес мяса, кг", $draft.meatWeightText)
        } header: {
            Text("Забой")
        } footer: {
            Text("Веса можно не заполнять.")
        }
    }

    private func weight(_ title: String, _ text: Binding<String>) -> some View {
        HStack {
            Text(title)
            TextField("—", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
        }
    }
}
