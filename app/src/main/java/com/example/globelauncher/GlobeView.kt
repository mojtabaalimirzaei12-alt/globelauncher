package com.example.globelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    // Footer texts: change these two lines to edit what is shown at the bottom
    private val ownerName = "MOJTABA ALIMIRZAEI"
    private val instagram = "@power_life313"

    // Points on the globe
    private val n: Int
    private val lat: DoubleArray
    private val lon: DoubleArray
    private val sinLat: DoubleArray
    private val cosLat: DoubleArray
    private val wx: DoubleArray
    private val wy: DoubleArray
    private val wz: DoubleArray
    private val cid: IntArray

    // Projected data
    private val vx: FloatArray
    private val vy: FloatArray
    private val vz: FloatArray
    private val sx: FloatArray
    private val sy: FloatArray

    // Edges (kind 0 = sea, 1 = land)
    private val edgeA: IntArray
    private val edgeB: IntArray
    private val edgeKind: IntArray
    private val edgeCount: Int
    private val bufSea: FloatArray
    private val bufLand: FloatArray
    private val bufBorder: FloatArray
    private val bufSel: FloatArray

    // View state
    private var rotLon = 53.0
    private var rotLat = 32.0
    private var velLon = 0.0
    private val autoSpin = 5.0
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var lastFrame = 0L
    private var cx = 0f
    private var cy = 0f
    private var radius = 1f

    // Satellites
    private val satCount = 9
    private val satInc = DoubleArray(satCount)
    private val satPhase = DoubleArray(satCount)
    private val satSpeed = DoubleArray(satCount)
    private val satNode = DoubleArray(satCount)
    private val satX = FloatArray(satCount)
    private val satY = FloatArray(satCount)
    private val satZ = FloatArray(satCount)
    private val satSx = FloatArray(satCount)
    private val satSy = FloatArray(satCount)
    private val satVisible = BooleanArray(satCount)
    private val satRadius = 1.3f

    private val tzCache = HashMap<String, TimeZone>()

    private val palette = intArrayOf(
        Color.rgb(120, 235, 255),
        Color.rgb(90, 230, 200),
        Color.rgb(110, 170, 255),
        Color.rgb(170, 150, 255),
        Color.rgb(140, 255, 200),
        Color.rgb(100, 210, 255)
    )

    // Paints
    private val bgPaint = Paint()
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val seaLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 70, 160, 255)
        strokeWidth = 0.8f * density
    }
    private val landLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 90, 220, 255)
        strokeWidth = 0.9f * density
    }
    private val borderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = 1.5f * density
    }
    private val selLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 200, 70)
        strokeWidth = 1.3f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val satLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(240, 255, 200, 60)
        strokeWidth = 2f * density
    }
    private val countryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 21f * density
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 44f * density
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 120, 220, 255)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val instaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 230, 245, 255)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
        letterSpacing = 0.08f
    }

    init {
        // Build points from the 2 degree country grid, thinned toward the poles
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        val ids = ArrayList<Int>()
        for (r in 0 until WorldData.ROWS) {
            val latD = 90.0 - (r + 0.5) * 2.0
            val c = cos(Math.toRadians(latD))
            val step = min(12, max(1, Math.round(1.0 / c).toInt()))
            for (col in 0 until WorldData.COLS) {
                val id = WorldData.idAtCell(r, col)
                val lonD = -180.0 + (col + 0.5) * 2.0
                val keep = if (id > 0) {
                    col % step == 0
                } else {
                    r % 3 == 1 && col % (3 * step) == 1
                }
                if (keep) {
                    la.add(Math.toRadians(latD))
                    lo.add(Math.toRadians(lonD))
                    ids.add(id)
                }
            }
        }
        val cnt = la.size
        val latArr = la.toDoubleArray()
        val lonArr = lo.toDoubleArray()
        val sinArr = DoubleArray(cnt) { sin(latArr[it]) }
        val cosArr = DoubleArray(cnt) { cos(latArr[it]) }
        n = cnt
        lat = latArr
        lon = lonArr
        cid = ids.toIntArray()
        sinLat = sinArr
        cosLat = cosArr
        wx = DoubleArray(cnt) { cosArr[it] * cos(lonArr[it]) }
        wy = DoubleArray(cnt) { cosArr[it] * sin(lonArr[it]) }
        wz = sinArr
        vx = FloatArray(n)
        vy = FloatArray(n)
        vz = FloatArray(n)
        sx = FloatArray(n)
        sy = FloatArray(n)

        // Edges between neighbouring points (points are ordered from north to south)
        val ea = ArrayList<Int>()
        val eb = ArrayList<Int>()
        val ek = ArrayList<Int>()
        val landThr = cos(Math.toRadians(3.4))
        val seaThr = cos(Math.toRadians(9.5))
        val maxDlat = Math.toRadians(10.0)
        for (i in 0 until n) {
            var j = i + 1
            while (j < n && lat[i] - lat[j] <= maxDlat) {
                val d = wx[i] * wx[j] + wy[i] * wy[j] + wz[i] * wz[j]
                val iLand = cid[i] > 0
                val jLand = cid[j] > 0
                if (iLand && jLand) {
                    if (d > landThr) {
                        ea.add(i)
                        eb.add(j)
                        ek.add(1)
                    }
                } else if (!iLand && !jLand) {
                    if (d > seaThr) {
                        ea.add(i)
                        eb.add(j)
                        ek.add(0)
                    }
                }
                j++
            }
        }
        edgeCount = ea.size
        edgeA = ea.toIntArray()
        edgeB = eb.toIntArray()
        edgeKind = ek.toIntArray()
        bufSea = FloatArray(edgeCount * 4)
        bufLand = FloatArray(edgeCount * 4)
        bufBorder = FloatArray(edgeCount * 4)
        bufSel = FloatArray(edgeCount * 4)

        for (k in 0 until satCount) {
            satInc[k] = Math.toRadians(35.0 + (k * 37) % 55)
            satPhase[k] = k * 0.9
            satSpeed[k] = 0.18 + 0.03 * (k % 4)
            satNode[k] = k * 0.7
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h * 0.34f
        radius = min(w * 0.36f, h * 0.19f)
        bgPaint.shader = RadialGradient(
            cx, cy, max(w, h) * 0.7f,
            intArrayOf(Color.rgb(8, 24, 48), Color.rgb(2, 6, 14), Color.BLACK),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        discPaint.shader = RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.3f,
            intArrayOf(Color.argb(200, 10, 40, 80), Color.argb(230, 3, 10, 25)),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016 else min(0.05, (now - lastFrame) / 1e9)
        lastFrame = now

        if (!dragging) {
            rotLon += velLon * dt
            velLon += (autoSpin - velLon) * min(1.0, dt * 1.5)
        }
        rotLon = wrapLon(rotLon)

        val sel = WorldData.countryAt(rotLat, rotLon)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Atmosphere glow
        ringPaint.color = Color.argb(30, 80, 200, 255)
        ringPaint.strokeWidth = 14f * density
        canvas.drawCircle(cx, cy, radius + 4f * density, ringPaint)
        ringPaint.color = Color.argb(110, 100, 220, 255)
        ringPaint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cy, radius, ringPaint)

        canvas.drawCircle(cx, cy, radius, discPaint)

        project()

        // Edges
        var cs = 0
        var cl = 0
        var cb = 0
        var cn = 0
        for (e in 0 until edgeCount) {
            val a = edgeA[e]
            val b = edgeB[e]
            if (vz[a] <= 0f || vz[b] <= 0f) continue
            if (edgeKind[e] == 0) {
                bufSea[cs++] = sx[a]
                bufSea[cs++] = sy[a]
                bufSea[cs++] = sx[b]
                bufSea[cs++] = sy[b]
            } else if (sel > 0 && cid[a] == sel && cid[b] == sel) {
                bufSel[cn++] = sx[a]
                bufSel[cn++] = sy[a]
                bufSel[cn++] = sx[b]
                bufSel[cn++] = sy[b]
            } else if (cid[a] != cid[b]) {
                bufBorder[cb++] = sx[a]
                bufBorder[cb++] = sy[a]
                bufBorder[cb++] = sx[b]
                bufBorder[cb++] = sy[b]
            } else {
                bufLand[cl++] = sx[a]
                bufLand[cl++] = sy[a]
                bufLand[cl++] = sx[b]
                bufLand[cl++] = sy[b]
            }
        }
        canvas.drawLines(bufSea, 0, cs, seaLinePaint)
        canvas.drawLines(bufLand, 0, cl, landLinePaint)
        canvas.drawLines(bufBorder, 0, cb, borderLinePaint)
        canvas.drawLines(bufSel, 0, cn, selLinePaint)

        // Dots
        for (i in 0 until n) {
            val z = vz[i]
            if (z <= 0f) continue
            val id = cid[i]
            if (id == 0) {
                dotPaint.color = Color.argb((35 + 70 * z).toInt(), 60, 130, 220)
                canvas.drawCircle(sx[i], sy[i], (0.7f + 0.5f * z) * density, dotPaint)
            } else if (id == sel) {
                dotPaint.color = Color.argb((140 + 115 * z).toInt(), 255, 205, 90)
                canvas.drawCircle(sx[i], sy[i], (1.5f + 1.4f * z) * density, dotPaint)
            } else {
                val base = palette[(id * 7) % palette.size]
                dotPaint.color = Color.argb(
                    (100 + 150 * z).toInt(),
                    Color.red(base), Color.green(base), Color.blue(base)
                )
                canvas.drawCircle(sx[i], sy[i], (1.2f + 1.3f * z) * density, dotPaint)
            }
        }

        drawSatellites(canvas)

        // Center marker
        canvas.drawCircle(cx, cy, 9f * density, crossPaint)
        canvas.drawCircle(cx, cy, 2f * density, crossPaint)

        drawInfo(canvas, sel)

        postInvalidateOnAnimation()
    }

    private fun project() {
        val lon0 = Math.toRadians(rotLon)
        val lat0 = Math.toRadians(rotLat)
        val sl0 = sin(lat0)
        val cl0 = cos(lat0)
        for (i in 0 until n) {
            val dl = lon[i] - lon0
            val cd = cos(dl)
            val x = cosLat[i] * sin(dl)
            val y = cl0 * sinLat[i] - sl0 * cosLat[i] * cd
            val z = sl0 * sinLat[i] + cl0 * cosLat[i] * cd
            vx[i] = x.toFloat()
            vy[i] = y.toFloat()
            vz[i] = z.toFloat()
            sx[i] = cx + radius * x.toFloat()
            sy[i] = cy - radius * y.toFloat()
        }
    }

    private fun drawSatellites(canvas: Canvas) {
        val lon0 = Math.toRadians(rotLon)
        val lat0 = Math.toRadians(rotLat)
        val sl0 = sin(lat0)
        val cl0 = cos(lat0)
        val t = System.currentTimeMillis() / 1000.0

        for (k in 0 until satCount) {
            val u = satPhase[k] + satSpeed[k] * t
            val la = asin(sin(satInc[k]) * sin(u))
            val lo = atan2(cos(satInc[k]) * sin(u), cos(u)) + satNode[k]
            val dl = lo - lon0
            val cd = cos(dl)
            val x = (cos(la) * sin(dl)).toFloat()
            val y = (cl0 * sin(la) - sl0 * cos(la) * cd).toFloat()
            val z = (sl0 * sin(la) + cl0 * cos(la) * cd).toFloat()
            satX[k] = x * satRadius
            satY[k] = y * satRadius
            satZ[k] = z * satRadius
            satSx[k] = cx + radius * satRadius * x
            satSy[k] = cy - radius * satRadius * y
            val behindGlobe = z < 0f && (x * x + y * y) < 1f / (satRadius * satRadius)
            satVisible[k] = !behindGlobe
        }

        // Links between neighbouring satellites
        for (k in 0 until satCount) {
            val m = (k + 1) % satCount
            if (satVisible[k] && satVisible[m]) {
                canvas.drawLine(satSx[k], satSy[k], satSx[m], satSy[m], satLinkPaint)
            }
        }

        // Links from satellites to the closest visible surface point
        for (k in 0 until satCount) {
            if (!satVisible[k]) continue
            var best = -1
            var bestD = Float.MAX_VALUE
            for (i in 0 until n) {
                if (vz[i] <= 0.15f) continue
                val dx = vx[i] - satX[k]
                val dy = vy[i] - satY[k]
                val dz = vz[i] - satZ[k]
                val d = dx * dx + dy * dy + dz * dz
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            if (best >= 0) {
                canvas.drawLine(satSx[k], satSy[k], sx[best], sy[best], satLinkPaint)
            }
        }

        for (k in 0 until satCount) {
            if (satVisible[k]) {
                canvas.drawCircle(satSx[k], satSy[k], 3f * density, satPaint)
            }
        }
    }

    private fun offsetFor(zone: String, nowMs: Long): Long {
        if (zone != "LON") {
            val tz = tzCache.getOrPut(zone) { TimeZone.getTimeZone(zone) }
            if (tz.id == zone) return tz.getOffset(nowMs).toLong()
        }
        return Math.round(rotLon / 15.0) * 3600_000L
    }

    private fun drawFitted(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint, maxWidth: Float) {
        val original = paint.textSize
        val w = paint.measureText(text)
        if (w > maxWidth) paint.textSize = original * maxWidth / w
        canvas.drawText(text, x, y, paint)
        paint.textSize = original
    }

    private fun drawInfo(canvas: Canvas, sel: Int) {
        val nowMs = System.currentTimeMillis()
        val name = if (sel > 0) WorldData.names[sel] else "Ocean"
        val zone = if (sel > 0) WorldData.zones[sel] else "LON"
        val offMs = offsetFor(zone, nowMs)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = nowMs + offMs
        val time = String.format(
            Locale.US, "%02d:%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )
        val date = String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        val totalMin = (offMs / 60000L).toInt()
        val sign = if (totalMin >= 0) "+" else "-"
        val hh = abs(totalMin) / 60
        val mm = abs(totalMin) % 60
        val utc = if (mm == 0) "UTC$sign$hh" else String.format(Locale.US, "UTC%s%d:%02d", sign, hh, mm)

        val w = width.toFloat()
        val globeBottom = cy + radius
        drawFitted(canvas, name, cx, globeBottom + 30f * density, countryPaint, w * 0.9f)
        canvas.drawText(time, cx, globeBottom + 76f * density, timePaint)
        canvas.drawText("$date   $utc", cx, globeBottom + 100f * density, subPaint)

        ElvenText.draw(canvas, ownerName, cx, globeBottom + 128f * density, 30f * density, w * 0.92f)

        drawFitted(canvas, instagram, cx, globeBottom + 198f * density, instaPaint, w * 0.9f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                velLon = 0.0
                lastX = event.x
                lastY = event.y
                lastT = event.eventTime
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                val dtSec = max(0.001, (event.eventTime - lastT) / 1000.0)
                val dLon = dx / radius * 57.2958
                val dLat = dy / radius * 57.2958
                rotLon = wrapLon(rotLon - dLon)
                rotLat = max(-85.0, min(85.0, rotLat + dLat))
                velLon = max(-250.0, min(250.0, -dLon / dtSec))
                lastX = event.x
                lastY = event.y
                lastT = event.eventTime
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun wrapLon(v: Double): Double {
        var r = v
        while (r > 180.0) r -= 360.0
        while (r < -180.0) r += 360.0
        return r
    }
}
