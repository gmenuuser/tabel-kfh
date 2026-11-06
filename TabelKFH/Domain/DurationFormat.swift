import Foundation

/// «7 ч 30 мин», «8 ч», «45 мин». Used by Home, the employee history, the record editor and the calendar.
func durationText(minutes: Int) -> String {
    let h = minutes / 60, m = minutes % 60
    if h == 0 { return "\(m) мин" }
    return m == 0 ? "\(h) ч" : "\(h) ч \(m) мин"
}
