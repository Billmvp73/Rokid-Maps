# Project Milestones: Rokid HUD Maps — Sport HUD

## v1.0 MVP (Shipped: 2026-07-03)

**Delivered:** Turned the existing Rokid HUD navigation app into a full sport HUD — Strava OAuth, route import + on-glasses turn-by-turn navigation, GPS activity recording with a live SPORT HUD, and activity summary + one-tap Strava upload — shipped and device-verified end-to-end on real hardware.

**Phases completed:** 5 (25 plans total)

**Device verification:** All 5 phases verified on the real OPPO phone `3B164G01Y7L00000` + Rokid glasses `1901092544802583` (2026-07-03).

- **Phase 1 — Activity Recording Engine** (7 plans): GPS track log + live metrics, >20m accuracy gate, raw-Doppler moving-state hysteresis (0.7/0.3) + >50 m/s teleport/seam gate, robust background operation (WakeLock, foreground service, watchdog), atomic JSON persistence + checkpoints, `sport_state` 1Hz Bluetooth broadcast. Device-verified: mock-GPS pipeline, 35-min screen-off recording, flat distance at stops, no teleport distance. **PASSED.**
- **Phase 2 — Glasses Sport HUD** (4 plans): SPORT layout (elapsed / speed-or-pace / distance) in monochrome green at ~1Hz, reachable via the Full→Corner→Sport→Full tap cycle; Mini modes preserved. Device-verified: live metrics render on glasses (screencap-confirmed); SPORT survives settings re-broadcast (WR-01 fix). **PASSED.**
- **Phase 3 — Strava Authentication** (4 plans): OAuth 2.0 Authorization Code Grant (no PKCE, client_secret via BuildConfig), EncryptedSharedPreferences token store, transparent auto-refresh. Device-verified: real OAuth on device — **caught & fixed a real "Invalid redirect URI" bug** (redirect host `callback`→`rokidhud` to match the registered Authorization Callback Domain, commit ea09e21); token exchange + `GET /athlete` 200 ("Connected as Pengyuan Huang"); persistence + rotation proven across restart. **PASSED.**
- **Phase 4 — Strava Route Import + Navigation** (6 plans): browse/import Strava routes, GPX parse + Douglas-Peucker downsample, phone-map preview, OSRM via-point routing (`waypoints=0;{last}` single leg) with follow-route graceful degrade + shape-preserving off-route reroute. Device-verified: real routes (Milpitas 25.4km) → route line + real turn-by-turn on glasses ("Turn right onto Innovation Drive"); follow-route fallback confirmed off-route. **PASSED.**
- **Phase 5 — Activity Summary + Strava Upload** (4 plans): activity summary (time / moving time / distance / avg speed+pace / route map), one-tap Strava upload (GPX → POST /uploads → poll), local-data-safety on failure + retry, history list with uploaded badge. Device-verified: recorded a 667m ride → summary (moving 2:00 < elapsed 3:02) → **REAL Strava upload (activity id `19170698786`)** in the user's feed → history "Uploaded ✓" badge; write-back preserved 181 trackpoints. **PASSED.**

**v1.x enhancements (shipped this session — the start of v1.x, not yet a planned milestone):**
- **Whole-route bird's-eye page + 4-page swipeable glasses HUD** (quick uf4, base commit b5f03ed): FULL → CORNER → SPORT → WHOLE_ROUTE via the touchpad's real DPAD_LEFT/RIGHT keycodes; the birdview draws the full route zoomed-to-fit. A backward-compatible route `full` flag preserves the original imported route in a separate `wholeRoute` source — **D4 invariant proven on hardware**: starting 58km off-route, the instruction page rerouted while the birdview kept showing the real Strava route. All 4 pages screencapped.
- **Off-route-at-start "Head to route" fix** (quick w1l, commit c8001a7): defers the off-route reroute until the rider joins the imported route (latched via `hasBeenOnRoute`), shows "Head to route → {dist}" while approaching (no OSRM/Thread), caps mid-ride reroute waypoints to ≤25. Device-verified: "Head to route 59.9km" at 58km off → "Joined route (nearest 32m)" + real turn-by-turn on rejoin.

**Key accomplishments:**
- First test suite in the repo — **219 tests green** across shared/phone/glasses (project started with zero tests); both APKs build clean.
- Strava is the app's first external auth-required API; OkHttp + Gson introduced for the Strava client while preserving the codebase's no-coroutines / no-DI / callback+Thread conventions.
- Real end-to-end device verification of every phase, including two bugs caught only on hardware (the OAuth redirect-URI mismatch and the off-route-at-start UX) and fixed.

**Key decisions:**
- Built-in activity recording (Strava has no live-activity API) — ✓ recording→summary→upload verified end-to-end.
- OSRM via-point routing (`waypoints=0;{last}` single leg) with follow-route fallback for multi-waypoint Strava routes — ✓ single-leg + degrade verified on device.
- Raw-Doppler hysteresis with the 5-point speed moving-average removed (device measurement showed ~6.7m jitter-distance leak per stop) + >50 m/s seam gate — ✓ device-verified.
- Tokens in EncryptedSharedPreferences; client_secret in APK via BuildConfig (Strava has no PKCE) — ✓ persistence + rotation verified.
- Sport HUD added as a new glasses layout mode; OAuth on phone only (glasses receive processed data).

**Stats:**
- 5 phases, 25 plans, 219 tests
- 135 files changed, ~22,757 insertions across the milestone; ~14,720 Kotlin LOC total
- Git range: `feat(01-01)` → `feat(w1l)` (milestone work executed 2026-07-03; planning docs 2026-07-02)

**Known deferred items at close:** 4 historical doc-review quick tasks (260702-v6h, 260702-w4n, 260702-wvg, 260703-05e — iterative `.planning`-doc review passes, all applied) — see STATE.md Deferred Items. These are not v1 code work.

**What's next:** v1.x — the birdview + 4-page swipe HUD and the off-route "Head to route" fix are already shipped as the first v1.x enhancements. Next-milestone requirements to be defined via `/gsd-new-milestone` when the user chooses to start v1.x planning.

---
