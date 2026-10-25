package com.example.globelauncher

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

/** The live wallpaper: the same globe, usable on the home screen and the lock screen. */
class GlobeWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GlobeEngine()

    inner class GlobeEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var renderer: GlobeRenderer? = null
        private var visible = false
        private var w = 0
        private var h = 0

        private val frame = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, 40)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer = GlobeRenderer(this@GlobeWallpaperService, true)
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width
            h = height
            renderer?.onSizeChanged(width, height)
            drawFrame()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(frame)
            if (v) {
                renderer?.resetClock()
                handler.post(frame)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(frame)
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            renderer?.onTouchEvent(event)
        }

        private fun drawFrame() {
            val r = renderer ?: return
            if (w == 0 || h == 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) r.draw(canvas)
            } catch (e: Exception) {
                // ignore a frame that could not be drawn
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }
}
