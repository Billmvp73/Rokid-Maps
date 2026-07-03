package com.rokid.hud.phone.strava

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTH-01 seams: authorize-URL exactness, CSRF state lifecycle primitives,
 * callback parsing, granted-scope validation, plus the AUTH-03 30-minute
 * expiry boundary owned by [StravaOAuth].
 *
 * Pure JVM — no android.* anywhere (03-RESEARCH Validation Architecture;
 * 03-VALIDATION Wave 0 gap 1). Mirrors the ActivitySessionManagerTest shape:
 * JUnit4, exact-value assertEquals.
 */
class StravaAuthUrlTest {

    // ------------------------------------------------------------------
    // Authorize URL exactness (AUTH-01)
    // ------------------------------------------------------------------

    @Test
    fun buildAuthorizeUrlProducesExactMobileAuthorizeUrl() {
        assertEquals(
            "https://www.strava.com/oauth/mobile/authorize" +
                "?client_id=167334" +
                "&redirect_uri=rokidhud%3A%2F%2Fcallback" +
                "&response_type=code" +
                "&approval_prompt=auto" +
                "&scope=read%2Cread_all%2Cactivity%3Awrite" +
                "&state=aabb01",
            StravaOAuth.buildAuthorizeUrl("167334", "aabb01")
        )
    }

    @Test
    fun tokenUrlIsExactAndHasNoApiV3Segment() {
        // PITFALLS Integration Gotchas row 1: token endpoint has NO /api/v3 prefix.
        assertEquals("https://www.strava.com/oauth/token", StravaOAuth.TOKEN_URL)
        assertFalse(StravaOAuth.TOKEN_URL.contains("/api/v3"))
    }

    @Test
    fun lockedConstantsMatchContextDecisions() {
        assertEquals("https://www.strava.com/oauth/mobile/authorize", StravaOAuth.AUTHORIZE_URL)
        assertEquals("rokidhud://callback", StravaOAuth.REDIRECT_URI)
        // Comma-delimited (NOT space) — CONTEXT locked "Scope set".
        assertEquals("read,read_all,activity:write", StravaOAuth.SCOPES)
        assertEquals(setOf("read", "read_all", "activity:write"), StravaOAuth.REQUIRED_SCOPES)
        assertEquals(30L * 60L, StravaOAuth.REFRESH_WINDOW_SEC)
    }

    // ------------------------------------------------------------------
    // CSRF state nonce (Pitfall 5)
    // ------------------------------------------------------------------

    @Test
    fun newStateIs64LowercaseHexChars() {
        val state = StravaOAuth.newState()
        assertEquals(64, state.length)
        assertTrue("state must be lowercase hex, got: $state", state.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun newStateReturnsDifferentValuesOnConsecutiveCalls() {
        assertNotEquals(StravaOAuth.newState(), StravaOAuth.newState())
    }

    @Test
    fun validateStateAcceptsMatchRejectsMismatch() {
        assertTrue(StravaOAuth.validateState("abc", "abc"))
        assertFalse(StravaOAuth.validateState("abc", "abd"))
    }

    @Test
    fun validateStateRejectsNullAndEmpty() {
        assertFalse(StravaOAuth.validateState("", "abc"))
        assertFalse(StravaOAuth.validateState(null, "abc"))
        assertFalse(StravaOAuth.validateState("abc", null))
        assertFalse(StravaOAuth.validateState("abc", ""))
        assertFalse(StravaOAuth.validateState(null, null))
    }

    // ------------------------------------------------------------------
    // Callback URI parsing (java.net.URI — no android.net.Uri)
    // ------------------------------------------------------------------

    @Test
    fun parseCallbackExtractsCodeStateAndDecodedScope() {
        assertEquals(
            mapOf(
                "code" to "c0de",
                "state" to "aabb01",
                "scope" to "read,read_all,activity:write"
            ),
            StravaOAuth.parseCallback(
                "rokidhud://callback?code=c0de&state=aabb01&scope=read%2Cread_all%2Cactivity%3Awrite"
            )
        )
    }

    @Test
    fun parseCallbackWithoutQueryReturnsEmptyMap() {
        assertEquals(emptyMap<String, String>(), StravaOAuth.parseCallback("rokidhud://callback"))
    }

    @Test
    fun parseCallbackOnMalformedInputReturnsEmptyMapWithoutThrowing() {
        assertEquals(emptyMap<String, String>(), StravaOAuth.parseCallback("not a uri %%%"))
        assertEquals(emptyMap<String, String>(), StravaOAuth.parseCallback(null))
    }

    // ------------------------------------------------------------------
    // Granted-scope validation (Pitfall 4 — fail closed)
    // ------------------------------------------------------------------

    @Test
    fun grantedScopesCompleteAcceptsFullGrant() {
        assertTrue(StravaOAuth.grantedScopesComplete("read,read_all,activity:write"))
    }

    @Test
    fun grantedScopesCompleteAcceptsExtraScopes() {
        assertTrue(StravaOAuth.grantedScopesComplete("read,read_all,activity:write,profile:read_all"))
    }

    @Test
    fun grantedScopesCompleteFailsClosedOnMissingScopes() {
        // read_all unchecked on the consent screen — the Pitfall 4 case.
        assertFalse(StravaOAuth.grantedScopesComplete("read,activity:write"))
        assertFalse(StravaOAuth.grantedScopesComplete("read"))
        assertFalse(StravaOAuth.grantedScopesComplete(null))
        assertFalse(StravaOAuth.grantedScopesComplete(""))
    }

    // ------------------------------------------------------------------
    // 30-minute refresh window boundary (AUTH-03)
    // ------------------------------------------------------------------

    @Test
    fun needsRefreshTripsAtThirtyMinuteBoundary() {
        val now = 1_000_000L
        assertTrue("29:59 remaining must refresh", StravaOAuth.needsRefresh(now + 1799, now))
        assertTrue("exactly 30:00 remaining must refresh", StravaOAuth.needsRefresh(now + 1800, now))
        assertFalse("30:01 remaining must NOT refresh", StravaOAuth.needsRefresh(now + 1801, now))
        assertTrue("past expiry must refresh", StravaOAuth.needsRefresh(now - 10, now))
    }
}
