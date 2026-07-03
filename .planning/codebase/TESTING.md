# Testing Landscape

**Analysis Date:** 2026-07-02

## Test Infrastructure

- **Test framework:** None configured.
- **Test runner:** None configured.
- **Mocking library:** None configured.
- **Assertion library:** None configured.

## Test Directory Structure

There are **no test directories** in any of the three modules:

```
# Expected but missing:
shared/src/test/          -- NOT FOUND
phone/src/test/           -- NOT FOUND
glasses/src/test/         -- NOT FOUND
```

## Existing Tests

There are **zero test files** in the entire repository. No `*.test.*`, `*.spec.*`, or `*Test.*` files exist anywhere.

## Test Dependencies

No test dependencies are declared in any `build.gradle.kts` file. All three module build files lack the `testImplementation`, `androidTestImplementation`, or `debugImplementation` configurations entirely.

| File | Missing Test Config |
|------|-------------------|
| `shared/build.gradle.kts` | No `testImplementation`, no `androidTestImplementation` |
| `phone/build.gradle.kts` | No `testImplementation`, no `androidTestImplementation` |
| `glasses/build.gradle.kts` | No `testImplementation`, no `androidTestImplementation` |

## CI/CD

- **No CI configuration:** The only file in `.github/` is `FUNDING.yml` (GitHub Sponsors).
- **No GitHub Actions workflows** exist.
- **No linting or static analysis** is configured (no detekt, no ktlint, no Android lint baseline).

## Coverage Assessment

### Well-Tested Areas

*None.* No areas have any test coverage. The entire codebase (24 source files, ~5,365 lines of Kotlin) is untested.

### Untested / Under-tested Areas

Every functional area is untested:

| Area | Files | Risk |
|------|-------|------|
| **Bluetooth protocol encoding/decoding** | `shared/.../ProtocolCodec.kt`, `Messages.kt`, `ProtocolConstants.kt` | Silent corruption of JSON messages leads to `ParsedMessage.Unknown` on the glasses |
| **OSRM route parsing** | `phone/.../OsrmClient.kt` | Breaking changes in OSRM API response format cause route failures without tests |
| **Nominatim search** | `phone/.../NominatimClient.kt` | Search query construction or response parsing errors |
| **Overpass speed limit** | `phone/.../OverpassSpeedLimitClient.kt` | OSM tag parsing logic (mph/kmh conversion, regex extraction) |
| **NavigationManager logic** | `phone/.../NavigationManager.kt` | Step advancement, off-route detection, reroute cooldown, arrival radius — all critical for correct navigation |
| **BluetoothClient message routing** | `glasses/.../BluetoothClient.kt` | Sealed-class `when` branching for all 12 message types — missing a case silently ignores messages |
| **HudState transitions** | `glasses/.../HudState.kt` | `withNotification()`, `toggleLayout()` — data class copy operations |
| **DiskTileCache LRU eviction** | `shared/.../DiskTileCache.kt` | Eviction logic, size tracking, concurrent access |
| **TileManager fetch/disk coordination** | `glasses/.../TileManager.kt` | LruCache + disk cache + network fetch coordination, pending dedup |
| **HudView rendering** | `glasses/.../HudView.kt` | 4 layout modes, route drawing, turn alert overlay, compass rendering |
| **WifiConnector multi-method connect** | `glasses/.../WifiConnector.kt` | 3 connection strategies (specifier, legacy, suggestion), state management |
| **WifiShareManager P2P group** | `phone/.../WifiShareManager.kt` | Group creation, client tracking, error codes |
| **BluetoothAudioRouter/audio routing** | `phone/.../BluetoothAudioRouter.kt` | A2DP/SCO fallback, TTS integration |
| **RokidConnectionManager lifecycle** | `phone/.../RokidConnectionManager.kt` | Pairing state machine, CXR SDK integration |
| **APK update protocol** | `phone/.../HudStreamingService.kt`, `glasses/.../BluetoothClient.kt` | Chunked file transfer, Base64 encoding, reassembly |
| **HudApplication SDK init** | `phone/.../HudApplication.kt` | Rokid SDK init, notification channel creation |

