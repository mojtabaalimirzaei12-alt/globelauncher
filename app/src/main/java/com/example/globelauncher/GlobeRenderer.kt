package com.example.globelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import java.util.Calendar
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Loads the Earth picture and the country map from the app assets (once, in the background). */
object WorldAssets {
    @Volatile var tex: IntArray? = null
    @Volatile var texW = 0
    @Volatile var texH = 0
    @Volatile var world: World? = null
    @Volatile var finished = false
    private var started = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread {
            try {
                loadTexture(app)
            } catch (t: Throwable) {
                tex = null
            }
            try {
                loadWorld(app)
            } catch (t: Throwable) {
                world = null
            }
            finished = true
        }.start()
    }

    private fun loadTexture(app: Context) {
        val opts = BitmapFactory.Options()
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bmp = app.assets.open("earth.jpg").use { BitmapFactory.decodeStream(it, null, opts) } ?: return
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        texW = w
        texH = h
        tex = px
    }

    private fun loadWorld(app: Context) {
        val bytes = app.assets.open("countries.json").use { it.readBytes() }
        world = TopoWorld.parse(String(bytes, Charsets.UTF_8))
    }
}

/**
 * Everything that draws the globe screen. It is used both by the launcher view and by the live wallpaper.
 */
class GlobeRenderer(context: Context, private val wallpaper: Boolean) {

    private val appContext = context.applicationContext
    private val density = appContext.resources.displayMetrics.density

    // Texts shown at the bottom
    private val ownerName = "Mojtaba Alimirzaei"
    private val instagram = "@power_life313"

    // Layout
    private var width = 0
    private var height = 0
    private var cx = 0f
    private var cy = 0f
    private var radius = 1f

    // View state
    private var rotLon = 53.0
    private var rotLat = 32.0
    private var velLon = 0.0
    private val autoSpin = if (wallpaper) 4.0 else 5.0
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var lastFrame = 0L

    // Per frame rotation values
    private var fSinLat0 = 0f
    private var fCosLat0 = 1f
    private var fSinLon0 = 0f
    private var fCosLon0 = 1f

    // Planet picture
    private var sphere: EarthSphere? = null
    private var sphereBmp: Bitmap? = null
    private var spherePixels: IntArray? = null
    private val sphereDst = RectF()

    // Stars
    private var stars = FloatArray(0)
    private var brightStars = FloatArray(0)

    // Network of nodes around the globe
    private val meshN = 460
    private val mSinPhi = FloatArray(meshN)
    private val mCosPhi = FloatArray(meshN)
    private val mSinLam = FloatArray(meshN)
    private val mCosLam = FloatArray(meshN)
    private val mx = FloatArray(meshN)
    private val my = FloatArray(meshN)
    private val mz = FloatArray(meshN)
    private val meshA: IntArray
    private val meshB: IntArray
    private val meshBuf: FloatArray

    // Country borders (built when the map file is ready)
    private var worldReady: World? = null
    private var bSinPhi = FloatArray(0)
    private var bCosPhi = FloatArray(0)
    private var bSinLam = FloatArray(0)
    private var bCosLam = FloatArray(0)
    private var bx = FloatArray(0)
    private var by = FloatArray(0)
    private var bz = FloatArray(0)
    private var segA = IntArray(0)
    private var segB = IntArray(0)
    private var segBuf = FloatArray(0)

    // Work arrays for single rings (Iran, selected country)
    private var rx = FloatArray(0)
    private var ry = FloatArray(0)
    private var rz = FloatArray(0)
    private var rbuf = FloatArray(0)
    private val projTmp = FloatArray(3)

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
    private val iranPath = Path()
    private val bandPath = Path()

