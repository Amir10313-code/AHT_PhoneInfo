package com.mahirex.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

// ====================================================================== big speedometer
class SpeedGaugeView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unitP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var shown = 0f
    private var scaleMax = 10.0
    private var numberText = "0"
    private var unitText = "Mbps"
    private var caption = ""
    private val steps = doubleArrayOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0)
    private var colA = ContextCompatColor.get(ctx, R.color.accent3)
    private var colB = ContextCompatColor.get(ctx, R.color.accent2)
    private var anim: ValueAnimator? = null

    init {
        track.style = Paint.Style.STROKE
        track.strokeCap = Paint.Cap.ROUND
        track.color = ContextCompatColor.alpha(ContextCompatColor.get(ctx, R.color.text_muted), 55)
        arcP.style = Paint.Style.STROKE
        arcP.strokeCap = Paint.Cap.ROUND
        num.color = ContextCompatColor.get(ctx, R.color.text_primary)
        num.textAlign = Paint.Align.CENTER
        num.typeface = Typeface.DEFAULT_BOLD
        unitP.color = ContextCompatColor.get(ctx, R.color.accent)
        unitP.textAlign = Paint.Align.CENTER
        unitP.typeface = Typeface.DEFAULT_BOLD
        capP.color = ContextCompatColor.get(ctx, R.color.text_muted)
        capP.textAlign = Paint.Align.CENTER
    }

    fun resetScale() {
        scaleMax = 10.0
        shown = 0f
        invalidate()
    }

    /** valueInUnit is the number in the unit the user chose (Mbps or MB/s) */
    fun setValue(valueInUnit: Double, number: String, unit: String, cap: String) {
        numberText = number
        unitText = unit
        caption = cap
        for (s in steps) {
            if (valueInUnit * 1.1 <= s) {
                if (s > scaleMax) scaleMax = s
                break
            }
        }
        val target = (valueInUnit / scaleMax).toFloat().coerceIn(0f, 1f)
        anim?.cancel()
        val a = ValueAnimator.ofFloat(shown, target)
        a.duration = 220
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sw = Math.min(w, h) * 0.075f
        val rad = Math.min(w, h) / 2f - sw * 1.3f
        val cx = w / 2f
        val cy = h / 2f
        track.strokeWidth = sw
        arcP.strokeWidth = sw
        arcP.shader = LinearGradient(cx - rad, cy, cx + rad, cy, colA, colB, Shader.TileMode.CLAMP)
        oval.set(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawArc(oval, 135f, 270f, false, track)
        if (shown > 0.003f) c.drawArc(oval, 135f, 270f * shown, false, arcP)
        num.textSize = rad * 0.62f
        val fm = num.fontMetrics
        c.drawText(numberText, cx, cy - (fm.ascent + fm.descent) / 2f - rad * 0.06f, num)
        unitP.textSize = rad * 0.2f
        c.drawText(unitText, cx, cy + rad * 0.42f, unitP)
        capP.textSize = rad * 0.15f
        c.drawText(caption, cx, cy + rad * 0.92f, capP)
    }
}

/** tiny helper so the view above does not need androidx imports in its header */
private object ContextCompatColor {
    fun get(ctx: Context, id: Int): Int = androidx.core.content.ContextCompat.getColor(ctx, id)
    fun alpha(color: Int, a: Int): Int = androidx.core.graphics.ColorUtils.setAlphaComponent(color, a)
}

// ====================================================================== screen
class SpeedScreen(private val act: MainActivity) : Screen() {
    private val ui = act.ui
    private val handler = Handler(Looper.getMainLooper())
    private val fa = act.getString(R.string.lang_code) == "fa"

    private var unit = Prefs.getInt("speed_unit", SpeedFmt.MBPS)
    private var chosenId = Prefs.getStr("speed_server", "auto")
    private var running = false
    private var refreshing = false
    private var cancel = AtomicBoolean(false)
    private var refreshCancel = AtomicBoolean(false)
    private var usedServer: SpeedServer? = null
    private var phaseNow = -1
    private var lastRes: SpeedResult? = SpeedStore.load().firstOrNull()
    private val selected = LinkedHashSet<Long>()

