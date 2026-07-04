---
phase: 03-strava-authentication
plan: 04
subsystem: auth
tags: [strava, oauth, device-verification, redirect-uri, token-rotation, android]
status: complete
date: 2026-07-03
requirements: [AUTH-01, AUTH-02, AUTH-03]
device:
  phone: "OPPO 3B164G01Y7L00000"
  glasses: "Rokid 1901092544802583"
requires:
  - "03-03: MainActivity STRAVA card (connect/disconnect flows, warm+cold callback routing, GET /athlete proof, debug forced-refresh long-press hook)"
provides:
  - "On-device confirmation of the full OAuth flow (AUTH-01/02/03) on the real OPPO phone"
  - "Live redirect-URI fix: redirect host callback → rokidhud to match the registered Authorization Callback Domain (commit ea09e21)"
  - "CLAUDE.md rate-limit constraint corrected to live-verified values (200/15min + 2,000/day overall; 100 reads/15min + 1,000 reads/day)"
affects:
  - "Phase 4/5 (route import + upload build on the now-live-proven connected state)"
gate: "Live OAuth end-to-end on hardware — token exchange + GET /athlete 200; force-stop persistence; forced-refresh rotation"
---

# Phase 3 · Plan 04 — Strava Authentication Device Verification — Summary

**One-liner:** Live-verified the entire Strava OAuth flow on the real OPPO phone — and caught & fixed a real "Invalid redirect URI" bug in the process — closing AUTH-01/02/03 end-to-end on hardware.

## What was verified (on device: OPPO `3B164G01Y7L00000` + Rokid glasses `1901092544802583`)

- **Real OAuth login (AUTH-01, SC#1+SC#2):** tapped Connect Strava → browser/Strava consent → Authorize → returned to the app → toast + card **"Connected as Pengyuan Huang"**. Token exchange succeeded and an authenticated **`GET /athlete` returned 200** with rate-limit headers logged — the authenticated client is proven (ROADMAP "Delivers").
- **Persistence across restart (AUTH-02, SC#3):** after force-stop + relaunch, the card still showed "Connected as Pengyuan Huang" with **no re-login** (EncryptedSharedPreferences token store survives process death).
- **Transparent refresh / rotation (AUTH-03, SC#4):** the debug long-press forced a refresh; logcat showed a **rotated refresh token** (different rt# from the exchange) followed by a successful `GET /athlete`.

## Real bug caught & fixed during verification

**"Invalid redirect URI" on the live Authorize tap.** The redirect host was `callback` but Strava validates the redirect against the registered **Authorization Callback Domain** (a bare host), which was configured as `rokidhud`. Fix: change the redirect host `callback` → `rokidhud` so `rokidhud://…` matches the registered domain. Probed live against Strava's authorize endpoint until it accepted the request. Committed as **ea09e21**. This is exactly the highest-risk-phase failure PITFALLS.md predicted (redirect-URI/deep-link mismatch) — found and closed on real hardware.

## Docs corrected

- **CLAUDE.md** Strava rate-limit constraint updated to the live-verified new-app defaults: **200 requests/15 min + 2,000/day overall; 100 reads/15 min + 1,000 reads/day**.

## Outcome

All three AUTH requirements confirmed end-to-end on the real device. **PASSED.** Phase 3 is device-complete.
