import Foundation

/// The eight calculators of «Агрономические калькуляторы».
enum AgroKind: String, Codable, CaseIterable {
    case seeding, fertilizer, spraying, yield, moisture, fuel, area, transport

    var title: String {
        switch self {
        case .seeding: return "Посев семян"
        case .fertilizer: return "Удобрения"
        case .spraying: return "Опрыскивание"
        case .yield: return "Урожайность"
        case .moisture: return "Влажность зерна"
        case .fuel: return "Топливо"
        case .area: return "Площадь поля"
        case .transport: return "Перевозка и хранение зерна"
        }
    }

    var icon: String {
        switch self {
        case .seeding: return "leaf.fill"
        case .fertilizer: return "shippingbox.fill"
        case .spraying: return "drop.fill"
        case .yield: return "chart.bar.fill"
        case .moisture: return "humidity.fill"
        case .fuel: return "fuelpump.fill"
        case .area: return "square.dashed"
        case .transport: return "truck.box.fill"
        }
    }
}

/// One input field: the key in the inputs, the title and the unit shown next to it.
struct AgroField: Equatable {
    let key: String
    let title: String
    let unit: String
    /// May stay empty (the result is shown without what depends on it).
    var optional = false
}

/// A choice of units or of a mode, shown as a segmented control.
struct AgroChoice: Equatable {
    let key: String
    let title: String
    let options: [(value: String, title: String)]

    var defaultValue: String { options.first?.value ?? "" }

    static func == (a: AgroChoice, b: AgroChoice) -> Bool {
        a.key == b.key && a.title == b.title && a.options.map(\.value) == b.options.map(\.value)
    }
}

/// A line of the result; `main` lines are shown large.
struct AgroLine: Codable, Equatable {
    let title: String
    let text: String
    var main = false
}

struct AgroResult: Equatable {
    var lines: [AgroLine] = []
    var warnings: [String] = []
    /// Wrong values (text, zero, negative, out of range).
    var errors: [String] = []
    /// Required fields still empty.
    var missing: [String] = []

    /// Everything filled in and correct: the calculation can be saved.
    var isComplete: Bool { errors.isEmpty && missing.isEmpty && !lines.isEmpty }
}

/// Numbers as the owner types them («12,5», «1 200») and as they are shown (at most 2 decimals, «9 302,33»).
enum AgroNumber {
    static func parse(_ text: String?) -> Double? {
        guard let text else { return nil }
        let cleaned = text.filter { !$0.isWhitespace && $0 != "\u{00A0}" && $0 != "\u{202F}" }
            .replacingOccurrences(of: ",", with: ".")
        guard !cleaned.isEmpty, cleaned.filter({ $0 == "." }).count <= 1,
              cleaned.allSatisfy({ $0.isNumber || $0 == "." || $0 == "-" }), let v = Double(cleaned), v.isFinite
        else { return nil }
        return v
    }

    /// Rounded half away from zero to `decimals`, trailing zeros dropped, thousands by a no-break space.
    static func format(_ v: Double, decimals: Int = 2) -> String {
        var scale = 1.0
        for _ in 0..<decimals { scale *= 10 }
        let rounded = (abs(v) * scale).rounded(.toNearestOrAwayFromZero)
        let whole = Int((rounded / scale).rounded(.down))
        var fraction = String(Int(rounded - Double(whole) * scale))
        while fraction.count < decimals { fraction = "0" + fraction }
        while fraction.hasSuffix("0") { fraction.removeLast() }
        let digits = Array(String(whole))
        var grouped = ""
        for (i, d) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { grouped.append("\u{00A0}") }
            grouped.append(d)
        }
        let sign = v < 0 && rounded > 0 ? "−" : ""
        return sign + grouped + (fraction.isEmpty ? "" : "," + fraction)
    }

    static func with(_ v: Double, _ unit: String, decimals: Int = 2) -> String {
        format(v, decimals: decimals) + (unit.isEmpty ? "" : "\u{00A0}" + unit)
    }
}

/// The plots of «Площадь поля» in the inputs: «plots» = count, «plot.N.length», «plot.N.width», «plot.N.area».
enum AgroPlots {
    static func count(_ inputs: [String: String]) -> Int { max(1, Int(inputs["plots"] ?? "") ?? 1) }
    static func key(_ index: Int, _ part: String) -> String { "plot.\(index).\(part)" }
}

