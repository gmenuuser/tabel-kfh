package ru.kfh.core.agro

import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import ru.kfh.core.model.AgroCalculation
import ru.kfh.core.model.AgroLine

private const val NBSP = " "

/** The eight calculators of «Агрономические калькуляторы». `wire` is how the site and the server spell the kind. */
enum class AgroKind(val wire: String, val title: String, val icon: String) {
    SEEDING("seeding", "Посев семян", "leaf.fill"),
    FERTILIZER("fertilizer", "Удобрения", "shippingbox.fill"),
    SPRAYING("spraying", "Опрыскивание", "drop.fill"),
    YIELD("yield", "Урожайность", "chart.bar.fill"),
    MOISTURE("moisture", "Влажность зерна", "humidity.fill"),
    FUEL("fuel", "Топливо", "fuelpump.fill"),
    AREA("area", "Площадь поля", "square.dashed"),
    TRANSPORT("transport", "Перевозка и хранение зерна", "truck.box.fill");

    /** The choices of units or mode. */
    val choices: List<AgroChoice>
        get() = when (this) {
            FERTILIZER -> listOf(
                AgroChoice("rateUnit", "Норма", listOf("kg" to "кг/га", "c" to "ц/га", "t" to "т/га")),
                AgroChoice("priceUnit", "Цена", listOf("kg" to "за кг", "t" to "за тонну")),
            )
            SPRAYING -> listOf(AgroChoice("productUnit", "Препарат", listOf("l" to "л/га", "kg" to "кг/га")))
            YIELD -> listOf(AgroChoice("weightUnit", "Вес", listOf("t" to "тонны", "c" to "центнеры", "kg" to "кг")))
            FUEL -> listOf(AgroChoice("mode", "Расчёт", listOf("area" to "По площади", "stock" to "По запасу топлива")))
            TRANSPORT -> listOf(
                AgroChoice("grainUnit", "Вес зерна", listOf("t" to "тонны", "kg" to "кг")),
                AgroChoice("truckUnit", "Машина", listOf("t" to "тонны", "kg" to "кг")),
            )
            SEEDING, MOISTURE, AREA -> emptyList()
        }

    fun choice(key: String, inputs: Map<String, String>): String =
        inputs[key] ?: choices.firstOrNull { it.key == key }?.defaultValue ?: ""

    /** The fields for the current choices (the area plots are drawn separately). */
    fun fields(inputs: Map<String, String>): List<AgroField> = when (this) {
        SEEDING -> listOf(
            AgroField("area", "Площадь поля", "га"),
            AgroField("rate", "Норма высева", "кг/га"),
            AgroField("reserve", "Дополнительный запас", "%", optional = true),
            AgroField("bag", "Вес мешка", "кг", optional = true),
        )
        FERTILIZER -> {
            val rate = mapOf("kg" to "кг/га", "c" to "ц/га", "t" to "т/га")[choice("rateUnit", inputs)] ?: "кг/га"
            val price = if (choice("priceUnit", inputs) == "t") "₽/т" else "₽/кг"
            listOf(
                AgroField("area", "Площадь поля", "га"),
                AgroField("rate", "Норма внесения", rate),
                AgroField("price", "Цена", price, optional = true),
                AgroField("package", "Вес упаковки", "кг", optional = true),
            )
        }
        SPRAYING -> {
            val unit = if (choice("productUnit", inputs) == "kg") "кг/га" else "л/га"
            listOf(
                AgroField("area", "Площадь обработки", "га"),
                AgroField("rate", "Норма препарата (по этикетке)", unit),
                AgroField("water", "Расход воды", "л/га"),
                AgroField("tank", "Объём бака", "л", optional = true),
            )
        }
        YIELD -> {
            val unit = mapOf("t" to "т", "c" to "ц", "kg" to "кг")[choice("weightUnit", inputs)] ?: "т"
            listOf(AgroField("area", "Убранная площадь", "га"), AgroField("weight", "Собранный вес", unit))
        }
        MOISTURE -> listOf(
            AgroField("weight", "Вес зерна", "кг"),
            AgroField("actual", "Фактическая влажность", "%"),
            AgroField("target", "Целевая влажность", "%"),
        )
        FUEL ->
            if (choice("mode", inputs) == "stock") {
                listOf(AgroField("fuel", "Есть топлива", "л"), AgroField("rate", "Расход топлива", "л/га"))
            } else {
                listOf(
                    AgroField("area", "Площадь обработки", "га"),
                    AgroField("rate", "Расход топлива", "л/га"),
                    AgroField("price", "Цена дизельного топлива", "₽/л", optional = true),
                )
            }
        AREA -> emptyList()
        TRANSPORT -> {
            val grain = if (choice("grainUnit", inputs) == "kg") "кг" else "т"
            val truck = if (choice("truckUnit", inputs) == "kg") "кг" else "т"
            listOf(
                AgroField("grain", "Общий вес зерна", grain),
                AgroField("truck", "Вместимость машины", truck),
                AgroField("bag", "Вместимость мешка", "кг", optional = true),
            )
        }
    }

