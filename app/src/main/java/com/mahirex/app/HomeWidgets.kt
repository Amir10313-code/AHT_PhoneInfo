package com.mahirex.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Bundle
import android.os.StatFs
import android.os.Environment
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real Android home-screen widgets (AppWidgetProvider + RemoteViews).
 * Battery friendly by design: no service, no WorkManager, no wake locks and no network access.
 *  - the system refreshes them at most every 30 minutes (and the user can switch that off per widget),
 *  - the only extra work is a ~1.5 s traffic sample when the user taps "Measure now".
 */
object HomeWidgets {
    const val KIND_SPEED = 0
    const val KIND_TEST = 1
    const val KIND_HEALTH = 2

    const val ACTION_SAMPLE = "com.mahirex.app.WIDGET_SAMPLE"
    const val ACTION_OPEN = "com.mahirex.app.OPEN"
    const val EXTRA_DEST = "dest"

    // field bits (meaning depends on the widget kind)
    const val F1 = 1
    const val F2 = 2
    const val F3 = 4

    private const val SIZE_SMALL = 0
    private const val SIZE_MEDIUM = 1
    private const val SIZE_LARGE = 2

    val ACCENT_LIGHT = intArrayOf(0xFF2563EB.toInt(), 0xFF0EA5E9.toInt(), 0xFF9333EA.toInt(), 0xFF0E9F6E.toInt(), 0xFFF59E0B.toInt(), 0xFFEC4899.toInt())
    val ACCENT_DARK = intArrayOf(0xFF60A5FA.toInt(), 0xFF38BDF8.toInt(), 0xFFC084FC.toInt(), 0xFF34D399.toInt(), 0xFFFBBF24.toInt(), 0xFFF472B6.toInt())
    private val PILLS = intArrayOf(
        R.drawable.widget_pill_0, R.drawable.widget_pill_1, R.drawable.widget_pill_2,
        R.drawable.widget_pill_3, R.drawable.widget_pill_4, R.drawable.widget_pill_5
    )

    class Cfg(val theme: Int, val accent: Int, val fields: Int, val auto: Boolean)

    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("mahirex_widgets", Context.MODE_PRIVATE)

    fun defaultFields(kind: Int): Int = F1 or F2 or F3

    fun cfg(ctx: Context, id: Int, kind: Int): Cfg {
        val p = sp(ctx)
        return Cfg(
            p.getInt("t_$id", 0),
            p.getInt("c_$id", 0).coerceIn(0, ACCENT_LIGHT.size - 1),
            p.getInt("f_$id", defaultFields(kind)),
            p.getBoolean("a_$id", true)
        )
    }

    fun saveCfg(ctx: Context, id: Int, c: Cfg) {
        sp(ctx).edit().putInt("t_$id", c.theme).putInt("c_$id", c.accent).putInt("f_$id", c.fields).putBoolean("a_$id", c.auto).apply()
    }

    fun forget(ctx: Context, ids: IntArray) {
        val e = sp(ctx).edit()
        for (id in ids) {
            e.remove("t_$id").remove("c_$id").remove("f_$id").remove("a_$id").remove("r_$id")
        }
        e.apply()
    }

