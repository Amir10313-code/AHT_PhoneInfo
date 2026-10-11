package com.ahtteam.phoneinfo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
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
    private lateinit var chipRow: LinearLayout
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
        b.textSize = 12.5f
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
            val lp = LinearLayout.LayoutParams(0, dp(42), 1f)
            lp.setMargins(0, 0, endMargin, 0)
            lp
        }
        actions.addView(button("💾 ذخیره TXT", true) { saveText() }, lpBtn(dp(6)))
        actions.addView(button("📋 کپی", false) { copyText() }, lpBtn(dp(6)))
        actions.addView(button("📤 اشتراک", false) { shareText() }, lpBtn(dp(6)))
        actions.addView(button("🔄 بروزرسانی", false) { load() }, lpBtn(0))
        val actLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        actLp.topMargin = dp(10)
        head.addView(actions, actLp)
        root.addView(head)

        // section chips
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.setPadding(dp(12), dp(4), dp(12), dp(8))
        hs.addView(chipRow)
        root.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // content
        scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, dp(2), 0, dp(24))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        content.removeAllViews()
        chipRow.removeAllViews()
        cards.clear()
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
                    val v = text(item.value, 14f, R.color.text_primary, false)
                    v.setTextIsSelectable(true)
                    row.addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f))
                    val vlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 6f)
                    vlp.marginStart = dp(8)
                    row.addView(v, vlp)
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
            val chip = text(sec.title, 13f, R.color.text_primary, false)
            chip.setPadding(dp(14), dp(7), dp(14), dp(7))
            chip.background = rounded(col(R.color.card), 20, col(R.color.border))
            val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clp.marginEnd = dp(8)
            chip.setOnClickListener { scroll.post { scroll.smoothScrollTo(0, card.top) } }
            chipRow.addView(chip, clp)
        }
        val footer = text("سازنده: AHT TEAM  •  نسخه 1.0", 12f, R.color.text_muted, false)
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, dp(14), 0, dp(6))
        content.addView(footer)
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

    private fun copyText() {
        if (sections.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AHT PhoneInfo", buildText()))
        toast("اطلاعات کپی شد")
    }

    private fun shareText() {
        if (sections.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_SUBJECT, "گزارش اطلاعات گوشی - AHT TEAM")
        i.putExtra(Intent.EXTRA_TEXT, buildText())
        startActivity(Intent.createChooser(i, "اشتراک‌گذاری گزارش"))
    }
}
