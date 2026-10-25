package com.example.globelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
            if (lo >
