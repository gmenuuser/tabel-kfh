package ru.kfh.tabel

import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import ru.kfh.core.agro.AgroNumber
import ru.kfh.core.coal.CoalMath
import ru.kfh.tabel.ui.home.longDate

/**
 * The iPhone's walkthroughs (`TabelKFHUITests`, the `…WalkthroughTests.swift` files) on Android, against the [FarmHarness]
 * server: the owner's first day in the timesheet, a coal day with two drivers and the payments, the herd with a
 * slaughter, the calculators, the reports, the helper and the viewer roles, and the dark theme. Every screen is
 * kept as `review-samples/screens/NN-name.png` (the iPhone's numbering) for the review of the CI artifact.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WalkthroughTest {
    private val compose = createEmptyComposeRule()
    private val farm = FarmHarness(compose, "uitest-walk.db")

    /** The dark walkthrough switches the system's night mode (API 29+) before the activity starts; day again afterwards. */
    private val night = object : TestWatcher() {
        override fun finished(description: Description) {
            if (description.methodName == "darkTheme" && Build.VERSION.SDK_INT >= 29) FarmHarness.shell("cmd uimode night no")
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(night).around(farm).around(compose)

    // Добавление сотрудника, часы за день четырьмя кнопками, вчерашний день, сотрудники, табель за месяц, отчёт, настройки.
    @Test
    fun timesheetDay() {
        farm.launch()
        farm.signIn("14-sign-in")
        compose.waitUntilExactlyOneExists(hasText("Все данные синхронизированы"), 10_000)
        for (tag in listOf("openTimesheet", "openCoal", "openLivestock", "openAgro", "openReports")) compose.onNodeWithTag(tag).assertExists()
        farm.shot("00-main")

        compose.onNodeWithTag("openTimesheet").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("addEmployee"), 10_000)
        compose.onNodeWithText("В этот день нет работающих сотрудников.").assertIsDisplayed()
        farm.shot("01-home-empty")

        addEmployee("02-employee-form")
        compose.waitUntilExactlyOneExists(hasTestTag("row-Зубахин"), 10_000)
        compose.onNodeWithText("Зубахин Иван Петрович").assertIsDisplayed()
        farm.shot("03-home-employee")

        markPresence(screenshots = true)

        // «Сотрудники и история»: the person with the position, the history with «Часов за месяц» and «В архив».
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openEmployees").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("employee-Зубахин"), 10_000)
        anyText("Тракторист")
        farm.shot("07-employees")
        compose.onNodeWithTag("employee-Зубахин").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("monthHours"), 10_000)
        compose.onNodeWithText("Часов за месяц").assertExists()
        farm.shot("07a-employee-history")
        compose.onNodeWithTag("archive").performScrollTo().assertExists()
        farm.shot("07b-employee-history-archive")
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("employee-Зубахин"), 10_000)
        compose.onNodeWithTag("back").performClick()

        generateTimesheet()
        farm.shot("09-timesheet")
        for (tag in listOf("exportPdf", "exportXlsx", "exportPrint", "exportShare")) compose.onNodeWithTag(tag).performScrollTo().assertExists()
        farm.shot("10-timesheet-export")

        // «Отчёты» → «Табель работников»: the preview and the export bar.
        farm.backToMain()
        compose.onNodeWithTag("openReports").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("report-timesheet"), 10_000)
        compose.onNodeWithTag("report-timesheet").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("exportPdf"), 15_000)
        farm.shot("10a-timesheet-report")

        // «Настройки»: the owner's rows, «Помощники» with the helper, «Сохранить копию в файл», «Выйти».
        farm.backToMain()
        compose.onNodeWithTag("openSettings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("signOut"), 10_000)
        farm.shot("11-settings")
        compose.onNodeWithTag("openHelpers").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("helper-m1"), 10_000)
        anyText("anna")
        farm.shot("13-helpers")
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("signOut"), 10_000)
        compose.onNodeWithTag("saveBackup").performScrollTo().assertExists()
        compose.onNodeWithTag("signOut").performScrollTo().assertExists()
        farm.shot("12-settings-backup")
    }

    // A timesheet helper sees only «ТАБЕЛЬ РАБОТНИКОВ» and its reports.
    @Test
    fun timesheetHelperHome() {
        farm.role = "timesheet"
        farm.launch()
        farm.signIn()
        compose.onNodeWithTag("openReports").assertExists()
        for (hidden in listOf("openCoal", "openLivestock", "openAgro")) compose.onNodeWithTag(hidden).assertDoesNotExist()
        compose.onNodeWithTag("syncStatus").assertExists()
        farm.shot("15-helper-timesheet-home")
        compose.onNodeWithTag("openTimesheet").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("addEmployee"), 10_000)
    }

    // The viewer reads everything and changes nothing: no «Добавить сотрудника», the sale form is inactive.
    @Test
    fun viewerReadsOnly() {
        farm.role = "viewer"
        farm.launch()
        farm.signIn()
        compose.onNodeWithTag("openCoal").assertExists()
        compose.onNodeWithTag("openTimesheet").performClick()
        compose.waitUntilExactlyOneExists(hasText("Табель работников"), 10_000)
        compose.onNodeWithTag("addEmployee").assertDoesNotExist()
        farm.backToMain()
        compose.onNodeWithTag("openCoal").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("saveSale"), 15_000)
        compose.onNodeWithTag("saveSale").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("closeDay").assertDoesNotExist()
        farm.shot("16-viewer-coal")
    }

    // Coal sales the way the owner works: drivers from the truck buttons, two sales, the day closed, the payment, the reports.
    @Test
    fun coalDay() {
        farm.launch()
        farm.signIn()
        openCoal()
        farm.shot("30-coal-empty")

        addDriver("Мунгалов", "Сергей")
        addDriver("Кайдолов", "Пётр")
        compose.onNodeWithTag("driver-Мунгалов").performScrollTo().assertExists()
        compose.onNodeWithTag("driver-Кайдолов").assertExists()
        farm.shot("31-coal-drivers")

        sell("2", "Новоцурухайтуй", "driver-Мунгалов", CoalMath.rubles(1_400_000), "32-coal-sale")
        compose.onNodeWithText("Наёмная: Мунгалов", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("saleTotal").performScrollTo().assertTextEquals(CoalMath.rubles(0))
        sell("1", "Приаргунск", "vehicle-own", CoalMath.rubles(770_000), "33-coal-sale-own")
        compose.onNodeWithText("Собственная машина КФХ", useUnmergedTree = true).assertExists()
        farm.shot("34-coal-today")

        // «ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ»: what to pay Мунгалов, the own truck's delivery income, then the confirmation.
        compose.onNodeWithTag("closeDay").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("confirmClose"), 10_000)
        anyText("Мунгалов Сергей: к выплате")
        anyText("Доход от собственной машины")
        farm.shot("35-close-day")
        compose.onNodeWithTag("confirmClose").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Рабочий день завершён"), 10_000)
        farm.shot("36-day-closed")

        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openSummary").performClick()
        compose.waitUntilExactlyOneExists(hasText("Финансовые итоги"), 10_000)
        farm.shot("37-day-summary")

        farm.backToMain()
        farm.shot("38-main-after-sales")
        compose.onNodeWithTag("openReports").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("report-payments"), 10_000)
        farm.shot("39-reports")

        // «Выплаты водителям»: Мунгалов gets 500 ₽ of his 1 000 ₽.
        compose.onNodeWithTag("report-payments").performClick()
        val driverId = ops().single { it.entity == "driver" && it.fieldsJson()["lastName"].toString().contains("Мунгалов") }.recordId
        compose.waitUntilExactlyOneExists(hasTestTag("pay-$driverId"), 10_000)
        compose.onNodeWithTag("debt-$driverId").assertTextEquals(CoalMath.rubles(100_000))
        farm.shot("40-payments")
        compose.onNodeWithTag("pay-$driverId").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("paymentAmount"), 10_000)
        compose.onNodeWithTag("paymentAmount").performTextClearance()
        compose.onNodeWithTag("paymentAmount").performTextInput("500")
        farm.shot("41-payment")
        compose.onNodeWithTag("payConfirm").performClick()
        compose.waitUntilExactlyOneExists(hasText("получил ${CoalMath.rubles(50_000)}", substring = true), 10_000)
        compose.onNodeWithTag("debt-$driverId").assertTextEquals(CoalMath.rubles(50_000))
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("report-coal"), 10_000)
        compose.onNodeWithTag("report-coal").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("exportXlsx"), 15_000)
        farm.shot("42-sales-report")

        // «Настройки» → «Цена угля и населённые пункты», «Водители».
        farm.backToMain()
        compose.onNodeWithTag("openSettings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openCoalPrices"), 10_000)
        compose.onNodeWithTag("openCoalPrices").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("locationRow-Приаргунск"), 10_000)
        farm.shot("43-prices")
        compose.onNodeWithTag("back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openDrivers"), 10_000)
        compose.onNodeWithTag("openDrivers").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("driverRow-Мунгалов"), 10_000)
        anyText("Мунгалов Сергей")
        farm.shot("44-drivers")
    }

    // «Скот и забой»: 20 коров, a decrease needs a reason, a slaughter of 2 with the confirmation, the history, the reports.
    @Test
    fun herdAndSlaughter() {
        farm.launch()
        farm.signIn()
        compose.onNodeWithTag("openLivestock").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("openHerd"), 10_000)
        compose.waitUntil(10_000) { ops().count { it.entity == "livestockCategory" } == 15 }
        farm.shot("60-livestock-empty")

        compose.onNodeWithTag("openHerd").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("add-Коровы"), 10_000)
        farm.shot("61-herd")
        compose.onNodeWithTag("add-Коровы").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("headsField"), 10_000)
        compose.onNodeWithTag("headsField").performTextInput("20")
        closeSoftKeyboard()
        farm.shot("62-add")
        compose.onNodeWithTag("saveOperation").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("heads-Коровы"), 10_000)
        compose.waitUntil(10_000) { ops().count { it.entity == "livestockOperation" } == 1 }
        compose.onNodeWithTag("heads-Коровы").assertTextEquals("20")

        // «Уменьшить» needs a reason first; «Отмена» writes nothing off.
        compose.onNodeWithTag("decrease-Коровы").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("headsField"), 10_000)
        compose.onNodeWithTag("headsField").performTextInput("1")
        closeSoftKeyboard()
        compose.onNodeWithTag("saveOperation").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Выберите причину уменьшения"), 10_000)
        farm.shot("63-decrease-reason")
        compose.onNodeWithTag("cancel").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("heads-Коровы"), 10_000)
        compose.onNodeWithTag("heads-Коровы").assertTextEquals("20")
        compose.onNodeWithTag("back").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag("openSlaughter"), 10_000)
        compose.onNodeWithTag("openSlaughter").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("category-Коровы"), 10_000)
        compose.onNodeWithTag("category-Коровы").performClick()
        compose.onNodeWithTag("quick-2").performClick()
        farm.shot("64-slaughter")
        compose.onNodeWithTag("saveSlaughter").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Будет списано: 2 головы КРС (Коровы). Продолжить?"), 5_000)
        farm.shot("65-slaughter-confirm")
        compose.onNodeWithTag("confirm").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("savedNote"), 10_000)
        compose.onNodeWithTag("savedNote").assertTextEquals("Записано: забой 2 головы КРС (Коровы)")
        farm.shot("66-slaughter-saved")
        compose.onNodeWithTag("back").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag("herdSummary"), 10_000)
        compose.waitUntil(10_000) { ops().count { it.entity == "slaughterRecord" } == 1 }
        compose.onNodeWithTag("herd-cattle").assertTextEquals("18")
        farm.shot("67-livestock-after")

        compose.onNodeWithTag("openLivestockHistory").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("operationsCount"), 10_000)
        compose.onNodeWithTag("operationsCount").assertTextEquals("Операции (2)")
        anyText("Забой")
        anyText("Поступление")
        farm.shot("68-history")
        compose.onNodeWithTag("back").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag("openLivestockReports"), 10_000)
        compose.onNodeWithTag("openLivestockReports").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("exportPdf"), 15_000)
        farm.shot("69-reports")
    }

    // The calculators: seeding saved to the history, spraying with the tank, the history.
    @Test
    fun calculators() {
        farm.launch()
        farm.signIn()
        compose.onNodeWithTag("openAgro").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("agro-seeding"), 10_000)
        farm.shot("70-agro")

        compose.onNodeWithTag("agro-seeding").performScrollTo().performClick()
        type("field-area", "10")
        type("field-rate", "200")
        compose.waitUntilExactlyOneExists(hasTestTag("line-0"), 5_000)
        compose.onNodeWithTag("line-0").assertTextEquals(AgroNumber.with(2000.0, "кг"))
        closeSoftKeyboard()
        compose.onNodeWithTag("agroResult").performScrollTo()
        farm.shot("71-seeding")
        compose.onNodeWithTag("saveCalc").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Расчёт сохранён в истории"), 10_000)
        compose.onNodeWithTag("back").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag("agro-spraying"), 10_000)
        compose.onNodeWithTag("agro-spraying").performScrollTo().performClick()
        type("field-area", "10")
        type("field-rate", "2")
        type("field-water", "200")
        type("field-tank", "1000")
        closeSoftKeyboard()
        compose.waitUntilExactlyOneExists(hasText("Полных заправок", substring = true), 10_000)
        compose.onNodeWithText("Полных заправок", substring = true).performScrollTo()
        farm.shot("72-spraying")
        compose.onNodeWithTag("back").performClick()

        compose.waitUntilExactlyOneExists(hasText("История расчётов (1)"), 10_000)
        compose.onNodeWithTag("openAgroHistory").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("agroCount"), 10_000)
        anyText("Посев семян")
        farm.shot("73-agro-history")
    }

    // The dark theme: the main screen, a sale, the timesheet (the iPhone's 2N and 5N pictures).
    @Test
    fun darkTheme() {
        assumeTrue("night mode through the shell needs API 29", Build.VERSION.SDK_INT >= 29)
        FarmHarness.shell("cmd uimode night yes")
        val app = ApplicationProvider.getApplicationContext<TabelApp>()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !app.isNight()) Thread.sleep(200)
        assertTrue("the system is in night mode", app.isNight())

        farm.launch()
        farm.signIn("20-dark-sign-in")
        compose.waitUntilExactlyOneExists(hasText("Все данные синхронизированы"), 10_000)
        farm.shot("20-dark-main")
        compose.onNodeWithTag("openTimesheet").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("addEmployee"), 10_000)
        addEmployee(null)
        compose.waitUntilExactlyOneExists(hasTestTag("row-Зубахин"), 10_000)
        markPresence(screenshots = false)
        farm.shot("22-dark-home")
        generateTimesheet()
        farm.shot("23-dark-timesheet")

        farm.backToMain()
        openCoal()
        addDriver("Мунгалов", "Сергей")
        sell("2", "Новоцурухайтуй", "driver-Мунгалов", CoalMath.rubles(1_400_000), "50-dark-coal-sale")
        farm.shot("51-dark-coal")
        farm.backToMain()
        farm.shot("52-dark-main")
    }

    // MARK: - Steps

    private fun TabelApp.isNight() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun addEmployee(screenshot: String?) {
        compose.onNodeWithTag("addEmployee").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Новый сотрудник"), 10_000)
        compose.onNodeWithTag("lastName").performTextInput("Зубахин")
        compose.onNodeWithTag("firstName").performTextInput("Иван")
        compose.onNodeWithTag("patronymic").performTextInput("Петрович")
        compose.onNodeWithTag("position").performTextInput("Тракторист")
        closeSoftKeyboard()
        if (screenshot != null) farm.shot(screenshot)
        compose.onNodeWithTag("save").performClick()
    }

    /** 8 ч, then 4 ч replaces it, then 4 ч again removes the mark, then 12 ч; yesterday has no one (hired today). */
    private fun markPresence(screenshots: Boolean) {
        val today = farm.graph.timesheet.today()
        anyText("Не отмечен")
        compose.onNodeWithTag("hours-8-Зубахин").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Отмечено: 8 ч"), 10_000)
        if (screenshots) farm.shot("04-home-hours")
        compose.onNodeWithTag("hours-4-Зубахин").performClick()
        compose.waitUntilExactlyOneExists(hasText("Отмечено: 4 ч"), 10_000)
        compose.onNodeWithTag("hours-4-Зубахин").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Не отмечен", substring = true), 10_000)
        compose.onNodeWithTag("hours-12-Зубахин").performClick()
        compose.waitUntilExactlyOneExists(hasText("Отмечено: 12 ч"), 10_000)
        if (screenshots) farm.shot("05-home-12")

        compose.onNodeWithTag("day-Вчера").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText(longDate(today.minusDays(1))), 10_000)
        compose.waitUntilExactlyOneExists(hasText("нет работающих сотрудников", substring = true), 10_000)
        if (screenshots) farm.shot("06-home-yesterday")
        compose.onNodeWithTag("day-Сегодня").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Отмечено: 12 ч"), 10_000)
    }

    /** The month table: 12 on today, then a tap on the cell and «8 ч» makes it 8 hours. */
    private fun generateTimesheet() {
        val today = farm.graph.timesheet.today()
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openMonth").performClick()
        compose.waitUntilExactlyOneExists(hasText("Табель за месяц"), 10_000)
        compose.onNodeWithTag("monthTitle").assertExists()
        val employeeId = ops().single { it.entity == "employee" }.recordId
        val cell = "cell-$employeeId-${today.dayOfMonth}"
        compose.waitUntilExactlyOneExists(hasTestTag(cell), 10_000)
        compose.onNodeWithTag(cell).assertTextEquals("12")
        // Today's column is past the seventh day: the days scroll sideways under one finger.
        compose.onNodeWithTag(cell).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("choose-8"), 10_000)
        compose.onNodeWithTag("choose-8").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(cell) and hasText("8")).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Всего отработано").assertExists()
    }

    private fun openCoal() {
        compose.onNodeWithTag("openCoal").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("location-Новоцурухайтуй"), 15_000)
    }

    /** From the «+ Водитель» tile among the trucks. */
    private fun addDriver(lastName: String, firstName: String) {
        compose.onNodeWithTag("addDriver").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("driverLastName"), 5_000)
        compose.onNodeWithTag("driverLastName").performTextInput(lastName)
        compose.onNodeWithTag("driverFirstName").performTextInput(firstName)
        compose.onNodeWithTag("driverSave").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("driver-$lastName"), 10_000)
    }

    /** Tons, village, truck, then «СОХРАНИТЬ ПРОДАЖУ» — all on the one screen. */
    private fun sell(tons: String, place: String, vehicleTag: String, total: String, shotName: String) {
        compose.onNodeWithTag("tons-$tons").performScrollTo().performClick()
        compose.onNodeWithTag("tons-$tons").assertIsSelected()
        compose.onNodeWithTag("location-$place").performScrollTo().performClick()
        compose.onNodeWithTag("location-$place").assertIsSelected()
        compose.onNodeWithTag(vehicleTag).performScrollTo().performClick()
        compose.onNodeWithTag(vehicleTag).assertIsSelected()
        compose.onNodeWithTag("saleTotal").performScrollTo().assertTextEquals(total)
        farm.shot(shotName)
        compose.onNodeWithTag("saveSale").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Сохранено:", substring = true), 10_000)
    }

    /** At least one node shows [text] (the iPhone's `element(containing:)`). */
    private fun anyText(text: String) {
        compose.onAllNodesWithText(text, substring = true).onFirst().assertExists()
    }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
    }

    private fun ops() = runBlocking { farm.graph.db.outbox().all() }
}