    fun kindOf(ctx: Context, id: Int): Int {
        val cn = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)?.provider?.className ?: return KIND_SPEED
        return when {
            cn.endsWith("TestWidgetProvider") -> KIND_TEST
            cn.endsWith("HealthWidgetProvider") -> KIND_HEALTH
            else -> KIND_SPEED
        }
    }

    private fun providers(): List<Pair<Class<*>, Int>> = listOf(
        Pair(SpeedWidgetProvider::class.java, KIND_SPEED),
        Pair(TestWidgetProvider::class.java, KIND_TEST),
        Pair(HealthWidgetProvider::class.java, KIND_HEALTH)
    )

    /** refresh every placed widget (cheap: local data only) */
    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for ((cls, kind) in providers()) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            for (id in ids) update(ctx, mgr, id, kind)
        }
    }

    fun hasWidgets(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        for ((cls, _) in providers()) {
            if (mgr.getAppWidgetIds(ComponentName(ctx, cls)).isNotEmpty()) return true
        }
        return false
    }

    // ------------------------------------------------------------ live traffic sample (speed widget)
    fun saveLive(ctx: Context, down: Double, up: Double) {
        sp(ctx).edit().putFloat("live_d", down.toFloat()).putFloat("live_u", up.toFloat()).putLong("live_t", System.currentTimeMillis()).apply()
    }

    /** current device traffic in Mbps, measured over a short window. Blocking (~1.5 s). */
    fun sampleTraffic(ctx: Context) {
        val rx0 = TrafficStats.getTotalRxBytes()
        val tx0 = TrafficStats.getTotalTxBytes()
        val t0 = System.nanoTime()
        try {
            Thread.sleep(1500)
        } catch (e: InterruptedException) {
            return
        }
        val rx1 = TrafficStats.getTotalRxBytes()
        val tx1 = TrafficStats.getTotalTxBytes()
        val secs = (System.nanoTime() - t0) / 1e9
        if (rx0 == TrafficStats.UNSUPPORTED.toLong() || rx1 == TrafficStats.UNSUPPORTED.toLong() || secs <= 0.2) return
        saveLive(ctx, Math.max(0L, rx1 - rx0) * 8.0 / secs / 1e6, Math.max(0L, tx1 - tx0) * 8.0 / secs / 1e6)
    }

    // ------------------------------------------------------------ helpers
    private fun localized(ctx: Context): Context {
        val tag = Prefs.lang()
        if (tag.isEmpty()) return ctx
        val c = Configuration(ctx.resources.configuration)
        c.setLocale(Locale.forLanguageTag(tag))
        return ctx.createConfigurationContext(c)
    }

    private fun isDark(ctx: Context, theme: Int): Boolean {
        if (theme == 1) return false
        if (theme == 2) return true
        val m = Prefs.nightMode()
        if (m == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return true
        if (m == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return false
        return (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun sizeClass(mgr: AppWidgetManager, id: Int): Int {
        val o: Bundle = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        return when {
            h < 100 -> SIZE_SMALL
            w >= 240 -> SIZE_LARGE
            else -> SIZE_MEDIUM
        }
    }

    private fun openIntent(ctx: Context, dest: String, auto: Boolean, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        i.action = ACTION_OPEN
        i.putExtra(EXTRA_DEST, dest)
        i.putExtra("auto", auto)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun sampleIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, SpeedWidgetProvider::class.java)
        i.action = ACTION_SAMPLE
        return PendingIntent.getBroadcast(ctx, 7000 + id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun num(v: Double): String = when {
        v >= 100 -> String.format(Locale.US, "%.0f", v)
        v >= 10 -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.2f", v)
    }

    private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    private fun battery(ctx: Context): Triple<Int, Boolean, Float> {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) return Triple(-1, false, -1f)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return Triple(pct, charging, if (temp >= 0) temp / 10f else -1f)
    }

    private fun storagePct(): Int {
        return try {
            val s = StatFs(Environment.getDataDirectory().path)
            val total = s.totalBytes
            if (total <= 0) -1 else (((total - s.availableBytes) * 100) / total).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun ring(pct: Int, color: Int, track: Int, textColor: Int, label: String): Bitmap {
        val size = 150
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 14f
        p.strokeCap = Paint.Cap.ROUND
        val r = RectF(14f, 14f, size - 14f, size - 14f)
        p.color = track
        c.drawArc(r, 0f, 360f, false, p)
        if (pct > 0) {
            p.color = color
            c.drawArc(r, -90f, 360f * pct.coerceAtMost(100) / 100f, false, p)
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.color = textColor
        t.textAlign = Paint.Align.CENTER
        t.typeface = Typeface.DEFAULT_BOLD
        t.textSize = 38f
        val fm = t.fontMetrics
        c.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, t)
        return b
    }

    // ------------------------------------------------------------ rendering
    fun update(ctx0: Context, mgr: AppWidgetManager, id: Int, kind: Int) {
        val ctx = localized(ctx0.applicationContext)
        val cfg = cfg(ctx0, id, kind)
        val dark = isDark(ctx, cfg.theme)
        val accent = (if (dark) ACCENT_DARK else ACCENT_LIGHT)[cfg.accent]
        val bg = if (dark) 0xFF151B38.toInt() else 0xFFFFFFFF.toInt()
        val tx = if (dark) 0xFFF1F3FF.toInt() else 0xFF12183A.toInt()
        val mu = if (dark) 0xFFA9B2DA.toInt() else 0xFF5B6591.toInt()
        val size = sizeClass(mgr, id)

        val layout = when (kind) {
            KIND_TEST -> R.layout.widget_test
            KIND_HEALTH -> R.layout.widget_health
            else -> R.layout.widget_speed
        }
        val v = RemoteViews(ctx0.packageName, layout)
        v.setInt(R.id.w_bg, "setColorFilter", bg)
        v.setInt(R.id.w_bg, "setImageAlpha", if (dark) 240 else 245)

        fun txt(viewId: Int, text: String, color: Int, sp: Float? = null) {
            v.setTextViewText(viewId, text)
            v.setTextColor(viewId, color)
            if (sp != null) v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, sp)
        }

        fun show(viewId: Int, on: Boolean) = v.setViewVisibility(viewId, if (on) View.VISIBLE else View.GONE)

        fun pillBtn(viewId: Int, label: String, pi: PendingIntent) {
            v.setTextViewText(viewId, label)
            v.setTextColor(viewId, accent)
            v.setInt(viewId, "setBackgroundResource", PILLS[cfg.accent])
            v.setOnClickPendingIntent(viewId, pi)
        }

        val small = size == SIZE_SMALL
        when (kind) {
            KIND_SPEED -> {
                val live = sp(ctx0).let {
                    val t = it.getLong("live_t", 0L)
                    if (System.currentTimeMillis() - t < 10 * 60 * 1000L && t > 0) Triple(it.getFloat("live_d", 0f).toDouble(), it.getFloat("live_u", 0f).toDouble(), t) else null
                }
                val last = SpeedStore.last(ctx0)
                var d: Double? = null
                var u: Double? = null
                var sub = ctx.getString(R.string.wd_tap_measure)
                if (live != null) {
                    d = live.first
                    u = live.second
                    sub = ctx.getString(R.string.wd_live_at, hm(live.third))
                } else if (last != null) {
                    d = last.down
                    u = if (last.up >= 0) last.up else null
                    sub = ctx.getString(R.string.wd_last_at, hm(last.time))
                }
                val online = Net.isOnline(ctx0)
                val tr = when (Net.transport(ctx0)) {
                    Net.T_WIFI -> ctx.getString(R.string.sp_net_wifi)
                    Net.T_CELL -> ctx.getString(R.string.sp_net_cell)
                    Net.T_ETH -> ctx.getString(R.string.sp_net_eth)
                    Net.T_VPN -> ctx.getString(R.string.sp_net_vpn)
                    else -> ctx.getString(R.string.sp_net_other)
                }
                val status = if (online) tr + " · " + ctx.getString(R.string.wd_online) else ctx.getString(R.string.sp_net_none)
                txt(R.id.w_title, ctx.getString(R.string.wd_speed_title), accent)
                txt(R.id.w_status, status, if (online) mu else 0xFFD93F4F.toInt())
                val vs = if (small) 18f else 22f
                txt(R.id.w_down_lbl, if (small) "↓ Mbps" else "↓ " + ctx.getString(R.string.sp_download), mu)
                txt(R.id.w_down, if (d == null) "—" else num(d), tx, vs)
                txt(R.id.w_down_unit, "Mbps", mu)
                txt(R.id.w_up_lbl, if (small) "↑ Mbps" else "↑ " + ctx.getString(R.string.sp_upload), mu)
                txt(R.id.w_up, if (u == null) "—" else num(u), tx, vs)
                txt(R.id.w_up_unit, "Mbps", mu)
                txt(R.id.w_sub, sub, mu)
                show(R.id.w_head, !small)
                show(R.id.w_down_unit, !small)
                show(R.id.w_up_unit, !small)
                show(R.id.w_down_box, cfg.fields and F1 != 0)
                show(R.id.w_up_box, cfg.fields and F2 != 0)
                show(R.id.w_status, cfg.fields and F3 != 0)
                show(R.id.w_sub, !small)
                show(R.id.w_btns, !small)
                show(R.id.w_b2, size == SIZE_LARGE)
                pillBtn(R.id.w_b1, ctx.getString(R.string.wd_measure), sampleIntent(ctx0, id))
                pillBtn(R.id.w_b2, ctx.getString(R.string.sp_title), openIntent(ctx0, "speed", false, 100 + id))
                v.setOnClickPendingIntent(R.id.w_content, openIntent(ctx0, "speed", false, 200 + id))
            }
            KIND_TEST -> {
                val last = SpeedStore.last(ctx0)
                txt(R.id.w_title, ctx.getString(R.string.wd_test_title), accent)
                txt(R.id.w_when, if (last != null) hm(last.time) else "", mu)
                val vs = if (small) 18f else 22f
                txt(R.id.w_down_lbl, if (small) "↓ Mbps" else "↓ " + ctx.getString(R.string.sp_download), mu)
                txt(R.id.w_down, if (last == null) "—" else num(last.down), tx, vs)
                txt(R.id.w_down_unit, "Mbps", mu)
                txt(R.id.w_up_lbl, if (small) "↑ Mbps" else "↑ " + ctx.getString(R.string.sp_upload), mu)
                txt(R.id.w_up, if (last == null || last.up < 0) "—" else num(last.up), tx, vs)
                txt(R.id.w_up_unit, "Mbps", mu)
                txt(R.id.w_ping_lbl, ctx.getString(R.string.sp_ping), mu)
                txt(R.id.w_ping, if (last == null) "—" else String.format(Locale.US, "%.0f", last.ping), tx, vs)
                txt(R.id.w_ping_unit, "ms", mu)
                val showDown = cfg.fields and F1 != 0
                val showUp = cfg.fields and F2 != 0
                val showPing = cfg.fields and F3 != 0 && !(small && showDown && showUp)
                show(R.id.w_head, !small)
                show(R.id.w_down_box, showDown)
                show(R.id.w_up_box, showUp)
                show(R.id.w_ping_box, showPing)
                show(R.id.w_down_unit, !small)
                show(R.id.w_up_unit, !small)
                show(R.id.w_ping_unit, !small)
                show(R.id.w_btns, !small)
                pillBtn(R.id.w_b1, ctx.getString(R.string.wd_start_test), openIntent(ctx0, "speed", true, 300 + id))
                v.setOnClickPendingIntent(R.id.w_content, openIntent(ctx0, "speed", true, 400 + id))
            }
            else -> {
                val b = battery(ctx0)
                val sto = storagePct()
                val trackC = if (dark) 0x33FFFFFF else 0x22000000
                val batColor = if (b.first in 0..15 && !b.second) 0xFFEF4444.toInt() else accent
                val stoColor = if (sto >= 90) 0xFFEF4444.toInt() else accent
                val batTxt = if (b.first >= 0) b.first.toString() + "%" else "—"
                val stoTxt = if (sto >= 0) "$sto%" else "—"
                txt(R.id.w_title, ctx.getString(R.string.wd_health_title), accent)
                txt(R.id.w_when, "", mu)
                txt(R.id.w_line, ctx.getString(R.string.wd_health_line, batTxt, stoTxt), tx)
                v.setImageViewBitmap(R.id.w_ring1, ring(b.first.coerceAtLeast(0), batColor, trackC, tx, batTxt))
                v.setImageViewBitmap(R.id.w_ring2, ring(sto.coerceAtLeast(0), stoColor, trackC, tx, stoTxt))
                txt(R.id.w_ring1_lbl, ctx.getString(R.string.sec_battery) + if (b.second) " ⚡" else "", mu)
                txt(R.id.w_ring2_lbl, ctx.getString(R.string.sec_storage), mu)
                show(R.id.w_head, size == SIZE_LARGE)
                show(R.id.w_line, small)
                show(R.id.w_rings, !small)
                show(R.id.w_r1_box, cfg.fields and F1 != 0)
                show(R.id.w_r2_box, cfg.fields and F2 != 0)
                show(R.id.w_btns, size == SIZE_LARGE && cfg.fields and F3 != 0)
                pillBtn(R.id.w_b1, ctx.getString(R.string.tool_cleaner), openIntent(ctx0, "cleaner", false, 500 + id))
                pillBtn(R.id.w_b2, ctx.getString(R.string.tool_storage), openIntent(ctx0, "storage", false, 600 + id))
                pillBtn(R.id.w_b3, ctx.getString(R.string.tool_battery), openIntent(ctx0, "battery", false, 700 + id))
                v.setOnClickPendingIntent(R.id.w_content, openIntent(ctx0, "health", false, 800 + id))
            }
        }
        sp(ctx0).edit().putBoolean("r_$id", true).apply()
        mgr.updateAppWidget(id, v)
    }

    fun wasRendered(ctx: Context, id: Int): Boolean = sp(ctx).getBoolean("r_$id", false)
}

// ====================================================================== providers

abstract class BaseWidgetProvider(private val kind: Int) : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            // "auto update off": keep what is shown, only the first draw and explicit user actions refresh it
            val c = HomeWidgets.cfg(context, id, kind)
            if (!c.auto && HomeWidgets.wasRendered(context, id)) continue
            HomeWidgets.update(context, mgr, id, kind)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        HomeWidgets.update(context, mgr, id, kind)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        HomeWidgets.forget(context, ids)
    }
}

/** live traffic + last speed (also receives the "Measure now" tap) */
class SpeedWidgetProvider : BaseWidgetProvider(HomeWidgets.KIND_SPEED) {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HomeWidgets.ACTION_SAMPLE) {
            val pr: BroadcastReceiver.PendingResult = goAsync()
            val app = context.applicationContext
            Thread {
                try {
                    HomeWidgets.sampleTraffic(app)
                    HomeWidgets.updateAll(app)
                } catch (e: Throwable) {
                    // never crash a widget
                } finally {
                    pr.finish()
                }
            }.start()
            return
        }
        super.onReceive(context, intent)
    }
}

class TestWidgetProvider : BaseWidgetProvider(HomeWidgets.KIND_TEST)

class HealthWidgetProvider : BaseWidgetProvider(HomeWidgets.KIND_HEALTH)
