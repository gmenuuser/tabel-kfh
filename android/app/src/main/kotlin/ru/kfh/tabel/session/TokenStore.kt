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
 * a sign-in or a sign-out is on disk before the call returns. A file the Keystore can no longer open (the key was
 * lost with a factory reset's restore, or invalidated) is thrown away: the person signs in again instead of the app
 * failing to start.
 */
@Suppress("DEPRECATION") // security-crypto 1.1.0 is deprecated as a whole; it still is the Keystore-backed store.
class EncryptedTokenStore(context: Context) : TokenStore {
    private val app = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            open()
        } catch (e: Exception) {
            app.deleteSharedPreferences(FILE)
            open()
        }
    }

    private fun open(): SharedPreferences {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(token: String?) {
        val editor = prefs.edit()
        if (token == null) editor.remove(KEY) else editor.putString(KEY, token)
        check(editor.commit()) { "token not saved" }
    }

    override fun toString(): String = "EncryptedTokenStore"

    private companion object {
        const val FILE = "session"
        const val KEY = "token"
    }
}