    companion object {
        /** An unknown kind reads as «Посев семян», as on the iPhone. */
        fun fromWire(s: String): AgroKind = entries.firstOrNull { it.wire == s } ?: SEEDING
    }
}

/** One input field: the key in the inputs, the title and the unit shown next to it. */
data class AgroField(
    val key: String,
    val title: String,
    val unit: String,
    /** May stay empty (the result is shown without what depends on it). */
    val optional: Boolean = false,
)

/** A choice of units or of a mode, shown as a segmented control; options are (value, title). */
data class AgroChoice(val key: String, val title: String, val options: List<Pair<String, String>>) {
    val defaultValue: String get() = options.firstOrNull()?.first ?: ""
}

data class AgroResult(
    val lines: List<AgroLine> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Wrong values (text, zero, negative, out of range). */
    val errors: List<String> = emptyList(),
    /** Required fields still empty. */
    val missing: List<String> = emptyList(),
) {
    /** Everything filled in and correct: the calculation can be saved. */
    val isComplete: Boolean get() = errors.isEmpty() && missing.isEmpty() && lines.isNotEmpty()
}

sealed class AgroError(message: String) : Exception(message) {
    data class Invalid(val messages: List<String>) : AgroError(messages.joinToString("\n"))
}

private fun String.trimBlank(): String = trim { it.isWhitespace() || it == ' ' || it == ' ' }

/** Numbers as the owner types them («12,5», «1 200») and as they are shown (at most 2 decimals, «9 302,33»). */
object AgroNumber {
    fun parse(text: String?): Double? {
        if (text == null) return null
        val cleaned = text.filter { !it.isWhitespace() && it != ' ' && it != ' ' }.replace(',', '.')
        if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1) return null
        if (!cleaned.all { it in '0'..'9' || it == '.' || it == '-' }) return null
        val v = cleaned.toDoubleOrNull() ?: return null
        return if (v.isFinite()) v else null
    }

    /** Rounded half away from zero to `decimals`, trailing zeros dropped, thousands by a no-break space. */
    fun format(v: Double, decimals: Int = 2): String {
        var scale = 1.0
        repeat(decimals) { scale *= 10 }
        val x = abs(v) * scale
        val fl = floor(x)
        val rounded = if (x - fl >= 0.5) fl + 1 else fl
        val whole = floor(rounded / scale).toLong()
        var fraction = (rounded - whole.toDouble() * scale).toLong().toString()
        while (fraction.length < decimals) fraction = "0$fraction"
        fraction = fraction.trimEnd('0')
        val digits = whole.toString()
        val grouped = StringBuilder()
        for ((i, d) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) grouped.append(NBSP)
            grouped.append(d)
        }
        val sign = if (v < 0 && rounded > 0) "−" else ""
        return sign + grouped + (if (fraction.isEmpty()) "" else ",$fraction")
    }

    fun with(v: Double, unit: String, decimals: Int = 2): String =
        format(v, decimals) + (if (unit.isEmpty()) "" else NBSP + unit)
}

/** The plots of «Площадь поля» in the inputs: «plots» = count, «plot.N.length», «plot.N.width», «plot.N.area». */
object AgroPlots {
    fun count(inputs: Map<String, String>): Int = maxOf(1, inputs["plots"]?.toIntOrNull() ?: 1)

    fun key(index: Int, part: String): String = "plot.$index.$part"
}

