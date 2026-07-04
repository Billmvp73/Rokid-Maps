---
task: 260703-w1l
title: Defer off-route reroute until rider joins imported Strava route
status: complete
date: 2026-07-03
commit: c8001a7
requirements: [NAVV-03]
canonical_summary: 260703-w1l-SUMMARY.md
---

# Quick Task 260703-w1l — Summary

Status: **complete** (device-verified 2026-07-03). Full details in [`260703-w1l-SUMMARY.md`](./260703-w1l-SUMMARY.md).

**One-liner:** NavigationManager now distinguishes "approaching an imported Strava route from home" from "deviated off a route I was on" — it defers the off-route reroute until the rider has joined the route (latched via `hasBeenOnRoute`), shows "Head to route -> {dist}" while approaching (no OSRM, no Thread), and caps mid-ride reroute waypoints to <=25 for URL reliability.

Device-verified on the OPPO `3B164G01Y7L00000` + Rokid glasses `1901092544802583`: starting 58 km off-route showed **"^ Head to route 59.9 km"** (log: `Approaching route (58185m), not rerouting`) with no straight-line "Follow route"; stopping the mock so real GPS rejoined logged `Joined route (nearest 32m)` and re-engaged real turn-by-turn (`Turn right onto Innovation Drive, 283 m`). 190 phone tests green, assembleDebug exit 0 (commit c8001a7).

(This plain `SUMMARY.md` exists so the milestone audit recognizes the task as complete; the canonical write-up is `260703-w1l-SUMMARY.md` in this directory.)
