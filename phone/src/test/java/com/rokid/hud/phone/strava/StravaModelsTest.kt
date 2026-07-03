package com.rokid.hud.phone.strava

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AUTH-02 models: Gson parse of exchange vs refresh token responses,
 * malformed-JSON safety, and displayName fallbacks.
 *
 * Fixtures model the documented Strava response shape (token_type "Bearer",
 * expires_at epoch-seconds, summary athlete on initial exchange ONLY —
 * 03-RESEARCH Strava Facts / A1). Pure JVM, no android.*
 * (03-VALIDATION Wave 0 gap 2).
 */
class StravaModelsTest {

    /** Initial code-exchange response — athlete object present. */
    private val exchangeJson = """
        {
          "token_type": "Bearer",
          "expires_at": 1751600000,
          "expires_in": 21600,
          "refresh_token": "rt-e5n567567",
          "access_token": "at-a4b945687g",
          "athlete": {
            "id": 134815,
            "username": "bh",
            "firstname": "Bill",
            "lastname": "H"
          }
        }
    """.trimIndent()

    /** Refresh response — tokens only, NO athlete (RESEARCH A1). */
    private val refreshJson = """
        {
          "token_type": "Bearer",
          "access_token": "at-rotated111",
          "expires_at": 1751621600,
          "expires_in": 21600,
          "refresh_token": "rt-rotated222"
        }
    """.trimIndent()

    @Test
    fun exchangeResponseParsesWithAthlete() {
        val r = parseTokenResponse(exchangeJson)
        assertNotNull(r)
        assertEquals("Bearer", r!!.tokenType)
        assertEquals("at-a4b945687g", r.accessToken)
        assertEquals("rt-e5n567567", r.refreshToken)
        assertEquals(1751600000L, r.expiresAt)
        assertEquals(21600L, r.expiresIn)
        val athlete = r.athlete
        assertNotNull(athlete)
        assertEquals(134815L, athlete!!.id)
        assertEquals("bh", athlete.username)
        assertEquals("Bill", athlete.firstname)
        assertEquals("H", athlete.lastname)
    }

    @Test
    fun refreshResponseParsesWithoutAthlete() {
        val r = parseTokenResponse(refreshJson)
        assertNotNull(r)
        assertEquals("at-rotated111", r!!.accessToken)
        assertEquals("rt-rotated222", r.refreshToken)
        assertEquals(1751621600L, r.expiresAt)
        assertNull("refresh responses carry no athlete", r.athlete)
    }

    @Test
    fun missingRequiredFieldsYieldNull() {
        // refresh_token and expires_at missing
        assertNull(parseTokenResponse("""{"access_token":"x"}"""))
        // expires_at missing
        assertNull(parseTokenResponse("""{"access_token":"x","refresh_token":"y"}"""))
        // access_token missing
        assertNull(parseTokenResponse("""{"refresh_token":"y","expires_at":123}"""))
        // blank access_token fails validation
        assertNull(parseTokenResponse("""{"access_token":"","refresh_token":"y","expires_at":123}"""))
    }

    @Test
    fun malformedJsonYieldsNullNeverThrows() {
        assertNull(parseTokenResponse("not json"))
        assertNull(parseTokenResponse("[1,2,3]"))
        assertNull(parseTokenResponse(""))
        assertNull(parseTokenResponse("   "))
        assertNull(parseTokenResponse(null))
    }

    // ------------------------------------------------------------------
    // displayName fallback chain
    // ------------------------------------------------------------------

    @Test
    fun displayNameUsesFirstAndLastName() {
        assertEquals("Bill H", StravaAthlete(id = 42L, username = "bh", firstname = "Bill", lastname = "H").displayName())
    }

    @Test
    fun displayNameFallsBackToUsernameWhenNamesNull() {
        assertEquals("bh", StravaAthlete(id = 42L, username = "bh", firstname = null, lastname = null).displayName())
    }

    @Test
    fun displayNameSkipsBlankNameParts() {
        assertEquals("H", StravaAthlete(id = 42L, username = "bh", firstname = "  ", lastname = "H").displayName())
    }

    @Test
    fun displayNameFallsBackToGenericLabelWhenAllNull() {
        assertEquals("Strava athlete", StravaAthlete(id = null, username = null, firstname = null, lastname = null).displayName())
    }
}