extension AgroKind {
    /// The choices of units or mode.
    var choices: [AgroChoice] {
        switch self {
        case .fertilizer:
            return [AgroChoice(key: "rateUnit", title: "Норма", options: [("kg", "кг/га"), ("c", "ц/га"), ("t", "т/га")]),
                    AgroChoice(key: "priceUnit", title: "Цена", options: [("kg", "за кг"), ("t", "за тонну")])]
        case .spraying:
            return [AgroChoice(key: "productUnit", title: "Препарат", options: [("l", "л/га"), ("kg", "кг/га")])]
        case .yield:
            return [AgroChoice(key: "weightUnit", title: "Вес", options: [("t", "тонны"), ("c", "центнеры"), ("kg", "кг")])]
        case .fuel:
            return [AgroChoice(key: "mode", title: "Расчёт", options: [("area", "По площади"), ("stock", "По запасу топлива")])]
        case .transport:
            return [AgroChoice(key: "grainUnit", title: "Вес зерна", options: [("t", "тонны"), ("kg", "кг")]),
                    AgroChoice(key: "truckUnit", title: "Машина", options: [("t", "тонны"), ("kg", "кг")])]
        case .seeding, .moisture, .area:
            return []
        }
    }

    func choice(_ key: String, in inputs: [String: String]) -> String {
        inputs[key] ?? choices.first { $0.key == key }?.defaultValue ?? ""
    }

    /// The fields for the current choices (the area plots are drawn separately).
    func fields(_ inputs: [String: String]) -> [AgroField] {
        switch self {
        case .seeding:
            return [AgroField(key: "area", title: "Площадь поля", unit: "га"),
                    AgroField(key: "rate", title: "Норма высева", unit: "кг/га"),
                    AgroField(key: "reserve", title: "Дополнительный запас", unit: "%", optional: true),
                    AgroField(key: "bag", title: "Вес мешка", unit: "кг", optional: true)]
        case .fertilizer:
            let rate = ["kg": "кг/га", "c": "ц/га", "t": "т/га"][choice("rateUnit", in: inputs)] ?? "кг/га"
            let price = choice("priceUnit", in: inputs) == "t" ? "₽/т" : "₽/кг"
            return [AgroField(key: "area", title: "Площадь поля", unit: "га"),
                    AgroField(key: "rate", title: "Норма внесения", unit: rate),
                    AgroField(key: "price", title: "Цена", unit: price, optional: true),
                    AgroField(key: "package", title: "Вес упаковки", unit: "кг", optional: true)]
        case .spraying:
            let unit = choice("productUnit", in: inputs) == "kg" ? "кг/га" : "л/га"
            return [AgroField(key: "area", title: "Площадь обработки", unit: "га"),
                    AgroField(key: "rate", title: "Норма препарата (по этикетке)", unit: unit),
                    AgroField(key: "water", title: "Расход воды", unit: "л/га"),
                    AgroField(key: "tank", title: "Объём бака", unit: "л", optional: true)]
        case .yield:
            let unit = ["t": "т", "c": "ц", "kg": "кг"][choice("weightUnit", in: inputs)] ?? "т"
            return [AgroField(key: "area", title: "Убранная площадь", unit: "га"),
                    AgroField(key: "weight", title: "Собранный вес", unit: unit)]
        case .moisture:
            return [AgroField(key: "weight", title: "Вес зерна", unit: "кг"),
                    AgroField(key: "actual", title: "Фактическая влажность", unit: "%"),
                    AgroField(key: "target", title: "Целевая влажность", unit: "%")]
        case .fuel:
            if choice("mode", in: inputs) == "stock" {
                return [AgroField(key: "fuel", title: "Есть топлива", unit: "л"),
                        AgroField(key: "rate", title: "Расход топлива", unit: "л/га")]
            }
            return [AgroField(key: "area", title: "Площадь обработки", unit: "га"),
                    AgroField(key: "rate", title: "Расход топлива", unit: "л/га"),
                    AgroField(key: "price", title: "Цена дизельного топлива", unit: "₽/л", optional: true)]
        case .area:
            return []
        case .transport:
            let grain = choice("grainUnit", in: inputs) == "kg" ? "кг" : "т"
            let truck = choice("truckUnit", in: inputs) == "kg" ? "кг" : "т"
            return [AgroField(key: "grain", title: "Общий вес зерна", unit: grain),
                    AgroField(key: "truck", title: "Вместимость машины", unit: truck),
                    AgroField(key: "bag", title: "Вместимость мешка", unit: "кг", optional: true)]
        }
    }
}

