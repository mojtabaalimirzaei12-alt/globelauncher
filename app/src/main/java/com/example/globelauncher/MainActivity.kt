package com.example.globelauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.addView(
            GlobeView(this),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val density = resources.displayMetrics.density
        val button = Button(this).apply {
            text = "Apps"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(120, 20, 80, 140))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AppsActivity::class.java))
            }
        }
        val lp = FrameLayout.LayoutParams(
            (160 * density).toInt(),
            (48 * density).toInt(),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lp.bottomMargin = (28 * density).toInt()
        root.addView(button, lp)

        setContentView(root)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    // A launcher should stay on the home screen when Back is pressed.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }
}
