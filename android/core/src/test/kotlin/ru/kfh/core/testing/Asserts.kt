package ru.kfh.core.testing

import org.junit.Assert

fun <T> assertEquals(expected: T, actual: T, message: String? = null) = Assert.assertEquals(message, expected, actual)

fun <T> assertNotEquals(unexpected: T, actual: T, message: String? = null) = Assert.assertNotEquals(message, unexpected, actual)

fun assertTrue(actual: Boolean, message: String? = null) = Assert.assertTrue(message, actual)

fun assertFalse(actual: Boolean, message: String? = null) = Assert.assertFalse(message, actual)

fun assertNull(actual: Any?, message: String? = null) = Assert.assertNull(message, actual)

fun <T : Any> assertNotNull(actual: T?, message: String? = null): T {
    Assert.assertNotNull(message, actual)
    return actual!!
}

/** The thrown exception, which must be a [T]. */
inline fun <reified T : Throwable> assertFailsWith(message: String? = null, block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("${message.orEmpty()} expected ${T::class.java.name} but was $e", e)
    }
    throw AssertionError("${message.orEmpty()} expected ${T::class.java.name} but nothing was thrown")
}
