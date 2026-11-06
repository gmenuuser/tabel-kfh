import SwiftUI
import SwiftData

/// Календарь: a month of marks for the farm or one employee. Tapping a day lists its people.
struct CalendarView: View {
    @Environment(\.modelContext) private var context
    @State private var model: CalendarViewModel?

    var body: some View {
        Group {
            if let model {
                CalendarContent(model: model)
            } else {
                Color.clear
            }
        }
        .navigationTitle("Календарь")
        .onAppear {
            if model == nil { model = CalendarViewModel(context: context) }
        }
    }
}

private struct CalendarContent: View {
    @Bindable var model: CalendarViewModel
    @State private var cells: [CalendarCell] = []
    @State private var choices: [Employee] = []
    @State private var pickingMonth = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 7)
    private static let weekdays = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                monthHeader
                employeeMenu
                grid
                legend
            }
            .padding(16)
        }
        .background(Theme.background)
        .sheet(isPresented: $pickingMonth) {
            MonthYearPicker(month: model.month, year: model.year) { month, year in
                model.show(month: month, year: year)
            }
            .presentationDetents([.medium])
        }
        .onAppear(perform: reload)
        .onChange(of: model.month) { _, _ in reload() }
        .onChange(of: model.year) { _, _ in reload() }
        .onChange(of: model.employeeFilter?.id) { _, _ in reload() }
    }

    private func reload() {
        cells = model.cells()
        choices = model.employees()
    }

    private var monthHeader: some View {
        HStack {
            Button { model.previous() } label: {
                Image(systemName: "chevron.left").font(.title3.weight(.semibold)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Предыдущий месяц")
            Spacer()
            Button { pickingMonth = true } label: {
                HStack(spacing: 6) {
                    Text(model.title).font(.title3.weight(.semibold))
                    Image(systemName: "chevron.down").font(.caption.weight(.semibold))
                }
                .foregroundStyle(.primary)
            }
            .accessibilityHint("Выбрать месяц и год")
            Spacer()
            Button { model.next() } label: {
                Image(systemName: "chevron.right").font(.title3.weight(.semibold)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Следующий месяц")
        }
        .tint(Theme.primary)
    }

    private var employeeMenu: some View {
        Menu {
            Button("Все сотрудники") { model.employeeFilter = nil }
            ForEach(choices) { e in
                Button(e.isArchived ? "\(e.fullName) (архив)" : e.fullName) { model.employeeFilter = e }
            }
        } label: {
            HStack {
                Image(systemName: model.employeeFilter == nil ? "person.2" : "person")
                Text(model.employeeFilter?.fullName ?? "Все сотрудники")
                    .lineLimit(1)
                Spacer()
                Image(systemName: "chevron.up.chevron.down").font(.caption)
            }
            .foregroundStyle(.primary)
            .card()
        }
    }

    private var grid: some View {
        LazyVGrid(columns: columns, spacing: 4) {
            ForEach(Self.weekdays, id: \.self) { name in
                Text(name)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
            ForEach(cells) { cell in
                if let day = cell.day {
                    NavigationLink {
                        DayDetailView(model: model, day: day)
                    } label: {
                        DayCellView(cell: cell, filtered: model.employeeFilter != nil)
                    }
                    .buttonStyle(.plain)
                } else {
                    Color.clear.frame(minHeight: 52)
                }
            }
        }
    }

    private var legend: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 8, alignment: .leading)],
                  alignment: .leading, spacing: 8) {
            ForEach(DayStatus.allCases, id: \.self) { status in
                HStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 4).fill(status.color).frame(width: 14, height: 14)
                    Text("\(status.rawValue) — \(status.title)").font(.caption)
                }
            }
        }
        .card()
    }
}

/// One day square. All employees: filled with the status colour when every mark agrees, small dots
/// when the day is mixed. One employee: that person's status code and hours.
private struct DayCellView: View {
    let cell: CalendarCell
    let filtered: Bool

