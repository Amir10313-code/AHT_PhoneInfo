package com.ahtteam.phoneinfo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
    private lateinit var chipGrid: LinearLayout
    private lateinit var body: LinearLayout
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

        // content
        scroll = ScrollView(this)
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        chipGrid = LinearLayout(this)
        chipGrid.orientation = LinearLayout.VERTICAL
        chipGrid.setPadding(dp(11), dp(2), dp(11), dp(6))
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, dp(2), 0, dp(24))
        body.addView(chipGrid)
        body.addView(content)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        content.removeAllViews()
        chipGrid.removeAllViews()
        cards.clear()
        val chips = ArrayList<View>()
        for (sec in sections) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(16), dp(14), dp(16), dp(10))
            card.background = rounded(col(R.color.card), 16, col(R.color.border))

            val title = text(sec.title, 17f, R.color.accent, true)
            title.setPadding(0, 0, 0, dp(6))
            card.addView(title)

            for (item in sec.items) {
                if (item.header) {
                    val h = text(item.key, 14f, R.color.accent, true)
                    h.setPadding(0, dp(12), 0, dp(4))
                    card.addView(h)
                } else {
                    val row = LinearLayout(this)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.setPadding(0, dp(8), 0, dp(8))
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
            val chip = text(sec.title, 12.5f, R.color.text_primary, false)
            chip.gravity = Gravity.CENTER
            chip.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
            chip.maxLines = 2
            chip.setPadding(dp(4), dp(4), dp(4), dp(4))
            chip.background = rounded(col(R.color.card), 12, col(R.color.border))
            chip.setOnClickListener { scroll.post { scroll.smoothScrollTo(0, content.top + card.top) } }
            chips.add(chip)
        }
        // chips in a fixed 3-column grid so every section button is visible
        var idx = 0
        while (idx < chips.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 3) {
                val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
                lp.setMargins(dp(3), dp(3), dp(3), dp(3))
                val v = chips.getOrNull(idx + c)
                row.addView(v ?: View(this), lp)
            }
            chipGrid.addView(row)
            idx += 3
        }
        content.addView(buildFooter())
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
        chipGrid.removeAllViews()
        val msg = text("در حال دریافت اطلاعات گوشی ...", 15f, R.color.text_muted, false)
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, dp(60), 0, 0)
        content.addView(msg)
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
                    sb.append(">> ").append(item.key).append("\n")
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
