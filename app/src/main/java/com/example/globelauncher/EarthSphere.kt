package com.example.globelauncher

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a lit, textured sphere into a square pixel array.
 * The texture is a normal world picture (left edge = 180 W, top = 90 N).
 */
class EarthSphere(private val size: Int) {
    private val n: Int
    private val idx: IntArray
    private val xs: FloatArray
    private val ys: FloatArray
    private val zs: FloatArray
    private val lit: FloatArray
    private val rim: FloatArray
    private val alpha: IntArray

    // Depend on the viewing tilt (latitude)
    private val vRow: FloatArray
    private val dLamN: FloatArray
    private var builtLat = Double.NaN
    private var builtTexH = -1

    init {
        val tmpIdx = IntArray(size * size)
        var c = 0
        // light comes from the upper left, in front
        var lx = -0.45
        var ly = 0.55
        var lz = 0.70
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        lx /= ll
        ly /= ll
        lz /= ll
        val px = FloatArray(size * size)
        val py = FloatArray(size * size)
        val pz = FloatArray(size * size)
        val pl = FloatArray(size * size)
        val pr = FloatArray(size * size)
        val pa = IntArray(size * size)
        for (j in 0 until size) {
            for (i in 0 until size) {
                var x = (i + 0.5) / size * 2.0 - 1.0
                var y = 1.0 - (j + 0.5) / size * 2.0
                val r = sqrt(x * x + y * y)
                val a = ((0.5 + (1.0 - r) * size / 2.0).coerceIn(0.0, 1.0) * 255.0).toInt()
                if (a <= 0) continue
                if (r > 0.9999) {
                    x *= 0.9999 / r
                    y *= 0.9999 / r
                }
                val z = sqrt(max(0.0, 1.0 - x * x - y * y))
                val ndl = max(0.0, x * lx + y * ly + z * lz)
                tmpIdx[c] = j * size + i
                px[c] = x.toFloat()
                py[c] = y.toFloat()
                pz[c] = z.toFloat()
                pl[c] = min(1.18, 0.30 + 0.95 * ndl).toFloat()
                pr[c] = (0.85 * (1.0 - z).pow(2.6)).toFloat()
                pa[c] = a
                c++
            }
        }
        n = c
        idx = tmpIdx.copyOf(c)
        xs = px.copyOf(c)
        ys = py.copyOf(c)
        zs = pz.copyOf(c)
        lit = pl.copyOf(c)
        rim = pr.copyOf(c)
        alpha = pa.copyOf(c)
        vRow = FloatArray(c)
        dLamN = FloatArray(c)
    }

    private fun prepare(latRad: Double, texH: Int) {
        if (builtTexH == texH && abs(latRad - builtLat) < 0.003) return
        builtLat = latRad
        builtTexH = texH
        val sp0 = sin(latRad)
        val cp0 = cos(latRad)
        val inv2pi = (1.0 / (2.0 * PI)).toFloat()
        for (i in 0 until n) {
            val x = xs[i].toDouble()
            val y = ys[i].toDouble()
            val z = zs[i].toDouble()
            val sinPhi = (y * cp0 + z * sp0).coerceIn(-1.0, 1.0)
            val phi = asin(sinPhi)
            val dl = atan2(x, z * cp0 - y * sp0)
            var v = ((0.5 - phi / PI) * texH - 0.5).toFloat()
            if (v < 0f) v = 0f
            val vmax = texH - 1.0001f
            if (v > vmax) v = vmax
            vRow[i] = v
            dLamN[i] = dl.toFloat() * inv2pi
        }
    }

    private fun abs(v: Double): Double = if (v < 0) -v else v

    /** Fill [out] (size*size ARGB ints) with the sphere seen from (lonRad, latRad). */
    fun render(tex: IntArray, tw: Int, th: Int, lonRad: Double, latRad: Double, out: IntArray) {
        prepare(latRad, th)
        val base = ((lonRad + PI) / (2.0 * PI)).toFloat()
        val twf = tw.toFloat()
        for (i in 0 until n) {
            var t = base + dLamN[i]
            if (t < 0f) t += 1f
            if (t >= 1f) t -= 1f
            var fx = t * twf - 0.5f
            if (fx < 0f) fx += twf
            val x0 = fx.toInt()
            val ax = fx - x0
            val x1 = if (x0 + 1 >= tw) 0 else x0 + 1
            val fy = vRow[i]
            val y0 = fy.toInt()
            val ay = fy - y0
            val r0 = y0 * tw
            val r1 = r0 + tw
            val p00 = tex[r0 + x0]
            val p10 = tex[r0 + x1]
            val p01 = tex[r1 + x0]
            val p11 = tex[r1 + x1]
            val w00 = (1f - ax) * (1f - ay)
            val w10 = ax * (1f - ay)
            val w01 = (1f - ax) * ay
            val w11 = ax * ay
            val rr = ((p00 shr 16) and 255) * w00 + ((p10 shr 16) and 255) * w10 +
                ((p01 shr 16) and 255) * w01 + ((p11 shr 16) and 255) * w11
            val gg = ((p00 shr 8) and 255) * w00 + ((p10 shr 8) and 255) * w10 +
                ((p01 shr 8) and 255) * w01 + ((p11 shr 8) and 255) * w11
            val bb = (p00 and 255) * w00 + (p10 and 255) * w10 +
                (p01 and 255) * w01 + (p11 and 255) * w11
            val l = lit[i]
            val rm = rim[i]
            val keep = 1f - rm
            var r = rr * l * keep + 120f * rm
            var g = gg * l * keep + 185f * rm
            var b = bb * l * keep + 255f * rm
            if (r > 255f) r = 255f
            if (g > 255f) g = 255f
            if (b > 255f) b = 255f
            out[idx[i]] = (alpha[i] shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
    }
}
