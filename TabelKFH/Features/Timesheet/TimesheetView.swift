import SwiftUI
import SwiftData

/// «Табель за месяц»: rows are employees, columns the days, each cell the code of the day in its colour.
/// The names stay on the left while the days scroll sideways; «−» / «+» or two fingers zoom; a tap on a cell
/// changes that day. Totals of every status at the end of each row and for the whole farm under the table.
struct MonthTimesheetView: View {
    struct CellTarget: Identifiable {
        let employeeId: UUID
        let name: String
        let day: Date
        var id: String { "\(employeeId)-\(day.timeIntervalSince1970)" }
    }

    @Environment(\.modelContext) private var context
    @Query private var employees: [Employee]
    @State private var month = Calendar.farm.component(.month, from: AppClock.now())
    @State private var year = Calendar.farm.component(.year, from: AppClock.now())
    @State private var document: TimesheetDocument?
    @State private var scale: CGFloat = 1
    @State private var pinchStart: CGFloat?
    @State private var pickingMonth = false
    @State private var editing: CellTarget?
    @State private var message: String?

    private var calendar: Calendar { .farm }
    private static let scales: ClosedRange<CGFloat> = 0.6...1.8

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 14) {
                monthBar
                if let document {
                    if document.rows.isEmpty {
                        Text("В этом месяце нет работающих сотрудников.")
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 24)
                    } else {
                        zoomBar
                        grid(document)
                        farmTotals(document)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Обозначения").font(.headline)
                        StatusLegend()
                    }
                    .card()
                    ExportActions(document: document, surname: nil)
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Табель за месяц")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: reload)
        .onChange(of: month) { _, _ in reload() }
        .onChange(of: year) { _, _ in reload() }
        .sheet(isPresented: $pickingMonth) {
            MonthYearPicker(month: month, year: year) { m, y in
                month = m
                year = y
            }
            .presentationDetents([.medium])
        }
        .confirmationDialog(editing.map { "\($0.name), \(CoalText.date($0.day))" } ?? "",
                            isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } }),
                            titleVisibility: .visible, presenting: editing) { target in
            ForEach(DayStatus.statuses(on: target.day, today: AppClock.now()), id: \.self) { s in
                Button("\(s.rawValue) — \(s.title)") { set(s, target) }
            }
            Button("Снять отметку", role: .destructive) { set(nil, target) }
            Button("Отмена", role: .cancel) {}
        }
        .alert("Не получилось", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
    }

    // MARK: Bars

    private var monthBar: some View {
        HStack {
            Button { shiftMonth(-1) } label: {
                Image(systemName: "chevron.left").font(.title2.bold()).frame(width: 48, height: 48)
            }
            .accessibilityLabel("Предыдущий месяц")
            Spacer()
            Button { pickingMonth = true } label: {
                Text(calendar.monthTitle(month: month, year: year).uppercased())
                    .font(.title2.bold())
                    .foregroundStyle(Theme.primary)
            }
            .accessibilityIdentifier("monthTitle")
            Spacer()
            Button { shiftMonth(1) } label: {
                Image(systemName: "chevron.right").font(.title2.bold()).frame(width: 48, height: 48)
            }
            .accessibilityLabel("Следующий месяц")
        }
        .card()
    }

    private var zoomBar: some View {
        HStack(spacing: 12) {
            Text("Нажмите на клетку, чтобы изменить день").font(.footnote).foregroundStyle(.secondary)
            Spacer()
            Button { zoom(-0.2) } label: { Image(systemName: "minus.magnifyingglass").font(.title3) }
                .accessibilityLabel("Уменьшить")
            Button { zoom(0.2) } label: { Image(systemName: "plus.magnifyingglass").font(.title3) }
                .accessibilityLabel("Увеличить")
        }
    }

    // MARK: Grid

    private var cellWidth: CGFloat { 34 * scale }
    private var rowHeight: CGFloat { 42 * scale }
    private var nameWidth: CGFloat { 128 * scale }
    private var totalWidth: CGFloat { 32 * scale }

    private func grid(_ d: TimesheetDocument) -> some View {
        HStack(alignment: .top, spacing: 0) {
            // The names stay in place.
            VStack(spacing: 0) {
                headerCell("Сотрудник", width: nameWidth, alignment: .leading)
                ForEach(d.rows, id: \.employee.id) { row in
                    Text(TimesheetBuilder.shortName(row.employee.fullName))
                        .font(.system(size: 14 * scale, weight: .semibold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.7)
                        .padding(.horizontal, 6)
                        .frame(width: nameWidth, height: rowHeight, alignment: .leading)
                        .background(Theme.card)
                        .border(Color(uiColor: .separator), width: 0.5)
                }
                Text("Итого")
                    .font(.system(size: 14 * scale, weight: .bold))
                    .padding(.horizontal, 6)
                    .frame(width: nameWidth, height: rowHeight, alignment: .leading)
                    .background(Theme.card)
                    .border(Color(uiColor: .separator), width: 0.5)
            }
            ScrollView(.horizontal, showsIndicators: true) {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        ForEach(1...max(1, d.daysInMonth), id: \.self) { day in dayHeader(day, d) }
                        ForEach(TimesheetTotals.columnTitles, id: \.self) { t in
                            headerCell(t, width: totalWidth, alignment: .center)
                        }
                    }
                    ForEach(d.rows, id: \.employee.id) { row in
                        HStack(spacing: 0) {
                            ForEach(Array(row.cells.enumerated()), id: \.offset) { index, cell in
                                dayCell(cell, row: row, day: index + 1, d)
                            }
                            totalsCells(row.totals, bold: false)
                        }
                    }
                    HStack(spacing: 0) {
                        ForEach(1...max(1, d.daysInMonth), id: \.self) { day in
                            let present = d.rows.filter { $0.cells[day - 1].employed && $0.cells[day - 1].status == .present }.count
                            Text(present == 0 ? "" : "\(present)")
                                .font(.system(size: 12 * scale, weight: .semibold).monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(width: cellWidth, height: rowHeight)
                                .background(Theme.card)
                                .border(Color(uiColor: .separator), width: 0.5)
                        }
                        totalsCells(d.farmTotals, bold: true)
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .simultaneousGesture(MagnifyGesture()
            .onChanged { value in
                let start = pinchStart ?? scale
                pinchStart = start
                scale = min(Self.scales.upperBound, max(Self.scales.lowerBound, start * value.magnification))
            }
            .onEnded { _ in pinchStart = nil })
    }

    private func headerCell(_ text: String, width: CGFloat, alignment: Alignment) -> some View {
        Text(text)
            .font(.system(size: 12 * scale, weight: .bold))
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, alignment == .leading ? 6 : 0)
            .frame(width: width, height: rowHeight, alignment: alignment)
            .background(Theme.primary.opacity(0.15))
            .border(Color(uiColor: .separator), width: 0.5)
    }

    private func dayHeader(_ day: Int, _ d: TimesheetDocument) -> some View {
        let date = calendar.date(day: day, month: d.month, year: d.year)
        let weekend = calendar.isDateInWeekend(date)
        let weekday = ["вс", "пн", "вт", "ср", "чт", "пт", "сб"][calendar.component(.weekday, from: date) - 1]
        return VStack(spacing: 0) {
            Text("\(day)").font(.system(size: 13 * scale, weight: .bold).monospacedDigit())
            Text(weekday).font(.system(size: 9 * scale))
        }
        .foregroundStyle(weekend ? Theme.accent : .primary)
        .frame(width: cellWidth, height: rowHeight)
        .background(Theme.primary.opacity(weekend ? 0.08 : 0.15))
        .border(Color(uiColor: .separator), width: 0.5)
    }

    private func dayCell(_ cell: TimesheetCell, row: TimesheetRow, day: Int, _ d: TimesheetDocument) -> some View {
        let date = calendar.date(day: day, month: d.month, year: d.year)
        return Button {
            guard cell.employed else { return }
            editing = CellTarget(employeeId: row.employee.id, name: row.employee.fullName, day: date)
        } label: {
            Text(cell.text)
                .font(.system(size: 15 * scale, weight: .bold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundStyle(.primary)
                .frame(width: cellWidth, height: rowHeight)
                .background(!cell.employed ? Color(uiColor: .systemGray4)
                            : cell.status?.fill ?? Theme.background)
                .border(Color(uiColor: .separator), width: 0.5)
        }
        .buttonStyle(.plain)
        .disabled(!cell.employed)
        .accessibilityLabel("\(row.employee.fullName), \(day): \(cell.status?.title ?? "не отмечен")")
    }

    private func totalsCells(_ t: TimesheetTotals, bold: Bool) -> some View {
        ForEach(Array(TimesheetTotals.columnStatuses.enumerated()), id: \.offset) { _, s in
            Text("\(t.days(s))")
                .font(.system(size: 13 * scale, weight: bold ? .heavy : .semibold).monospacedDigit())
                .frame(width: totalWidth, height: rowHeight)
                .background(s.fill.opacity(0.6))
                .border(Color(uiColor: .separator), width: 0.5)
        }
    }

    private func farmTotals(_ d: TimesheetDocument) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Итоги по хозяйству").font(.headline)
            let columns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]
            LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                ForEach(TimesheetTotals.columnStatuses, id: \.self) { s in
                    HStack {
                        Text(s.title).font(.subheadline)
                        Spacer()
                        Text("\(d.farmTotals.days(s))").font(.headline.monospacedDigit())
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(s.fill, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
            }
            Text("Сотрудников в табеле: \(d.rows.count)").font(.subheadline).foregroundStyle(.secondary)
        }
        .card()
    }

    // MARK: Actions

    private func reload() {
        do {
            document = try TimesheetService(context: context).liveDocument(month: month, year: year)
        } catch {
            message = error.localizedDescription
        }
    }

    private func shiftMonth(_ delta: Int) {
        let first = calendar.date(day: 1, month: month, year: year)
        guard let moved = calendar.date(byAdding: .month, value: delta, to: first) else { return }
        let c = calendar.dateComponents([.month, .year], from: moved)
        year = c.year ?? year
        month = c.month ?? month
    }

    private func zoom(_ delta: CGFloat) {
        withAnimation(.easeInOut(duration: 0.15)) {
            scale = min(Self.scales.upperBound, max(Self.scales.lowerBound, scale + delta))
        }
    }

    private func set(_ status: DayStatus?, _ target: CellTarget) {
        guard let employee = employees.first(where: { $0.id == target.employeeId }) else { return }
        do {
            try AttendanceService(context: context).setStatus(status, for: employee, on: target.day)
        } catch {
            message = error.localizedDescription
        }
        reload()
    }
}
