package ru.kfh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kfh.core.money.Money

class MoneyTest {
    @Test
    fun parseRubles() {
        assertEquals(650_000L, Money.parseRubles("6500"))
        assertEquals(650_000L, Money.parseRubles("6 500"))
        assertEquals(650_050L, Money.parseRubles("6 500,5"))
        assertEquals(120_000L, Money.parseRubles("1200.00"))
        assertEquals(0L, Money.parseRubles("0"))
        assertEquals(650_000L, Money.parseRubles("6 500"))
        assertEquals(650_000L, Money.parseRubles("6 500"))
        assertEquals(650_000L, Money.parseRubles("6 500"))
        assertEquals(120_050L, Money.parseRubles("1 200,50"))
        assertEquals(120_050L, Money.parseRubles("1 200,50"))
        assertNull(Money.parseRubles("12,345"))
        assertNull(Money.parseRubles("-5"))
        assertNull(Money.parseRubles(""))
        assertNull(Money.parseRubles(","))
        assertNull(Money.parseRubles("1,2,3"))
        assertNull(Money.parseRubles("два"))
    }

    @Test
    fun rubles() {
        assertEquals("14 000 ₽", Money.rubles(1_400_000))
        assertEquals("16 250,50 ₽", Money.rubles(1_625_050))
        assertEquals("1 000 000 ₽", Money.rubles(100_000_000))
        assertEquals("−1 200 ₽", Money.rubles(-120_000))
        assertEquals("0 ₽", Money.rubles(0))
    }
}
