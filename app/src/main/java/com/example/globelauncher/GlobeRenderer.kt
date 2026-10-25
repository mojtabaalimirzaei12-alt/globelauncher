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

        // Atmos