    private lateinit var gauge: SpeedGaugeView
    private lateinit var status: TextView
    private lateinit var btnHolder: FrameLayout
    private lateinit var spark: SparkView
    private lateinit var sparkCard: LinearLayout
    private lateinit var serverName: TextView
    private lateinit var serverSub: TextView
    private lateinit var tPing: TextView
    private lateinit var tJit: TextView
    private lateinit var tDown: TextView
    private lateinit var tUp: TextView
    private lateinit var tDownU: TextView
    private lateinit var tUpU: TextView
    private lateinit var unitRow: LinearLayout
    private lateinit var histBox: LinearLayout

    // ------------------------------------------------------------ build
    override fun build(): View {
        val sv = pageScroll(act)
        val col = pageColumn(act)
        sv.addView(col)

        // hero: speedometer + start / stop
        val hero = ui.card(14, 28)
        hero.gravity = Gravity.CENTER_HORIZONTAL
        gauge = SpeedGaugeView(act)
        hero.addView(gauge, LinearLayout.LayoutParams(ui.MATCH, ui.dp(230)))
        status = ui.centered(ui.tv(act.getString(R.string.sp_ready), 13.5f, ui.col(R.color.text_muted)))
        hero.addView(status, ui.lp(ui.MATCH, ui.WRAP, 0, 10))
        btnHolder = FrameLayout(act)
        hero.addView(btnHolder, ui.lp(ui.MATCH, ui.dp(50)))
        col.addView(hero, ui.lp(ui.MATCH, ui.WRAP, 8))

        // unit switch (never restarts the test)
        val unitCard = ui.card(12, 22)
        unitCard.addView(ui.sectionTitle(act.getString(R.string.sp_unit)), ui.lp(ui.WRAP, ui.WRAP, 0, 8, 4))
        unitRow = LinearLayout(act)
        unitRow.orientation = LinearLayout.HORIZONTAL
        unitCard.addView(unitRow, ui.lp(ui.MATCH, ui.WRAP))
        col.addView(unitCard, ui.lp(ui.MATCH, ui.WRAP, 12))

        // server
        val srvCard = ui.card(14, 22)
        srvCard.addView(ui.sectionTitle(act.getString(R.string.sp_server)), ui.lp(ui.WRAP, ui.WRAP, 0, 6, 2))
        serverName = ui.tv("", 15.5f, ui.col(R.color.text_primary), true)
        srvCard.addView(serverName)
        serverSub = ui.tv("", 12.5f, ui.col(R.color.text_muted))
        srvCard.addView(serverSub, ui.lp(ui.MATCH, ui.WRAP, 2))
        val srvBtns = LinearLayout(act)
        srvBtns.orientation = LinearLayout.HORIZONTAL
        srvBtns.addView(ui.pillButton(act.getString(R.string.sp_change), Ic.LIST, false) { showServers() }, ui.lp(0, ui.dp(44), 0, 0, 0, 8).also { it.weight = 1f })
        srvBtns.addView(ui.pillButton(act.getString(R.string.sp_refresh_servers), Ic.REFRESH, false) { refreshServers() }, ui.lp(0, ui.dp(44)).also { it.weight = 1f })
        srvCard.addView(srvBtns, ui.lp(ui.MATCH, ui.WRAP, 12))
        col.addView(srvCard, ui.lp(ui.MATCH, ui.WRAP, 12))

        // results
        val resCard = ui.card(12, 22)
        val r1 = LinearLayout(act)
        r1.orientation = LinearLayout.HORIZONTAL
        val r2 = LinearLayout(act)
        r2.orientation = LinearLayout.HORIZONTAL
        val ping = tile(r1, act.getString(R.string.sp_ping), "ms", ui.col(R.color.accent3))
        val jit = tile(r1, act.getString(R.string.sp_jitter), "ms", ui.col(R.color.accent2))
        val dn = tile(r2, act.getString(R.string.sp_download), SpeedFmt.unitLabel(unit), ui.col(R.color.ok))
        val up = tile(r2, act.getString(R.string.sp_upload), SpeedFmt.unitLabel(unit), ui.col(R.color.accent))
        tPing = ping.first
        tJit = jit.first
        tDown = dn.first
        tDownU = dn.second
        tUp = up.first
        tUpU = up.second
        resCard.addView(r1, ui.lp(ui.MATCH, ui.WRAP, 0, 8))
        resCard.addView(r2, ui.lp(ui.MATCH, ui.WRAP))
        col.addView(resCard, ui.lp(ui.MATCH, ui.WRAP, 12))

        // live curve
        sparkCard = ui.card(12, 22)
        spark = SparkView(act)
        spark.setColors(ui.col(R.color.accent3), ui.col(R.color.accent2))
        spark.fixedMin = 0f
        sparkCard.addView(spark, LinearLayout.LayoutParams(ui.MATCH, ui.dp(90)))
        sparkCard.visibility = View.GONE
        col.addView(sparkCard, ui.lp(ui.MATCH, ui.WRAP, 12))

        // history
        histBox = ui.card(12, 22)
        col.addView(histBox, ui.lp(ui.MATCH, ui.WRAP, 12))

        // privacy note
        col.addView(ui.centered(ui.tv(act.getString(R.string.sp_privacy_note), 12f, ui.col(R.color.text_muted))), ui.lp(ui.MATCH, ui.WRAP, 14, 0, 6, 6))
        col.addView(brandFooter(act))

        renderUnits()
        renderServer()
        renderResults()
        renderHistory()
        setButton()
        if (lastRes != null && !running) showFinalGauge(lastRes!!)
        return sv
    }

