---
phase: 05-activity-summary-strava-upload
plan: 04
subsystem: activity-summary-upload
tags: [strava-upload, activity-summary, history, gpx, device-verification, milestone-finale]
status: complete
date: 2026-07-03
requirements: [UPL-01, UPL-02, UPL-03, UPL-04]
device:
  phone: "OPPO 3B164G01Y7L00000"
  glasses: "Rokid 1901092544802583"
requires:
  - "05-03: ActivitySummaryActivity + HistoryActivity + finish→summary launch + one-tap upload (driveUpload/StravaUploader)"
  - "05-01: SessionStore.readSession/updateUploadState (atomic add-only write-back) + SummaryMath + GpxWriter"
  - "05-02: StravaUploader.startUpload/poll (POST /uploads + poll GET /uploads/{id_str})"
  - "03-04: live Strava connection"
provides:
  - "On-device confirmation of the full record→summary→upload→history story (UPL-01..04), including the one irreducible human proof (activity visible in the real Strava feed)"
affects:
  - "Milestone v1.0 close (this is the milestone-finale device gate)"
gate: "Real recorded activity uploaded to the user's real Strava feed (activity 19170698786); local data-safety + history badge confirmed on hardware"
---

# Phase 5 · Plan 04 — Activity Summary + Strava Upload Device Verification (Milestone Finale) — Summary

**One-liner:** Recorded a real ride on the OPPO, viewed its summary, uploaded it to the user's real Strava feed (activity `19170698786`), and confirmed the history badge — closing UPL-01..04 end-to-end and completing the v1.0 milestone on hardware.

## What was verified (on device: OPPO `3B164G01Y7L00000` + Rokid glasses `1901092544802583`)

- **Summary render (UPL-01, SC#1):** recorded a **667 m ride** → Finish opened the activity summary with plausible metrics: **moving 2:00 < elapsed 3:02**, avg ~20 km/h, avg pace, and the route map/line. The P5-WR-01 read-retry window (15×200 ms ≈ 3 s) covered the real async finalize timing — no "Activity not found".
- **Real upload (UPL-02, SC#2 — the one irreducible human moment):** one tap on Upload generated the GPX, `POST /uploads`, polled to completion, and the activity landed in the user's **real Strava feed — activity id `19170698786`** (correct sport, route, time/distance). Human-confirmed in-feed.
- **Data safety (UPL-03, SC#4):** the upload write-back was **add-only** — the local session JSON kept all **181 trackpoints** intact; a forced-failure path leaves `stravaUploaded:false` and Retry re-uploads the same file (local data never destroyed by upload).
- **History (UPL-04, SC#3):** History listed the session newest-first with the **"Uploaded ✓"** badge, and the row reopened the summary.

## Outcome

All 4 UPL requirements confirmed end-to-end on real hardware, including the real Strava-feed landing. **PASSED.** Phase 5 is device-complete and this closes the v1.0 milestone's device verification.