## Test Patterns

No test patterns exist in the codebase. The following patterns would be idiomatic for this codebase:

### Example: Testing a JSON codec

The `ProtocolCodec` object is the most testable unit — pure functions, no Android dependencies:

```kotlin
// Expected pattern (not yet written)
@Test
fun encodeState_returnsValidJson() {
    val msg = StateMessage(37.77, -122.41, 90f, 10.5f, 5f, 50, 200.0)
    val json = ProtocolCodec.encodeState(msg)
    assertTrue(json.contains("\"t\":\"state\""))
    assertTrue(json.contains("\"lat\":37.77"))
}

@Test
fun decode_stateMessage_returnsState() {
    val json = """{"t":"state","lat":37.77,"lng":-122.41,"bearing":90,"speed":10.5,"accuracy":5,"spdLim":50,"distNext":200}"""
    val parsed = ProtocolCodec.decode(json)
    assertTrue(parsed is ParsedMessage.State)
    assertEquals(37.77, (parsed as ParsedMessage.State).msg.latitude, 0.001)
}
```

### Example: Testing NavigationManager logic

```kotlin
// Expected pattern (not yet written)
@Test
fun advanceStep_whenWithinRadius_advancesIndex() {
    val nav = NavigationManager(mockCallback)
    nav.startNavigation(destLat, destLng, currentLat, currentLng)
    // ... inject mock route, then simulate location near next step
}
```

## Recommendations

### Priority 1: Unit test the shared module

The `:shared` module has zero Android framework dependencies and is the most straightforward to test:

1. **Add test dependencies** to `shared/build.gradle.kts`:
   ```kotlin
   dependencies {
       testImplementation("junit:junit:4.13.2")
   }
   ```
2. **Test `ProtocolCodec`** — encode/decode round-trips for all 12 message types, edge cases (null fields, empty lists, missing type field, malformed JSON).
3. **Test `Messages.kt`** — data class construction, default values.
4. **Test `DiskTileCache`** — put/get, LRU eviction, max size enforcement, concurrent access safety.

### Priority 2: Unit test NavigationManager

The `NavigationManager` in `:phone` contains critical navigation logic with no tests:

1. Step advancement radius (150m)
2. Double-step advancement when position passes two steps
3. Off-route detection (80m threshold)
4. Reroute cooldown (15s)
5. Arrival detection (30m radius)
6. `getDistanceToNextStep()` edge cases
7. Integration with `OsrmClient` (mock HTTP)

The class can be tested by providing a mock `NavigationCallback` (no mocking library needed — implement the interface manually).

### Priority 3: Test OsrmClient parsing

The OSRM response parser in `OsrmClient.kt` parses a specific JSON structure from OSRM API v5. A breaking upstream change or geometry format change would silently break routing. Test with:
- Example JSON responses (known good, edge cases).
- The `buildInstruction()` and `toManeuverKey()` methods (pure string logic, 10+ branches each).

### Priority 4: Add Android-specific test infrastructure

- **Add `debugImplementation("androidx.test:monitor:1.6.1")`** for instrumented tests.
- **Add MockK or Mockito** for mocking Android system services.
- **Add Google Truth or AssertJ** for fluent assertions.

### Priority 5: Set up CI

- Create `.github/workflows/ci.yml` with:
  - `./gradlew test` on push/PR to main.
  - `./gradlew lint` for code quality.
  - `./gradlew ktlintCheck` or detekt for Kotlin static analysis.

### Priority 6: Set up linting/detekt before adding tests

- Add `detekt` or `ktlint` to `build.gradle.kts` with a baseline to gradually improve code quality alongside test coverage.
- No `.editorconfig` or lint config exists — adding one would enforce consistent formatting from the outset.

---

**Testing analysis:** 2026-07-02