    // Paints
    private val bgPaint = Paint()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 220, 235, 255)
        strokeWidth = 1.3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val brightStarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 0.9f * density
    }
    private val meshLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 120, 225, 255)
        strokeWidth = 0.8f * density
    }
    private val meshDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 215, 110)
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val goldGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 9f * density
    }
    private val goldMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.6f * density
    }
    private val goldCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.7f * density
    }
    private val emblemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(218, 0, 0)
    }
    private val emblemPath = Path()
    private val gulfPath = Path()
    private val gulfMeasure = PathMeasure()
    private val gulfGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val gulfTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val satGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 150, 220, 255) }
    private val satLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
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
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 210, 110)
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 232, 170)
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        textAlign = Paint.Align.CENTER
        textSize = 24f * density
        letterSpacing = 0.05f
    }

    init {
        WorldAssets.ensureLoaded(appContext)

        // Nodes on a sphere
        val golden = PI * (3.0 - sqrt(5.0))
        val wx = DoubleArray(meshN)
        val wy = DoubleArray(meshN)
        val wz = DoubleArray(meshN)
        for (i in 0 until meshN) {
            val s = 1.0 - 2.0 * (i + 0.5) / meshN
            val la = asin(s)
            var lo = (golden * i) % (2.0 * PI)
            if (lo > PI) lo -= 2.0 * PI
            mSinPhi[i] = sin(la).toFloat()
            mCosPhi[i] = cos(la).toFloat()
            mSinLam[i] = sin(lo).toFloat()
            mCosLam[i] = cos(lo).toFloat()
            wx[i] = cos(la) * cos(lo)
            wy[i] = cos(la) * sin(lo)
            wz[i] = s
        }
        val ea = ArrayList<Int>()
        val eb = ArrayList<Int>()
        val thr = cos(0.2)
        for (i in 0 until meshN) {
            for (j in i + 1 until meshN) {
                if (wx[i] * wx[j] + wy[i] * wy[j] + wz[i] * wz[j] > thr) {
                    ea.add(i)
                    eb.add(j)
                }
            }
        }
        meshA = ea.toIntArray()
        meshB = eb.toIntArray()
        meshBuf = FloatArray(meshA.size * 4)

        for (k in 0 until satCount) {
            satInc[k] = Math.toRadians(35.0 + (k * 37) % 55)
            satPhase[k] = k * 0.9
            satSpeed[k] = 0.18 + 0.03 * (k % 4)
            satNode[k] = k * 0.7
        }
    }

    fun onSizeChanged(w: Int, h: Int) {
        width = w
        height = h
        if (w <= 0 || h <= 0) return
        if (wallpaper) {
            radius = min(w * 0.42f, (h - 330f * density) / 2f * 0.95f)
            cx = w / 2f
            cy = min(h * 0.46f, h - radius - 235f * density)
        } else {
            radius = min(w * 0.45f, (h - 340f * density) / 2f * 0.98f)
            cx = w / 2f
            cy = min(h * 0.36f, h - radius - 310f * density)
        }
        radius = max(radius, 40f)
        cy = max(cy, radius + 20f * density)

        bgPaint.shader = RadialGradient(
            cx, cy, max(w, h) * 0.75f,
            intArrayOf(Color.rgb(7, 20, 42), Color.rgb(2, 6, 14), Color.BLACK),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        discPaint.shader = RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.3f,
            intArrayOf(Color.rgb(30, 90, 170), Color.rgb(6, 20, 50)),
            null,
            Shader.TileMode.CLAMP
        )

        // Stars
        val rnd = Random(7)
        val count = 150
        val st = FloatArray(count * 2)
        for (i in 0 until count) {
            st[2 * i] = rnd.nextFloat() * w
            st[2 * i + 1] = rnd.nextFloat() * h
        }
        stars = st
        val bs = FloatArray(24)
        for (i in 0 until 12) {
            bs[2 * i] = rnd.nextFloat() * w
            bs[2 * i + 1] = rnd.nextFloat() * h
        }
        brightStars = bs

        // Planet bitmap (drawn small and enlarged smoothly: fast and soft)
        val s = min(320, max(64, (radius * 2f).toInt()))
        sphere = EarthSphere(s)
        sphereBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        spherePixels = IntArray(s * s)
        sphereDst.set(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    fun resetClock() {
        lastFrame = 0L
    }

    private fun prepareWorld(w: World) {
        worldReady = w
        val n = w.drawLon.size
        bSinPhi = FloatArray(n)
        bCosPhi = FloatArray(n)
        bSinLam = FloatArray(n)
        bCosLam = FloatArray(n)
        bx = FloatArray(n)
        by = FloatArray(n)
        bz = FloatArray(n)
        for (i in 0 until n) {
            val la = Math.toRadians(w.drawLat[i].toDouble())
            val lo = Math.toRadians(w.drawLon[i].toDouble())
            bSinPhi[i] = sin(la).toFloat()
            bCosPhi[i] = cos(la).toFloat()
            bSinLam[i] = sin(lo).toFloat()
            bCosLam[i] = cos(lo).toFloat()
        }
        val a = ArrayList<Int>()
        val b = ArrayList<Int>()
        for (arc in 0 until w.arcStart.size - 1) {
            for (k in w.arcStart[arc] until w.arcStart[arc + 1] - 1) {
                val lo0 = w.drawLon[k]
                val lo1 = w.drawLon[k + 1]
                val la0 = w.drawLat[k]
                val la1 = w.drawLat[k + 1]
                // skip the artificial cut lines on the 180 degree meridian and at the poles
                if (abs(lo0) > 179.9f && abs(lo1) > 179.9f) continue
                if (la0 < -89.9f && la1 < -89.9f) continue
                if (la0 > 89.9f && la1 > 89.9f) continue
                a.add(k)
                b.add(k + 1)
            }
        }
        segA = a.toIntArray()
        segB = b.toIntArray()
        segBuf = FloatArray(segA.size * 4)
    }

    // ------------------------------------------------------------------ drawing

    fun draw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016 else min(0.05, (now - lastFrame) / 1e9)
        lastFrame = now
        if (!dragging) {
            rotLon += velLon * dt
            velLon += (autoSpin - velLon) * min(1.0, dt * 1.5)
        }
        rotLon = wrapLon(rotLon)

        val lon0 = Math.toRadians(rotLon)
        val lat0 = Math.toRadians(rotLat)
        fSinLat0 = sin(lat0).toFloat()
        fCosLat0 = cos(lat0).toFloat()
        fSinLon0 = sin(lon0).toFloat()
        fCosLon0 = cos(lon0).toFloat()

        val world = WorldAssets.world
        if (world != null && worldReady !== world) prepareWorld(world)
        val sel = world?.countryAt(rotLat, rotLon) ?: -1

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawPoints(stars, starPaint)
        canvas.drawPoints(brightStars, brightStarPaint)

        // Atmosphere glow
        glowPaint.color = Color.argb(26, 70, 170, 255)
        glowPaint.strokeWidth = 26f * density
        canvas.drawCircle(cx, cy, radius + 10f * density, glowPaint)
        glowPaint.color = Color.argb(48, 100, 200, 255)
        glowPaint.strokeWidth = 12f * density
        canvas.drawCircle(cx, cy, radius + 4f * density, glowPaint)

        // The planet
        drawPlanet(canvas)

        // Network around the globe
        drawMesh(canvas)

        // Country borders
        if (world != null) drawBorders(canvas)

        // Iran with the flag colours and the golden border, or the selected country
        if (world != null && world.iran >= 0) drawIran(canvas, world, sel == world.iran)
        if (world != null && sel >= 0 && sel != world.iran) drawSelected(canvas, world.countries[sel])

        drawSatellites(canvas)

        // Centre marker
        canvas.drawCircle(cx, cy, 9f * density, crossPaint)
        canvas.drawCircle(cx, cy, 2f * density, crossPaint)

        drawInfo(canvas, world, sel)
    }

    private fun drawPlanet(canvas: Canvas) {
        val tex = WorldAssets.tex
        val sph = sphere
        val bmp = sphereBmp
        val px = spherePixels
        if (tex != null && sph != null && bmp != null && px != null) {
            sph.render(tex, WorldAssets.texW, WorldAssets.texH, Math.toRadians(rotLon), Math.toRadians(rotLat), px)
            bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, null, sphereDst, bitmapPaint)
        } else {
            canvas.drawCircle(cx, cy, radius, discPaint)
            if (!WorldAssets.finished) {
                canvas.drawText("Loading...", cx, cy, subPaint)
            } else {
                canvas.drawText("earth.jpg is missing", cx, cy, subPaint)
            }
        }
    }

    private fun drawMesh(canvas: Canvas) {
        for (i in 0 until meshN) {
            val sd = mSinLam[i] * fCosLon0 - mCosLam[i] * fSinLon0
            val cd = mCosLam[i] * fCosLon0 + mSinLam[i] * fSinLon0
            mx[i] = mCosPhi[i] * sd
            my[i] = fCosLat0 * mSinPhi[i] - fSinLat0 * mCosPhi[i] * cd
            mz[i] = fSinLat0 * mSinPhi[i] + fCosLat0 * mCosPhi[i] * cd
        }
        var c = 0
        for (e in meshA.indices) {
            val a = meshA[e]
            val b = meshB[e]
            if (mz[a] <= 0.02f || mz[b] <= 0.02f) continue
            meshBuf[c++] = cx + radius * mx[a]
            meshBuf[c++] = cy - radius * my[a]
            meshBuf[c++] = cx + radius * mx[b]
            meshBuf[c++] = cy - radius * my[b]
        }
        canvas.drawLines(meshBuf, 0, c, meshLinePaint)
        for (i in 0 until meshN) {
            val z = mz[i]
            if (z <= 0.02f) continue
            meshDotPaint.color = Color.argb((50 + 120 * z).toInt(), 150, 235, 255)
            canvas.drawCircle(cx + radius * mx[i], cy - radius * my[i], (0.9f + 0.8f * z) * density, meshDotPaint)
        }
    }

    private fun drawBorders(canvas: Canvas) {
        val n = bSinPhi.size
        for (i in 0 until n) {
            val sd = bSinLam[i] * fCosLon0 - bCosLam[i] * fSinLon0
            val cd = bCosLam[i] * fCosLon0 + bSinLam[i] * fSinLon0
            bx[i] = bCosPhi[i] * sd
            by[i] = fCosLat0 * bSinPhi[i] - fSinLat0 * bCosPhi[i] * cd
            bz[i] = fSinLat0 * bSinPhi[i] + fCosLat0 * bCosPhi[i] * cd
        }
        var c = 0
        for (s in segA.indices) {
            val a = segA[s]
            val b = segB[s]
            if (bz[a] <= 0f || bz[b] <= 0f) continue
            segBuf[c++] = cx + radius * bx[a]
            segBuf[c++] = cy - radius * by[a]
            segBuf[c++] = cx + radius * bx[b]
            segBuf[c++] = cy - radius * by[b]
        }
        canvas.drawLines(segBuf, 0, c, borderPaint)
    }

    private fun ensureRing(m: Int) {
        if (rx.size < m) {
            rx = FloatArray(m)
            ry = FloatArray(m)
            rz = FloatArray(m)
            rbuf = FloatArray(m * 4)
        }
    }

    /** Projects a ring to view coordinates (rx, ry, rz) and builds the line buffer. Returns number of floats. */
    private fun projectRing(ring: Ring): Int {
        val m = ring.lon.size
        ensureRing(m)
        for (k in 0 until m) {
            projectDeg(ring.lon[k], ring.lat[k])
            rx[k] = projTmp[0]
            ry[k] = projTmp[1]
            rz[k] = projTmp[2]
        }
        var c = 0
        for (k in 0 until m - 1) {
            if (rz[k] > 0f && rz[k + 1] > 0f) {
                rbuf[c++] = cx + radius * rx[k]
                rbuf[c++] = cy - radius * ry[k]
                rbuf[c++] = cx + radius * rx[k + 1]
                rbuf[c++] = cy - radius * ry[k + 1]
            }
        }
        return c
    }

    private fun projectDeg(lonDeg: Float, latDeg: Float) {
        val lo = Math.toRadians(lonDeg.toDouble())
        val la = Math.toRadians(latDeg.toDouble())
        val sinLam = sin(lo).toFloat()
        val cosLam = cos(lo).toFloat()
        val sinPhi = sin(la).toFloat()
        val cosPhi = cos(la).toFloat()
        val sd = sinLam * fCosLon0 - cosLam * fSinLon0
        val cd = cosLam * fCosLon0 + sinLam * fSinLon0
        projTmp[0] = cosPhi * sd
        projTmp[1] = fCosLat0 * sinPhi - fSinLat0 * cosPhi * cd
        projTmp[2] = fSinLat0 * sinPhi + fCosLat0 * cosPhi * cd
    }

    private fun drawSelected(canvas: Canvas, country: Country) {
        for (ring in country.rings) {
            val c = projectRing(ring)
            if (c > 0) canvas.drawLines(rbuf, 0, c, selPaint)
        }
    }

    private fun addClamped(path: Path, first: Boolean) {
        var x = projTmp[0]
        var y = projTmp[1]
        if (projTmp[2] < 0f) {
            val len = sqrt(x * x + y * y)
            if (len < 1e-4f) {
                x = 1f
                y = 0f
            } else {
                x /= len
                y /= len
            }
        }
        val sx = cx + radius * x
        val sy = cy - radius * y
        if (first) path.moveTo(sx, sy) else path.lineTo(sx, sy)
    }

    private fun drawIran(canvas: Canvas, world: World, selected: Boolean) {
        val iran = world.countries[world.iran]
        if (iran.rings.isEmpty()) return

        // How close is Iran to the middle of the view (0 far, 1 in the middle)
        val dotC = fCosLat0 * (cos(Math.toRadians(32.5)).toFloat() *
            (fCosLon0 * cos(Math.toRadians(54.0)).toFloat() + fSinLon0 * sin(Math.toRadians(54.0)).toFloat())) +
            fSinLat0 * sin(Math.toRadians(32.5)).toFloat()
        val ang = Math.toDegrees(acos(dotC.coerceIn(-1f, 1f).toDouble()))
        if (ang > 95.0) return
        var t = ((60.0 - ang) / 45.0).coerceIn(0.0, 1.0)
        t = t * t * (3.0 - 2.0 * t)
        if (selected) t = 1.0
        val pulse = if (t > 0.95) 0.88f + 0.12f * sin(System.nanoTime() / 2.5e8).toFloat() else 1f
        val strength = ((0.30 + 0.70 * t) * pulse).toFloat().coerceIn(0f, 1f)

        // Flag fill (green, white, red) clipped to the country shape
        iranPath.reset()
        var any = false
        for (ring in iran.rings) {
            val m = ring.lon.size
            var started = false
            for (k in 0 until m) {
                projectDeg(ring.lon[k], ring.lat[k])
                if (projTmp[2] > 0f) any = true
                addClamped(iranPath, !started)
                started = true
            }
            iranPath.close()
        }
        if (any) {
            val main = iran.rings[0]
            val top = main.maxLat
            val bottom = main.minLat
            val third = (top - bottom) / 3f
            val l0 = main.minLon - 3f
            val l1 = main.maxLon + 3f
            canvas.save()
            canvas.clipPath(iranPath)
            drawBand(canvas, top + 3f, top - third, l0, l1, Color.argb(215, 30, 160, 70))
            drawBand(canvas, top - third, top - 2f * third, l0, l1, Color.argb(225, 248, 248, 248))
            drawBand(canvas, top - 2f * third, bottom - 3f, l0, l1, Color.argb(215, 218, 30, 40))
            canvas.restore()
            drawEmblem(canvas, (main.minLon + main.maxLon) / 2f, top - 1.5f * third, third)
        }

        // Golden border with a glow that grows when Iran comes to the middle
        goldGlowPaint.color = Color.argb((95 * strength).toInt(), 255, 170, 30)
        goldMidPaint.color = Color.argb((175 * strength).toInt(), 255, 205, 70)
        goldCorePaint.color = Color.argb((60 + 195 * strength).toInt(), 255, 240, 170)
        for (ring in iran.rings) {
            val c = projectRing(ring)
            if (c > 0) {
                canvas.drawLines(rbuf, 0, c, goldGlowPaint)
                canvas.drawLines(rbuf, 0, c, goldMidPaint)
                canvas.drawLines(rbuf, 0, c, goldCorePaint)
            }
        }

        drawGulfLabel(canvas, t.toFloat(), pulse)
    }

    /** The red emblem in the middle of the flag, drawn from the traced shapes in EmblemShape. */
    private fun drawEmblem(canvas: Canvas, lon: Float, lat: Float, band: Float) {
        projectDeg(lon, lat)
        if (projTmp[2] < 0.3f) return
        // half of the emblem width in degrees: its height fills most of the white band
        val half = band * 0.86f / (2f * EmblemShape.HEIGHT_RATIO)
        val k = 1f / cos(Math.toRadians(lat.toDouble())).toFloat()
        for (shape in EmblemShape.shapes) {
            emblemPath.reset()
            var i = 0
            while (i + 1 < shape.size) {
                projectDeg(lon + shape[i] * half * k, lat + shape[i + 1] * half)
                val x = cx + radius * projTmp[0]
                val y = cy - radius * projTmp[1]
                if (i == 0) emblemPath.moveTo(x, y) else emblemPath.lineTo(x, y)
                i += 2
            }
            emblemPath.close()
            canvas.drawPath(emblemPath, emblemPaint)
        }
    }

    /** The glowing words "PERSIAN GULF" drawn along the Gulf; they shine when Iran comes to the middle. */
    private fun drawGulfLabel(canvas: Canvas, t: Float, pulse: Float) {
        if (t < 0.05f) return
        gulfPath.reset()
        var minZ = 1f
        var first = true
        var lon = 37f
        while (lon <= 66f) {
            val lat = 27.55f - 0.40f * (lon - 51.5f)
            projectDeg(lon, lat)
            if (projTmp[2] < minZ) minZ = projTmp[2]
            val x = cx + radius * projTmp[0]
            val y = cy - radius * projTmp[1]
            if (first) gulfPath.moveTo(x, y) else gulfPath.lineTo(x, y)
            first = false
            lon += 1f
        }
        if (minZ < 0.1f) return
        val vis = (((minZ - 0.1f) / 0.3f).coerceIn(0f, 1f) * t * pulse).coerceIn(0f, 1f)
        val text = "PERSIAN GULF"
        val size = radius * 0.042f
        gulfTextPaint.textSize = size
        gulfTextPaint.letterSpacing = 0.18f
        gulfGlowPaint.textSize = size
        gulfGlowPaint.letterSpacing = 0.18f
        gulfMeasure.setPath(gulfPath, false)
        val len = gulfMeasure.length
        val w = gulfTextPaint.measureText(text)
        val off = if (len > w) (len - w) / 2f else 0f
        val vOff = size * 0.35f

        gulfGlowPaint.strokeWidth = size * 0.55f
        gulfGlowPaint.color = Color.argb((80 * vis).toInt(), 255, 170, 30)
        canvas.drawTextOnPath(text, gulfPath, off, vOff, gulfGlowPaint)
        gulfGlowPaint.strokeWidth = size * 0.22f
        gulfGlowPaint.color = Color.argb((170 * vis).toInt(), 255, 205, 70)
        canvas.drawTextOnPath(text, gulfPath, off, vOff, gulfGlowPaint)
        gulfTextPaint.color = Color.argb((255 * vis).toInt(), 255, 246, 205)
        canvas.drawTextOnPath(text, gulfPath, off, vOff, gulfTextPaint)
    }

    private fun drawBand(canvas: Canvas, latHi: Float, latLo: Float, lonA: Float, lonB: Float, color: Int) {
        bandPath.reset()
        var first = true
        var lon = lonA
        while (lon < lonB) {
            projectDeg(lon, latLo)
            addClamped(bandPath, first)
            first = false
            lon += 1.5f
        }
        var lat = latLo
        while (lat < latHi) {
            projectDeg(lonB, lat)
            addClamped(bandPath, false)
            lat += 1.5f
        }
        lon = lonB
        while (lon > lonA) {
            projectDeg(lon, latHi)
            addClamped(bandPath, false)
            lon -= 1.5f
        }
        lat = latHi
        while (lat > latLo) {
            projectDeg(lonA, lat)
            addClamped(bandPath, false)
            lat -= 1.5f
        }
        bandPath.close()
        flagPaint.color = color
        canvas.drawPath(bandPath, flagPaint)
    }

    private fun drawSatellites(canvas: Canvas) {
        val t = System.currentTimeMillis() / 1000.0
        for (k in 0 until satCount) {
            val u = satPhase[k] + satSpeed[k] * t
            val la = asin(sin(satInc[k]) * sin(u))
            val lo = atan2(cos(satInc[k]) * sin(u), cos(u)) + satNode[k]
            val sinPhi = sin(la).toFloat()
            val cosPhi = cos(la).toFloat()
            val sinLam = sin(lo).toFloat()
            val cosLam = cos(lo).toFloat()
            val sd = sinLam * fCosLon0 - cosLam * fSinLon0
            val cd = cosLam * fCosLon0 + sinLam * fSinLon0
            val x = cosPhi * sd
            val y = fCosLat0 * sinPhi - fSinLat0 * cosPhi * cd
            val z = fSinLat0 * sinPhi + fCosLat0 * cosPhi * cd
            satX[k] = x * satRadius
            satY[k] = y * satRadius
            satZ[k] = z * satRadius
            satSx[k] = cx + radius * satRadius * x
            satSy[k] = cy - radius * satRadius * y
            val behind = z < 0f && (x * x + y * y) < 1f / (satRadius * satRadius)
            satVisible[k] = !behind
        }

        // Links between neighbouring satellites
        for (k in 0 until satCount) {
            val m = (k + 1) % satCount
            if (satVisible[k] && satVisible[m]) {
                canvas.drawLine(satSx[k], satSy[k], satSx[m], satSy[m], satLinkPaint)
            }
        }

        // Link from every satellite down to the nearest visible node of the network
        for (k in 0 until satCount) {
            if (!satVisible[k]) continue
            var best = -1
            var bestD = Float.MAX_VALUE
            for (i in 0 until meshN) {
                if (mz[i] <= 0.15f) continue
                val dx = mx[i] - satX[k]
                val dy = my[i] - satY[k]
                val dz = mz[i] - satZ[k]
                val d = dx * dx + dy * dy + dz * dz
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            if (best >= 0) {
                canvas.drawLine(satSx[k], satSy[k], cx + radius * mx[best], cy - radius * my[best], satLinkPaint)
            }
        }

        for (k in 0 until satCount) {
            if (satVisible[k]) {
                canvas.drawCircle(satSx[k], satSy[k], 7f * density, satGlowPaint)
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

    private fun drawInfo(canvas: Canvas, world: World?, sel: Int) {
        val nowMs = System.currentTimeMillis()
        val name = if (world != null && sel >= 0) Zones.pretty(world.countries[sel].name) else "Ocean"
        val zone = if (world != null && sel >= 0) world.countries[sel].zone else "LON"
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
        val gb = cy + radius
        drawFitted(canvas, name, cx, gb + 30f * density, countryPaint, w * 0.9f)
        canvas.drawText(time, cx, gb + 76f * density, timePaint)
        canvas.drawText("$date   $utc", cx, gb + 100f * density, subPaint)

        // Name between two golden lines
        val lineTop = gb + 124f * density
        val lineBottom = gb + 172f * density
        val lx0 = cx - w * 0.40f
        val lx1 = cx + w * 0.40f
        canvas.drawLine(lx0, lineTop, lx1, lineTop, linePaint)
        canvas.drawLine(lx0, lineBottom, lx1, lineBottom, linePaint)
        drawFitted(canvas, ownerName, cx, gb + 156f * density, namePaint, w * 0.78f)

        drawFitted(canvas, instagram, cx, gb + 205f * density, instaPaint, w * 0.9f)
    }

    // ------------------------------------------------------------------ touch

    fun onTouchEvent(event: MotionEvent): Boolean {
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
        return false
    }

    private fun wrapLon(v: Double): Double {
        var r = v
        while (r > 180.0) r -= 360.0
        while (r < -180.0) r += 360.0
        return r
    }
}