/** Every formula of the module. Values are used unrounded; only the shown texts are rounded. Port of `AgroEngine`. */
object AgroEngine {
    fun calculate(kind: AgroKind, inputs: Map<String, String>): AgroResult {
        if (kind == AgroKind.AREA) return area(inputs)
        val errors = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val v = mutableMapOf<String, Double>()
        for (f in kind.fields(inputs)) {
            val text = (inputs[f.key] ?: "").trimBlank()
            if (text.isEmpty()) {
                if (!f.optional) missing += f.title
                continue
            }
            val value = AgroNumber.parse(text)
            if (value == null) {
                errors += "«${f.title}»: введите число, например 12,5"
                continue
            }
            val zeroAllowed = f.optional && f.key == "reserve"
            if (value < 0 || (value == 0.0 && !zeroAllowed)) {
                errors += "«${f.title}» должно быть больше нуля"
                continue
            }
            v[f.key] = value
        }
        if (errors.isNotEmpty() || missing.isNotEmpty()) return AgroResult(errors = errors, missing = missing)
        return when (kind) {
            AgroKind.SEEDING -> seeding(v)
            AgroKind.FERTILIZER -> fertilizer(v, kind, inputs)
            AgroKind.SPRAYING -> spraying(v, kind, inputs)
            AgroKind.YIELD -> yieldOf(v, kind, inputs)
            AgroKind.MOISTURE -> moisture(v)
            AgroKind.FUEL -> fuel(v, kind, inputs)
            AgroKind.TRANSPORT -> transport(v, kind, inputs)
            AgroKind.AREA -> AgroResult()
        }
    }

    private fun seeding(v: Map<String, Double>): AgroResult {
        val area = v["area"]!!
        val rate = v["rate"]!!
        val reserve = v["reserve"] ?: 0.0
        if (reserve > 100) return AgroResult(errors = listOf("Запас — не больше 100 %"))
        val kg = area * rate * (1 + reserve / 100)
        val lines = mutableListOf(
            AgroLine("Нужно семян", AgroNumber.with(kg, "кг"), true),
            AgroLine("В центнерах", AgroNumber.with(kg / 100, "ц")),
            AgroLine("В тоннах", AgroNumber.with(kg / 1000, "т", decimals = 3)),
        )
        if (reserve > 0) lines += AgroLine("Без запаса", AgroNumber.with(area * rate, "кг"))
        v["bag"]?.let { bag ->
            lines += AgroLine("Мешков по ${AgroNumber.format(bag)} кг", "${ceil(kg / bag).toLong()}")
        }
        return AgroResult(lines = lines)
    }

    private fun fertilizer(v: Map<String, Double>, kind: AgroKind, inputs: Map<String, String>): AgroResult {
        val perKg = mapOf("kg" to 1.0, "c" to 100.0, "t" to 1000.0)[kind.choice("rateUnit", inputs)] ?: 1.0
        val kg = v["area"]!! * v["rate"]!! * perKg
        val lines = mutableListOf(
            AgroLine("Нужно удобрения", AgroNumber.with(kg, "кг"), true),
            AgroLine("В тоннах", AgroNumber.with(kg / 1000, "т", decimals = 3)),
        )
        v["package"]?.let { p ->
            lines += AgroLine("Упаковок по ${AgroNumber.format(p)} кг", "${ceil(kg / p).toLong()}")
        }
        v["price"]?.let { price ->
            val cost = if (kind.choice("priceUnit", inputs) == "t") kg / 1000 * price else kg * price
            lines += AgroLine("Стоимость", AgroNumber.with(cost, "₽"), true)
        }
        return AgroResult(lines = lines)
    }

    private fun spraying(v: Map<String, Double>, kind: AgroKind, inputs: Map<String, String>): AgroResult {
        val area = v["area"]!!
        val liquid = kind.choice("productUnit", inputs) != "kg"
        val product = area * v["rate"]!!
        val water = area * v["water"]!!
        // A powder (кг) is not added to the volume: that needs the density.
        val solution = water + (if (liquid) product else 0.0)
        val lines = mutableListOf(
            AgroLine("Препарата", AgroNumber.with(product, if (liquid) "л" else "кг"), true),
            AgroLine("Воды", AgroNumber.with(water, "л")),
            AgroLine("Рабочего раствора", AgroNumber.with(solution, "л"), true),
        )
        val warnings = mutableListOf<String>()
        if (!liquid) {
            warnings += "Препарат в килограммах: в объём раствора он не добавлен — для этого нужна его плотность."
        }
        v["tank"]?.let { tank ->
            val full = floor(solution / tank).toLong()
            val rest = solution - full.toDouble() * tank
            lines += AgroLine("Полных заправок", "$full", true)
            lines += AgroLine("Остаток на последнюю заправку", if (rest > 1e-9) AgroNumber.with(rest, "л") else "нет")
            if (full > 0) {
                lines += AgroLine("Препарата на полный бак", AgroNumber.with(product / solution * tank, if (liquid) "л" else "кг"))
            }
        }
        warnings += "Норму препарата берите только с этикетки: приложение дозировки не назначает."
        return AgroResult(lines = lines, warnings = warnings)
    }

