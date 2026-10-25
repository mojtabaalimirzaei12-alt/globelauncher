package com.example.globelauncher

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** The launcher screen: a thin view around GlobeRenderer (which does all the drawing). */
class GlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val renderer = GlobeRenderer(context, false)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.onSizeChanged(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas)
        postInvalidateDelayed(40)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = renderer.onTouchEvent(event)
        invalidate()
        return r
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            renderer.resetClock()
            invalidate()
        }
    }
}
