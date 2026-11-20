package ru.kfh.core.fields

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kfh.core.model.Field

class FieldTextTest {
    @Test
    fun `area is typed in hectares and kept in square metres`() {
        assertEquals(125_000L, FieldText.parseAreaSqM("12,5"))
        assertEquals(125_000L, FieldText.parseAreaSqM("12.5"))
        assertEquals(1L, FieldText.parseAreaSqM("0,0001"))
        assertNull(FieldText.parseAreaSqM("0"))
        assertNull(FieldText.parseAreaSqM(""))
        assertNull(FieldText.parseAreaSqM("abc"))
        assertNull(FieldText.parseAreaSqM("1,23456"))
        assertEquals(1_000_000_000L, FieldText.parseAreaSqM("100000"))
        assertNull("больше 100 000 га — не поле", FieldText.parseAreaSqM("100001"))
    }

    @Test
    fun `area is shown with at most four decimals and no trailing zeros`() {
        assertEquals("12,5", FieldText.areaHa(125_000))
        assertEquals("1", FieldText.areaHa(10_000))
        assertEquals("1,2345", FieldText.areaHa(12_345))
        assertEquals("Поле 3 — 12,5 га", FieldText.fieldLabel("Поле 3", 125_000))
    }

    @Test
    fun `the name is required and short enough`() {
        assertEquals(listOf("Название поля — от 1 до 60 символов"), FieldText.errors("   ", 100, null, null))
        assertEquals(listOf("Название поля — от 1 до 60 символов"), FieldText.errors("я".repeat(61), 100, null, null))
        assertEquals(listOf(FieldText.AREA_ERROR), FieldText.errors("Поле 1", 0, null, null))
        assertEquals(listOf(FieldText.AREA_ERROR), FieldText.errors("Поле 1", 1_000_000_001, null, null))
        assertEquals(listOf("Культура — до 60 символов"), FieldText.errors("Поле 1", 100, "к".repeat(61), null))
        assertEquals(emptyList<String>(), FieldText.errors("Поле 1", 100, null, null))
    }

    @Test
    fun `fields are sorted by name in Russian and the cultures are unique`() {
        val fields = listOf(
            Field(id = "1", name = "Яблоневый", areaSqM = 100, culture = "Пшеница"),
            Field(id = "2", name = "Ближний", areaSqM = 100, culture = ""),
            Field(id = "3", name = "Верхний", areaSqM = 100, culture = "Овёс"),
        )
        assertEquals(listOf("2", "3", "1"), FieldText.sort(fields).map { it.id })
        assertEquals(listOf("Овёс", "Пшеница"), FieldText.cultures(fields))
    }
}
