package com.ahtteam.phoneinfo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Cache {
    @Volatile
    var sections: List<InfoSection>? = null
}

class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var chipRow: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private val chipViews = ArrayList<TextView>()
    private var activeIdx = -1
    private var pendingStartScroll = false
    private var sections: List<InfoSection> = emptyList()
    private val cards = ArrayList<View>()
    private var busy = false

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) writeTo(uri)
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        val cached = Cache.sections
        if (cached != null) {
            sections = cached
            render()
        } else {
            load()
        }
    }

    // ------------------------------------------------------------ helpers
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun col(id: Int): Int = ContextCompat.getColor(this, id)

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun text(t: String, sp: Float, color: Int, bold: Boolean): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = sp
        tv.setTextColor(col(color))
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        return tv
    }

    private fun rounded(fill: Int, radiusDp: Int, strokeColor: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radiusDp).toFloat()
        g.setStroke(dp(1), strokeColor)
        return g
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): MaterialButton {
        val b = if (primary) MaterialButton(this)
        else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = label
        b.textSize = 14f
        b.maxLines = 1
        b.gravity = Gravity.CENTER
        b.isAllCaps = false
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.insetTop = 0
        b.insetBottom = 0
        b.setPadding(dp(8), 0, dp(8), 0)
        b.cornerRadius = dp(12)
        if (!primary) {
            b.strokeColor = ColorStateList.valueOf(col(R.color.border))
            b.setTextColor(col(R.color.text_primary))
        }
        b.setOnClickListener { onClick() }
        return b
    }

    // ------------------------------------------------------------ status pills
    private val okWords = setOf("بله", "روشن", "فعال", "آماده", "خوب", "پر شده", "در حال شارژ")
    private val badWords = setOf(
        "خیر", "خاموش", "غیرفعال", "خراب", "سیم\u200Cکارت قرار نگرفته", "غیرفعال دائمی", "قفل شبکه",
        "ولتاژ بیش از حد", "داغ شدن بیش از حد", "خطای کارت"
    )
    private val warnWords = setOf(
        "نامشخص", "در حال مصرف", "شارژ نمی\u200Cشود", "آماده نیست", "سرد", "محدودشده",
        "نیازمند PIN", "نیازمند PUK", "خطای نامشخص"
    )

    private fun pillColor(v: String): Int? = when {
        okWords.contains(v) -> Color.parseColor("#1FA463")
        badWords.contains(v) -> Color.parseColor("#D64545")
        warnWords.contains(v) -> Color.parseColor("#C77D0A")
        else -> null
    }

    private fun pill(label: String, color: Int, sp: Float): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = sp
        tv.setTextColor(color)
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(14), dp(4), dp(14), dp(4))
        val g = GradientDrawable()
        g.setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
        g.cornerRadius = dp(30).toFloat()
        g.setStroke(dp(1), color)
        tv.background = g
        return tv
    }

    // ------------------------------------------------------------ UI
    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(col(R.color.bg))

        // header
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(16), dp(12), dp(16), dp(8))

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.addView(text("اطلاعات گوشی", 22f, R.color.text_primary, true))
        titleBox.addView(text("AHT TEAM", 12f, R.color.accent, true))
        titleRow.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val themeBtn = button(if (isNight()) "☀️ حالت روز" else "🌙 حالت شب", false) { toggleTheme() }
        titleRow.addView(themeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        head.addView(titleRow)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val lpBtn = { endMargin: Int ->
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.marginEnd = endMargin
            lp
        }
        actions.addView(button("💾 ذخیره TXT", true) { saveText() }, lpBtn(dp(8)))
        actions.addView(button("🔄 بروزرسانی", false) { load() }, lpBtn(0))
        val actLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actLp.topMargin = dp(10)
        head.addView(actions, actLp)
        root.addView(head)

        // pinned section bar (one swipeable row, always visible)
        chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.overScrollMode = View.OVER_SCROLL_NEVER
        // plain LTR container; chips are added in reverse so the first section sits at the right edge
        chipScroll.layoutDirection = View.LAYOUT_DIRECTION_LTR
        chipRow = LinearLayout(this)
        chipRow.layoutDirection = View.LAYOUT_DIRECTION_LTR
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.setPadding(dp(12), dp(4), dp(12), dp(10))
        chipRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (pendingStartScroll && chipRow.width > 0) {
                pendingStartScroll = false
                chipScroll.scrollTo(chipRow.width, 0)
            }
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = View(this)
        bar.setBackgroundColor(col(R.color.border))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // content
        scroll = ScrollView(this)
        scroll.isFillViewport = true
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, dp(6), 0, dp(24))
        scroll.addView(content)
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> updateActive(y) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        content.removeAllViews()
        chipRow.removeAllViews()
        chipViews.clear()
        activeIdx = -1
        cards.clear()
        for (sec in sections) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(16), dp(14), dp(16), dp(10))
            card.background = rounded(col(R.color.card), 16, col(R.color.border))

            val title = text(sec.title, 17f, R.color.accent, true)
            title.setPadding(0, 0, 0, dp(6))
            card.addView(title)

            var indent = 0
            for (item in sec.items) {
                if (item.header) {
                    if (item.level >= 2) {
                        indent = dp(12)
                        val h = text("\u25C2 " + item.key, 13f, R.color.text_primary, true)
                        h.setPaddingRelative(dp(12), dp(12), 0, dp(2))
                        card.addView(h)
                    } else {
                        indent = 0
                        val h = text(item.key, 14.5f, R.color.accent, true)
                        h.setPadding(0, dp(14), 0, dp(4))
                        card.addView(h)
                    }
                } else {
                    val row = LinearLayout(this)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.setPaddingRelative(indent, dp(8), 0, dp(8))
                    val k = text(item.key, 13f, R.color.text_muted, false)
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f))
                    val vlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 6f)
                    vlp.marginStart = dp(8)
                    val pc = pillColor(item.value)
                    if (pc != null) {
                        val box = LinearLayout(this)
                        box.orientation = LinearLayout.HORIZONTAL
                        box.addView(pill(item.value, pc, 13f))
                        row.addView(box, vlp)
                    } else {
                        val v = text(item.value, 14f, R.color.text_primary, false)
                        v.setTextIsSelectable(true)
                        row.addView(v, vlp)
                    }
                    card.addView(row)
                    val line = View(this)
                    line.setBackgroundColor(col(R.color.border))
                    card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(14), dp(6), dp(14), dp(6))
            content.addView(card, lp)
            cards.add(card)

            // chip
            val idxNow = cards.size - 1
            val chip = text(sec.title, 13f, R.color.text_primary, false)
            chip.gravity = Gravity.CENTER
            chip.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
            chip.maxLines = 1
            chip.setPadding(dp(16), dp(8), dp(16), dp(8))
            chip.background = rounded(col(R.color.card), 20, col(R.color.border))
            chip.setOnClickListener {
                setActive(idxNow, true)
                scroll.post { scroll.smoothScrollTo(0, card.top) }
            }
            chipViews.add(chip)
        }
        for (k in chipViews.indices.reversed()) {
            val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            clp.marginStart = dp(8)
            chipRow.addView(chipViews[k], clp)
        }
        pendingStartScroll = true
        chipRow.requestLayout()
        scroll.scrollTo(0, 0)
        setActive(0, false)
        content.addView(buildFooter())
    }

    private fun setActive(i: Int, follow: Boolean) {
        if (i == activeIdx || i !in chipViews.indices) return
        activeIdx = i
        for ((k, c) in chipViews.withIndex()) {
            val on = k == i
            val accent = col(R.color.accent)
            if (on) {
                val g = GradientDrawable()
                g.setColor(Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)))
                g.cornerRadius = dp(20).toFloat()
                g.setStroke(dp(2), accent)
                c.background = g
                c.setTextColor(accent)
                c.setTypeface(Typeface.DEFAULT_BOLD)
            } else {
                c.background = rounded(col(R.color.card), 20, col(R.color.border))
                c.setTextColor(col(R.color.text_primary))
                c.setTypeface(Typeface.DEFAULT)
            }
        }
        val chip = chipViews[i]
        chip.post {
            chip.requestRectangleOnScreen(Rect(-dp(40), 0, chip.width + dp(40), chip.height), false)
        }
    }

    private fun updateActive(y: Int) {
        if (cards.isEmpty()) return
        var idx = 0
        for (k in cards.indices) {
            if (cards[k].top <= y + dp(60)) idx = k
        }
        if (!scroll.canScrollVertically(1)) idx = cards.size - 1
        setActive(idx, true)
    }

    private fun buildLoader(): View {
        val accent = col(R.color.accent)
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.gravity = Gravity.CENTER

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(32), dp(28), dp(32), dp(24))
        card.background = rounded(col(R.color.card), 22, col(R.color.border))

        val ring = ProgressBar(this)
        ring.isIndeterminate = true
        ring.indeterminateTintList = ColorStateList.valueOf(accent)
        card.addView(ring, LinearLayout.LayoutParams(dp(54), dp(54)))

        val title = text("در حال دریافت اطلاعات گوشی", 17f, R.color.text_primary, true)
        title.gravity = Gravity.CENTER
        title.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = dp(18)
        card.addView(title, tlp)

        val sub = text("لطفاً چند لحظه صبر کنید", 13f, R.color.text_muted, false)
        sub.gravity = Gravity.CENTER
        sub.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(6)
        card.addView(sub, slp)

        val tag = pill("AHT TEAM", accent, 11.5f)
        val glp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        glp.topMargin = dp(18)
        card.addView(tag, glp)

        holder.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holder.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        return holder
    }

    private fun buildFooter(): View {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1"
        } catch (e: Throwable) {
            "1.1"
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(16), dp(22), dp(16), dp(28))

        val line = View(this)
        line.setBackgroundColor(col(R.color.border))
        val llp = LinearLayout.LayoutParams(dp(64), dp(2))
        llp.bottomMargin = dp(16)
        box.addView(line, llp)

        val made = text("طراحی و ساخت", 12f, R.color.text_muted, false)
        made.gravity = Gravity.CENTER
        made.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        box.addView(made, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val name = text("AHT TEAM", 22f, R.color.accent, true)
        name.gravity = Gravity.CENTER
        name.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        name.letterSpacing = 0.18f
        box.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val pills = LinearLayout(this)
        pills.orientation = LinearLayout.HORIZONTAL
        pills.gravity = Gravity.CENTER
        val accent = col(R.color.accent)
        val p1 = pill("نسخه " + ver, accent, 12.5f)
        val p2 = pill("اندروید " + android.os.Build.VERSION.RELEASE, Color.parseColor("#1FA463"), 12.5f)
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        plp.marginStart = dp(4)
        plp.marginEnd = dp(4)
        pills.addView(p1, plp)
        pills.addView(p2, LinearLayout.LayoutParams(plp))
        val pllp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pllp.topMargin = dp(12)
        box.addView(pills, pllp)

        box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return box
    }

    // ------------------------------------------------------------ actions
    private fun toggleTheme() {
        val mode = if (isNight()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        getSharedPreferences("aht", MODE_PRIVATE).edit().putInt("night", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)   // recreates the activity
    }

    private fun load() {
        if (busy) return
        busy = true
        content.removeAllViews()
        chipRow.removeAllViews()
        chipViews.clear()
        activeIdx = -1
        content.addView(buildLoader())
        val appCtx = applicationContext
        Thread {
            val result: List<InfoSection> = try {
                Collector.collect(appCtx)
            } catch (e: Throwable) {
                emptyList()
            }
            Cache.sections = result
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                sections = result
                render()
            }
        }.start()
    }

    private fun buildText(): String {
        val line = "=".repeat(60)
        val thin = "-".repeat(60)
        val sb = StringBuilder()
        sb.append(line).append("\n")
        sb.append("گزارش اطلاعات گوشی").append("\n")
        sb.append("سازنده برنامه: AHT TEAM").append("\n")
        sb.append("تاریخ گزارش: ").append(Collector.dateStr(System.currentTimeMillis())).append("\n")
        sb.append(line).append("\n")
        for (sec in sections) {
            sb.append("\n[ ").append(sec.title).append(" ]\n")
            sb.append(thin).append("\n")
            var firstSub = true
            for (item in sec.items) {
                if (item.header) {
                    if (!firstSub) sb.append("\n")
                    firstSub = false
                    sb.append(if (item.level >= 2) "   > " else ">> ").append(item.key).append("\n")
                } else {
                    sb.append(item.key).append(": ").append(item.value).append("\n")
                }
            }
        }
        sb.append("\n").append(line).append("\n")
        sb.append("پایان گزارش - AHT TEAM").append("\n")
        return sb.toString()
    }

    private fun fileName(): String =
        "PhoneInfo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"

    private fun saveText() {
        if (sections.isEmpty()) {
            toast("اطلاعاتی برای ذخیره وجود ندارد")
            return
        }
        createDoc.launch(fileName())
    }

    private fun writeTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                out.write(buildText().toByteArray(Charsets.UTF_8))
            }
            toast("فایل با موفقیت ذخیره شد")
        } catch (e: Exception) {
            toast("ذخیره فایل ناموفق بود")
        }
    }
}
