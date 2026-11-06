import SwiftUI
import SwiftData

/// «Продажа угля»: a module of its own, opened full screen from Главная, with its own tabs.
/// Money never appears in the timesheet tabs and working hours never appear here.
struct CoalModuleView: View {
    enum Tab: Hashable { case sales, summary, drivers, history, prices }

    let onClose: () -> Void

    @Environment(\.modelContext) private var context
    @State private var tab: Tab = .sales

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { CoalTodayView(onClose: onClose) }
                .tabItem { Label("Продажи", systemImage: "cart") }
                .tag(Tab.sales)
            NavigationStack { CoalDaySummaryView(onClose: onClose) }
                .tabItem { Label("Итоги дня", systemImage: "list.bullet.rectangle") }
                .tag(Tab.summary)
            NavigationStack { DriversView(onClose: onClose) }
                .tabItem { Label("Водители", systemImage: "truck.box") }
                .tag(Tab.drivers)
            NavigationStack { CoalHistoryView(onClose: onClose) }
                .tabItem { Label("История", systemImage: "clock.arrow.circlepath") }
                .tag(Tab.history)
            NavigationStack { CoalPricesView(onClose: onClose) }
                .tabItem { Label("Цены", systemImage: "rublesign.circle") }
                .tag(Tab.prices)
        }
        .tint(Theme.primary)
        .environment(\.calendar, Calendar.farm)
        .environment(\.timeZone, Calendar.farm.timeZone)
        .onAppear {
            // The price and the owner's two villages are made on the first opening.
            CoalSettingsService(context: context).current()
        }
    }
}

/// «Табель» in the top bar of every coal screen: back to the timesheet.
struct CoalCloseButton: ToolbarContent {
    let onClose: () -> Void

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button(action: onClose) {
                Label("Табель", systemImage: "chevron.backward")
                    .labelStyle(.titleAndIcon)
            }
        }
    }
}

/// The large «Продажа угля» card on Главная. The module opens again on launch when it was left open.
struct CoalEntryCard: View {
    @AppStorage("coalModuleOpen") private var isOpen = false

    var body: some View {
        Button {
            isOpen = true
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "truck.box.fill")
                    .font(.title)
                    .frame(width: 48, height: 48)
                    .background(.white.opacity(0.2), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Продажа угля").font(.title3.bold())
                    Text("Продажи, доставка, расчёты с водителями").font(.subheadline).opacity(0.9)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.headline)
            }
            .foregroundStyle(.white)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.accent, in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Продажа угля")
        .accessibilityIdentifier("openCoalModule")
        .fullScreenCover(isPresented: $isOpen) {
            CoalModuleView { isOpen = false }
        }
    }
}

extension View {
    /// «Табель» in the top bar when the screen is a tab of the module (nil when it is pushed from another one).
    @ViewBuilder
    func coalCloseButton(_ onClose: (() -> Void)?) -> some View {
        if let onClose {
            toolbar { CoalCloseButton(onClose: onClose) }
        } else {
            self
        }
    }
}
