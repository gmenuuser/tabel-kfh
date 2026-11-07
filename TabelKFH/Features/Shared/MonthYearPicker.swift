import SwiftUI

/// Month and year wheels; «Готово» applies the choice.
/// Shared by the Табель, Отчёты, Главная and Уголь screens.
struct MonthYearPicker: View {
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
        let current = Calendar.farm.component(.year, from: AppClock.now())
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