/// Every formula of the module. Values are used unrounded; only the shown texts are rounded.
enum AgroEngine {
    static func compute(_ kind: AgroKind, _ inputs: [String: String]) -> AgroResult {
        var r = AgroResult()
        var v: [String: Double] = [:]
        if kind == .area { return area(inputs) }
        for f in kind.fields(inputs) {
            let text = (inputs[f.key] ?? "").trimmingCharacters(in: .whitespaces)
            if text.isEmpty {
                if !f.optional { r.missing.append(f.title) }
                continue
            }
            guard let value = AgroNumber.parse(text) else {
                r.errors.append("«\(f.title)»: введите число, например 12,5")
                continue
            }
            let zeroAllowed = f.optional && (f.key == "reserve")
            if value < 0 || (value == 0 && !zeroAllowed) {
                r.errors.append("«\(f.title)» должно быть больше нуля")
                continue
            }
            v[f.key] = value
        }
        guard r.errors.isEmpty, r.missing.isEmpty else { return r }
        switch kind {
        case .seeding: seeding(v, &r)
        case .fertilizer: fertilizer(v, kind, inputs, &r)
        case .spraying: spraying(v, kind, inputs, &r)
        case .yield: yield(v, kind, inputs, &r)
        case .moisture: moisture(v, &r)
        case .fuel: fuel(v, kind, inputs, &r)
        case .transport: transport(v, kind, inputs, &r)
        case .area: break
        }
        return r
    }

    private static func seeding(_ v: [String: Double], _ r: inout AgroResult) {
        let area = v["area"]!, rate = v["rate"]!, reserve = v["reserve"] ?? 0
        if reserve > 100 { r.errors.append("Запас — не больше 100 %"); return }
        let kg = area * rate * (1 + reserve / 100)
        r.lines = [AgroLine(title: "Нужно семян", text: AgroNumber.with(kg, "кг"), main: true),
                   AgroLine(title: "В центнерах", text: AgroNumber.with(kg / 100, "ц")),
                   AgroLine(title: "В тоннах", text: AgroNumber.with(kg / 1000, "т", decimals: 3))]
        if reserve > 0 {
            r.lines.append(AgroLine(title: "Без запаса", text: AgroNumber.with(area * rate, "кг")))
        }
        if let bag = v["bag"] {
            r.lines.append(AgroLine(title: "Мешков по \(AgroNumber.format(bag)) кг", text: "\(Int((kg / bag).rounded(.up)))"))
        }
    }

    private static func fertilizer(_ v: [String: Double], _ kind: AgroKind, _ inputs: [String: String],
                                   _ r: inout AgroResult) {
        let perKg = ["kg": 1.0, "c": 100.0, "t": 1000.0][kind.choice("rateUnit", in: inputs)] ?? 1
        let kg = v["area"]! * v["rate"]! * perKg
        r.lines = [AgroLine(title: "Нужно удобрения", text: AgroNumber.with(kg, "кг"), main: true),
                   AgroLine(title: "В тоннах", text: AgroNumber.with(kg / 1000, "т", decimals: 3))]
        if let package = v["package"] {
            r.lines.append(AgroLine(title: "Упаковок по \(AgroNumber.format(package)) кг",
                                    text: "\(Int((kg / package).rounded(.up)))"))
        }
        if let price = v["price"] {
            let cost = kind.choice("priceUnit", in: inputs) == "t" ? kg / 1000 * price : kg * price
            r.lines.append(AgroLine(title: "Стоимость", text: AgroNumber.with(cost, "₽"), main: true))
        }
    }