    private fun yieldOf(v: Map<String, Double>, kind: AgroKind, inputs: Map<String, String>): AgroResult {
        val divisor = mapOf("t" to 1.0, "c" to 10.0, "kg" to 1000.0)[kind.choice("weightUnit", inputs)] ?: 1.0
        val tons = v["weight"]!! / divisor
        val perHa = tons / v["area"]!!
        return AgroResult(
            lines = listOf(
                AgroLine("Урожайность", AgroNumber.with(perHa * 10, "ц/га"), true),
                AgroLine("Урожайность", AgroNumber.with(perHa, "т/га", decimals = 3), true),
                AgroLine("Собрано всего", AgroNumber.with(tons, "т", decimals = 3)),
                AgroLine("Собрано всего", AgroNumber.with(tons * 10, "ц")),
            ),
        )
    }

    private fun moisture(v: Map<String, Double>): AgroResult {
        val weight = v["weight"]!!
        val actual = v["actual"]!!
        val target = v["target"]!!
        if (actual >= 100 || target >= 100) return AgroResult(errors = listOf("Влажность — меньше 100 %"))
        if (target >= actual) return AgroResult(errors = listOf("Целевая влажность должна быть меньше фактической"))
        val after = weight * (100 - actual) / (100 - target)
        return AgroResult(
            lines = listOf(
                AgroLine("Исходный вес", AgroNumber.with(weight, "кг")),
                AgroLine("Вес после сушки", AgroNumber.with(after, "кг"), true),
                AgroLine("Потеря массы", AgroNumber.with(weight - after, "кг"), true),
                AgroLine("Удалить воды (расчётно)", AgroNumber.with(weight - after, "кг")),
            ),
            warnings = listOf(
                "Это расчёт теоретической массы при сохранении сухого вещества, без учёта технологических потерь и изменения состава зерна.",
            ),
        )
    }

    private fun fuel(v: Map<String, Double>, kind: AgroKind, inputs: Map<String, String>): AgroResult {
        if (kind.choice("mode", inputs) == "stock") {
            return AgroResult(lines = listOf(AgroLine("Можно обработать", AgroNumber.with(v["fuel"]!! / v["rate"]!!, "га"), true)))
        }
        val liters = v["area"]!! * v["rate"]!!
        val lines = mutableListOf(AgroLine("Нужно топлива", AgroNumber.with(liters, "л"), true))
        v["price"]?.let { lines += AgroLine("Стоимость топлива", AgroNumber.with(liters * it, "₽"), true) }
        return AgroResult(lines = lines)
    }

    private fun area(inputs: Map<String, String>): AgroResult {
        val errors = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val lines = mutableListOf<AgroLine>()
        var total = 0.0
        var counted = 0
        for (i in 1..AgroPlots.count(inputs)) {
            val l = (inputs[AgroPlots.key(i, "length")] ?: "").trimBlank()
            val w = (inputs[AgroPlots.key(i, "width")] ?: "").trimBlank()
            val a = (inputs[AgroPlots.key(i, "area")] ?: "").trimBlank()
            if (l.isEmpty() && w.isEmpty() && a.isEmpty()) continue
            val square: Double
            if (a.isNotEmpty()) {
                val value = AgroNumber.parse(a)
                if (value == null || value <= 0) {
                    errors += "Участок $i: площадь — число больше нуля"
                    continue
                }
                square = value
            } else {
                val length = AgroNumber.parse(l)
                val width = AgroNumber.parse(w)
                if (length == null || width == null || length <= 0 || width <= 0) {
                    errors += "Участок $i: укажите длину и ширину больше нуля"
                    continue
                }
                square = length * width
            }
            total += square
            counted += 1
            lines += AgroLine(
                "Участок $i",
                AgroNumber.with(square, "м²") + " = " + AgroNumber.with(square / 10_000, "га", decimals = 4),
            )
        }
        if (counted == 0 && errors.isEmpty()) missing += "Длина и ширина участка"
        if (errors.isNotEmpty() || counted == 0) return AgroResult(errors = errors, missing = missing)
        lines += AgroLine("Всего", AgroNumber.with(total, "м²"))
        lines += AgroLine("Площадь поля", AgroNumber.with(total / 10_000, "га", decimals = 4), true)
        return AgroResult(
            lines = lines,
            warnings = listOf("Длина × ширина верна только для прямоугольника. Поле сложной формы разбейте на части и сложите их."),
        )
    }

