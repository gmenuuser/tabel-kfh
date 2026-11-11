package ru.kfh.tabel

import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * The owner's eyes and the cheapest phone: font scale 2.0 («Огромный» in the Android settings) on a 360×640 dp
 * screen (720×1280 px at 320 dpi, the Galaxy Nexus profile of the CI). Every big button of the main screen and
 * «СОХРАНИТЬ ПРОДАЖУ» are reachable and displayed after a scroll, and their labels are shown whole: not clipped,
 * not ellipsized. The display and the font scale are set through the shell before the activity starts and reset
 * after the test, so the test holds on any emulator (the Medium_Phone at home, the CI's profiles).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmallScreenLargeFontTest {
    private val compose = createEmptyComposeRule()
    private val farm = FarmHarness(compose, "uitest-small.db")

    /** 360×640 dp at font scale 2.0 for the test, the device's own values back afterwards (even after a failure). */
    private val display = object : TestWatcher() {
        override fun starting(description: Description) {
            FarmHarness.shell("settings put system font_scale 2.0")
            FarmHarness.shell("wm size 720x1280")
            FarmHarness.shell("wm density 320")
            val app = ApplicationProvider.getApplicationContext<TabelApp>()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !applied(app)) Thread.sleep(200)
            Log.i(TAG, "display: fontScale=${app.resources.configuration.fontScale} metrics=${app.resources.displayMetrics}")
        }

        private fun applied(app: TabelApp): Boolean {
            val m = app.resources.displayMetrics
            return app.resources.configuration.fontScale == 2f && m.densityDpi == 320 && m.widthPixels == 720
        }

        override fun finished(description: Description) {
            FarmHarness.shell("wm density reset")
            FarmHarness.shell("wm size reset")
            FarmHarness.shell("settings put system font_scale 1.0")
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(display).around(farm).around(compose)

    @Test
    fun homeButtonsAndSaveSaleAreWholeOnASmallScreenWithHugeText() {
        farm.launch()
        farm.signIn()

        // The screen is what the test says it is: 360 dp wide, 640 dp tall, the text twice its size.
        val root = compose.onRoot().getBoundsInRoot()
        assertEquals("root width dp", 360f, root.width.value, 1f)
        assertEquals("root height dp", 640f, root.height.value, 1f)
        assertEquals(2f, farm.app.resources.configuration.fontScale)

        // Every big button of the owner: reachable by a scroll, displayed, its title whole.
        compose.waitUntilExactlyOneExists(hasText("Все данные синхронизированы"), 10_000)
        val buttons = mapOf(
            "openTimesheet" to "ТАБЕЛЬ РАБОТНИКОВ",
            "openCoal" to "ПРОДАЖА УГЛЯ",
            "openLivestock" to "СКОТ И ЗАБОЙ",
            "openAgro" to "АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ",
            "openReports" to "ОТЧЁТЫ",
        )
        farm.shot("90-small-home-top")
        for ((tag, title) in buttons) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            compose.onLabel(tag, title).assertTextNotCut()
            labelInsideButton(tag, title)
        }
        compose.onNodeWithTag("farmName").performScrollTo().assertTextNotCut()
        compose.onNodeWithTag("openAgro").performScrollTo()
        farm.shot("91-small-home-bottom")

        // «СОХРАНИТЬ ПРОДАЖУ» and «ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ» on the sale screen.
        compose.onNodeWithTag("openCoal").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("saveSale"), 15_000)
        compose.onNodeWithTag("tonsField").performTextInput("2")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("saveSale").performScrollTo().assertIsDisplayed()
        compose.onLabel("saveSale", "СОХРАНИТЬ ПРОДАЖУ").assertTextNotCut()
        labelInsideButton("saveSale", "СОХРАНИТЬ ПРОДАЖУ")
        farm.shot("92-small-coal-save")
        compose.onNodeWithTag("closeDay").performScrollTo().assertIsDisplayed()
        compose.onLabel("closeDay", "ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ").assertTextNotCut()
        labelInsideButton("closeDay", "ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ")
        farm.shot("93-small-coal-close")

        // The sign-in button too: the first thing the owner sees.
        farm.backToMain()
        compose.onNodeWithTag("openSettings").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("signOut"), 10_000)
        compose.onNodeWithTag("signOut").performScrollTo().assertIsDisplayed()
        compose.onLabel("signOut", "Выйти").assertTextNotCut()
        farm.shot("94-small-settings")
    }

    /** The label's box lies inside the button's box: a label taller than its button would be the clipping we look for. */
    private fun labelInsideButton(tag: String, title: String) {
        val button = compose.onNodeWithTag(tag).getBoundsInRoot()
        val label = compose.onLabel(tag, title).getBoundsInRoot()
        assertTrue(
            "«$title» ($label) sticks out of its button ($button)",
            label.top >= button.top - 0.5f.dp() && label.bottom <= button.bottom + 0.5f.dp() &&
                label.left >= button.left - 0.5f.dp() && label.right <= button.right + 0.5f.dp(),
        )
    }

    private fun Float.dp() = androidx.compose.ui.unit.Dp(this)

    private companion object {
        const val TAG = "SmallScreenLargeFontTest"
    }
}
