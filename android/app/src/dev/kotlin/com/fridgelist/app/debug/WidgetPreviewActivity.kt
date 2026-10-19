package com.fridgelist.app.debug

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fridgelist.app.widget.ListWidgetReceiver
import com.fridgelist.app.widget.Widgets
import kotlinx.coroutines.launch

/**
 * dev flavor only: hosts the list widget at 2×2, 4×2 and 4×4 like a launcher does, for screenshots
 * of every size (A8). Needs `adb shell appwidget grantbind --package <applicationId>` once.
 *
 *   adb shell am start -n <applicationId>/com.fridgelist.app.debug.WidgetPreviewActivity
 */
class WidgetPreviewActivity : ComponentActivity() { // not AppCompat: its inflater breaks RemoteViews, like no launcher does

    private lateinit var host: AppWidgetHost
    private val ids = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, HOST_ID)
        host.startListening()
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, ListWidgetReceiver::class.java)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        // Typical 2×2, 4×2 and 4×4 cells on a phone launcher.
        for (size in listOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp))) {
            val id = host.allocateAppWidgetId()
            ids += id
            if (!manager.bindAppWidgetIdIfAllowed(id, provider)) {
                column.addView(TextView(this).apply { text = "adb shell appwidget grantbind --package $packageName" })
                break
            }
            val view = host.createView(this, id, manager.getAppWidgetInfo(id))
            val w = size.width.value
            val h = size.height.value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(Bundle(), w.toInt(), h.toInt(), w.toInt(), h.toInt())
            }
            column.addView(view, LinearLayout.LayoutParams(dp(w), dp(h)).apply { bottomMargin = dp(16f) })
        }
        // A wallpaper-like backdrop, so the widget card reads as on a home screen.
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF7A8A86.toInt()); addView(column) })
        lifecycleScope.launch { Widgets.updateAll(this@WidgetPreviewActivity) }
    }

    override fun onDestroy() {
        ids.forEach(host::deleteAppWidgetId)
        host.stopListening()
        super.onDestroy()
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun dp(value: Int): Int = dp(value.toFloat())

    private companion object {
        const val HOST_ID = 4711
    }
}