    private fun transport(v: Map<String, Double>, kind: AgroKind, inputs: Map<String, String>): AgroResult {
        val grainKg = v["grain"]!! * (if (kind.choice("grainUnit", inputs) == "kg") 1 else 1000)
        val truckKg = v["truck"]!! * (if (kind.choice("truckUnit", inputs) == "kg") 1 else 1000)
        val full = floor(grainKg / truckKg + 1e-9).toLong()
        val rest = maxOf(0.0, grainKg - full.toDouble() * truckKg)
        val lines = mutableListOf(
            AgroLine("Полных рейсов", "$full", true),
            AgroLine("Остаток на последний рейс", if (rest > 1e-6) AgroNumber.with(rest, "кг") else "нет"),
            AgroLine("Всего рейсов", "${full + (if (rest > 1e-6) 1 else 0)}"),
        )
        v["bag"]?.let { bag ->
            val bags = floor(grainKg / bag + 1e-9).toLong()
            val left = maxOf(0.0, grainKg - bags.toDouble() * bag)
            lines += AgroLine("Полных мешков по ${AgroNumber.format(bag)} кг", "$bags", true)
            lines += AgroLine("Остаток зерна", if (left > 1e-6) AgroNumber.with(left, "кг") else "нет")
        }
        return AgroResult(lines = lines)
    }

    /** The inputs as «Площадь поля: 10 га; …» for the history and the export. */
    fun inputsText(kind: AgroKind, inputs: Map<String, String>): String {
        val parts = mutableListOf<String>()
        for (c in kind.choices) {
            val value = kind.choice(c.key, inputs)
            c.options.firstOrNull { it.first == value }?.let { parts += "${c.title}: ${it.second}" }
        }
        if (kind == AgroKind.AREA) {
            for (i in 1..AgroPlots.count(inputs)) {
                val a = inputs[AgroPlots.key(i, "area")]
                val l = inputs[AgroPlots.key(i, "length")]
                val w = inputs[AgroPlots.key(i, "width")]
                if (!a.isNullOrEmpty()) {
                    parts += "Участок $i: $a м²"
                } else if (!l.isNullOrEmpty() && !w.isNullOrEmpty()) {
                    parts += "Участок $i: $l × $w м"
                }
            }
        }
        for (f in kind.fields(inputs)) {
            val t = inputs[f.key]?.trimBlank()
            if (!t.isNullOrEmpty()) parts += "${f.title}: $t ${f.unit}"
        }
        return parts.joinToString("; ")
    }

    /** The result as text, for copying and sharing. */
    fun shareText(kind: AgroKind, title: String, inputs: Map<String, String>, result: AgroResult): String {
        val out = mutableListOf(kind.title + (if (title.isEmpty()) "" else " — $title"), "Данные: " + inputsText(kind, inputs))
        out += result.lines.map { "${it.title}: ${it.text}" }
        out += result.warnings
        return out.joinToString("\n")
    }

    // ---- Saved calculations (port of `AgroService`) ----

    /**
     * A new calculation, or the corrected `over` one. Only a complete result is saved; the result lines are
     * computed again from the inputs and empty fields are not stored.
     */
    fun record(
        kind: AgroKind, inputs: Map<String, String>, title: String, id: String, now: Instant, over: AgroCalculation? = null,
    ): AgroCalculation {
        val result = calculate(kind, inputs)
        if (!result.isComplete) throw AgroError.Invalid(result.errors + result.missing.map { "Заполните: $it" })
        val cleaned = inputs.filter { it.value.trimBlank().isNotEmpty() }
        val trimmed = title.trim { it.isWhitespace() }
        return over?.copy(title = trimmed, inputs = cleaned, lines = result.lines, updatedAt = now)
            ?: AgroCalculation(
                id = id, kind = kind.wire, title = trimmed, inputs = cleaned, lines = result.lines, createdAt = now, updatedAt = now,
            )
    }

    /** Newest first, filtered by kind, a text in the title or inputs, and `from <= createdAt < to`. */
    fun history(
        items: List<AgroCalculation>, kind: AgroKind? = null, search: String = "", from: Instant? = null, to: Instant? = null,
    ): List<AgroCalculation> {
        val query = search.trimBlank()
        return items.sortedByDescending { it.createdAt }.filter { c ->
            val k = AgroKind.fromWire(c.kind)
            when {
                kind != null && k != kind -> false
                from != null && c.createdAt < from -> false
                to != null && c.createdAt >= to -> false
                query.isEmpty() -> true
                else -> (c.title + " " + k.title + " " + inputsText(k, c.inputs)).contains(query, ignoreCase = true)
            }
        }
    }
}