    private static func spraying(_ v: [String: Double], _ kind: AgroKind, _ inputs: [String: String],
                                 _ r: inout AgroResult) {
        let area = v["area"]!
        let liquid = kind.choice("productUnit", in: inputs) != "kg"
        let product = area * v["rate"]!
        let water = area * v["water"]!
        // A powder (кг) is not added to the volume: that needs the density.
        let solution = water + (liquid ? product : 0)
        r.lines = [AgroLine(title: "Препарата", text: AgroNumber.with(product, liquid ? "л" : "кг"), main: true),
                   AgroLine(title: "Воды", text: AgroNumber.with(water, "л")),
                   AgroLine(title: "Рабочего раствора", text: AgroNumber.with(solution, "л"), main: true)]
        if !liquid {
            r.warnings.append("Препарат в килограммах: в объём раствора он не добавлен — для этого нужна его плотность.")
        }
        if let tank = v["tank"] {
            let full = Int((solution / tank).rounded(.down))
            let rest = solution - Double(full) * tank
            r.lines.append(AgroLine(title: "Полных заправок", text: "\(full)", main: true))
            r.lines.append(AgroLine(title: "Остаток на последнюю заправку",
                                    text: rest > 1e-9 ? AgroNumber.with(rest, "л") : "нет"))
            if full > 0 {
                r.lines.append(AgroLine(title: "Препарата на полный бак",
                                        text: AgroNumber.with(product / solution * tank, liquid ? "л" : "кг")))
            }
        }
        r.warnings.append("Норму препарата берите только с этикетки: приложение дозировки не назначает.")
    }

    private static func yield(_ v: [String: Double], _ kind: AgroKind, _ inputs: [String: String], _ r: inout AgroResult) {
        let tons = v["weight"]! / (["t": 1.0, "c": 10.0, "kg": 1000.0][kind.choice("weightUnit", in: inputs)] ?? 1)
        let perHa = tons / v["area"]!
        r.lines = [AgroLine(title: "Урожайность", text: AgroNumber.with(perHa * 10, "ц/га"), main: true),
                   AgroLine(title: "Урожайность", text: AgroNumber.with(perHa, "т/га", decimals: 3), main: true),
                   AgroLine(title: "Собрано всего", text: AgroNumber.with(tons, "т", decimals: 3)),
                   AgroLine(title: "Собрано всего", text: AgroNumber.with(tons * 10, "ц"))]
    }

    private static func moisture(_ v: [String: Double], _ r: inout AgroResult) {
        let weight = v["weight"]!, actual = v["actual"]!, target = v["target"]!
        if actual >= 100 || target >= 100 { r.errors.append("Влажность — меньше 100 %"); return }
        if target >= actual { r.errors.append("Целевая влажность должна быть меньше фактической"); return }
        let after = weight * (100 - actual) / (100 - target)
        r.lines = [AgroLine(title: "Исходный вес", text: AgroNumber.with(weight, "кг")),
                   AgroLine(title: "Вес после сушки", text: AgroNumber.with(after, "кг"), main: true),
                   AgroLine(title: "Потеря массы", text: AgroNumber.with(weight - after, "кг"), main: true),
                   AgroLine(title: "Удалить воды (расчётно)", text: AgroNumber.with(weight - after, "кг"))]
        r.warnings.append("Это расчёт теоретической массы при сохранении сухого вещества, без учёта технологических потерь и изменения состава зерна.")
    }

    private static func fuel(_ v: [String: Double], _ kind: AgroKind, _ inputs: [String: String], _ r: inout AgroResult) {
        if kind.choice("mode", in: inputs) == "stock" {
            r.lines = [AgroLine(title: "Можно обработать", text: AgroNumber.with(v["fuel"]! / v["rate"]!, "га"), main: true)]
            return
        }
        let liters = v["area"]! * v["rate"]!
        r.lines = [AgroLine(title: "Нужно топлива", text: AgroNumber.with(liters, "л"), main: true)]
        if let price = v["price"] {
            r.lines.append(AgroLine(title: "Стоимость топлива", text: AgroNumber.with(liters * price, "₽"), main: true))
        }
    }

