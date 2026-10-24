package com.example.globelauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class AppsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pm = packageManager
        val density = resources.displayMetrics.density
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val grid = GridView(this)
        grid.numColumns = 4
        grid.setBackgroundColor(Color.rgb(3, 8, 16))
        grid.setPadding(0, (36 * density).toInt(), 0, (12 * density).toInt())
        grid.clipToPadding = false
        grid.verticalSpacing = (12 * density).toInt()

        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val info = apps[position]
                val cell = LinearLayout(this@AppsActivity)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL

                val icon = ImageView(this@AppsActivity)
                icon.setImageDrawable(info.loadIcon(pm))
                cell.addView(
                    icon,
                    LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                )

                val label = TextView(this@AppsActivity)
                label.text = info.loadLabel(pm)
                label.setTextColor(Color.WHITE)
                label.textSize = 11f
                label.maxLines = 1
                label.gravity = Gravity.CENTER
                label.setPadding(4, (4 * density).toInt(), 4, 0)
                cell.addView(label)
                return cell
            }
        }

        grid.setOnItemClickListener { _, _, position, _ ->
            val info = apps[position].activityInfo
            val launch = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(info.packageName, info.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(launch)
        }

        setContentView(grid)
    }
}
