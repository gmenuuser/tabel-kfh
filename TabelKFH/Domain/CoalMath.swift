import Foundation

/// Money and mass of the coal module in whole units: kopecks and kilograms. Never `Double`.
enum CoalMath {
    /// 1 тонна.
    static let kilogramsPerTon = 1000
    /// The largest sale the form accepts, 100 т (a typo guard).
    static let maxKilograms = 100_000
    /// The largest price the forms accept, 10 000 000 ₽.
    static let maxKopecks = 1_000_000_000

    /// Tons × price per ton, rounded half up to a kopeck: 2,5 т × 6500 ₽ = 16 250 ₽.
    static func coalAmount(kilograms: Int, pricePerTon: Int) -> Int {
        guard kilograms > 0, pricePerTon > 0 else { return 0 }
        return (kilograms * pricePerTon + kilogramsPerTon / 2) / kilogramsPerTon
    }

    // MARK: - Parsing what the owner types

    /// «2», «2,5», «0.125», « 1,50 » → kilograms; nil for empty, negative, text or more than 3 decimals.
    static func parseTons(_ text: String) -> Int? {
        parseFixed(text, decimals: 3)
    }

    /// «6500», «6 500», «6500,5», «1200.00» → kopecks; nil for empty, negative, text or more than 2 decimals.
    static func parseRubles(_ text: String) -> Int? {
        parseFixed(text, decimals: 2)
    }

    /// A non-negative decimal with at most `decimals` digits after «,» or «.», as an integer of 10^-decimals.
    private static func parseFixed(_ text: String, decimals: Int) -> Int? {
        let cleaned = text.filter { !$0.isWhitespace && $0 != "\u{00A0}" && $0 != "\u{202F}" }
            .replacingOccurrences(of: ",", with: ".")
        guard !cleaned.isEmpty else { return nil }
        let parts = cleaned.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2 else { return nil }
        let whole = parts[0], fraction = parts.count == 2 ? parts[1] : ""
        guard !(whole.isEmpty && fraction.isEmpty), fraction.count <= decimals,
              whole.allSatisfy(\.isASCIIDigit), fraction.allSatisfy(\.isASCIIDigit), whole.count <= 12
        else { return nil }
        var scale = 1
        for _ in 0..<decimals { scale *= 10 }
        let wholeValue = Int(whole.isEmpty ? "0" : whole) ?? 0
        let padded = fraction + String(repeating: "0", count: decimals - fraction.count)
        return wholeValue * scale + (Int(padded) ?? 0)
    }

    // MARK: - Text

    /// «6 500 ₽», «16 250,50 ₽», «−1 200 ₽».
    static func rubles(_ kopecks: Int) -> String {
        rublesNumber(kopecks) + "\u{00A0}₽"
    }

    /// «6 500», «16 250,50» (thousands separated by a no-break space).
    static func rublesNumber(_ kopecks: Int) -> String {
        let sign = kopecks < 0 ? "−" : ""
        let value = abs(kopecks)
        let whole = grouped(value / 100)
        let cents = value % 100
        return sign + whole + (cents == 0 ? "" : String(format: ",%02d", cents))
    }

    /// What a price field shows to be edited: «6500», «1200,50».
    static func rublesField(_ kopecks: Int) -> String {
        let value = abs(kopecks)
        let cents = value % 100
        return "\(value / 100)" + (cents == 0 ? "" : String(format: ",%02d", cents))
    }

    /// «2», «2,5», «0,125».
    static func tons(_ kilograms: Int) -> String {
        let sign = kilograms < 0 ? "−" : ""
        let value = abs(kilograms)
        var fraction = String(format: "%03d", value % kilogramsPerTon)
        while fraction.hasSuffix("0") { fraction.removeLast() }
        return sign + grouped(value / kilogramsPerTon) + (fraction.isEmpty ? "" : "," + fraction)
    }

    /// «2,5 т».
    static func tonsText(_ kilograms: Int) -> String {
        tons(kilograms) + "\u{00A0}т"
    }

    /// The amount as a number for a spreadsheet cell: «16250.5», «6500».
    static func spreadsheetRubles(_ kopecks: Int) -> String {
        let sign = kopecks < 0 ? "-" : ""
        let value = abs(kopecks)
        let cents = value % 100
        if cents == 0 { return sign + "\(value / 100)" }
        return sign + "\(value / 100)." + String(format: "%02d", cents)
    }

    /// Tons as a number for a spreadsheet cell: «2.5».
    static func spreadsheetTons(_ kilograms: Int) -> String {
        tons(kilograms).replacingOccurrences(of: ",", with: ".").replacingOccurrences(of: "\u{00A0}", with: "")
            .replacingOccurrences(of: "−", with: "-")
    }

    /// 1234567 → «1 234 567».
    private static func grouped(_ n: Int) -> String {
        let digits = Array(String(n))
        var out = ""
        for (index, digit) in digits.enumerated() {
            if index > 0 && (digits.count - index) % 3 == 0 { out.append("\u{00A0}") }
            out.append(digit)
        }
        return out
    }
}

private extension Character {
    var isASCIIDigit: Bool { ("0"..."9").contains(self) }
}
