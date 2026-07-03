package com.rokid.hud.phone.strava

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * AUTH-03 refresh semantics locked by deterministic JVM tests against fakes
 * (03-VALIDATION Wave 0 gap 3): needsRefresh boundaries, single-flight
 * concurrency, rotation persistence, wipe-on-400/401 vs keep-on-transient,
 * and the Authenticator retryTokenAfter401 double-check.
 *
 * Clock injected as a fixed lambda so boundaries are deterministic; transport
 * is a counting fake — no network, no android.*
 */
class TokenExpiryTest {

    companion object {
        /** Fixed injected clock (epoch seconds). */
        private const val NOW = 1_000_000L
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeStore(var tokens: StoredTokens?) : TokenPersistence {
        var cleared = false
        var saveCount = 0
        override fun load(): StoredTokens? = tokens
        override fun save(tokens: StoredTokens) {
            saveCount++
            this.tokens = tokens
        }
        override fun clear() {
            cleared = true
            tokens = null
        }
    }

    private class CountingTransport(
        private val sleepMs: Long = 0L,
        private val outcome: () -> RefreshOutcome
    ) : RefreshTransport {
        val count = AtomicInteger(0)
        override fun refresh(refreshToken: String): RefreshOutcome {
            count.incrementAndGet()
            if (sleepMs > 0) Thread.sleep(sleepMs)
            return outcome()
        }
    }

    /** Previous persisted state: expired by default, with athlete identity to preserve. */
    private fun stored(
        access: String = "oldAccess",
        refresh: String = "oldRefresh",
        expiresAt: Long = NOW - 100
    ) = StoredTokens(
        accessToken = access,
        refreshToken = refresh,
        expiresAt = expiresAt,
        athleteId = 42L,
        athleteName = "Bill H"
    )

    /** Well-formed refresh response (no athlete — refresh bodies never carry one). */
    private fun successOutcome(
        access: String = "newAccess",
        refresh: String = "rotatedRefresh",
        expiresAt: Long? = NOW + 21600
    ) = RefreshOutcome.Success(
        TokenResponse(
            tokenType = "Bearer",
            accessToken = access,
            refreshToken = refresh,
            expiresAt = expiresAt,
            expiresIn = 21600L,
            athlete = null
        )
    )

    private fun coordinator(store: FakeStore, transport: CountingTransport) =
        TokenRefreshCoordinator(store, transport, { NOW })

    // ------------------------------------------------------------------
    // needsRefresh boundary math (AUTH-03, VALIDATION test map)
    // ------------------------------------------------------------------

    @Test
    fun needsRefreshBoundaryMath() {
        assertTrue("29:59 remaining must refresh", StravaOAuth.needsRefresh(NOW + 1799, NOW))
        assertTrue("exactly 30:00 remaining must refresh", StravaOAuth.needsRefresh(NOW + 1800, NOW))
        assertFalse("30:01 remaining must NOT refresh", StravaOAuth.needsRefresh(NOW + 1801, NOW))
        assertTrue("past expiry must refresh", StravaOAuth.needsRefresh(NOW - 10, NOW))
    }

    // ------------------------------------------------------------------
    // Proactive window
    // ------------------------------------------------------------------

    @Test
    fun freshTokenReturnsStoredAccessTokenWithoutTransportCall() {
        val store = FakeStore(stored(access = "storedAccess", expiresAt = NOW + 7200))
        val transport = CountingTransport { successOutcome() }
        assertEquals("storedAccess", coordinator(store, transport).ensureFreshToken())
        assertEquals(0, transport.count.get())
        assertEquals(0, store.saveCount)
    }

    @Test
    fun expiredTokenRefreshesAndReturnsNewAccessToken() {
        val store = FakeStore(stored(expiresAt = NOW - 100))
        val transport = CountingTransport { successOutcome(access = "newAccess") }
        assertEquals("newAccess", coordinator(store, transport).ensureFreshToken())
        assertEquals(1, transport.count.get())
    }

    // ------------------------------------------------------------------
    // Rotation persistence (Strava: older refresh_token stops working)
    // ------------------------------------------------------------------

    @Test
    fun refreshPersistsRotatedRefreshTokenAndPreservesAthleteIdentity() {
        val store = FakeStore(stored(access = "oldAccess", refresh = "oldRefresh"))
        val transport = CountingTransport {
            successOutcome(access = "newAccess", refresh = "rotatedRefresh", expiresAt = NOW + 21600)
        }
        coordinator(store, transport).ensureFreshToken()
        val saved = store.tokens!!
        assertEquals("newAccess", saved.accessToken)
        assertEquals("rotatedRefresh", saved.refreshToken)
        assertEquals(NOW + 21600, saved.expiresAt)
        assertEquals(42L, saved.athleteId)
        assertEquals("Bill H", saved.athleteName)
    }

    // ------------------------------------------------------------------
    // Single-flight: two threads, ONE transport refresh
    // ------------------------------------------------------------------

    @Test
    fun concurrentEnsureFreshTokenPerformsExactlyOneTransportRefresh() {
        val store = FakeStore(stored(expiresAt = NOW - 100))
        val transport = CountingTransport(sleepMs = 200) { successOutcome(access = "newAccess") }
        val coord = coordinator(store, transport)
        val results = arrayOfNulls<String>(2)
        val t1 = Thread { results[0] = coord.ensureFreshToken() }
        val t2 = Thread { results[1] = coord.ensureFreshToken() }
        t1.start()
        t2.start()
        t1.join(5000)
        t2.join(5000)
        assertEquals("exactly one network attempt", 1, transport.count.get())
        assertEquals("newAccess", results[0])
        assertEquals("newAccess", results[1])
    }

    // ------------------------------------------------------------------
    // Wipe only on definitive rejection; keep on transient
    // ------------------------------------------------------------------

    @Test
    fun rejected401WipesStoredTokens() {
        val store = FakeStore(stored())
        val transport = CountingTransport { RefreshOutcome.Rejected(401) }
        assertNull(coordinator(store, transport).ensureFreshToken())
        assertTrue("401 must wipe tokens (Reconnect state)", store.cleared)
        assertNull(store.tokens)
    }

    @Test
    fun rejected400WipesStoredTokens() {
        val store = FakeStore(stored())
        val transport = CountingTransport { RefreshOutcome.Rejected(400) }
        assertNull(coordinator(store, transport).ensureFreshToken())
        assertTrue("400 must wipe tokens (Reconnect state)", store.cleared)
    }

    @Test
    fun rejected500KeepsStoredTokens() {
        val store = FakeStore(stored())
        val transport = CountingTransport { RefreshOutcome.Rejected(500) }
        assertNull(coordinator(store, transport).ensureFreshToken())
        assertFalse("5xx is transient — never lose auth", store.cleared)
        assertEquals("oldAccess", store.tokens!!.accessToken)
    }

    @Test
    fun rejected429KeepsStoredTokens() {
        val store = FakeStore(stored())
        val transport = CountingTransport { RefreshOutcome.Rejected(429) }
        assertNull(coordinator(store, transport).ensureFreshToken())
        assertFalse("rate limit is transient — never lose auth", store.cleared)
    }

    @Test
    fun transientErrorKeepsStoredTokens() {
        val store = FakeStore(stored())
        val transport = CountingTransport { RefreshOutcome.TransientError("timeout") }
        assertNull(coordinator(store, transport).ensureFreshToken())
        assertFalse("IOException is transient — never lose auth", store.cleared)
        assertEquals("oldRefresh", store.tokens!!.refreshToken)
    }

    @Test
    fun malformedSuccessBodyIsTreatedAsTransient() {
        // Missing refresh_token in a 200 body
        val storeA = FakeStore(stored())
        val transportA = CountingTransport {
            RefreshOutcome.Success(
                TokenResponse(
                    tokenType = "Bearer",
                    accessToken = "newAccess",
                    refreshToken = null,
                    expiresAt = NOW + 21600,
                    expiresIn = 21600L,
                    athlete = null
                )
            )
        }
        assertNull(coordinator(storeA, transportA).ensureFreshToken())
        assertFalse(storeA.cleared)
        assertEquals("oldRefresh", storeA.tokens!!.refreshToken)

        // Missing expires_at in a 200 body
        val storeB = FakeStore(stored())
        val transportB = CountingTransport { successOutcome(expiresAt = null) }
        assertNull(coordinator(storeB, transportB).ensureFreshToken())
        assertFalse(storeB.cleared)
        assertEquals("oldAccess", storeB.tokens!!.accessToken)
    }

    // ------------------------------------------------------------------
    // Authenticator double-check (retryTokenAfter401)
    // ------------------------------------------------------------------

    @Test
    fun retryAfter401ReturnsNewerTokenWithoutRefreshWhenAnotherThreadAlreadyRefreshed() {
        val store = FakeStore(stored(access = "newerTok", expiresAt = NOW + 7200))
        val transport = CountingTransport { successOutcome() }
        assertEquals("newerTok", coordinator(store, transport).retryTokenAfter401("staleTok"))
        assertEquals(0, transport.count.get())
    }

    @Test
    fun retryAfter401RefreshesOnceWhenStoredTokenIsTheFailedOne() {
        val store = FakeStore(stored(access = "newerTok", expiresAt = NOW + 7200))
        val transport = CountingTransport { successOutcome(access = "freshTok") }
        assertEquals("freshTok", coordinator(store, transport).retryTokenAfter401("newerTok"))
        assertEquals(1, transport.count.get())
    }

    // ------------------------------------------------------------------
    // Empty store: no crash, no transport call
    // ------------------------------------------------------------------

    @Test
    fun emptyStoreReturnsNullEverywhereWithoutTransportCalls() {
        val store = FakeStore(null)
        val transport = CountingTransport { successOutcome() }
        val coord = coordinator(store, transport)
        assertNull(coord.ensureFreshToken())
        assertNull(coord.forceRefresh())
        assertNull(coord.retryTokenAfter401("x"))
        assertEquals(0, transport.count.get())
    }

    // ------------------------------------------------------------------
    // forceRefresh ignores the window (Wave-3 debug rotation hook)
    // ------------------------------------------------------------------

    @Test
    fun forceRefreshIgnoresFreshnessWindow() {
        val store = FakeStore(stored(access = "stillFresh", expiresAt = NOW + 7200))
        val transport = CountingTransport { successOutcome(access = "forcedNew") }
        assertEquals("forcedNew", coordinator(store, transport).forceRefresh())
        assertEquals(1, transport.count.get())
        assertEquals("forcedNew", store.tokens!!.accessToken)
    }
}
