# Deferred / Out-of-Scope Items — quick task 260703-uf4

## Pre-existing test/source mismatch fixed to unblock the exit-0 gate

**Discovered during:** Task 1 build+test gate (2 of 187 phone tests failed).

**File:** `phone/src/test/java/com/rokid/hud/phone/strava/StravaAuthUrlTest.kt`
**Failing tests:** `buildAuthorizeUrlProducesExactMobileAuthorizeUrl`, `lockedConstantsMatchContextDecisions`

**Root cause (pre-existing, NOT caused by this quick task):** Commit `ea09e21`
("fix(03): correct Strava redirect_uri host to registered callback domain")
changed the shipped source constant `StravaOAuth.REDIRECT_URI` from
`rokidhud://callback` to `rokidhud://rokidhud` (host must equal Strava's
registered Authorization Callback Domain `rokidhud`, live-verified 2026-07-03),
but the corresponding test assertions were not updated. Those two tests have
been red since `ea09e21`, which predates this quick task.

**Relation to this task:** ZERO. This quick task touches only the route
protocol (`shared/.../protocol/*`) and phone navigation
(`NavigationManager`/`HudStreamingService`/`MainActivity`). StravaOAuth is on
a disjoint compile path.

**Resolution:** Because the task gate mandates `testDebugUnitTest` exit 0, the
two stale test assertions were aligned to the authoritative shipped source
constant `rokidhud://rokidhud` (source unchanged; only the test's expected
strings updated). This is the minimal alignment that restores a green baseline
without altering any product behavior. Documented as a Task-1 deviation in
SUMMARY.md.
