package ru.kfh.core.agro

import java.time.Instant
import java.util.Locale
import ru.kfh.core.coal.CoalReportLayout
import ru.kfh.core.coal.CoalText
import ru.kfh.core.model.AgroCalculation
import ru.kfh.core.report.ReportAlign
import ru.kfh.core.report.ReportBlock
import ru.kfh.core.report.ReportCell
import ru.kfh.core.report.ReportColumn
import ru.kfh.core.report.ReportDocument
import ru.kfh.core.report.ReportTable
import ru.kfh.core.time.FarmTime
import ru.kfh.core.timesheet.FarmInfo

/** The history of calculations as a printable document (port of `AgroReportLayout` in AgroModels.swift). */
object AgroReportLayout {
    fun history(items: List<AgroCalculation>, info: FarmInfo, createdAt: Instant, farm: FarmTime): ReportDocument {
        val zone = farm.zone
        val blocks = mutableListOf<ReportBlock>(
            ReportBlock.Title("АГРОНОМИЧЕСКИЕ РАСЧЁТЫ"), ReportBlock.BoldText(info.farmName), ReportBlock.Text("Расчётов: ${items.size}"),
        )
        if (items.isEmpty()) {
            blocks += ReportBlock.Text("Сохранённых расчётов нет.")
        } else {
            blocks += ReportBlock.Table(
                ReportTable(
                    columns = listOf(
                        ReportColumn("Дата", 12.0, ReportAlign.CENTER),
                        ReportColumn("Расчёт", 14.0, ReportAlign.LEFT),
                        ReportColumn("Поле / комментарий", 14.0, ReportAlign.LEFT),
                        ReportColumn("Данные", 30.0, ReportAlign.LEFT),
                        ReportColumn("Результат", 30.0, ReportAlign.LEFT),
                    ),
                    rows = items.map { c ->
                        val kind = AgroKind.fromWire(c.kind)
                        listOf(
                            ReportCell.text(CoalText.dateTime(c.createdAt, zone)), ReportCell.text(kind.title), ReportCell.text(c.title),
                            ReportCell.text(AgroEngine.inputsText(kind, c.inputs)),
                            ReportCell.text(c.lines.joinToString("; ") { "${it.title}: ${it.text}" }),
                        )
                    },
                    total = null,
                ),
            )
        }
        blocks += ReportBlock.Note("Расчёты справочные. Нормы препаратов — только по этикетке.")
        blocks += ReportBlock.Signature(CoalReportLayout.SIGNATURE_LABEL, info.managerName)
        blocks += ReportBlock.Note("Дата формирования: ${CoalText.dateTime(createdAt, zone)}")
        val d = farm.dayOf(createdAt)
        return ReportDocument(
            fileName = String.format(Locale.ROOT, "Агрорасчёты_%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth),
            runningHeader = "${info.farmName} · Агрономические расчёты",
            blocks = blocks,
        )
    }
}
