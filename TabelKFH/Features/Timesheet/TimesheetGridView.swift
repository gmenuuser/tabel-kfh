import SwiftUI

/// A generated timesheet as a table: № and ФИО stay in place, the day and totals columns scroll
/// sideways; the last row is the farm total.
struct TimesheetGridView: View {
    let document: TimesheetDocument

    private static let numberWidth: CGFloat = 30
    private static let nameWidth: CGFloat = 132
    private static let dayWidth: CGFloat = 30
    private static let totalWidth: CGFloat = 42
    private static let headerHeight: CGFloat = 34
    /// Two lines fit «Я» with the hours, and a two-line ФИО.
    private static let rowHeight: CGFloat = 38
    private static let gridLine = Color(uiColor: .separator)
    private static let weekendFill = Color(uiColor: .tertiarySystemFill)
    private static let totalFill = Theme.primary.opacity(0.12)

    private var days: [Int] { Array(1...max(1, document.daysInMonth)) }

    private var weekends: Set<Int> {
        let calendar = Calendar.farm
        return Set(days.filter { day in
            let date = calendar.date(day: day, month: document.month, year: document.year)
            return calendar.isDateInWeekend(date)
        })
    }

    var body: some View {
        let weekends = self.weekends
        HStack(alignment: .top, spacing: 0) {
            fixedColumns
            ScrollView(.horizontal, showsIndicators: true) {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        ForEach(days, id: \.self) { day in
                            cell("\(day)", width: Self.dayWidth, height: Self.headerHeight, bold: true,
                                 fill: weekends.contains(day) ? Self.weekendFill : nil)
                        }
                        ForEach(TimesheetViewModel.totalsTitles, id: \.self) { title in
                            cell(title, width: Self.totalWidth, height: Self.headerHeight, bold: true,
                                 fill: Self.totalFill)
                        }
                    }
                    ForEach(document.rows, id: \.employee.id) { row in
                        HStack(spacing: 0) {
                            ForEach(Array(row.cells.enumerated()), id: \.offset) { index, c in
                                dayCell(c, weekend: weekends.contains(index + 1))
                            }
                            totals(row.totals, bold: false)
                        }
                    }
                    HStack(spacing: 0) {
                        ForEach(days, id: \.self) { _ in
                            cell("", width: Self.dayWidth, height: Self.rowHeight, fill: Self.totalFill)
                        }
                        totals(document.farmTotals, bold: true)
                    }
                }
            }
        }
        .overlay(Rectangle().stroke(Self.gridLine, lineWidth: 0.5))
        .accessibilityElement(children: .contain)
    }

    private var fixedColumns: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                cell("№", width: Self.numberWidth, height: Self.headerHeight, bold: true)
                cell("ФИО", width: Self.nameWidth, height: Self.headerHeight, bold: true, leading: true)
            }
            ForEach(document.rows, id: \.employee.id) { row in
                HStack(spacing: 0) {
                    cell("\(row.number)", width: Self.numberWidth, height: Self.rowHeight)
                    cell(row.employee.fullName, width: Self.nameWidth, height: Self.rowHeight, leading: true)
                }
            }
            cell("Итого по хозяйству", width: Self.numberWidth + Self.nameWidth, height: Self.rowHeight,
                 bold: true, leading: true, fill: Self.totalFill)
        }
        .background(Theme.background)
    }

    private func dayCell(_ c: TimesheetCell, weekend: Bool) -> some View {
        let fill: Color? = !c.employed ? Color(uiColor: .systemFill) : (weekend ? Self.weekendFill : nil)
        return cell(c.text, width: Self.dayWidth, height: Self.rowHeight, fill: fill,
                    color: c.unfinished ? Theme.accent : .primary)
            .accessibilityLabel(Self.spoken(c))
    }

    private static func spoken(_ c: TimesheetCell) -> String {
        guard c.employed else { return "не работал" }
        return c.text.isEmpty ? "нет отметки" : c.text.replacingOccurrences(of: "\n", with: " ")
    }

    private func totals(_ t: TimesheetTotals, bold: Bool) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(TimesheetViewModel.totalsValues(t).enumerated()), id: \.offset) { _, value in
                cell(value, width: Self.totalWidth, height: Self.rowHeight, bold: bold, fill: Self.totalFill)
            }
        }
    }

    private func cell(_ text: String, width: CGFloat, height: CGFloat, bold: Bool = false, leading: Bool = false,
                      fill: Color? = nil, color: Color = .primary) -> some View {
        Text(text)
            .font(.system(size: 11, weight: bold ? .semibold : .regular).monospacedDigit())
            .foregroundStyle(color)
            .multilineTextAlignment(leading ? .leading : .center)
            .lineLimit(2)
            .minimumScaleFactor(0.7)
            .padding(.horizontal, 3)
            .frame(width: width, height: height, alignment: leading ? .leading : .center)
            .background(fill ?? Color.clear)
            .overlay(Rectangle().stroke(Self.gridLine, lineWidth: 0.5))
    }
}
