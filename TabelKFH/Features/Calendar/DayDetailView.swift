import SwiftUI
import SwiftData

/// The people employed on one day (archived ones too) with their status.
/// Tapping a row opens the record editor.
struct DayDetailView: View {
    let model: CalendarViewModel
    let day: Date

    @State private var entries: [CalendarViewModel.DayEntry] = []

    private var isFuture: Bool { day > model.today }

    var body: some View {
        List {
            if entries.isEmpty {
                Text("В этот день в хозяйстве никто не работал")
                    .foregroundStyle(.secondary)
            }
            Section {
                ForEach(entries) { entry in
                    NavigationLink {
                        RecordEditorView(employee: entry.employee, day: day)
                    } label: {
                        DayEntryRow(entry: entry)
                    }
                }
            } footer: {
                if isFuture && !entries.isEmpty {
                    Text("Будущий день: можно заранее отметить выходной, больничный, отпуск, отгул, прогул или командировку.")
                }
            }
        }
        .navigationTitle(Self.dayFormatter.string(from: day))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { entries = model.entries(on: day) }
    }

    /// «6 ноября, пятница».
    private static var dayFormatter: DateFormatter {
        let f = DateFormatter()
        f.calendar = Calendar.farm
        f.locale = Locale(identifier: "ru_RU")
        f.timeZone = Calendar.farm.timeZone
        f.dateFormat = "d MMMM, EEEE"
        return f
    }
}

private struct DayEntryRow: View {
    let entry: CalendarViewModel.DayEntry

    var body: some View {
        HStack(spacing: 12) {
            EmployeeAvatar(employee: entry.employee, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.employee.fullName)
                    .font(.subheadline.weight(.semibold))
                if entry.employee.isArchived {
                    Text("В архиве").font(.caption).foregroundStyle(Theme.accent)
                } else if !entry.employee.position.isEmpty {
                    Text(entry.employee.position).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                if let r = entry.record {
                    Text("\(r.status.rawValue) — \(r.status.title)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(r.status.color)
                } else {
                    Text("Нет отметки")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }

}
