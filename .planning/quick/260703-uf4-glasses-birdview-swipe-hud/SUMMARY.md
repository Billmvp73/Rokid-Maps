---
task: 260703-uf4
title: Glasses whole-route birdview + 4-page swipe HUD + backward-compatible route `full` flag
status: complete
date: 2026-07-03
commit: b5f03ed
requirements: [v1.x-enhancement]
canonical_summary: 260703-uf4-SUMMARY.md
---

# Quick Task 260703-uf4 — Summary

Status: **complete** (device-verified 2026-07-03). Full details in [`260703-uf4-SUMMARY.md`](./260703-uf4-SUMMARY.md).

**One-liner:** Added a whole-route bird's-eye page and a 4-page swipeable glasses HUD (FULL → CORNER → SPORT → WHOLE_ROUTE) driven by the touchpad's real DPAD_LEFT/RIGHT keycodes; a backward-compatible route `full` flag preserves the original imported route in a separate `wholeRoute` source so an off-route reroute never clobbers the birdview.

Device-verified on the OPPO `3B164G01Y7L00000` + Rokid glasses `1901092544802583`: all 4 pages screencapped; the **D4 invariant proven on hardware** — starting 58 km off-route, the instruction page rerouted (to SF) while the whole-route birdview kept showing the real Milpitas Strava route. Test suite 208 → 219 green. Shipped across 5 commits (base `b5f03ed`).

(This plain `SUMMARY.md` exists so the milestone audit recognizes the task as complete; the canonical write-up with the full dependency graph is `260703-uf4-SUMMARY.md` in this directory.)