    private fun tile(parent: LinearLayout, label: String, unitText: String, color: Int): Pair<TextView, TextView> {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        box.background = ui.glass(16)
        box.addView(ui.tv(label, 12f, ui.col(R.color.text_muted), true))
        val v = ui.tv("—", 24f, ui.col(R.color.text_primary), true)
        box.addView(v, ui.lp(ui.WRAP, ui.WRAP, 2))
        val u = ui.tv(unitText, 12f, color, true)
        box.addView(u)
        val lp = ui.weight(ui.WRAP)
        lp.marginStart = ui.dp(4)
        lp.marginEnd = ui.dp(4)
        parent.addView(box, lp)
        return Pair(v, u)
    }

    // ------------------------------------------------------------ life cycle
    override fun onPause() {
        // leaving the screen (or the app) stops all network work immediately
        cancel.set(true)
        refreshCancel.set(true)
    }

    // ------------------------------------------------------------ rendering
    private fun renderUnits() {
        unitRow.removeAllViews()
        val labels = listOf(SpeedFmt.unitLabel(SpeedFmt.MBPS), SpeedFmt.unitLabel(SpeedFmt.MBYTES))
        for (i in 0..1) {
            val sel = unit == i
            val t = ui.centered(ui.tv(labels[i], 14.5f, if (sel) ui.col(R.color.accent) else ui.col(R.color.text_primary), true))
            t.setPadding(0, ui.dp(11), 0, ui.dp(11))
            ui.feel(t, ui.glass(16, sel), 16)
            t.setOnClickListener {
                if (unit != i) {
                    unit = i
                    Prefs.putInt("speed_unit", i)
                    renderUnits()
                    renderResults()
                    renderHistory()
                    val r = lastRes
                    if (!running && r != null) showFinalGauge(r)
                }
            }
            val lp = ui.weight(ui.WRAP)
            lp.marginStart = ui.dp(4)
            lp.marginEnd = ui.dp(4)
            unitRow.addView(t, lp)
        }
    }

    private fun chosenServer(): SpeedServer? =
        if (chosenId == "auto") null else SpeedEngine.servers.firstOrNull { it.id == chosenId }

    private fun renderServer() {
        val cs = chosenServer()
        val shown = usedServer
        if (cs == null) {
            serverName.text = act.getString(R.string.sp_auto)
            serverSub.text = if (shown != null) shown.name + (if (shown.pingMs >= 0) "  ·  " + SpeedFmt.ms(shown.pingMs) + " ms" else "") else act.getString(R.string.sp_auto_hint)
        } else {
            serverName.text = cs.name
            val parts = ArrayList<String>()
            if (cs.location.isNotEmpty()) parts.add(cs.location)
            if (cs.reachable) parts.add(act.getString(R.string.sp_ping) + " " + SpeedFmt.ms(cs.pingMs) + " ms")
            serverSub.text = parts.joinToString("  ·  ")
        }
    }