    var body: some View {
        VStack(spacing: 2) {
            Text("\(cell.number)")
                .font(.subheadline.weight(cell.isToday ? .bold : .regular).monospacedDigit())
            detail
        }
        .foregroundStyle(foreground)
        .frame(maxWidth: .infinity, minHeight: 52)
        .background(background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay {
            if cell.isToday {
                RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.accent, lineWidth: 2)
            }
        }
        .opacity(filtered && !cell.isEmployed ? 0.4 : 1)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var statuses: [DayStatus] {
        DayStatus.allCases.filter { (cell.statusCounts[$0] ?? 0) > 0 }
    }

    /// The single colour of the day, if it has one.
    private var fill: Color? {
        if filtered { return cell.status?.color }
        return statuses.count == 1 ? statuses[0].color : nil
    }

    private var background: Color { fill ?? Theme.card }

    private var foreground: Color {
        if fill != nil { return .white }
        return cell.isFuture ? .secondary : .primary
    }

    @ViewBuilder
    private var detail: some View {
        if filtered {
            if let status = cell.status {
                Text(cell.minutes.map { Self.hours($0) } ?? status.rawValue)
                    .font(.caption2.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            } else {
                Text(" ").font(.caption2)
            }
        } else if statuses.count == 1, let status = statuses.first {
            Text("\(status.rawValue) \(cell.statusCounts[status] ?? 0)")
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        } else if statuses.count > 1 {
            HStack(spacing: 2) {
                ForEach(statuses, id: \.self) { status in
                    Circle().fill(status.color).frame(width: 6, height: 6)
                }
            }
            .frame(height: 12)
        } else {
            Text(" ").font(.caption2)
        }
    }

    /// «8», «7,5» — hours for the small cell.
    private static func hours(_ minutes: Int) -> String {
        let value = Double(minutes) / 60
        let text = value.formatted(.number.precision(.fractionLength(0...1)).locale(Locale(identifier: "ru_RU")))
        return "\(text) ч"
    }

    private var accessibilityText: String {
        var parts = ["\(cell.number)"]
        if filtered {
            if !cell.isEmployed {
                parts.append("не работал в хозяйстве")
            } else if let status = cell.status {
                parts.append(status.title)
                if let minutes = cell.minutes { parts.append(durationText(minutes: minutes)) }
            } else {
                parts.append("нет отметки")
            }
        } else if statuses.isEmpty {
            parts.append("нет отметок")
        } else {
            parts += statuses.map { "\($0.title): \(cell.statusCounts[$0] ?? 0)" }
        }
        if cell.isToday { parts.append("сегодня") }
        return parts.joined(separator: ", ")
    }
}

/// Month and year wheels; «Готово» applies the choice.
private struct MonthYearPicker: View {
    let onDone: (Int, Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var month: Int
    @State private var year: Int

    private static let names = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
                                "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"]

    init(month: Int, year: Int, onDone: @escaping (Int, Int) -> Void) {
        self.onDone = onDone
        _month = State(initialValue: month)
        _year = State(initialValue: year)
    }

    private var years: [Int] {
        let current = Calendar.farm.component(.year, from: Date())
        return Array(min(current - 10, year)...max(current + 2, year))
    }

    var body: some View {
        NavigationStack {
            HStack(spacing: 0) {
                Picker("Месяц", selection: $month) {
                    ForEach(1...12, id: \.self) { m in Text(Self.names[m - 1]).tag(m) }
                }
                .pickerStyle(.wheel)
                Picker("Год", selection: $year) {
                    ForEach(years, id: \.self) { y in Text(String(y)).tag(y) }
                }
                .pickerStyle(.wheel)
            }
            .padding(.horizontal)
            .navigationTitle("Месяц и год")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Отмена") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Готово") {
                        onDone(month, year)
                        dismiss()
                    }
                    .bold()
                }
            }
        }
    }
}

extension DayStatus {
    /// Calendar colour: Я green, В grey, Б red, О blue, ОТ teal, ПР dark red, К purple.
    var color: Color {
        switch self {
        case .present: return Theme.primary
        case .dayOff: return .gray
        case .sick: return .red
        case .vacation: return .blue
        case .timeOff: return .teal
        case .absent: return Color(red: 0.55, green: 0.05, blue: 0.08)
        case .businessTrip: return .purple
        }
    }
}
