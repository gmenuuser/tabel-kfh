import SwiftUI
import SwiftData
import PhotosUI
import UIKit

/// Adds a new employee (`employee == nil`) or edits one. Closes itself after saving.
struct EmployeeFormView: View {
    let employee: Employee?

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var draft: EmployeeDraft
    @State private var photoItem: PhotosPickerItem?
    @State private var triedSave = false
    @State private var errorMessage: String?

    init(employee: Employee?) {
        self.employee = employee
        _draft = State(initialValue: employee.map { EmployeeDraft($0) } ?? EmployeeDraft())
    }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 16) {
                    photoView
                    VStack(alignment: .leading, spacing: 8) {
                        PhotosPicker(selection: $photoItem, matching: .images) {
                            Text(draft.photo == nil ? "Выбрать фото" : "Заменить фото")
                        }
                        if draft.photo != nil {
                            Button("Убрать фото", role: .destructive) {
                                draft.photo = nil
                                photoItem = nil
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                }
            }

            Section("ФИО и должность") {
                // Names are not dictionary words: no autocorrection.
                TextField("Фамилия", text: $draft.lastName)
                    .textContentType(.familyName)
                    .autocorrectionDisabled()
                TextField("Имя", text: $draft.firstName)
                    .textContentType(.givenName)
                    .autocorrectionDisabled()
                TextField("Отчество", text: $draft.patronymic)
                    .textContentType(.middleName)
                    .autocorrectionDisabled()
                TextField("Должность", text: $draft.position)
                    .textContentType(.jobTitle)
            }

            Section("Работа в хозяйстве") {
                DatePicker("Дата приёма", selection: $draft.hireDate, displayedComponents: .date)
                Toggle("Уволен", isOn: dismissedBinding)
                if let dismissal = draft.dismissalDate {
                    DatePicker("Дата увольнения",
                               selection: Binding(get: { dismissal }, set: { draft.dismissalDate = $0 }),
                               displayedComponents: .date)
                }
            }

            Section("График и норма") {
                Picker("Рабочий график", selection: $draft.workSchedule) {
                    ForEach(WorkSchedule.allCases, id: \.self) { s in
                        Text(s.title).tag(s)
                    }
                }
                Stepper("Норма: \(hoursText) ч в день", value: $draft.standardWorkHours,
                        in: EmployeeDraft.hoursRange, step: 0.5)
                Stepper("Перерыв: \(draft.defaultBreakMinutes) мин", value: $draft.defaultBreakMinutes,
                        in: EmployeeDraft.breakRange, step: 5)
            }

            if triedSave && !draft.validationErrors.isEmpty {
                Section {
                    ForEach(draft.validationErrors, id: \.self) { message in
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(Theme.accent)
                    }
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .keyboardDoneButton()
        .navigationTitle(employee == nil ? "Новый сотрудник" : "Изменить данные")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Отмена") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Сохранить", action: save).bold().editable(.employee)
            }
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task { @MainActor in
                if let data = try? await item.loadTransferable(type: Data.self),
                   let small = Self.downscaled(data) {
                    draft.photo = small
                } else {
                    errorMessage = "Не удалось загрузить фото"
                }
            }
        }
        .alert("Не получилось", isPresented: errorShown) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var photoView: some View {
        Group {
            if let data = draft.photo, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "person.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Theme.primary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Theme.primary.opacity(0.15))
            }
        }
        .frame(width: 72, height: 72)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    /// «8», «7,5».
    private var hoursText: String {
        let h = draft.standardWorkHours
        if h == h.rounded() { return String(Int(h)) }
        return String(format: "%.1f", h).replacingOccurrences(of: ".", with: ",")
    }

    private var dismissedBinding: Binding<Bool> {
        Binding(get: { draft.dismissalDate != nil },
                set: { on in
                    let today = Calendar.farm.dayStart(AppClock.now())
                    draft.dismissalDate = on ? max(today, Calendar.farm.dayStart(draft.hireDate)) : nil
                })
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })
    }

    private func save() {
        triedSave = true
        guard draft.isValid else { return }
        let service = EmployeeService(context: context)
        do {
            if let employee {
                try service.update(employee, from: draft)
            } else {
                try service.add(draft)
            }
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// JPEG no larger than 600 px on the long side, so the store stays small.
    private static func downscaled(_ data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let maxSide: CGFloat = 600
        let longest = max(image.size.width, image.size.height)
        let scale = longest > maxSide ? maxSide / longest : 1
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return resized.jpegData(compressionQuality: 0.8)
    }
}