    private fun renderResults() {
        val r = lastRes
        tPing.text = if (r != null) SpeedFmt.ms(r.pingMs) else "—"
        tJit.text = if (r != null) SpeedFmt.ms1(r.jitterMs) else "—"
        tDown.text = if (r != null) SpeedFmt.text(r.downMbps, unit) else "—"
        tUp.text = if (r != null) SpeedFmt.text(r.upMbps, unit) else "—"
        tDownU.text = SpeedFmt.unitLabel(unit)
        tUpU.text = SpeedFmt.unitLabel(unit)
    }

    private fun showFinalGauge(r: SpeedResult) {
        gauge.resetScale()
        gauge.setValue(SpeedFmt.value(r.downMbps, unit), SpeedFmt.text(r.downMbps, unit), SpeedFmt.unitLabel(unit), act.getString(R.string.sp_download))
    }

    private fun setButton() {
        btnHolder.removeAllViews()
        val b = if (running) {
            ui.pillButton(act.getString(R.string.sp_stop), Ic.CLOSE, false) { stopTest() }
        } else {
            ui.pillButton(act.getString(R.string.sp_start), Ic.GAUGE, true) { startTest() }
        }
        btnHolder.addView(b, FrameLayout.LayoutParams(ui.MATCH, ui.MATCH))
    }

    private fun phaseText(p: Int): String = when (p) {
        SpeedEngine.PH_CHECK -> act.getString(R.string.sp_phase_check)
        SpeedEngine.PH_SERVER -> act.getString(R.string.sp_phase_server)
        SpeedEngine.PH_PING -> act.getString(R.string.sp_phase_ping)
        SpeedEngine.PH_DOWN -> act.getString(R.string.sp_phase_down)
        SpeedEngine.PH_UP -> act.getString(R.string.sp_phase_up)
        else -> act.getString(R.string.sp_done)
    }

    // ------------------------------------------------------------ test control
    private val listener = object : SpeedListener {
        override fun phase(p: Int) {
            handler.post {
                phaseNow = p
                status.text = phaseText(p)
                if (p == SpeedEngine.PH_DOWN || p == SpeedEngine.PH_UP) {
                    gauge.resetScale()
                    spark.clear()
                    sparkCard.visibility = View.VISIBLE
                }
            }
        }

        override fun server(s: SpeedServer) {
            handler.post {
                usedServer = s
                renderServer()
            }
        }

        override fun ping(ms: Double, jitter: Double) {
            handler.post {
                tPing.text = SpeedFmt.ms(ms)
                tJit.text = SpeedFmt.ms1(jitter)
            }
        }

        override fun live(phase: Int, mbps: Double, fraction: Float) {
            handler.post {
                val v = SpeedFmt.value(mbps, unit)
                val cap = if (phase == SpeedEngine.PH_UP) act.getString(R.string.sp_upload) else act.getString(R.string.sp_download)
                gauge.setValue(v, SpeedFmt.text(mbps, unit), SpeedFmt.unitLabel(unit), cap)
                spark.push(v.toFloat())
                if (phase == SpeedEngine.PH_UP) tUp.text = SpeedFmt.text(mbps, unit) else tDown.text = SpeedFmt.text(mbps, unit)
            }
        }
    }

    private fun startTest() {
        if (running || refreshing) return
        running = true
        cancel = AtomicBoolean(false)
        val c = cancel
        usedServer = null
        tPing.text = "—"
        tJit.text = "—"
        tDown.text = "—"
        tUp.text = "—"
        gauge.resetScale()
        gauge.setValue(0.0, "0", SpeedFmt.unitLabel(unit), "")
        status.text = phaseText(SpeedEngine.PH_CHECK)
        setButton()
        val ctx = act.applicationContext
        val chosen = chosenServer()
        val t = Thread {
            var res: SpeedResult? = null
            var errKind = 0
            var stopped = false
            try {
                res = SpeedEngine.run(ctx, chosen, c, listener)
            } catch (e: SpeedCancelled) {
                stopped = true
            } catch (e: SpeedError) {
                errKind = e.kind
            } catch (e: Exception) {
                errKind = SpeedError.DOWN
            }
            handler.post { finishTest(res, errKind, stopped) }
        }
        t.isDaemon = true
        t.start()
    }

    private fun stopTest() {
        cancel.set(true)
        status.text = act.getString(R.string.sp_stopping)
    }

