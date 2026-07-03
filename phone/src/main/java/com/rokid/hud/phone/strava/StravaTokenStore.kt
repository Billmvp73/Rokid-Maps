package com.rokid.hud.phone.strava

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences-backed [TokenPersistence] (AUTH-02).
 *
 * First access performs Keystore + Tink keyset creation (can take hundreds
 * of ms) — CALL ONLY FROM BACKGROUND THREADS (03-RESEARCH Pitfall 6).
 * Read/write after creation is ordinary SharedPreferences.
 *
 * Catch-and-reset (03-RESEARCH Pattern 4): if creation throws (e.g.
 * AEADBadTagException after a backup restore orphaned the ciphertext from
 * its device-bound Keystore key), the store deletes the prefs file via
 * [onCorrupt] and recreates once. If the retry also fails, the store
 * degrades: load() returns null, save()/clear() no-op — the user sees the
 * disconnected "Reconnect to Strava" state instead of a crash loop.
 *
 * The factory/callback constructor exists so this reset logic is testable
 * on the JVM without Keystore; production wiring uses the Context overload.
 */
class StravaTokenStore(
    private val espFactory: () -> SharedPreferences,
    private val onCorrupt: () -> Unit,
    private val log: (String, Exception?) -> Unit = { m, e -> Log.e(TAG, m, e) }
) : TokenPersistence {

    companion object {
        private const val TAG = "StravaTokenStore"

        /** Must match the sharedpref exclusions in backup_rules.xml / data_extraction_rules.xml. */
        const val PREFS_FILE = "strava_auth"

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_ATHLETE_ID = "athlete_id"
        private const val KEY_ATHLETE_NAME = "athlete_name"

        /** security-crypto 1.1.0 API: MasterKey.Builder + scheme enums. */
        private fun createEsp(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context, PREFS_FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    constructor(context: Context) : this(
        espFactory = { createEsp(context.applicationContext) },
        // deleteSharedPreferences is API 24+; minSdk 28 OK.
        onCorrupt = { context.applicationContext.deleteSharedPreferences(PREFS_FILE) }
    )

    @Volatile private var prefs: SharedPreferences? = null

    /**
     * Double-checked lazy creation inside synchronized — ESP first-creation
     * is not race-safe (tink #535). Null return means the store is
     * unavailable even after a reset; callers degrade to disconnected.
     */
    private fun prefs(): SharedPreferences? {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            return try {
                espFactory().also { prefs = it }
            } catch (e: Exception) {
                log("Token store corrupt, resetting", e)
                onCorrupt()
                try {
                    espFactory().also { prefs = it }
                } catch (e2: Exception) {
                    log("Token store unavailable", e2)
                    null
                }
            }
        }
    }

    /**
     * Null unless access/refresh tokens are non-blank AND expires_at is
     * present — a partial write is the disconnected state, never a half-token.
     */
    override fun load(): StoredTokens? {
        val p = prefs() ?: return null
        val access = p.getString(KEY_ACCESS, null)
        val refresh = p.getString(KEY_REFRESH, null)
        val expiresAt = p.getLong(KEY_EXPIRES_AT, -1L)
        if (access.isNullOrBlank() || refresh.isNullOrBlank() || expiresAt == -1L) return null
        val athleteId = p.getLong(KEY_ATHLETE_ID, -1L).takeIf { it != -1L }
        val athleteName = p.getString(KEY_ATHLETE_NAME, null)
        return StoredTokens(access, refresh, expiresAt, athleteId, athleteName)
    }

    override fun save(tokens: StoredTokens) {
        val p = prefs() ?: return
        p.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES_AT, tokens.expiresAt)
            .putLong(KEY_ATHLETE_ID, tokens.athleteId ?: -1L)
            .putString(KEY_ATHLETE_NAME, tokens.athleteName)
            .apply()
    }

    override fun clear() {
        val p = prefs() ?: return
        p.edit().clear().apply()
    }
}
