import SwiftUI
import SwiftData

/// The two parts of the app.
enum AppSection: Hashable {
    /// «Табель»: employees, attendance, timesheets.
    case timesheet
    /// «Уголь»: coal sales, deliveries, drivers.
    case coal
}

private struct OpenSectionKey: EnvironmentKey {
    static let defaultValue: (AppSection) -> Void = { _ in }
}

extension EnvironmentValues {
    /// Switches the app to the other part (the «Продажа угля» card on Главная, «Табель» in the coal module).
    var openSection: (AppSection) -> Void {
        get { self[OpenSectionKey.self] }
        set { self[OpenSectionKey.self] = newValue }
    }
}

/// On every launch the owner chooses «Табель» or «Уголь» first.
struct AppRootView: View {
    @State private var section: AppSection?

    var body: some View {
        Group {
            switch section {
            case nil:
                SectionChooserView { open($0) }
            case .timesheet:
                RootTabView()
            case .coal:
                CoalModuleView { open(.timesheet) }
            }
        }
        .environment(\.openSection) { open($0) }
    }

    private func open(_ s: AppSection) {
        withAnimation(.easeInOut(duration: 0.2)) { section = s }
    }
}

/// The start screen: two large buttons, «Табель» and «Уголь».
struct SectionChooserView: View {
    let onChoose: (AppSection) -> Void

    @Environment(\.modelContext) private var context
    @State private var farmName = ""

    var body: some View {
        VStack(spacing: 20) {
            Spacer(minLength: 20)
            VStack(spacing: 6) {
                Text(farmName)
                    .font(.largeTitle.bold())
                    .foregroundStyle(Theme.primary)
                    .multilineTextAlignment(.center)
                Text("Выберите раздел")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 10)
            choice(title: "Табель", subtitle: "Сотрудники, приход и уход, календарь, табель за месяц",
                   icon: "tablecells", color: Theme.primary, id: "chooseTimesheet") { onChoose(.timesheet) }
            choice(title: "Уголь", subtitle: "Продажа и доставка угля, водители, итоги дня",
                   icon: "truck.box.fill", color: Theme.accent, id: "chooseCoal") { onChoose(.coal) }
            Spacer(minLength: 20)
        }
        .padding(20)
        .frame(maxWidth: 600)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
        .onAppear {
            // The settings row is made here on the very first launch.
            farmName = FarmSettingsStore.current(in: context).farmName
        }
    }

    private func choice(title: String, subtitle: String, icon: String, color: Color, id: String,
                        action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.system(size: 34, weight: .semibold))
                    .frame(width: 64, height: 64)
                    .background(.white.opacity(0.2), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.title.bold())
                    Text(subtitle).font(.subheadline).opacity(0.9).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.title3.bold())
            }
            .foregroundStyle(.white)
            .padding(20)
            .frame(maxWidth: .infinity, minHeight: 120, alignment: .leading)
            .background(color, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint(subtitle)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(id)
    }
}
