package com.mahirex.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/** Shown when a widget is added (and when it is reconfigured): theme, accent colour, shown info, auto update. */
class WidgetConfigActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var kind = HomeWidgets.KIND_SPEED
    private var theme = 0
    private var accent = 0
    private var fields = 0
    private var auto = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        ui = Ui(this)
        window.decorView.layoutDirection = ui.dir
        kind = HomeWidgets.kindOf(this, widgetId)
        val c = HomeWidgets.cfg(this, widgetId, kind)
        theme = c.theme
        accent = c.accent
        fields = c.fields
        auto = c.auto
        setContentView(build())
    }

    private fun fieldLabels(): List<Int> = when (kind) {
        HomeWidgets.KIND_TEST -> listOf(R.string.sp_download, R.string.sp_upload, R.string.sp_ping)
        HomeWidgets.KIND_HEALTH -> listOf(R.string.sec_battery, R.string.sec_storage, R.string.wd_cfg_shortcuts)
        else -> listOf(R.string.sp_download, R.string.sp_upload, R.string.wd_cfg_status)
    }

    private fun build(): View {
        val root = FrameLayout(this)
        root.addView(AuroraView(this), FrameLayout.LayoutParams(ui.MATCH, ui.MATCH))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ui.dp(14), ui.dp(24), ui.dp(14), ui.dp(24))
        sv.addView(col)
        root.addView(sv, FrameLayout.LayoutParams(ui.MATCH, ui.MATCH))

        col.addView(ui.tv(getString(R.string.wd_cfg_title), 22f, ui.col(R.color.text_primary), true), ui.lp(ui.WRAP, ui.WRAP, 0, 12, 6))

        // theme
        val tc = ui.card(12, 24)
        tc.addView(ui.sectionTitle(getString(R.string.set_theme)), ui.lp(ui.WRAP, ui.WRAP, 2, 8, 6))
        val names = listOf(R.string.set_system, R.string.set_light, R.string.set_dark)
        val trow = LinearLayout(this)
        trow.orientation = LinearLayout.HORIZONTAL
        val chips = ArrayList<View>()
        for ((i, n) in names.withIndex()) {
            val chip = ui.centered(ui.tv(getString(n), 13.5f, ui.col(R.color.text_primary), true))
            chip.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
            chip.setOnClickListener {
                theme = i
                paint(chips, theme)
            }
            chips.add(chip)
            trow.addView(chip, ui.lp(0, ui.WRAP, 0, 0, if (i == 0) 0 else 6, 0).also { it.weight = 1f })
        }
        paint(chips, theme)
        tc.addView(trow)
        col.addView(tc, ui.lp(ui.MATCH, ui.WRAP, 8))

        // accent colour
        val ac = ui.card(12, 24)
        ac.addView(ui.sectionTitle(getString(R.string.wd_cfg_color)), ui.lp(ui.WRAP, ui.WRAP, 2, 8, 6))
        val arow = LinearLayout(this)
        arow.orientation = LinearLayout.HORIZONTAL
        arow.gravity = Gravity.CENTER
        val dots = ArrayList<View>()
        for (i in HomeWidgets.ACCENT_LIGHT.indices) {
            val d = View(this)
            d.setOnClickListener {
                accent = i
                paintDots(dots)
            }
            dots.add(d)
            arow.addView(d, ui.lp(0, ui.dp(40), 0, 0, 4, 4).also { it.weight = 1f })
        }
        paintDots(dots)
        ac.addView(arow)
        col.addView(ac, ui.lp(ui.MATCH, ui.WRAP, 12))

        // shown info
        val fc = ui.card(12, 24)
        fc.addView(ui.sectionTitle(getString(R.string.wd_cfg_show)), ui.lp(ui.WRAP, ui.WRAP, 2, 8, 6))
        val bits = intArrayOf(HomeWidgets.F1, HomeWidgets.F2, HomeWidgets.F3)
        for ((i, l) in fieldLabels().withIndex()) {
            fc.addView(toggleRow(getString(l), fields and bits[i] != 0) { on ->
                fields = if (on) fields or bits[i] else fields and bits[i].inv()
            })
        }
        col.addView(fc, ui.lp(ui.MATCH, ui.WRAP, 12))

        // auto update
        val uc = ui.card(12, 24)
        uc.addView(toggleRow(getString(R.string.wd_cfg_auto), auto) { auto = it })
        uc.addView(ui.tv(getString(R.string.wd_cfg_auto_note), 12f, ui.col(R.color.text_muted)), ui.lp(ui.MATCH, ui.WRAP, 2, 4, 10, 10))
        col.addView(uc, ui.lp(ui.MATCH, ui.WRAP, 12))

        col.addView(ui.pillButton(getString(R.string.wd_cfg_save), Ic.CHECK, true) { save() }, ui.lp(ui.MATCH, ui.dp(52), 18))
        return root
    }

    private fun paint(chips: List<View>, sel: Int) {
        for ((i, c) in chips.withIndex()) ui.feel(c, if (i == sel) ui.glass(14, true) else ui.glass(14), 14)
    }

    private fun paintDots(dots: List<View>) {
        for ((i, d) in dots.withIndex()) {
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(HomeWidgets.ACCENT_LIGHT[i])
            if (i == accent) g.setStroke(ui.dp(3), ui.col(R.color.text_primary))
            d.background = g
        }
    }

    private fun toggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        var on = initial
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12))
        ui.feel(row, null, 14)
        row.addView(ui.tv(label, 15f, ui.col(R.color.text_primary)), ui.lp(0, ui.WRAP, 0, 0, 0, 10).also { it.weight = 1f })
        val sw = androidx.appcompat.widget.SwitchCompat(this)
        sw.isChecked = on
        sw.setOnCheckedChangeListener { _, v ->
            on = v
            onChange(v)
        }
        row.addView(sw)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        return row
    }

    private fun save() {
        HomeWidgets.saveCfg(this, widgetId, HomeWidgets.Cfg(theme, accent, fields, auto))
        HomeWidgets.update(this, AppWidgetManager.getInstance(this), widgetId, kind)
        val r = Intent()
        r.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(Activity.RESULT_OK, r)
        finish()
    }
}
