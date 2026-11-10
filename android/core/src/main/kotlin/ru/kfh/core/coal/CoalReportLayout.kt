package ru.kfh.core.coal

import java.util.Locale
import ru.kfh.core.report.ReportAlign
import ru.kfh.core.report.ReportBlock
import ru.kfh.core.report.ReportCell
import ru.kfh.core.report.ReportColumn
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.report.ReportPair
import ru.kfh.core.report.ReportTable
import ru.kfh.core.time.FarmTime

/** Turns a day or month report into blocks (port of `CoalReportLayout` in CoalReportDocument.swift). */
object CoalReportLayout {
    const val SIGNATURE_LABEL = "Руководитель КФХ: ___________________"
    const val REMAINDER_NOTE = "Остаток после выплат водителям — не чистая прибыль: себестоимость угля и прочие расходы не учтены."

    fun document(r: CoalPeriodReport, farm: FarmTime): ReportDocument {
        val zone = farm.zone
        val blocks = mutableListOf<ReportBlock>()
        val period = r.periodTitle
        if (!r.isMonth) {
            blocks += ReportBlock.Title("ОТЧЁТ ЗА РАБОЧИЙ ДЕНЬ")
            blocks += ReportBlock.BoldText(r.farmName)
            blocks += ReportBlock.Text("Дата: $period (${CoalText.longDate(r.start).substringBefore(",")})")
            val closedAt = r.closedAt
            blocks += if (closedAt != null) {
                ReportBlock.Text("Рабочий день завершён: ${CoalText.dateTime(closedAt, zone)}")
            } else {
                ReportBlock.Text("Рабочий день не завершён")
            }
        } else {
            blocks += ReportBlock.Title("ОТЧЁТ ЗА МЕСЯЦ")
            blocks += ReportBlock.BoldText(r.farmName)
            blocks += ReportBlock.Text("Месяц: $period")
        }

        blocks += ReportBlock.Heading("Продажи угля")
        if (r.rows.isEmpty()) {
            blocks += ReportBlock.Text("Продаж нет.")
        } else {
            blocks += ReportBlock.Table(salesTable(r, farm))
            val adjusted = r.rows.mapNotNull { row ->
                val list = row.listTotal ?: return@mapNotNull null
                "№${row.number}: по прайсу ${CoalMath.rubles(list)}, фактически ${CoalMath.rubles(row.totalAmount)}"
            }
            if (adjusted.isNotEmpty()) blocks += ReportBlock.Note("* Цена изменена вручную. " + adjusted.joinToString("; ") + ".")
        }

        val t = r.totals
        blocks += ReportBlock.Heading("Финансовые итоги")
        val pairs = mutableListOf(
            ReportPair("Продаж", ReportCell.count(t.salesCount), false),
            ReportPair("Продано угля, т", ReportCell.tons(t.kilograms), false),
            ReportPair("Доставок наёмными машинами", ReportCell.count(t.hiredDeliveries), false),
            ReportPair("Доставок собственной машиной", ReportCell.count(t.ownDeliveries), false),
            ReportPair("А. Общая выручка хозяйства, ₽", ReportCell.money(t.revenue), true),
            ReportPair("Б. Стоимость проданного угля, ₽", ReportCell.money(t.coalAmount), false),
            ReportPair("В. Общая стоимость доставки, ₽", ReportCell.money(t.deliveryAmount), false),
            ReportPair("Г. Выплаты наёмным водителям (начислено), ₽", ReportCell.money(t.driverPayments), false),
            ReportPair("Д. Доход от собственной машины, ₽", ReportCell.money(t.ownVehicleDeliveryRevenue), false),
            ReportPair("Е. Остаток после выплат водителям (А − Г), ₽", ReportCell.money(t.remainderAfterDrivers), true),
        )
        if (r.totalPaid > 0) {
            pairs += ReportPair("Фактически выплачено водителям за период, ₽", ReportCell.money(r.totalPaid), false)
        }
        blocks += ReportBlock.Pairs(pairs)
        blocks += ReportBlock.Note(REMAINDER_NOTE)

        blocks += ReportBlock.Heading("Взаиморасчёты с водителями")
        if (r.drivers.isEmpty()) {
            blocks += ReportBlock.Text("Начислений наёмным водителям нет.")
        } else {
            blocks += ReportBlock.Table(driversTable(r))
            blocks += ReportBlock.Note(
                "Начисленное не считается выплаченным: долг уменьшается только записанными выплатами " +
                    "и переносится на следующие дни.",
            )
        }

        if (r.isMonth && r.locations.isNotEmpty()) {
            blocks += ReportBlock.Heading("Итоги по населённым пунктам")
            blocks += ReportBlock.Table(locationsTable(r))
        }

        if (r.payments.isNotEmpty()) {
            blocks += ReportBlock.Heading("Выплаты водителям")
            blocks += ReportBlock.Table(paymentsTable(r, farm))
        }

        blocks += ReportBlock.Signature(SIGNATURE_LABEL, r.managerName)
        blocks += ReportBlock.Note("Дата формирования: ${CoalText.dateTime(r.createdAt, zone)}")

        val kindTitle = if (r.isMonth) "Отчёт за месяц" else "Отчёт за день"
        return ReportDocument(fileName(r), "${r.farmName} · $kindTitle $period", blocks)
    }

