package ru.kfh.tabel.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The device token of the sign-in (`POST /auth/login`). Never logged, never put into an exception. */
interface TokenStore {
    fun get(): String?

    /** null — signed out: the token is removed. */
    fun set(token: String?)
}

/** In memory only: JVM tests and the instrumented UI test. */
class MemoryTokenStore(initial: String? = null) : TokenStore {
    @Volatile private var token: String? = initial

    override fun get(): String? = token

    override fun set(token: String?) {
        this.token = token
    }

    override fun toString(): String = "MemoryTokenStore"
}

/**
 * The phone's token in EncryptedSharedPreferences (AES-256, the key in the Android Keystore). Writes are `commit()`:
 * a sign-in or a sign-out is on disk before the call returns.
 *
 * The Keystore fails in known ways (the key lost with a restore from a backup, invalidated, a value that no longer
 * decrypts — `SecurityException` from `getString`). None of them may crash the app or strand a sign-out:
 * - [get] never throws: a file that cannot be read is thrown away and the phone is signed out;
 * - [set] with null (sign-out) never throws: when the file cannot be written it is deleted, which is the same thing;
 * - [set] with a token throws only when the token really could not be saved (the sign-in screen says so).
 */
@Suppress("DEPRECATION") // security-crypto 1.1.0 is deprecated as a whole; it still is the Keystore-backed store.
class EncryptedTokenStore(context: Context) : TokenStore {
    private val app = context.applicationContext
    private val lock = Any()
    private var prefs: SharedPreferences? = null

    private fun open(): SharedPreferences {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The open file, or a fresh one when the stored one cannot be opened. Throws when the Keystore itself is unwell. */
    private fun prefs(): SharedPreferences = synchronized(lock) {
        prefs ?: try {
            open()
        } catch (e: Exception) {
            reset()
            open()
        }.also { prefs = it }
    }

    /** Forgets the file: whatever was in it (at most the token) is gone, the next call starts over. */
    private fun reset() = synchronized(lock) {
        prefs = null
        app.deleteSharedPreferences(FILE)
    }

    override fun get(): String? = try {
        prefs().getString(KEY, null)
    } catch (e: Exception) {
        // Undecryptable or unopenable: the token is lost either way, so the phone is signed out.
        reset()
        null
    }

    override fun set(token: String?) {
        try {
            val editor = prefs().edit()
            if (token == null) editor.remove(KEY) else editor.putString(KEY, token)
            if (editor.commit()) return
            if (token != null) error("token not saved")
        } catch (e: Exception) {
            if (token != null) throw e
        }
        // A sign-out that could not be written: without the file there is no token at all.
        reset()
    }

    override fun toString(): String = "EncryptedTokenStore"

    private companion object {
        const val FILE = "session"
        const val KEY = "token"
    }
}