    private static func area(_ inputs: [String: String]) -> AgroResult {
        var r = AgroResult()
        var total = 0.0
        var counted = 0
        for i in 1...AgroPlots.count(inputs) {
            let l = (inputs[AgroPlots.key(i, "length")] ?? "").trimmingCharacters(in: .whitespaces)
            let w = (inputs[AgroPlots.key(i, "width")] ?? "").trimmingCharacters(in: .whitespaces)
            let a = (inputs[AgroPlots.key(i, "area")] ?? "").trimmingCharacters(in: .whitespaces)
            if l.isEmpty && w.isEmpty && a.isEmpty { continue }
            let square: Double
            if !a.isEmpty {
                guard let value = AgroNumber.parse(a), value > 0 else {
                    r.errors.append("Участок \(i): площадь — число больше нуля"); continue
                }
                square = value
            } else {
                guard let length = AgroNumber.parse(l), let width = AgroNumber.parse(w), length > 0, width > 0 else {
                    r.errors.append("Участок \(i): укажите длину и ширину больше нуля"); continue
                }
                square = length * width
            }
            total += square
            counted += 1
            r.lines.append(AgroLine(title: "Участок \(i)", text: AgroNumber.with(square, "м²") + " = "
                                    + AgroNumber.with(square / 10_000, "га", decimals: 4)))
        }
        if counted == 0 && r.errors.isEmpty { r.missing.append("Длина и ширина участка") }
        guard r.errors.isEmpty, counted > 0 else { r.lines = []; return r }
        r.lines.append(AgroLine(title: "Всего", text: AgroNumber.with(total, "м²")))
        r.lines.append(AgroLine(title: "Площадь поля", text: AgroNumber.with(total / 10_000, "га", decimals: 4), main: true))
        r.warnings.append("Длина × ширина верна только для прямоугольника. Поле сложной формы разбейте на части и сложите их.")
        return r
    }

    private static func transport(_ v: [String: Double], _ kind: AgroKind, _ inputs: [String: String],
                                  _ r: inout AgroResult) {
        let grainKg = v["grain"]! * (kind.choice("grainUnit", in: inputs) == "kg" ? 1 : 1000)
        let truckKg = v["truck"]! * (kind.choice("truckUnit", in: inputs) == "kg" ? 1 : 1000)
        let full = Int((grainKg / truckKg + 1e-9).rounded(.down))
        let rest = max(0, grainKg - Double(full) * truckKg)
        r.lines = [AgroLine(title: "Полных рейсов", text: "\(full)", main: true),
                   AgroLine(title: "Остаток на последний рейс", text: rest > 1e-6 ? AgroNumber.with(rest, "кг") : "нет"),
                   AgroLine(title: "Всего рейсов", text: "\(full + (rest > 1e-6 ? 1 : 0))")]
        if let bag = v["bag"] {
            let bags = Int((grainKg / bag + 1e-9).rounded(.down))
            let left = max(0, grainKg - Double(bags) * bag)
            r.lines.append(AgroLine(title: "Полных мешков по \(AgroNumber.format(bag)) кг", text: "\(bags)", main: true))
            r.lines.append(AgroLine(title: "Остаток зерна", text: left > 1e-6 ? AgroNumber.with(left, "кг") : "нет"))
        }
    }

    /// The inputs as «Площадь поля: 10 га; …» for the history and the export.
    static func inputsText(_ kind: AgroKind, _ inputs: [String: String]) -> String {
        var parts: [String] = []
        for c in kind.choices {
            let value = kind.choice(c.key, in: inputs)
            if let title = c.options.first(where: { $0.value == value })?.title { parts.append("\(c.title): \(title)") }
        }
        if kind == .area {
            for i in 1...AgroPlots.count(inputs) {
                if let a = inputs[AgroPlots.key(i, "area")], !a.isEmpty {
                    parts.append("Участок \(i): \(a) м²")
                } else if let l = inputs[AgroPlots.key(i, "length")], let w = inputs[AgroPlots.key(i, "width")],
                          !l.isEmpty, !w.isEmpty {
                    parts.append("Участок \(i): \(l) × \(w) м")
                }
            }
        }
        for f in kind.fields(inputs) {
            if let t = inputs[f.key]?.trimmingCharacters(in: .whitespaces), !t.isEmpty {
                parts.append("\(f.title): \(t) \(f.unit)")
            }
        }
        return parts.joined(separator: "; ")
    }

    /// The result as text, for copying and sharing.
    static func shareText(_ kind: AgroKind, title: String, inputs: [String: String], result: AgroResult) -> String {
        var out = [kind.title + (title.isEmpty ? "" : " — " + title), "Данные: " + inputsText(kind, inputs)]
        out += result.lines.map { "\($0.title): \($0.text)" }
        out += result.warnings
        return out.joined(separator: "\n")
    }
}