    private fun finishTest(res: SpeedResult?, errKind: Int, stopped: Boolean) {
        running = false
        setButton()
        if (res != null) {
            SpeedStore.add(res)
            lastRes = res
            renderResults()
            renderHistory()
            showFinalGauge(res)
            status.text = if (res.complete) act.getString(R.string.sp_done) else act.getString(R.string.sp_partial)
            return
        }
        // failed or stopped: show what really happened, keep the previous saved results untouched
        renderResults()
        status.text = when {
            stopped -> act.getString(R.string.sp_cancelled)
            errKind == SpeedError.NO_NET -> act.getString(R.string.sp_err_nonet)
            errKind == SpeedError.NO_SERVER -> act.getString(R.string.sp_err_server)
            errKind == SpeedError.PING -> act.getString(R.string.sp_err_ping)
            else -> act.getString(R.string.sp_err_down)
        }
        val r = lastRes
        if (r != null) showFinalGauge(r) else gauge.setValue(0.0, "0", SpeedFmt.unitLabel(unit), "")
    }

    // ------------------------------------------------------------ servers
    private fun refreshServers() {
        if (running || refreshing) return
        if (!SpeedEngine.hasInternet(act)) {
            status.text = act.getString(R.string.sp_err_nonet)
            return
        }
        refreshing = true
        refreshCancel = AtomicBoolean(false)
        val rc = refreshCancel
        status.text = act.getString(R.string.sp_servers_updating)
        val t = Thread {
            var n = -1
            try {
                n = SpeedEngine.refreshServers(rc).size
            } catch (e: Exception) {
                n = -1
            }
            handler.post {
                refreshing = false
                if (n >= 0) {
                    if (chosenId != "auto" && SpeedEngine.servers.none { it.id == chosenId }) {
                        chosenId = "auto"
                        Prefs.putStr("speed_server", "auto")
                    }
                    status.text = act.getString(R.string.sp_servers_updated, n)
                } else {
                    status.text = act.getString(R.string.sp_servers_none)
                }
                renderServer()
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun showServers() {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(14))
        box.background = ui.solidGlass(26)
        box.layoutDirection = ui.dir
        box.addView(ui.tv(act.getString(R.string.sp_server), 18f, ui.col(R.color.text_primary), true))
        val dialog = AlertDialog.Builder(act).setView(box).create()
        val list = LinearLayout(act)
        list.orientation = LinearLayout.VERTICAL
        list.addView(serverRow("auto", act.getString(R.string.sp_auto), act.getString(R.string.sp_auto_hint), dialog))
        for (s in SpeedEngine.servers) {
            val sub = ArrayList<String>()
            if (s.location.isNotEmpty()) sub.add(s.location)
            if (s.reachable) sub.add(act.getString(R.string.sp_ping) + " " + SpeedFmt.ms(s.pingMs) + " ms")
            list.addView(serverRow(s.id, s.name, sub.joinToString("  ·  "), dialog))
        }
        val sc = ScrollView(act)
        sc.addView(list)
        val maxH = Math.min(ui.dp(380), (act.resources.displayMetrics.heightPixels * 0.6f).toInt())
        box.addView(sc, ui.lp(ui.MATCH, maxH, 10))
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun serverRow(id: String, name: String, sub: String, dialog: AlertDialog): View {
        val sel = chosenId == id
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        ui.feel(row, if (sel) ui.glass(14, true) else null, 14)
        val tb = LinearLayout(act)
        tb.orientation = LinearLayout.VERTICAL
        val n = ui.tv(name, 14.5f, if (sel) ui.col(R.color.accent) else ui.col(R.color.text_primary), true)
        n.maxLines = 2
        n.ellipsize = TextUtils.TruncateAt.END
        tb.addView(n)
        if (sub.isNotEmpty()) tb.addView(ui.tv(sub, 12f, ui.col(R.color.text_muted)))
        row.addView(tb, ui.lp(0, ui.WRAP).also { it.weight = 1f })
        if (sel) row.addView(ui.icon(Ic.CHECK, 18), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
        row.setOnClickListener {
            chosenId = id
            Prefs.putStr("speed_server", id)
            usedServer = null
            renderServer()
            dialog.dismiss()
        }
        val lp = ui.lp(ui.MATCH, ui.WRAP, 0, 4)
        row.layoutParams = lp
        return row
    }

    // ------------------------------------------------------------ history
    private fun renderHistory() {
        histBox.removeAllViews()
        histBox.addView(ui.sectionTitle(act.getString(R.string.sp_history)), ui.lp(ui.WRAP, ui.WRAP, 4, 8, 6))
        val list = SpeedStore.load()
        selected.retainAll(list.map { it.time }.toSet())
        if (list.isEmpty()) {
            histBox.addView(ui.tv(act.getString(R.string.sp_history_empty), 13f, ui.col(R.color.text_muted)), ui.lp(ui.MATCH, ui.WRAP, 4, 8, 6, 6))
            return
        }
        histBox.addView(ui.tv(act.getString(R.string.sp_compare_hint), 12f, ui.col(R.color.text_muted)), ui.lp(ui.MATCH, ui.WRAP, 0, 8, 6, 6))
        for (r in list) histBox.addView(historyRow(r), ui.lp(ui.MATCH, ui.WRAP, 0, 4))
        val b1 = LinearLayout(act)
        b1.orientation = LinearLayout.HORIZONTAL
        b1.addView(ui.pillButton(act.getString(R.string.sp_compare), Ic.LAYERS, false) { compareSelected() }, ui.lp(0, ui.dp(44), 0, 0, 0, 8).also { it.weight = 1f })
        b1.addView(ui.pillButton(act.getString(R.string.sp_share_img), Ic.SHARE, true) { shareSelectedOrLatest() }, ui.lp(0, ui.dp(44)).also { it.weight = 1f })
        histBox.addView(b1, ui.lp(ui.MATCH, ui.WRAP, 10))
        histBox.addView(ui.pillButton(act.getString(R.string.sp_clear), Ic.CLOSE, false) { confirmClear() }, ui.lp(ui.MATCH, ui.dp(44), 8))
    }

    private fun historyRow(r: SpeedResult): View {
        val sel = selected.contains(r.time)
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ui.dp(10), ui.dp(8), ui.dp(4), ui.dp(8))
        ui.feel(row, if (sel) ui.glass(14, true) else null, 14)
        val tb = LinearLayout(act)
        tb.orientation = LinearLayout.VERTICAL
        tb.addView(ui.tv(Fmt.date(r.time, fa), 13f, ui.col(R.color.text_primary), true))
        val u = SpeedFmt.unitLabel(unit)
        val line = "↓ " + SpeedFmt.text(r.downMbps, unit) + "   ↑ " + SpeedFmt.text(r.upMbps, unit) + " " + u + "   ·   " + SpeedFmt.ms(r.pingMs) + " ms"
        tb.addView(ui.tv(line, 12.5f, ui.col(R.color.text_primary)))
        val meta = ArrayList<String>()
        if (r.conn.isNotEmpty()) meta.add(r.conn)
        if (r.serverName.isNotEmpty()) meta.add(r.serverName)
        if (!r.complete) meta.add(act.getString(R.string.sp_incomplete))
        if (meta.isNotEmpty()) {
            val m = ui.tv(meta.joinToString("  ·  "), 11.5f, ui.col(R.color.text_muted))
            m.maxLines = 1
            m.ellipsize = TextUtils.TruncateAt.END
            tb.addView(m)
        }
        row.addView(tb, ui.lp(0, ui.WRAP).also { it.weight = 1f })
        val del = FrameLayout(act)
        ui.feel(del, null, 16)
        val bad = ui.col(R.color.bad)
        del.addView(ui.icon(Ic.CLOSE, 16, bad, bad), FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
        del.setOnClickListener {
            SpeedStore.remove(r.time)
            selected.remove(r.time)
            if (lastRes?.time == r.time) {
                lastRes = SpeedStore.load().firstOrNull()
                renderResults()
            }
            renderHistory()
        }
        row.addView(del, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        row.setOnClickListener {
            if (selected.contains(r.time)) {
                selected.remove(r.time)
            } else {
                if (selected.size >= 2) selected.remove(selected.first())
                selected.add(r.time)
            }
            renderHistory()
        }
        return row
    }

    private fun confirmClear() {
        AlertDialog.Builder(act)
            .setMessage(R.string.sp_clear_confirm)
            .setPositiveButton(R.string.sp_clear) { _, _ ->
                SpeedStore.clear()
                selected.clear()
                lastRes = null
                renderResults()
                renderHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun compareSelected() {
        val all = SpeedStore.load()
        val two = all.filter { selected.contains(it.time) }.sortedBy { it.time }
        if (two.size != 2) {
            status.text = act.getString(R.string.sp_compare_need_two)
            return
        }
        val a = two[0]
        val b = two[1]
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(14))
        box.background = ui.solidGlass(26)
        box.layoutDirection = ui.dir
        box.addView(ui.tv(act.getString(R.string.sp_compare), 18f, ui.col(R.color.text_primary), true))
        box.addView(cmpRow("", Fmt.date(a.time, fa).substringBefore("   ("), Fmt.date(b.time, fa).substringBefore("   ("), "", true, 0), ui.lp(ui.MATCH, ui.WRAP, 10, 4))
        val u = SpeedFmt.unitLabel(unit)
        box.addView(cmpMetric(act.getString(R.string.sp_ping) + " (ms)", a.pingMs, b.pingMs, false, false), ui.lp(ui.MATCH, ui.WRAP, 4))
        box.addView(cmpMetric(act.getString(R.string.sp_jitter) + " (ms)", a.jitterMs, b.jitterMs, false, false), ui.lp(ui.MATCH, ui.WRAP, 4))
        box.addView(cmpMetric(act.getString(R.string.sp_download) + " ($u)", a.downMbps, b.downMbps, true, true), ui.lp(ui.MATCH, ui.WRAP, 4))
        box.addView(cmpMetric(act.getString(R.string.sp_upload) + " ($u)", a.upMbps, b.upMbps, true, true), ui.lp(ui.MATCH, ui.WRAP, 4))
        val dialog = AlertDialog.Builder(act).setView(box).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun cmpMetric(label: String, a: Double, b: Double, higherBetter: Boolean, speed: Boolean): View {
        fun f(v: Double): String = if (v < 0) "—" else if (speed) SpeedFmt.text(v, unit) else SpeedFmt.ms1(v)
        var delta = ""
        var color = 0
        if (a >= 0 && b >= 0) {
            val dv = if (speed) SpeedFmt.value(b, unit) - SpeedFmt.value(a, unit) else b - a
            delta = (if (dv >= 0) "+" else "−") + String.format(java.util.Locale.US, "%.1f", Math.abs(dv))
            val good = if (higherBetter) dv > 0 else dv < 0
            color = if (Math.abs(dv) < 0.05) ui.col(R.color.text_muted) else if (good) ui.col(R.color.ok) else ui.col(R.color.bad)
        }
        return cmpRow(label, f(a), f(b), delta, false, color)
    }

    private fun cmpRow(label: String, a: String, b: String, delta: String, header: Boolean, deltaColor: Int): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.VERTICAL
        if (!header) {
            row.addView(ui.tv(label, 12f, ui.col(R.color.text_muted), true))
        }
        val line = LinearLayout(act)
        line.orientation = LinearLayout.HORIZONTAL
        val tc = ui.col(R.color.text_primary)
        val ta = ui.tv(a, if (header) 11.5f else 15f, if (header) ui.col(R.color.text_muted) else tc, !header)
        val tb = ui.tv(b, if (header) 11.5f else 15f, if (header) ui.col(R.color.text_muted) else tc, !header)
        val td = ui.tv(delta, 13f, if (deltaColor != 0) deltaColor else tc, true)
        line.addView(ta, ui.weight(ui.WRAP))
        line.addView(tb, ui.weight(ui.WRAP))
        line.addView(td, ui.weight(ui.WRAP, 0.7f))
        row.addView(line)
        return row
    }

    // ------------------------------------------------------------ share as image
    private fun shareSelectedOrLatest() {
        val all = SpeedStore.load()
        val r = all.firstOrNull { selected.contains(it.time) } ?: all.firstOrNull()
        if (r == null) {
            status.text = act.getString(R.string.sp_history_empty)
            return
        }
        shareImage(r)
    }

    private fun shareImage(r: SpeedResult) {
        try {
            val W = 1080
            val H = 1350
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0xFF050816.toInt(), 0xFF111B4A.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
            p.shader = null
            p.color = 0x330EA5E9
            c.drawCircle(W * 0.85f, 120f, 330f, p)
            p.color = 0x339333EA
            c.drawCircle(W * 0.1f, H * 0.95f, 380f, p)

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.textAlign = Paint.Align.CENTER
            tp.typeface = Typeface.DEFAULT_BOLD
            tp.letterSpacing = 0.18f
            tp.textSize = 66f
            tp.shader = LinearGradient(300f, 0f, 780f, 0f, 0xFF2FD6FF.toInt(), 0xFFA56BFF.toInt(), Shader.TileMode.CLAMP)
            c.drawText("MAHIREX PULSE", W / 2f, 140f, tp)
            tp.shader = null
            tp.letterSpacing = 0f
            tp.color = 0xFFAAB4E8.toInt()
            tp.textSize = 40f
            c.drawText(act.getString(R.string.menu_speedtest), W / 2f, 210f, tp)

            val card = Paint(Paint.ANTI_ALIAS_FLAG)
            card.color = 0x1AFFFFFF
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 3f
            stroke.color = 0x662FD6FF
            val r1 = RectF(60f, 270f, W - 60f, 800f)
            c.drawRoundRect(r1, 56f, 56f, card)
            c.drawRoundRect(r1, 56f, 56f, stroke)

            val u = SpeedFmt.unitLabel(unit)
            fun big(cx: Float, label: String, v: Double, color: Int) {
                tp.typeface = Typeface.DEFAULT_BOLD
                tp.color = 0xFFAAB4E8.toInt()
                tp.textSize = 42f
                c.drawText(label, cx, 380f, tp)
                tp.color = 0xFFFFFFFF.toInt()
                tp.textSize = 128f
                c.drawText(SpeedFmt.text(v, unit), cx, 560f, tp)
                tp.color = color
                tp.textSize = 46f
                c.drawText(u, cx, 640f, tp)
            }
            big(W * 0.29f, act.getString(R.string.sp_download), r.downMbps, 0xFF2EE6A0.toInt())
            big(W * 0.71f, act.getString(R.string.sp_upload), r.upMbps, 0xFF4DA3FF.toInt())

            val r2 = RectF(60f, 840f, W - 60f, 1090f)
            c.drawRoundRect(r2, 56f, 56f, card)
            c.drawRoundRect(r2, 56f, 56f, stroke)
            fun small(cx: Float, label: String, v: String) {
                tp.color = 0xFFAAB4E8.toInt()
                tp.textSize = 38f
                c.drawText(label, cx, 925f, tp)
                tp.color = 0xFFFFFFFF.toInt()
                tp.textSize = 84f
                c.drawText(v, cx, 1020f, tp)
                tp.color = 0xFF2FD6FF.toInt()
                tp.textSize = 36f
                c.drawText("ms", cx, 1070f, tp)
            }
            small(W * 0.29f, act.getString(R.string.sp_ping), SpeedFmt.ms(r.pingMs))
            small(W * 0.71f, act.getString(R.string.sp_jitter), SpeedFmt.ms1(r.jitterMs))

            tp.typeface = Typeface.DEFAULT
            tp.color = 0xFFAAB4E8.toInt()
            tp.textSize = 36f
            val srv = TextUtils.ellipsize(
                (if (r.conn.isNotEmpty()) r.conn + "  ·  " else "") + r.serverName, tp, 900f, TextUtils.TruncateAt.END
            ).toString()
            c.drawText(srv, W / 2f, 1160f, tp)
            c.drawText(Fmt.date(r.time, false), W / 2f, 1215f, tp)
            tp.color = 0xFF2FD6FF.toInt()
            tp.textSize = 34f
            tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            c.drawText(act.getString(R.string.tag_know), W / 2f, 1290f, tp)

            val dir = File(act.cacheDir, "share")
            dir.mkdirs()
            // keep the cache tidy: remove shared images older than a day
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
            val f = File(dir, "MAHIREX_speed_" + r.time + ".png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "image/png"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.clipData = android.content.ClipData.newRawUri("", uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(i, act.getString(R.string.share_chooser)))
        } catch (e: Exception) {
            act.toast(R.string.saved_fail)
        }
    }
}
