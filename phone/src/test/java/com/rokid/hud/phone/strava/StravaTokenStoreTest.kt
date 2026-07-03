package com.rokid.hud.phone.strava

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for StravaTokenStore via its injected-factory constructor —
 * no Context, no Android runtime, no Keystore. SharedPreferences is an
 * interface, so a HashMap-backed fake covers everything the store touches.
 */
class StravaTokenStoreTest {

    private fun storeOver(prefs: SharedPreferences): StravaTokenStore =
        StravaTokenStore(espFactory = { prefs }, onCorrupt = {}, log = { _, _ -> })

    // --- round-trip -------------------------------------------------------

    @Test
    fun saveThenLoadRoundTripsAllFiveFields() {
        val store = storeOver(FakeSharedPreferences())
        store.save(StoredTokens("at", "rt", 123L, 42L, "Bill H"))
        val loaded = store.load()
        assertEquals("at", loaded?.accessToken)
        assertEquals("rt", loaded?.refreshToken)
        assertEquals(123L, loaded?.expiresAt)
        assertEquals(42L, loaded?.athleteId)
        assertEquals("Bill H", loaded?.athleteName)
    }

    @Test
    fun nullAthleteFieldsRoundTripAsNull() {
        val store = storeOver(FakeSharedPreferences())
        store.save(StoredTokens("at", "rt", 123L, null, null))
        val loaded = store.load()
        assertEquals("at", loaded?.accessToken)
        assertNull(loaded?.athleteId)
        assertNull(loaded?.athleteName)
    }

    @Test
    fun loadAfterClearReturnsNull() {
        val store = storeOver(FakeSharedPreferences())
        store.save(StoredTokens("at", "rt", 123L, 42L, "Bill H"))
        store.clear()
        assertNull(store.load())
    }

    // --- partial writes never yield a half-token --------------------------

    @Test
    fun loadReturnsNullWhenAccessTokenAbsent() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("refresh_token", "rt").putLong("expires_at", 123L).apply()
        assertNull(storeOver(prefs).load())
    }

    @Test
    fun loadReturnsNullWhenRefreshTokenAbsent() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("access_token", "at").putLong("expires_at", 123L).apply()
        assertNull(storeOver(prefs).load())
    }

    @Test
    fun loadReturnsNullWhenExpiresAtAbsent() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("access_token", "at").putString("refresh_token", "rt").apply()
        assertNull(storeOver(prefs).load())
    }

    // --- catch-and-reset (backup-restored ciphertext) ---------------------

    @Test
    fun corruptStoreResetsExactlyOnceAndRecovers() {
        val good = FakeSharedPreferences()
        var factoryCalls = 0
        var corruptCalls = 0
        val store = StravaTokenStore(
            espFactory = {
                factoryCalls++
                // First creation simulates AEADBadTagException after a backup restore.
                if (factoryCalls == 1) throw RuntimeException("simulated AEADBadTagException")
                good
            },
            onCorrupt = { corruptCalls++ },
            log = { _, _ -> }
        )
        store.save(StoredTokens("at", "rt", 123L, 42L, "Bill H"))
        assertEquals("at", store.load()?.accessToken)
        assertEquals(1, corruptCalls)
        assertEquals(2, factoryCalls)
    }

    @Test
    fun unavailableStoreDegradesWithoutCrashing() {
        val store = StravaTokenStore(
            espFactory = { throw RuntimeException("keystore unavailable") },
            onCorrupt = {},
            log = { _, _ -> }
        )
        // Both-fail path: no crash, no half-state — just the disconnected state.
        store.save(StoredTokens("at", "rt", 123L, 42L, "Bill H"))
        assertNull(store.load())
        store.clear()
        assertNull(store.load())
    }
}

// --- minimal HashMap-backed SharedPreferences fake --------------------------

private class FakeSharedPreferences : SharedPreferences {
    val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? =
        map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}
}

private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
    private val pending = HashMap<String, Any?>()
    private var clearFirst = false

    override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
    override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
    override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
    override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
    override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
    override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
    override fun remove(key: String?) = apply { pending[key!!] = REMOVED }
    override fun clear() = apply { clearFirst = true }
    override fun commit(): Boolean { applyChanges(); return true }
    override fun apply() = applyChanges()

    private fun applyChanges() {
        if (clearFirst) prefs.map.clear()
        for ((k, v) in pending) {
            if (v === REMOVED) prefs.map.remove(k) else prefs.map[k] = v
        }
        pending.clear()
        clearFirst = false
    }

    companion object { private val REMOVED = Any() }
}
