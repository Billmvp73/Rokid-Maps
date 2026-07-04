package com.rokid.hud.glasses

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.rokid.hud.shared.protocol.Waypoint
import kotlin.math.*

class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TILE_SIZE = 256
        private const val MAP_ZOOM = 16
    }

    // Monochrome green palette — these glasses only display green
    private val hudBrightGreen = Color.parseColor("#00FF00")
    private val hudGreen = Color.parseColor("#00CC00")
    private val hudDimGreen = Color.parseColor("#008800")
    private val hudDarkGreen = Color.parseColor("#004400")
    private val hudFaintGreen = Color.parseColor("#003300")

    // Green-only color matrix: converts all color channels to green luminance
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,       // R output = 0
            0.30f, 0.59f, 0.11f, 0f, 0f, // G output = luminance
            0f, 0f, 0f, 0f, 0f,       // B output = 0
            0f, 0f, 0f, 1f, 0f        // A output = alpha
        )))
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; style = Paint.Style.STROKE; strokeWidth = 7f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x66, 0, 0xFF, 0); style = Paint.Style.STROKE; strokeWidth = 18f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; style = Paint.Style.FILL
    }
    private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 20f
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 16f
    }
    private val notifTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 15f
    }
    private val notifBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 13f
    }
    private val separatorPaint = Paint().apply {
        color = hudDarkGreen; strokeWidth = 1f
    }
    private val bgPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }
    private val mapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; style = Paint.Style.FILL
    }

    // Turn alert overlay paints (pre-allocated, all green)
    private val turnAlertBgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0); style = Paint.Style.FILL
    }
    private val turnAlertArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; typeface = Typeface.MONOSPACE; textSize = 64f
        textAlign = Paint.Align.CENTER
    }
    private val turnAlertInstrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val turnAlertDistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; typeface = Typeface.MONOSPACE; textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    // Sport layout paints (pre-allocated, all green)
    private val sportLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 16f
        textAlign = Paint.Align.CENTER
    }
    private val sportElapsedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 44f
        textAlign = Paint.Align.CENTER
    }
    private val sportPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 120f
        textAlign = Paint.Align.CENTER
    }
    private val sportUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val sportSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val sportDistancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 48f
        textAlign = Paint.Align.CENTER
    }
    private val sportBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudBrightGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    // Reusable Rect for tile drawing (avoids allocation in draw loop)
    private val tileDestRect = Rect()

    // 1Hz staleness ticker — SPORT-only, self-rescheduling (phone sportStateTicker
    // shape). Keeps invalidating while in SPORT so sportDisplayMode transitions
    // (dim / NO DATA) appear even when no messages arrive. The layoutMode guard
    // stops the chain automatically and makes over-scheduling harmless.
    private val sportTick = object : Runnable {
        override fun run() {
            if (state.layoutMode == MapLayoutMode.SPORT) {
                invalidate()
                postDelayed(this, 1000L)
            }
        }
    }

    var state: HudState = HudState()
        set(value) {
            val enteredSport = value.layoutMode == MapLayoutMode.SPORT &&
                    field.layoutMode != MapLayoutMode.SPORT
            val leftSport = value.layoutMode != MapLayoutMode.SPORT &&
                    field.layoutMode == MapLayoutMode.SPORT
            field = value
            if (enteredSport) { removeCallbacks(sportTick); postDelayed(sportTick, 1000L) }
            if (leftSport) removeCallbacks(sportTick)
            postInvalidate()
        }

    var tileManager: TileManager? = null
    var onLayoutToggle: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onLayoutToggle?.invoke()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
        override fun onDown(e: MotionEvent): Boolean = true
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Belt-and-braces re-arm if the view attaches while already in SPORT
        if (state.layoutMode == MapLayoutMode.SPORT) { removeCallbacks(sportTick); postDelayed(sportTick, 1000L) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(sportTick) // never leak the ticker past view teardown
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        when (state.layoutMode) {
            MapLayoutMode.FULL_SCREEN -> drawFullScreenLayout(canvas, w, h)
            MapLayoutMode.SMALL_CORNER -> drawSmallCornerLayout(canvas, w, h)
            MapLayoutMode.SPORT -> drawSportLayout(canvas, w, h)
            // Transitional: real birdview rendering (drawWholeRoute) lands in Task 3.
            MapLayoutMode.WHOLE_ROUTE -> drawFullScreenLayout(canvas, w, h)
            MapLayoutMode.MINI_BOTTOM -> drawMiniBottomLayout(canvas, w, h)
            MapLayoutMode.MINI_SPLIT -> drawMiniSplitLayout(canvas, w, h)
        }
        drawStatusBar(canvas, w)
        drawModeIndicator(canvas, w)
        drawTurnAlertOverlay(canvas, w, h)
        state.closingMessage?.let { drawClosingMessage(canvas, w, h, it) }
    }

    private fun drawClosingMessage(canvas: Canvas, w: Float, h: Float, message: String) {
        val overlayPaint = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudGreen
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        val x = w / 2f
        val y = h / 2f - (msgPaint.descent() + msgPaint.ascent()) / 2f
        canvas.drawText(message, x, y, msgPaint)
    }

    private fun drawTurnAlertOverlay(canvas: Canvas, w: Float, h: Float) {
        if (!state.showTurnAlert) return
        if (state.instruction.isBlank()) return
        if (state.maneuver.contains("arrive", true)) return
        val dist = state.distToNextStep
        if (dist < 1.0 || dist > 200.0) return

        // Draw semi-transparent overlay
        canvas.drawRect(0f, h * 0.15f, w, h * 0.85f, turnAlertBgPaint)

        val cx = w / 2f
        val cy = h / 2f

        // Large maneuver arrow
        val sym = maneuverToArrow(state.maneuver)
        canvas.drawText(sym, cx, cy - 20f, turnAlertArrowPaint)

        // Distance
        val distStr = formatDistance(dist)
        canvas.drawText(distStr, cx, cy + 25f, turnAlertDistPaint)

        // Instruction text (truncated)
        val instrTrunc = truncateText(state.instruction, turnAlertInstrPaint, w * 0.85f)
        canvas.drawText(instrTrunc, cx, cy + 58f, turnAlertInstrPaint)
    }

    // ── Full-screen: map top 72%, text bottom 28% ─────────────────────────

    private fun drawFullScreenLayout(canvas: Canvas, w: Float, h: Float) {
        val mapH = h * 0.72f
        val pad = 8f
        drawLiveMap(canvas, pad, pad, w - 2 * pad, mapH - 2 * pad)
        canvas.drawRect(pad, pad, w - pad, mapH - pad, mapBorderPaint)
        drawCompass(canvas, w - 50f, 50f, 36f)

        val textTop = mapH + 4f
        drawDirections(canvas, pad, textTop, w - 2 * pad)
        val dirH = 44f
        drawInfoArea(canvas, pad, textTop + dirH, w - 2 * pad, h - textTop - dirH - pad)
    }

    // ── Mini bottom (phone toggle): map 25% at bottom, direction+distance at bottom, no notifications ─

    private fun drawMiniBottomLayout(canvas: Canvas, w: Float, h: Float) {
        val dirStripH = 32f
        val mapHeight = h * 0.25f - dirStripH
        val mapTop = h - h * 0.25f
        val pad = 6f
        drawLiveMap(canvas, pad, mapTop + pad, w - 2 * pad, mapHeight - pad)
        canvas.drawRect(pad, mapTop + pad, w - pad, mapTop + mapHeight, mapBorderPaint)
        drawCompass(canvas, w - 32f, mapTop + 20f, 20f)
        val dirTop = h - dirStripH - 4f
        drawDirections(canvas, pad, dirTop, w - 2 * pad)
    }

    // ── Mini split: bottom 25% — map left, directions right ───────────────

    private fun drawMiniSplitLayout(canvas: Canvas, w: Float, h: Float) {
        val stripH = h * 0.25f
        val stripTop = h - stripH
        val pad = 6f
        val halfW = w / 2f

        // Left half: map
        drawLiveMap(canvas, pad, stripTop + pad, halfW - 2 * pad, stripH - 2 * pad)
        canvas.drawRect(pad, stripTop + pad, halfW - pad, stripTop + stripH - pad, mapBorderPaint)
        drawCompass(canvas, halfW - pad - 20f, stripTop + pad + 20f, 16f)

        // Right half: directions
        val textLeft = halfW + pad
        val textW = halfW - 2 * pad
        val centerY = stripTop + stripH / 2f

        if (state.instruction.isBlank()) {
            val p = Paint(textPaint).apply { textSize = 16f }
            canvas.drawText("Waiting for nav...", textLeft, centerY + 6f, p)
        } else {
            val isArrived = state.maneuver.contains("arrive", true) && state.stepDistance <= 0.0
            if (isArrived) {
                val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 22f
                }
                val arrivedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = hudBrightGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 16f
                }
                canvas.drawText("\u2713", textLeft, centerY - 4f, checkPaint)
                canvas.drawText("Arrived!", textLeft + 28f, centerY - 4f, arrivedPaint)
            } else {
                val sym = maneuverToArrow(state.maneuver)
                val dist = formatDistance(effectiveStepDistance())

                // Maneuver arrow + distance
                val arrowPaintL = Paint(textPaint).apply { textSize = 28f }
                val distPaintL = Paint(textPaint).apply { textSize = 20f }
                canvas.drawText(sym, textLeft, centerY - 6f, arrowPaintL)
                canvas.drawText(dist, textLeft + 36f, centerY - 6f, distPaintL)

                // Instruction text (wrapped to fit)
                val instrPaint = Paint(smallTextPaint).apply { textSize = 14f; color = hudGreen }
                val instrTrunc = truncateText(state.instruction, instrPaint, textW)
                canvas.drawText(instrTrunc, textLeft, centerY + 16f, instrPaint)
            }
        }
    }

    // ── Small-corner: text left 62%, map bottom-right 38% ─────────────────

    private fun drawSmallCornerLayout(canvas: Canvas, w: Float, h: Float) {
        val mapW = w * 0.38f
        val mapH = h * 0.42f
        val mapLeft = w - mapW - 6f
        val mapTop = h - mapH - 6f
        val pad = 8f

        drawLiveMap(canvas, mapLeft, mapTop, mapW, mapH)
        canvas.drawRect(mapLeft, mapTop, mapLeft + mapW, mapTop + mapH, mapBorderPaint)
        drawCompass(canvas, mapLeft + mapW - 30f, mapTop - 36f, 28f)

        val textW = w - mapW - 20f
        drawDirections(canvas, pad, pad, textW)
        val dirH = 44f
        drawInfoArea(canvas, pad, pad + dirH, textW, h - dirH - 2 * pad)
    }

    // ── Live tile map with bearing rotation ───────────────────────────────

    private fun drawLiveMap(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        canvas.save()
        canvas.clipRect(left, top, left + w, top + h)

        val cx = left + w / 2
        val cy = top + h / 2

        if (state.latitude == 0.0 && state.longitude == 0.0) {
            val p = Paint(smallTextPaint).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("Waiting for GPS...", cx, cy, p)
            canvas.restore()
            return
        }

        val n = (1 shl MAP_ZOOM).toDouble()
        val fracX = (state.longitude + 180.0) / 360.0 * n
        val latRad = Math.toRadians(state.latitude)
        val fracY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
        val gpxX = fracX * TILE_SIZE
        val gpxY = fracY * TILE_SIZE

        // Rotate map so heading points up
        canvas.save()
        canvas.rotate(-state.bearing, cx, cy)

        drawTiles(canvas, cx, cy, gpxX, gpxY, w, h, n)

        if (state.waypoints.size >= 2) {
            drawRouteOnTiles(canvas, cx, cy, gpxX, gpxY, n)
        }

        canvas.restore() // undo rotation

        // Player arrow always points up (direction of travel)
        drawPlayerArrow(canvas, cx, cy)

        canvas.restore() // undo clip
    }

    private fun drawTiles(
        canvas: Canvas, cx: Float, cy: Float,
        gpxX: Double, gpxY: Double, viewW: Float, viewH: Float, n: Double
    ) {
        val diag = sqrt(viewW * viewW + viewH * viewH)
        val margin = (diag / 2 + TILE_SIZE).toInt()
        val tilesMargin = margin / TILE_SIZE + 1
        val centerTileX = floor(gpxX / TILE_SIZE).toInt()
        val centerTileY = floor(gpxY / TILE_SIZE).toInt()
        val maxTile = n.toInt()

        for (dy in -tilesMargin..tilesMargin) {
            for (dx in -tilesMargin..tilesMargin) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                if (ty < 0 || ty >= maxTile) continue
                val wrappedTx = ((tx % maxTile) + maxTile) % maxTile

                val screenX = (tx * TILE_SIZE - gpxX + cx).toFloat()
                val screenY = (ty * TILE_SIZE - gpxY + cy).toFloat()

                val bmp = tileManager?.getTile(MAP_ZOOM, wrappedTx, ty)
                if (bmp != null && !bmp.isRecycled) {
                    tileDestRect.set(
                        screenX.toInt(), screenY.toInt(),
                        (screenX + TILE_SIZE).toInt(), (screenY + TILE_SIZE).toInt()
                    )
                    canvas.drawBitmap(bmp, null, tileDestRect, tilePaint)
                }
            }
        }
    }

    private fun drawRouteOnTiles(
        canvas: Canvas, cx: Float, cy: Float,
        gpxX: Double, gpxY: Double, n: Double
    ) {
        val path = Path()
        var first = true
        for (wp in state.waypoints) {
            val wpFracX = (wp.longitude + 180.0) / 360.0 * n
            val wpLatRad = Math.toRadians(wp.latitude)
            val wpFracY = (1.0 - ln(tan(wpLatRad) + 1.0 / cos(wpLatRad)) / Math.PI) / 2.0 * n
            val sx = (wpFracX * TILE_SIZE - gpxX + cx).toFloat()
            val sy = (wpFracY * TILE_SIZE - gpxY + cy).toFloat()
            if (first) { path.moveTo(sx, sy); first = false } else { path.lineTo(sx, sy) }
        }
        canvas.drawPath(path, routeGlowPaint)
        canvas.drawPath(path, routePaint)
    }

    private fun drawPlayerArrow(canvas: Canvas, cx: Float, cy: Float) {
        val arrowPath = Path().apply {
            moveTo(cx, cy - 16f)
            lineTo(cx - 10f, cy + 12f)
            lineTo(cx, cy + 5f)
            lineTo(cx + 10f, cy + 12f)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.drawPath(arrowPath, arrowOutlinePaint)
    }

    // ── Compass ───────────────────────────────────────────────────────────

    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, compassPaint)
        canvas.save()
        canvas.rotate(-state.bearing, cx, cy)
        val nPaint = Paint(textPaint).apply { textSize = 14f; textAlign = Paint.Align.CENTER; color = hudGreen }
        canvas.drawText("N", cx, cy - radius + 14f, nPaint)
        canvas.drawLine(cx, cy, cx, cy - radius + 4f, dotPaint)
        canvas.restore()
        val degPaint = Paint(smallTextPaint).apply { textSize = 11f; textAlign = Paint.Align.CENTER }
        canvas.drawText("${state.bearing.toInt()}°", cx, cy + radius + 14f, degPaint)
    }

    // ── Directions ────────────────────────────────────────────────────────

    private fun effectiveStepDistance(): Double {
        return if (state.distToNextStep >= 0) state.distToNextStep else state.stepDistance
    }

    private fun drawDirections(canvas: Canvas, left: Float, top: Float, maxWidth: Float) {
        if (state.instruction.isBlank()) {
            val p = Paint(textPaint).apply { textSize = 18f }
            canvas.drawText("Waiting for navigation...", left, top + 20f, p)
            return
        }

        val isArrived = state.maneuver.contains("arrive", true) && state.stepDistance <= 0.0

        if (isArrived) {
            val arrivedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = hudBrightGreen; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 24f
                textAlign = Paint.Align.CENTER
            }
            val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            val cx = left + maxWidth / 2
            canvas.drawText("\u2713", cx, top + 24f, checkPaint)
            canvas.drawText("You have arrived!", cx, top + 52f, arrivedPaint)
            return
        }

        val sym = maneuverToArrow(state.maneuver)
        val dist = formatDistance(effectiveStepDistance())
        val instrPaint = Paint(textPaint).apply { textSize = 20f }
        val distPaint = Paint(textPaint).apply { textSize = 18f; textAlign = Paint.Align.RIGHT }
        val instrTrunc = truncateText("$sym ${state.instruction}", instrPaint, maxWidth - 80f)
        canvas.drawText(instrTrunc, left, top + 22f, instrPaint)
        canvas.drawText(dist, left + maxWidth, top + 22f, distPaint)
        canvas.drawLine(left, top + 34f, left + maxWidth, top + 34f, separatorPaint)
    }

    // ── Notifications / upcoming steps area ──────────────────────────────

    private fun drawInfoArea(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        if (!state.streamNotifications && state.showUpcomingSteps && state.allSteps.isNotEmpty()) {
            drawUpcomingSteps(canvas, left, top, maxWidth, maxHeight)
        } else if (state.streamNotifications) {
            drawNotifications(canvas, left, top, maxWidth, maxHeight)
        }
        // If streamNotifications is off and showUpcomingSteps is off, draw nothing
    }

    private fun drawNotifications(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        if (state.notifications.isEmpty()) return
        var y = top + 6f
        val lineHeight = 30f
        for (notif in state.notifications) {
            if (y + lineHeight > top + maxHeight) break
            val title = notif.title ?: "Notification"
            val body = notif.text ?: ""
            canvas.drawText(truncateText(title, notifTitlePaint, maxWidth - 10f), left + 2f, y + 14f, notifTitlePaint)
            if (body.isNotBlank()) {
                canvas.drawText(truncateText(body, notifBodyPaint, maxWidth - 10f), left + 2f, y + 28f, notifBodyPaint)
            }
            y += lineHeight
            canvas.drawLine(left, y, left + maxWidth, y, separatorPaint)
            y += 4f
        }
    }

    private fun drawUpcomingSteps(canvas: Canvas, left: Float, top: Float, maxWidth: Float, maxHeight: Float) {
        // Show steps after the current one (current step is already in the directions bar)
        val startIdx = state.currentStepIndex + 1
        if (startIdx >= state.allSteps.size) return

        val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudDimGreen; typeface = Typeface.MONOSPACE; textSize = 14f
        }
        val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hudGreen; typeface = Typeface.MONOSPACE; textSize = 13f; textAlign = Paint.Align.RIGHT
        }

        var y = top + 6f
        val lineHeight = 24f
        for (i in startIdx until state.allSteps.size) {
            if (y + lineHeight > top + maxHeight) break
            val step = state.allSteps[i]
            val sym = maneuverToArrow(step.maneuver)
            val dist = formatDistance(step.distance)
            val instr = truncateText("$sym ${step.instruction}", stepPaint, maxWidth - 70f)
            canvas.drawText(instr, left + 2f, y + 14f, stepPaint)
            canvas.drawText(dist, left + maxWidth - 2f, y + 14f, distPaint)
            y += lineHeight
            canvas.drawLine(left, y, left + maxWidth, y, separatorPaint)
            y += 3f
        }
    }

    // ── Status bar (BT + WiFi) ────────────────────────────────────────────

    private fun drawStatusBar(canvas: Canvas, w: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; textAlign = Paint.Align.LEFT
        }
        val y = 14f
        var x = 8f

        p.color = if (state.btConnected) hudGreen else hudDimGreen
        val btLabel = if (state.btConnected) "BT:ON" else "BT:--"
        canvas.drawText(btLabel, x, y, p)
        x += p.measureText(btLabel) + 10f

        p.color = if (state.wifiConnected) hudGreen else hudDimGreen
        val wifiLabel = if (state.wifiConnected) "WiFi:ON" else "WiFi:--"
        canvas.drawText(wifiLabel, x, y, p)
        x += p.measureText(wifiLabel) + 10f

        if (state.batteryLevel >= 0) {
            p.color = hudGreen
            val batLabel = "BAT:${state.batteryLevel}%"
            canvas.drawText(batLabel, x, y, p)
            x += p.measureText(batLabel) + 10f
        }

        // Speed display (always show when enabled)
        if (state.showSpeed) {
            val speedVal: Int
            val speedUnit: String
            if (state.useImperial) {
                speedVal = (state.speed * 2.23694f).toInt() // m/s to mph
                speedUnit = "mph"
            } else {
                speedVal = (state.speed * 3.6f).toInt() // m/s to km/h
                speedUnit = "km/h"
            }

            // Check if over speed limit (only highlight red when limit display is also on)
            val overLimit = if (state.showSpeedLimit && state.speedLimitKmh > 0) {
                val currentKmh = (state.speed * 3.6f).toInt()
                currentKmh > state.speedLimitKmh
            } else false

            p.color = hudGreen
            val speedPrefix = if (overLimit) "! " else ""
            val speedLabel = "$speedPrefix$speedVal $speedUnit"
            canvas.drawText(speedLabel, x, y, p)
            x += p.measureText(speedLabel) + 10f

            // Speed limit if available and enabled
            if (state.showSpeedLimit && state.speedLimitKmh > 0) {
                val limitVal = if (state.useImperial) {
                    (state.speedLimitKmh / 1.60934).toInt()
                } else state.speedLimitKmh
                val limitUnit = if (state.useImperial) "mph" else "km/h"
                p.color = hudDimGreen
                val limitLabel = "lim:$limitVal$limitUnit"
                canvas.drawText(limitLabel, x, y, p)
            }
        }
    }

    // ── Sport layout: pure metrics, no map, huge numerals (HUD-01..04) ────

    private fun drawSportLayout(canvas: Canvas, w: Float, h: Float) {
        // Draw-time classification against the same monotonic clock family as the
        // BluetoothClient receipt stamp (lastSportStateAtMs).
        val mode = state.sportDisplayMode(SystemClock.elapsedRealtime())
        val dimmed = mode == SportDisplayMode.STALE_DIM || mode == SportDisplayMode.STALE_NO_DATA
        val cx = w / 2f

        // Geometry as fractions of h; comments give the 480x640 reference values.
        // All content stays below y = 24f — the status strip (y=14f) and "[ SPORT ]"
        // indicator are drawn by onDraw AFTER this branch; never redraw them here.
        val yElapsedLabel = h * 0.19f   // ~120 on the 640 canvas
        val yElapsed = h * 0.26f        // ~165
        val yBanner = h * 0.36f         // ~230 (FINISHED banner, between elapsed and primary)
        val yPrimary = h * 0.52f        // ~330 (huge center numeral)
        val yPrimaryUnit = h * 0.59f    // ~375
        val ySecondary = h * 0.69f      // ~440
        val yDistanceLabel = h * 0.81f  // ~520
        val yDistance = h * 0.88f       // ~565

        // Dim rule (HUD-04): values step to hudDimGreen, labels step to hudDarkGreen —
        // one level down each. Colors are assigned per pass (drawStatusBar pattern).
        sportLabelPaint.color = if (dimmed) hudDarkGreen else hudDimGreen
        sportElapsedPaint.color = if (dimmed) hudDimGreen else hudGreen
        sportPrimaryPaint.color = if (dimmed) hudDimGreen else hudBrightGreen
        sportUnitPaint.color = if (dimmed) hudDarkGreen else hudDimGreen
        sportSecondaryPaint.color = if (dimmed) hudDimGreen else hudGreen
        sportDistancePaint.color = if (dimmed) hudDimGreen else hudGreen
        sportBannerPaint.color = hudBrightGreen // banners always demand attention

        if (mode == SportDisplayMode.NOT_RECORDING) {
            // Hint in the primary slot + ASCII "--" placeholders (em-dash tofu risk)
            canvas.drawText("ELAPSED", cx, yElapsedLabel, sportLabelPaint)
            canvas.drawText("--", cx, yElapsed, sportElapsedPaint)
            canvas.drawText("NOT RECORDING", cx, yPrimary, sportBannerPaint)
            canvas.drawText("--", cx, ySecondary, sportSecondaryPaint)
            canvas.drawText("DISTANCE", cx, yDistanceLabel, sportLabelPaint)
            canvas.drawText("--", cx, yDistance, sportDistancePaint)
            return
        }

        // Tracking family (LIVE / STALE_DIM / STALE_NO_DATA / FINISHED): full stack.
        // Sport-aware primary — lenient: decode already defaults unknown sports to "ride".
        val isRide = state.sport != "run"
        val primaryText: String
        val primaryUnit: String
        val secondaryText: String?
        if (isRide) {
            primaryText = SportFormat.formatSpeed(state.currentSpeedMps, state.useImperial)
            primaryUnit = SportFormat.speedUnit(state.useImperial)
            // Pace unset below the phone's 100m floor: hide the secondary line entirely
            secondaryText = if (state.avgPaceMsPerKm == 0L) null
            else SportFormat.formatPace(state.avgPaceMsPerKm, state.useImperial) +
                    " " + SportFormat.paceUnit(state.useImperial)
        } else {
            // Run primary shows "--:--" when pace is unset (a huge blank hole is worse)
            primaryText = SportFormat.formatPace(state.avgPaceMsPerKm, state.useImperial)
            primaryUnit = SportFormat.paceUnit(state.useImperial)
            secondaryText = SportFormat.formatSpeed(state.currentSpeedMps, state.useImperial) +
                    " " + SportFormat.speedUnit(state.useImperial)
        }

        canvas.drawText("ELAPSED", cx, yElapsedLabel, sportLabelPaint)
        canvas.drawText(SportFormat.formatElapsed(state.elapsedMs), cx, yElapsed, sportElapsedPaint)

        if (mode == SportDisplayMode.FINISHED) {
            // Banner over retained FULL-brightness values; staleness never applies here
            canvas.drawText("FINISHED", cx, yBanner, sportBannerPaint)
        }

        if (mode == SportDisplayMode.STALE_NO_DATA) {
            // Banner paint, NOT the 120f primary: "NO DATA" at 120f MONOSPACE is
            // ~504px wide and would overflow the 480px canvas
            canvas.drawText("NO DATA", cx, yPrimary, sportBannerPaint)
        } else {
            canvas.drawText(primaryText, cx, yPrimary, sportPrimaryPaint)
            if (mode == SportDisplayMode.LIVE || mode == SportDisplayMode.STALE_DIM) {
                // Moving dot right of the primary numeral, secondary color; a dot on
                // FINISHED/NO DATA/NOT RECORDING would imply live data
                val dotX = cx + sportPrimaryPaint.measureText(primaryText) / 2f + 20f
                canvas.drawText(SportFormat.movingDot(state.currentSpeedMps), dotX, yPrimary, sportSecondaryPaint)
            }
        }
        canvas.drawText(primaryUnit, cx, yPrimaryUnit, sportUnitPaint)

        if (secondaryText != null) {
            canvas.drawText(secondaryText, cx, ySecondary, sportSecondaryPaint)
        }

        canvas.drawText("DISTANCE", cx, yDistanceLabel, sportLabelPaint)
        // formatDistance returns "" below 1m — render an explicit zero while tracking
        val distanceText = if (state.distanceM < 1) {
            if (state.useImperial) "0 ft" else "0 m"
        } else formatDistance(state.distanceM)
        canvas.drawText(distanceText, cx, yDistance, sportDistancePaint)
    }

    // ── Mode indicator ────────────────────────────────────────────────────

    private fun drawModeIndicator(canvas: Canvas, w: Float) {
        val label = when (state.layoutMode) {
            MapLayoutMode.FULL_SCREEN -> "[ FULL ]"
            MapLayoutMode.SMALL_CORNER -> "[ CORNER ]"
            MapLayoutMode.SPORT -> "[ SPORT ]"
            MapLayoutMode.WHOLE_ROUTE -> "[ ROUTE ]"
            MapLayoutMode.MINI_BOTTOM -> "[ STRIP ]"
            MapLayoutMode.MINI_SPLIT -> "[ SPLIT ]"
        }
        val p = Paint(smallTextPaint).apply { textSize = 11f; textAlign = Paint.Align.RIGHT }
        canvas.drawText(label, w - 8f, 14f, p)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun truncateText(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("...") > maxW) end--
        return text.substring(0, end) + "..."
    }

    private fun formatDistance(meters: Double): String {
        if (meters < 1) return ""
        return if (state.useImperial) {
            val feet = meters * 3.28084
            val miles = meters / 1609.344
            when {
                miles >= 0.1 -> String.format("%.1f mi", miles)
                else -> String.format("%.0f ft", feet)
            }
        } else {
            when {
                meters >= 1000 -> String.format("%.1f km", meters / 1000)
                else -> String.format("%.0f m", meters)
            }
        }
    }

    private fun maneuverToArrow(maneuver: String): String = when {
        maneuver.contains("left", true) -> "←"
        maneuver.contains("right", true) -> "→"
        maneuver.contains("uturn", true) -> "↩"
        maneuver.contains("straight", true) -> "↑"
        maneuver.contains("arrive", true) -> "●"
        maneuver.contains("depart", true) -> "▶"
        maneuver.contains("merge", true) -> "⤵"
        maneuver.contains("ramp", true) -> "↗"
        maneuver.contains("fork", true) -> "⑂"
        else -> "↑"
    }
}