    /**
     * «Выплаты водителям» for a day or a month: what each hired driver earned, was paid and is owed, and the payments
     * themselves.
     */
    fun paymentsDocument(r: CoalPeriodReport, farm: FarmTime): ReportDocument {
        val period = r.periodTitle
        val blocks = mutableListOf<ReportBlock>(
            ReportBlock.Title("ВЫПЛАТЫ ВОДИТЕЛЯМ"),
            ReportBlock.BoldText(r.farmName),
            ReportBlock.Text(if (r.isMonth) "Месяц: $period" else "Дата: $period"),
            ReportBlock.Heading("Взаиморасчёты с водителями"),
        )
        if (r.drivers.isEmpty()) {
            blocks += ReportBlock.Text("Начислений и долгов перед наёмными водителями нет.")
        } else {
            blocks += ReportBlock.Table(driversTable(r))
            blocks += ReportBlock.Note(
                "«К выплате» — долг на конец периода: начислено за доставки минус выплачено. " +
                    "Начисленное само выплаченным не считается.",
            )
        }
        blocks += ReportBlock.Heading("Выплаты")
        if (r.payments.isEmpty()) {
            blocks += ReportBlock.Text("Выплат за период не было.")
        } else {
            blocks += ReportBlock.Table(paymentsTable(r, farm))
        }
        blocks += ReportBlock.Signature(SIGNATURE_LABEL, r.managerName)
        blocks += ReportBlock.Note("Дата формирования: ${CoalText.dateTime(r.createdAt, farm.zone)}")
        val d = r.start
        val name = if (r.isMonth) {
            String.format(Locale.ROOT, "Выплаты_водителям_%04d-%02d", d.year, d.monthValue)
        } else {
            String.format(Locale.ROOT, "Выплаты_водителям_%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
        }
        return ReportDocument(name, "${r.farmName} · Выплаты водителям $period", blocks)
    }

    /** «Уголь_день_2026-11-06», «Уголь_месяц_2026-11». */
    fun fileName(r: CoalPeriodReport): String {
        val d = r.start
        return if (r.isMonth) {
            String.format(Locale.ROOT, "Уголь_месяц_%04d-%02d", d.year, d.monthValue)
        } else {
            String.format(Locale.ROOT, "Уголь_день_%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
        }
    }

    /** «Наёмная» / «Своя» (`VehicleType.shortTitle`). */
    private fun shortTitle(vehicle: String): String = if (vehicle == VehicleType.HIRED) "Наёмная" else "Своя"

    private fun salesTable(r: CoalPeriodReport, farm: FarmTime): ReportTable {
        val timeTitle = if (r.isMonth) "Дата, время" else "Время"
        val columns = listOf(
            ReportColumn("№", 4.0, ReportAlign.CENTER),
            ReportColumn(timeTitle, if (r.isMonth) 12.0 else 7.0, ReportAlign.CENTER),
            ReportColumn("Тонн", 6.0, ReportAlign.RIGHT),
            ReportColumn("Населённый пункт", 17.0, ReportAlign.LEFT),
            ReportColumn("Машина", 9.0, ReportAlign.LEFT),
            ReportColumn("Водитель", 15.0, ReportAlign.LEFT),
            ReportColumn("Уголь, ₽", 11.0, ReportAlign.RIGHT),
            ReportColumn("Доставка, ₽", 10.0, ReportAlign.RIGHT),
            ReportColumn("Итого, ₽", 11.0, ReportAlign.RIGHT),
        )
        val rows = r.rows.map { row ->
            val time = if (r.isMonth) CoalText.dateTime(row.date, farm.zone) else CoalText.time(row.date, farm.zone)
            val total = ReportCell.money(row.totalAmount)
            listOf(
                ReportCell.count(row.number), ReportCell.text(time), ReportCell.tons(row.kilograms), ReportCell.text(row.location),
                ReportCell.text(shortTitle(row.vehicle)), ReportCell.text(row.driver.ifEmpty { "—" }),
                ReportCell.money(row.coalAmount), ReportCell.money(row.deliveryPrice),
                if (row.listTotal == null) total else ReportCell(total.text + "*", ReportCell.Kind.MONEY, total.number),
            )
        }
        val t = r.totals
        return ReportTable(
            columns, rows,
            listOf(
                ReportCell.text(""), ReportCell.text("Итого"), ReportCell.tons(t.kilograms), ReportCell.text(CoalText.sales(t.salesCount)),
                ReportCell.text(""), ReportCell.text(""), ReportCell.money(t.coalAmount), ReportCell.money(t.deliveryAmount),
                ReportCell.money(t.revenue),
            ),
        )
    }

    private fun driversTable(r: CoalPeriodReport): ReportTable {
        val columns = listOf(
            ReportColumn("Водитель", 26.0, ReportAlign.LEFT),
            ReportColumn("Доставок", 9.0, ReportAlign.CENTER),
            ReportColumn("Долг на начало, ₽", 14.0, ReportAlign.RIGHT),
            ReportColumn("Начислено, ₽", 13.0, ReportAlign.RIGHT),
            ReportColumn("Выплачено, ₽", 13.0, ReportAlign.RIGHT),
            ReportColumn("К выплате, ₽", 14.0, ReportAlign.RIGHT),
        )
        val rows = r.drivers.map {
            listOf(
                ReportCell.text(it.name), ReportCell.count(it.deliveries), ReportCell.money(it.debtBefore), ReportCell.money(it.accrued),
                ReportCell.money(it.paid), ReportCell.money(it.debtAfter),
            )
        }
        return ReportTable(
            columns, rows,
            listOf(
                ReportCell.text("ИТОГО К ВЫПЛАТЕ"), ReportCell.count(r.drivers.sumOf { it.deliveries }),
                ReportCell.money(r.drivers.sumOf { it.debtBefore }), ReportCell.money(r.drivers.sumOf { it.accrued }),
                ReportCell.money(r.drivers.sumOf { it.paid }), ReportCell.money(r.drivers.sumOf { it.debtAfter }),
            ),
        )
    }

    private fun locationsTable(r: CoalPeriodReport): ReportTable {
        val columns = listOf(
            ReportColumn("Населённый пункт", 30.0, ReportAlign.LEFT),
            ReportColumn("Продаж", 10.0, ReportAlign.CENTER),
            ReportColumn("Тонн", 10.0, ReportAlign.RIGHT),
            ReportColumn("Доставка, ₽", 15.0, ReportAlign.RIGHT),
            ReportColumn("Выручка, ₽", 15.0, ReportAlign.RIGHT),
        )
        val rows = r.locations.map {
            listOf(ReportCell.text(it.name), ReportCell.count(it.salesCount), ReportCell.tons(it.kilograms), ReportCell.money(it.deliveryAmount), ReportCell.money(it.revenue))
        }
        val t = r.totals
        return ReportTable(
            columns, rows,
            listOf(ReportCell.text("Итого"), ReportCell.count(t.salesCount), ReportCell.tons(t.kilograms), ReportCell.money(t.deliveryAmount), ReportCell.money(t.revenue)),
        )
    }

    private fun paymentsTable(r: CoalPeriodReport, farm: FarmTime): ReportTable {
        val columns = listOf(
            ReportColumn("Дата", 14.0, ReportAlign.CENTER),
            ReportColumn("Водитель", 26.0, ReportAlign.LEFT),
            ReportColumn("Сумма, ₽", 14.0, ReportAlign.RIGHT),
            ReportColumn("Комментарий", 30.0, ReportAlign.LEFT),
        )
        val rows = r.payments.map {
            listOf(ReportCell.text(CoalText.dateTime(it.date, farm.zone)), ReportCell.text(it.driver), ReportCell.money(it.amount), ReportCell.text(it.comment))
        }
        return ReportTable(columns, rows, listOf(ReportCell.text("Итого"), ReportCell.text(""), ReportCell.money(r.totalPaid), ReportCell.text("")))
    }
}
