package com.mahirex.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Advanced Widgets: in-app live cards (not home-screen widgets, so there is no background work).
 * One single sampler runs once per second ONLY while this screen is visible; every card reads
 * the same snapshot, so adding cards does not add monitoring engines.
 * All numbers come from Android APIs; a value the phone does not expose is shown as "not available".
 */
class WidgetsScreen(private val act: MainActivity) : Screen() {
    private val ui = act.ui
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var tickN = 0
    private val updaters = ArrayList<(Snap) -> Unit>()
    private lateinit var holder: LinearLayout

    // network delta state
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastT = 0L

    // storage changes slowly: re-read every few seconds only
    private var stoTotal = 0L
    private var stoFree = 0L

    private class Snap {
        var ramPct = -1f
        var ramUsed = 0L
        var ramFree = 0L
        var ramTotal = 0L
        var cpuPct = -1f
        var cpuAvgKhz = -1L
        val freqs = ArrayList<Long>()
        var batPct = -1f
        var batTemp = Float.NaN
        var batMv = -1
        var batMa = Int.MIN_VALUE
        var batStatus = -1
        var batHealth = -1
        var stoTotal = 0L
        var stoFree = 0L
        var rxBps = -1.0
        var txBps = -1.0
        var rxTot = -1L
        var txTot = -1L
        var conn = ""
        var online = false
    }

    private class Cfg(val id: String, var on: Boolean)

    companion object {
        val IDS = listOf("overview", "system", "battery", "cpu", "ram", "storage", "network", "live")
    }

    // ------------------------------------------------------------ saved layout
    private fun loadCfg(): MutableList<Cfg> {
        val out = ArrayList<Cfg>()
        try {
            val a = JSONArray(Prefs.getStr("wd_cfg", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id in IDS && out.none { it.id == id }) out.add(Cfg(id, o.optBoolean("on", true)))
            }
        } catch (e: Exception) {
            // ignore: fall back to defaults
        }
        for (id in IDS) if (out.none { it.id == id }) out.add(Cfg(id, true))
        return out
    }

