package com.example.globelauncher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/**
 * Draws text in an invented, ring-inscription inspired script (stems and bows, glowing gold).
 * It is a decorative imitation drawn in code, not a real Tengwar font.
 * Letters A-Z and spaces are supported.
 */
object ElvenText {

    private const val STEM_X = 38f
    private const val CONSONANT_ADV = 76f
    private const val VOWEL_ADV = 44f
    private const val SPACE_ADV = 40f

    private class Glyph(val adv: Float, val path: Path)

    // stem (F full, U upper, L lower), bow (side r/l + shape u/l/U/L/f), mark (n none, d dot, b bar)
    private val consonantSpecs = mapOf(
        'B' to "F rl n",
        'C' to "F ru n",
        'D' to "F rL n",
        'F' to "F rU n",
        'G' to "F lu n",
        'H' to "F ll n",
        'J' to "F rf n",
        'K' to "F lf n",
        'L' to "U ru n",
        'M' to "U rU n",
        'N' to "U lu n",
        'P' to "L rl n",
        'Q' to "L rL n",
        'R' to "L ll n",
        'S' to "F ru d",
        'T' to "F rl d",
        'V' to "F rf d",
        'W' to "F lf d",
        'X' to "F ru b",
        'Y' to "F rl b",
        'Z' to "F rU b"
    )

    private val glyphs = HashMap<Char, Glyph>()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 17f
        color = Color.argb(70, 255, 120, 20)
    }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 9.5f
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4.4f
        color = Color.rgb(255, 240, 190)
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.8f
        color = Color.argb(200, 255, 200, 90)
    }

    init {
        for ((ch, spec) in consonantSpecs) {
            glyphs[ch] = Glyph(CONSONANT_ADV, consonant(spec))
        }
        glyphs['A'] = Glyph(VOWEL_ADV, Path().apply { addCircle(22f, 50f, 11f, Path.Direction.CW) })
        glyphs['E'] = Glyph(VOWEL_ADV, Path().apply {
            moveTo(8f, 50f)
            lineTo(36f, 50f)
        })
        glyphs['I'] = Glyph(VOWEL_ADV, Path().apply { addCircle(22f, 50f, 2.5f, Path.Direction.CW) })
        glyphs['O'] = Glyph(VOWEL_ADV, Path().apply { addArc(RectF(8f, 36f, 36f, 64f), 30f, 300f) })
        glyphs['U'] = Glyph(VOWEL_ADV, Path().apply {
            addCircle(13f, 50f, 2.5f, Path.Direction.CW)
            addCircle(31f, 50f, 2.5f, Path.Direction.CW)
        })
    }

    private fun bow(p: Path, x: Float, dir: Float, y0: Float, y1: Float) {
        val w = min(34f, (y1 - y0) * 0.75f + 8f)
        p.moveTo(x, y0)
        p.cubicTo(x + dir * w * 1.3f, y0, x + dir * w * 1.3f, y1, x, y1)
    }

    private fun consonant(spec: String): Path {
        val parts = spec.split(" ")
        val stem = parts[0]
        val bows = parts[1]
        val mark = parts[2]
        val x = STEM_X
        val p = Path()

        when (stem) {
            "F" -> {
                p.moveTo(x, 0f)
                p.lineTo(x, 100f)
            }
            "U" -> {
                p.moveTo(x, 0f)
                p.lineTo(x, 50f)
            }
            "L" -> {
                p.moveTo(x, 50f)
                p.lineTo(x, 100f)
            }
        }

        val dir = if (bows[0] == 'l') -1f else 1f
        when (bows.substring(1)) {
            "u" -> bow(p, x, dir, 0f, 50f)
            "l" -> bow(p, x, dir, 50f, 100f)
            "U" -> {
                bow(p, x, dir, 0f, 25f)
                bow(p, x, dir, 25f, 50f)
            }
            "L" -> {
                bow(p, x, dir, 50f, 75f)
                bow(p, x, dir, 75f, 100f)
            }
            "f" -> bow(p, x, dir, 0f, 100f)
        }

        when (mark) {
            "d" -> p.addCircle(x, -10f, 2.5f, Path.Direction.CW)
            "b" -> {
                p.moveTo(x - 11f, 50f)
                p.lineTo(x + 11f, 50f)
            }
        }
        return p
    }

    /**
     * Draws [text] centered on [cx]. [top] is the top of the letter area, [heightPx] its height.
     * The text shrinks if it would be wider than [maxWidthPx].
     */
    fun draw(canvas: Canvas, text: String, cx: Float, top: Float, heightPx: Float, maxWidthPx: Float) {
        val full = Path()
        var pen = 0f
        for (ch in text.uppercase()) {
            if (ch == ' ') {
                pen += SPACE_ADV
                continue
            }
            val g = glyphs[ch] ?: continue
            full.addPath(g.path, pen, 0f)
            pen += g.adv
        }
        val total = max(pen, 1f)
        val scale = min(heightPx / 100f, maxWidthPx / (total + 40f))

        val shader = LinearGradient(
            0f, 0f, total, 0f,
            intArrayOf(
                Color.argb(190, 255, 215, 110),
                Color.argb(190, 255, 140, 30),
                Color.argb(190, 255, 215, 110)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        midPaint.shader = shader

        val rules = Path()
        rules.moveTo(-20f, -24f)
        rules.lineTo(total + 20f, -24f)
        rules.moveTo(-20f, 124f)
        rules.lineTo(total + 20f, 124f)

        canvas.save()
        canvas.translate(cx - total * scale / 2f, top)
        canvas.scale(scale, scale)
        canvas.drawPath(rules, rulePaint)
        canvas.drawPath(full, glowPaint)
        canvas.drawPath(full, midPaint)
        canvas.drawPath(full, corePaint)
        canvas.restore()
    }
}
