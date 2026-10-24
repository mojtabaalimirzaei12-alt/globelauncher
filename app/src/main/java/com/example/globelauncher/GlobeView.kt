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
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class GlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val n = 700
    private val density = resources.displayMetrics.density

    // Very rough continent outlines: pairs of (lon, lat) in degrees
    private val polygons: Array<FloatArray> = arrayOf(
        // North America
        floatArrayOf(
            -168f, 66f, -160f, 70f, -140f, 70f, -120f, 72f, -95f, 72f, -82f, 68f, -64f, 60f,
            -56f, 52f, -66f, 45f, -70f, 42f, -76f, 35f, -81f, 30f, -80f, 25f, -84f, 30f,
            -90f, 29f, -97f, 27f, -97f, 22f, -92f, 18f, -88f, 21f, -87f, 15f, -83f, 10f,
            -78f, 8f, -80f, 7f, -85f, 10f, -92f, 14f, -97f, 16f, -105f, 20f, -110f, 24f,
            -113f, 31f, -117f, 32f, -123f, 38f, -124f, 46f, -130f, 54f, -140f, 60f,
            -152f, 58f, -165f, 55f, -165f, 62f
        ),
        // South America
        floatArrayOf(
            -78f, 8f, -72f, 12f, -62f, 10f, -52f, 5f, -50f, 0f, -35f, -5f, -39f, -14f,
            -41f, -22f, -48f, -26f, -58f, -35f, -65f, -41f, -68f, -52f, -72f, -54f,
            -74f, -45f, -72f, -30f, -70f, -18f, -76f, -14f, -81f, -5f, -80f, 0f
        ),
        // Greenland
        floatArrayOf(
            -73f, 78f, -60f, 82f, -30f, 83f, -18f, 76f, -22f, 70f, -42f, 60f, -50f, 64f, -56f, 70f
        ),
        // Eurasia
        floatArrayOf(
            -10f, 36f, -9f, 43f, -1f, 46f, -4f, 48f, 2f, 51f, 8f, 54f, 10f, 57f, 5f, 62f,
            15f, 69f, 28f, 71f, 40f, 67f, 60f, 69f, 80f, 73f, 105f, 78f, 130f, 72f, 160f, 70f,
            180f, 68f, 180f, 65f, 170f, 60f, 160f, 55f, 156f, 51f, 143f, 52f, 140f, 46f,
            130f, 42f, 127f, 38f, 125f, 40f, 121f, 40f, 122f, 31f, 120f, 25f, 110f, 20f,
            106f, 10f, 103f, 2f, 100f, 8f, 98f, 16f, 92f, 22f, 88f, 22f, 80f, 15f, 77f, 8f,
            73f, 17f, 70f, 22f, 62f, 25f, 57f, 26f, 49f, 30f, 44f, 37f, 36f, 36f, 30f, 36f,
            27f, 37f, 28f, 41f, 23f, 38f, 20f, 40f, 14f, 45f, 8f, 44f, 3f, 43f, -5f, 36f
        ),
        // Arabia
        floatArrayOf(
            35f, 30f, 43f, 12f, 52f, 16f, 59f, 22f, 56f, 26f, 51f, 25f, 48f, 30f
        ),
        // Africa
        floatArrayOf(
            -17f, 21f, -10f, 35f, 10f, 37f, 32f, 31f, 34f, 28f, 43f, 12f, 51f, 12f, 40f, -3f,
            40f, -15f, 35f, -25f, 30f, -31f, 20f, -35f, 15f, -28f, 12f, -15f, 9f, -1f,
            9f, 4f, -8f, 4f, -17f, 14f
        ),
        // Australia
        floatArrayOf(
            114f, -22f, 122f, -18f, 130f, -12f, 137f, -12f, 142f, -11f, 146f, -19f, 153f, -26f,
            150f, -37f, 140f, -38f, 131f, -31f, 115f, -34f
        ),
        // British Isles
        floatArrayOf(
            -5f, 50f, 1f, 51f, 0f, 57f, -5f, 58f, -6f, 54f
        ),
        // Japan
        floatArrayOf(
            130f, 31f, 135f, 34f, 141f, 36f, 142f, 45f, 140f, 40f, 136f, 36f
        ),
        // Indonesia (Sumatra / Borneo, very rough)
        floatArrayOf(
            95f, 5f, 105f, -6f, 102f, -4f, 98f, 2f
        ),
        floatArrayOf(
            109f, 1f, 117f, 7f, 119f, 1f, 116f, -4f, 110f, -3f
        )
    )

    // Point data (Fibonacci sphere)
    private val lat = DoubleArray(n)
    private val lon = DoubleArray(n)
    private val sinLat = DoubleArray(n)
    private val cosLat = DoubleArray(n)
    private val wx = DoubleArray(n)
    private val wy = DoubleArray(n)
    private val wz = DoubleArray(n)
    private val land = BooleanArray(n)

    // Projected data
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val vz = FloatArray(n)
    private val sx = FloatArray(n)
    private val sy = FloatArray(n)

    // Edges
    private val edgeA: IntArray
    private val edgeB: IntArray
    private val edgeLand: BooleanArray
    private val edgeCount: Int
    private val landLines: FloatArray
    private val seaLines: FloatArray

    // View state
    private var rotLon = 30.0
    private var rotLat = 20.0
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

    // Paints
    private val bgPaint = Paint()
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val seaLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 70, 160, 255)
        strokeWidth = 0.8f * density
    }
    private val landLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 90, 220, 255)
        strokeWidth = 1.1f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val satLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 200, 60)
        strokeWidth = 2f * density
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 46f * density
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 120, 220, 255)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
    }

    init {
        val golden = PI * (3.0 - sqrt(5.0))
        for (i in 0 until n) {
            val s = 1.0 - 2.0 * (i + 0.5) / n
            val la = asin(s)
            var lo = (golden * i) % (2.0 * PI)
            if (lo > PI) lo -= 2.0 * PI
            lat[i] = la
            lon[i] = lo
            sinLat[i] = sin(la)
            cosLat[i] = cos(la)
            wx[i] = cos(la) * cos(lo)
            wy[i] = cos(la) * sin(lo)
            wz[i] = sin(la)
            land[i] = isLand(Math.toDegrees(lo), Math.toDegrees(la))
        }

        val ea = ArrayList<Int>()
        val eb = ArrayList<Int>()
        val threshold = cos(0.2)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val d = wx[i] * wx[j] + wy[i] * wy[j] + wz[i] * wz[j]
                if (d > threshold) {
                    ea.add(i)
                    eb.add(j)
                }
            }
        }
        edgeCount = ea.size
        edgeA = ea.toIntArray()
        edgeB = eb.toIntArray()
        edgeLand = BooleanArray(edgeCount) { land[edgeA[it]] && land[edgeB[it]] }
        landLines = FloatArray(edgeCount * 4)
        seaLines = FloatArray(edgeCount * 4)

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
        cy = h * 0.42f
        radius = min(w, h) * 0.36f
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
        var lc = 0
        var sc = 0
        for (e in 0 until edgeCount) {
            val a = edgeA[e]
            val b = edgeB[e]
            if (vz[a] <= 0f || vz[b] <= 0f) continue
            if (edgeLand[e]) {
                landLines[lc++] = sx[a]
                landLines[lc++] = sy[a]
                landLines[lc++] = sx[b]
                landLines[lc++] = sy[b]
            } else {
                seaLines[sc++] = sx[a]
                seaLines[sc++] = sy[a]
                seaLines[sc++] = sx[b]
                seaLines[sc++] = sy[b]
            }
        }
        canvas.drawLines(seaLines, 0, sc, seaLinePaint)
        canvas.drawLines(landLines, 0, lc, landLinePaint)

        // Dots
        for (i in 0 until n) {
            val z = vz[i]
            if (z <= 0f) continue
            if (land[i]) {
                dotPaint.color = Color.argb((110 + 145 * z).toInt(), 140, 240, 255)
                canvas.drawCircle(sx[i], sy[i], (1.6f + 1.6f * z) * density, dotPaint)
            } else {
                dotPaint.color = Color.argb((40 + 80 * z).toInt(), 60, 130, 220)
                canvas.drawCircle(sx[i], sy[i], (0.7f + 0.6f * z) * density, dotPaint)
            }
        }

        drawSatellites(canvas)

        // Center marker
        canvas.drawCircle(cx, cy, 9f * density, crossPaint)
        canvas.drawCircle(cx, cy, 2f * density, crossPaint)

        drawClock(canvas)

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

    private fun drawClock(canvas: Canvas) {
        val offset = Math.round(rotLon / 15.0).toInt()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = System.currentTimeMillis() + offset * 3600_000L

        val time = String.format(
            Locale.US, "%02d:%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )
        val date = String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        val sign = if (offset >= 0) "+" else "-"
        val utc = "UTC" + sign + Math.abs(offset)
        val latStr = String.format(Locale.US, "%.1f%s", Math.abs(rotLat), if (rotLat >= 0) "N" else "S")
        val lonStr = String.format(Locale.US, "%.1f%s", Math.abs(rotLon), if (rotLon >= 0) "E" else "W")

        val baseY = cy + radius + 70f * density
        canvas.drawText(time, cx, baseY, timePaint)
        canvas.drawText("$date   $utc", cx, baseY + 26f * density, subPaint)
        canvas.drawText("$latStr  $lonStr", cx, baseY + 48f * density, subPaint)
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

    private fun isLand(lonDeg: Double, latDeg: Double): Boolean {
        if (latDeg < -72.0) return true
        for (poly in polygons) {
            if (inPolygon(poly, lonDeg, latDeg)) return true
        }
        return false
    }

    private fun inPolygon(poly: FloatArray, px: Double, py: Double): Boolean {
        var inside = false
        val count = poly.size / 2
        var j = count - 1
        for (i in 0 until count) {
            val xi = poly[2 * i].toDouble()
            val yi = poly[2 * i + 1].toDouble()
            val xj = poly[2 * j].toDouble()
            val yj = poly[2 * j + 1].toDouble()
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