    private fun saveCfg(list: List<Cfg>) {
        val a = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("on", c.on)
            a.put(o)
        }
        Prefs.putStr("wd_cfg", a.toString())
    }

    private fun titleOf(id: String): String = act.getString(
        when (id) {
            "overview" -> R.string.wd_overview
            "system" -> R.string.wd_system
            "battery" -> R.string.wd_battery
            "cpu" -> R.string.wd_cpu
            "ram" -> R.string.wd_ram
            "storage" -> R.string.wd_storage
            "network" -> R.string.wd_network
            else -> R.string.wd_live
        }
    )

    // ------------------------------------------------------------ screen
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val s = sample()
            for (u in updaters) {
                try {
                    u(s)
                } catch (e: Exception) {
                    // one broken card must never stop the others
                }
            }
            tickN++
            handler.postDelayed(this, 1000L)
        }
    }

    override fun build(): View {
        val sv = pageScroll(act)
        val col = pageColumn(act)
        sv.addView(col)
        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(ui.tv(act.getString(R.string.wd_hint), 12.5f, ui.col(R.color.text_muted)), ui.lp(0, ui.WRAP, 0, 0, 4, 8).also { it.weight = 1f })
        top.addView(ui.pillButton(act.getString(R.string.wd_customize), Ic.GEAR, false) { showCustomize() }, ui.lp(ui.WRAP, ui.dp(42)))
        col.addView(top, ui.lp(ui.MATCH, ui.WRAP, 4, 12))
        holder = LinearLayout(act)
        holder.orientation = LinearLayout.VERTICAL
        col.addView(holder, ui.lp(ui.MATCH, ui.WRAP))
        col.addView(brandFooter(act))
        buildCards()
        return sv
    }

    private fun buildCards() {
        holder.removeAllViews()
        updaters.clear()
        var any = false
        for (c in loadCfg()) {
            if (!c.on) continue
            any = true
            when (c.id) {
                "overview" -> buildOverview()
                "system" -> buildSystem()
                "battery" -> buildBattery()
                "cpu" -> buildCpu()
                "ram" -> buildRam()
                "storage" -> buildStorage()
                "network" -> buildNetwork()
                "live" -> buildLive()
            }
        }
        if (!any) {
            holder.addView(ui.centered(ui.tv(act.getString(R.string.wd_empty), 13.5f, ui.col(R.color.text_muted))), ui.lp(ui.MATCH, ui.WRAP, 30))
        }
    }

    override fun onResume() {
        if (running) return
        running = true
        handler.post(tick)
    }

    override fun onPause() {
        running = false
        handler.removeCallbacks(tick)
    }

    // ------------------------------------------------------------ sampling (single engine)
    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (e: Exception) {
        null
    }

    private fun sample(): Snap {
        val s = Snap()
        try {
            val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            if (mi.totalMem > 0) {
                s.ramTotal = mi.totalMem
                s.ramFree = mi.availMem
                s.ramUsed = mi.totalMem - mi.availMem
                s.ramPct = s.ramUsed * 100f / mi.totalMem
            }
        } catch (e: Exception) {
            // ignore
        }
        // CPU: Android hides /proc/stat from apps, so load is estimated from how far the cores are clocked up
        try {
            var sum = 0.0
            var n = 0
            var khz = 0L
            val cores = Runtime.getRuntime().availableProcessors()
            for (k in 0 until cores) {
                val base = "/sys/devices/system/cpu/cpu$k/cpufreq/"
                val cur = readLong(base + "scaling_cur_freq")
                val max = readLong(base + "cpuinfo_max_freq")
                if (cur != null) s.freqs.add(cur) else s.freqs.add(-1L)
                if (cur != null && max != null && max > 0) {
                    sum += cur * 100.0 / max
                    khz += cur
                    n++
                }
            }
            if (n > 0) {
                s.cpuPct = (sum / n).toFloat()
                s.cpuAvgKhz = khz / n
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            val i: Intent? = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) s.batPct = level * 100f / scale
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (t >= 0) s.batTemp = t / 10f
                s.batMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                s.batStatus = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                s.batHealth = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            }
            val bm = act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (cur != Int.MIN_VALUE && cur != 0) s.batMa = cur / 1000
        } catch (e: Exception) {
            // ignore
        }
        try {
            if (tickN % 5 == 0 || stoTotal == 0L) {
                val sf = StatFs(Environment.getDataDirectory().path)
                stoTotal = sf.totalBytes
                stoFree = sf.availableBytes
            }
            s.stoTotal = stoTotal
            s.stoFree = stoFree
        } catch (e: Exception) {
            // ignore
        }
        try {
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            val caps = if (n != null) cm.getNetworkCapabilities(n) else null
            if (caps != null) {
                s.online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                s.conn = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> act.getString(R.string.v_mobile_data)
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> ""
                }
            }
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val now = SystemClock.elapsedRealtime()
            if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
                s.rxTot = rx
                s.txTot = tx
                if (lastRx >= 0 && now > lastT) {
                    val dt = (now - lastT) / 1000.0
                    s.rxBps = Math.max(0.0, (rx - lastRx) / dt)
                    s.txBps = Math.max(0.0, (tx - lastTx) / dt)
                }
                lastRx = rx
                lastTx = tx
                lastT = now
            }
        } catch (e: Exception) {
            // ignore
        }
        return s
    }

    // ------------------------------------------------------------ small view helpers
    private fun na(): String = act.getString(R.string.wd_na)

    private fun pct(v: Float): String = String.format(Locale.US, "%.0f%%", v)

    private fun tone(p: Float): Int = if (p < 70f) ui.col(R.color.ok) else if (p < 85f) ui.col(R.color.warn) else ui.col(R.color.bad)

    private fun rate(bps: Double): String = if (bps < 0) na() else Fmt.size(bps.toLong()) + "/s"

    private fun card(titleRes: Int, icon: Int, color: Int): LinearLayout {
        val c = ui.card(14, 22)
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(ui.iconTile(icon, color, 30, 18))
        head.addView(ui.tv(act.getString(titleRes), 14.5f, ui.col(R.color.text_primary), true), ui.lp(0, ui.WRAP, 0, 0, 10, 0).also { it.weight = 1f })
        c.addView(head)
        holder.addView(c, ui.lp(ui.MATCH, ui.WRAP, 0, 12))
        return c
    }

    private fun row(parent: LinearLayout, label: String): TextView {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        val k = ui.tv(label, 12.5f, ui.col(R.color.text_muted))
        r.addView(k, ui.lp(0, ui.WRAP, 0, 0, 0, 8).also { it.weight = 1f })
        val v = ui.tv("—", 13f, ui.col(R.color.text_primary), true)
        v.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        r.addView(v, ui.lp(0, ui.WRAP).also { it.weight = 1.3f })
        parent.addView(r, ui.lp(ui.MATCH, ui.WRAP, 0, 4))
        return v
    }

    private fun gaugeRow(card: LinearLayout, a: Int, b: Int): Pair<GaugeView, LinearLayout> {
        val line = LinearLayout(act)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        val g = GaugeView(act)
        g.setColors(a, b)
        line.addView(g, LinearLayout.LayoutParams(ui.dp(112), ui.WRAP))
        val rows = LinearLayout(act)
        rows.orientation = LinearLayout.VERTICAL
        line.addView(rows, ui.lp(0, ui.WRAP, 0, 0, 12, 0).also { it.weight = 1f })
        card.addView(line, ui.lp(ui.MATCH, ui.WRAP, 10))
        return Pair(g, rows)
    }

    private fun spark(card: LinearLayout, color: Int, fixed: Boolean, hDp: Int = 64): SparkView {
        val sp = SparkView(act)
        sp.setColors(color, ui.col(R.color.accent2))
        if (fixed) {
            sp.fixedMin = 0f
            sp.fixedMax = 100f
        }
        card.addView(sp, ui.lp(ui.MATCH, ui.dp(hDp), 8))
        return sp
    }

    private fun upText(): String {
        var s = SystemClock.elapsedRealtime() / 1000
        val d = s / 86400
        s %= 86400
        val h = s / 3600
        s %= 3600
        val m = s / 60
        return (if (d > 0) "${d}d " else "") + "${h}h ${m}m"
    }

    // ------------------------------------------------------------ cards
    private fun buildOverview() {
        val c = card(R.string.wd_overview, Ic.PHONE, ui.col(R.color.accent3))
        val r = act.report
        val model = r?.str("model") ?: (Build.MANUFACTURER + " " + Build.MODEL)
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        c.addView(box, ui.lp(ui.MATCH, ui.WRAP, 10))
        row(box, act.getString(R.string.wd_model)).text = model
        row(box, act.getString(R.string.wd_android)).text = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        row(box, act.getString(R.string.wd_cpu_name)).text = r?.str("cpu") ?: Build.HARDWARE
        row(box, act.getString(R.string.wd_cores)).text = Runtime.getRuntime().availableProcessors().toString()
        val ram = row(box, act.getString(R.string.wd_ram))
        val sto = row(box, act.getString(R.string.wd_storage))
        val up = row(box, act.getString(R.string.wd_uptime))
        updaters.add { s ->
            ram.text = if (s.ramTotal > 0) Fmt.sizeShort(s.ramTotal) else na()
            sto.text = if (s.stoTotal > 0) Fmt.sizeShort(s.stoTotal) else na()
            up.text = upText()
        }
    }

    private fun buildSystem() {
        val c = card(R.string.wd_system, Ic.LAYERS, ui.col(R.color.accent2))
        val line = LinearLayout(act)
        line.orientation = LinearLayout.HORIZONTAL
        val gc = GaugeView(act)
        val gr = GaugeView(act)
        val gb = GaugeView(act)
        gb.setColors(ui.col(R.color.ok), ui.col(R.color.accent3))
        line.addView(gc, ui.weight(ui.WRAP))
        line.addView(gr, ui.weight(ui.WRAP))
        line.addView(gb, ui.weight(ui.WRAP))
        c.addView(line, ui.lp(ui.MATCH, ui.WRAP, 8))
        val cpuL = act.getString(R.string.wd_cpu_short)
        val ramL = act.getString(R.string.wd_ram_short)
        val batL = act.getString(R.string.wd_bat_short)
        updaters.add { s ->
            if (s.cpuPct >= 0) {
                gc.setColors(tone(s.cpuPct), ui.col(R.color.accent2))
                gc.setData(s.cpuPct, pct(s.cpuPct), cpuL)
            } else {
                gc.setData(0f, "—", cpuL)
            }
            if (s.ramPct >= 0) {
                gr.setColors(tone(s.ramPct), ui.col(R.color.accent2))
                gr.setData(s.ramPct, pct(s.ramPct), ramL)
            }
            if (s.batPct >= 0) gb.setData(s.batPct, pct(s.batPct), batL) else gb.setData(0f, "—", batL)
        }
    }

    private fun buildBattery() {
        val c = card(R.string.wd_battery, Ic.BATTERY, ui.col(R.color.ok))
        val gr = gaugeRow(c, ui.col(R.color.ok), ui.col(R.color.accent3))
        val st = row(gr.second, act.getString(R.string.wd_status))
        val tp = row(gr.second, act.getString(R.string.wd_temp))
        val vl = row(gr.second, act.getString(R.string.wd_volt))
        val cu = row(gr.second, act.getString(R.string.wd_current))
        val hl = row(gr.second, act.getString(R.string.wd_health))
        val sp = spark(c, ui.col(R.color.ok), true)
        updaters.add { s ->
            if (s.batPct >= 0) {
                gr.first.setData(s.batPct, pct(s.batPct), act.getString(R.string.wd_level))
                sp.push(s.batPct)
            }
            st.text = when (s.batStatus) {
                BatteryManager.BATTERY_STATUS_CHARGING -> act.getString(R.string.wd_bat_charging)
                BatteryManager.BATTERY_STATUS_DISCHARGING -> act.getString(R.string.wd_bat_discharging)
                BatteryManager.BATTERY_STATUS_FULL -> act.getString(R.string.wd_bat_full)
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> act.getString(R.string.wd_bat_notcharging)
                else -> na()
            }
            tp.text = if (!s.batTemp.isNaN()) String.format(Locale.US, "%.1f °C", s.batTemp) else na()
            vl.text = if (s.batMv > 0) String.format(Locale.US, "%.2f V", s.batMv / 1000.0) else na()
            cu.text = if (s.batMa != Int.MIN_VALUE) Math.abs(s.batMa).toString() + " mA" else na()
            // health is shown only when the phone reports a real value (not "unknown")
            val h = when (s.batHealth) {
                BatteryManager.BATTERY_HEALTH_GOOD -> R.string.wd_h_good
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.wd_h_overheat
                BatteryManager.BATTERY_HEALTH_DEAD -> R.string.wd_h_dead
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.wd_h_overvolt
                BatteryManager.BATTERY_HEALTH_COLD -> R.string.wd_h_cold
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.wd_h_failure
                else -> 0
            }
            hl.text = if (h != 0) act.getString(h) else na()
        }
    }

    private fun buildCpu() {
        val c = card(R.string.wd_cpu, Ic.CHIP, ui.col(R.color.accent))
        val gr = gaugeRow(c, ui.col(R.color.ok), ui.col(R.color.accent2))
        val load = row(gr.second, act.getString(R.string.wd_cpu_load))
        val avg = row(gr.second, act.getString(R.string.wd_cpu_freq))
        row(gr.second, act.getString(R.string.wd_cores)).text = Runtime.getRuntime().availableProcessors().toString()
        row(gr.second, act.getString(R.string.wd_cpu_name)).text = act.report?.str("cpu") ?: Build.HARDWARE
        val sp = spark(c, ui.col(R.color.accent), true)
        c.addView(ui.sectionTitle(act.getString(R.string.wd_cpu_cores)), ui.lp(ui.WRAP, ui.WRAP, 10, 4))
        val coreTxt = ui.tv("", 12.5f, ui.col(R.color.text_primary))
        c.addView(coreTxt)
        c.addView(ui.tv(act.getString(R.string.wd_cpu_note), 11.5f, ui.col(R.color.text_muted)), ui.lp(ui.MATCH, ui.WRAP, 8))
        val cl = act.getString(R.string.wd_cpu_short)
        updaters.add { s ->
            if (s.cpuPct >= 0) {
                gr.first.setColors(tone(s.cpuPct), ui.col(R.color.accent2))
                gr.first.setData(s.cpuPct, pct(s.cpuPct), cl)
                sp.push(s.cpuPct)
                load.text = pct(s.cpuPct)
                avg.text = (s.cpuAvgKhz / 1000).toString() + " MHz"
            } else {
                gr.first.setData(0f, "—", cl)
                load.text = na()
                avg.text = na()
            }
            val sb = StringBuilder()
            for ((i, f) in s.freqs.withIndex()) {
                if (i > 0) sb.append(if (i % 2 == 0) "\n" else "     ")
                sb.append(i).append(": ").append(if (f > 0) (f / 1000).toString() + " MHz" else "—")
            }
            coreTxt.text = if (s.cpuPct >= 0) sb.toString() else act.getString(R.string.wd_cpu_restricted)
        }
    }

    private fun buildRam() {
        val c = card(R.string.wd_ram, Ic.MEMORY, ui.col(R.color.accent3))
        val gr = gaugeRow(c, ui.col(R.color.ok), ui.col(R.color.accent3))
        val used = row(gr.second, act.getString(R.string.wd_used))
        val av = row(gr.second, act.getString(R.string.wd_avail))
        val tot = row(gr.second, act.getString(R.string.wd_total))
        val sp = spark(c, ui.col(R.color.accent3), true)
        updaters.add { s ->
            if (s.ramPct >= 0) {
                gr.first.setColors(tone(s.ramPct), ui.col(R.color.accent3))
                gr.first.setData(s.ramPct, pct(s.ramPct), "RAM")
                sp.push(s.ramPct)
                used.text = Fmt.size(s.ramUsed)
                av.text = Fmt.size(s.ramFree)
                tot.text = Fmt.size(s.ramTotal)
            }
        }
    }

    private fun buildStorage() {
        val c = card(R.string.wd_storage, Ic.STORAGE, 0xFFFFB547.toInt())
        val gr = gaugeRow(c, 0xFFFFB547.toInt(), 0xFFFF6BB5.toInt())
        val tot = row(gr.second, act.getString(R.string.wd_total))
        val used = row(gr.second, act.getString(R.string.wd_used))
        val free = row(gr.second, act.getString(R.string.wd_free))
        updaters.add { s ->
            if (s.stoTotal > 0) {
                val u = s.stoTotal - s.stoFree
                val p = u * 100f / s.stoTotal
                gr.first.setColors(if (p < 85f) 0xFFFFB547.toInt() else ui.col(R.color.bad), 0xFFFF6BB5.toInt())
                gr.first.setData(p, pct(p), act.getString(R.string.wd_storage))
                tot.text = Fmt.size(s.stoTotal)
                used.text = Fmt.size(u)
                free.text = Fmt.size(s.stoFree)
            }
        }
    }

    private fun buildNetwork() {
        val c = card(R.string.wd_network, Ic.WIFI, ui.col(R.color.accent))
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        c.addView(box, ui.lp(ui.MATCH, ui.WRAP, 10))
        val conn = row(box, act.getString(R.string.wd_conn))
        val net = row(box, act.getString(R.string.wd_internet))
        val rx = row(box, act.getString(R.string.wd_down_now))
        val tx = row(box, act.getString(R.string.wd_up_now))
        val rxT = row(box, act.getString(R.string.wd_rx_total))
        val txT = row(box, act.getString(R.string.wd_tx_total))
        val sp = spark(c, ui.col(R.color.accent), false)
        updaters.add { s ->
            conn.text = if (s.conn.isNotEmpty()) s.conn else act.getString(R.string.v_no_active)
            net.text = if (s.conn.isEmpty()) na() else if (s.online) act.getString(R.string.yes) else act.getString(R.string.no)
            rx.text = rate(s.rxBps)
            tx.text = rate(s.txBps)
            rxT.text = if (s.rxTot >= 0) Fmt.size(s.rxTot) else na()
            txT.text = if (s.txTot >= 0) Fmt.size(s.txTot) else na()
            if (s.rxBps >= 0) sp.push((s.rxBps / 1024.0).toFloat())
        }
    }

    private fun buildLive() {
        val c = card(R.string.wd_live, Ic.GAUGE, 0xFF2EE6E0.toInt())
        fun mini(label: String, color: Int, fixed: Boolean): Pair<SparkView, TextView> {
            val head = LinearLayout(act)
            head.orientation = LinearLayout.HORIZONTAL
            head.addView(ui.tv(label, 12.5f, ui.col(R.color.text_muted), true), ui.lp(0, ui.WRAP).also { it.weight = 1f })
            val v = ui.tv("--", 14f, ui.col(R.color.text_primary), true)
            head.addView(v)
            c.addView(head, ui.lp(ui.MATCH, ui.WRAP, 12))
            return Pair(spark(c, color, fixed, 52), v)
        }
        val a = mini(act.getString(R.string.live_cpu), 0xFF2EA6FF.toInt(), true)
        val b = mini(act.getString(R.string.live_ram), ui.col(R.color.accent3), true)
        val t = mini(act.getString(R.string.live_temp), 0xFFFF9F43.toInt(), false)
        updaters.add { s ->
            if (s.cpuPct >= 0) {
                a.first.push(s.cpuPct)
                a.second.text = pct(s.cpuPct)
            } else {
                a.second.text = na()
            }
            if (s.ramPct >= 0) {
                b.first.push(s.ramPct)
                b.second.text = pct(s.ramPct)
            }
            if (!s.batTemp.isNaN()) {
                t.first.push(s.batTemp)
                t.second.text = String.format(Locale.US, "%.1f °C", s.batTemp)
            } else {
                t.second.text = na()
            }
        }
    }

    // ------------------------------------------------------------ customize (show / hide / order)
    private fun showCustomize() {
        val cfg = loadCfg()
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(12))
        box.background = ui.solidGlass(26)
        box.layoutDirection = ui.dir
        box.addView(ui.tv(act.getString(R.string.wd_customize_title), 17f, ui.col(R.color.text_primary), true))
        val list = LinearLayout(act)
        list.orientation = LinearLayout.VERTICAL
        val sc = ScrollView(act)
        sc.addView(list)
        val maxH = Math.min(ui.dp(420), (act.resources.displayMetrics.heightPixels * 0.62f).toInt())
        box.addView(sc, ui.lp(ui.MATCH, maxH, 10))
        val dialog = AlertDialog.Builder(act).setView(box).create()
        dialog.setOnDismissListener { buildCards() }
        fun fill() {
            list.removeAllViews()
            for ((i, c) in cfg.withIndex()) {
                val r = LinearLayout(act)
                r.orientation = LinearLayout.HORIZONTAL
                r.gravity = Gravity.CENTER_VERTICAL
                val cb = CheckBox(act)
                cb.text = titleOf(c.id)
                cb.setTextColor(ui.col(R.color.text_primary))
                cb.textSize = 14.5f
                cb.isChecked = c.on
                cb.setOnCheckedChangeListener { _, on ->
                    c.on = on
                    saveCfg(cfg)
                }
                r.addView(cb, ui.lp(0, ui.WRAP).also { it.weight = 1f })
                r.addView(arrow("▲", i > 0) {
                    java.util.Collections.swap(cfg, i, i - 1)
                    saveCfg(cfg)
                    fill()
                }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
                r.addView(arrow("▼", i < cfg.size - 1) {
                    java.util.Collections.swap(cfg, i, i + 1)
                    saveCfg(cfg)
                    fill()
                }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
                list.addView(r, ui.lp(ui.MATCH, ui.WRAP, 2))
            }
        }
        fill()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun arrow(glyph: String, enabled: Boolean, onClick: () -> Unit): View {
        val t = ui.centered(ui.tv(glyph, 16f, if (enabled) ui.col(R.color.accent) else ui.alpha(ui.col(R.color.text_muted), 90), true))
        t.gravity = Gravity.CENTER
        if (enabled) {
            ui.feel(t, null, 12)
            t.setOnClickListener { onClick() }
        }
        return t
    }
}
