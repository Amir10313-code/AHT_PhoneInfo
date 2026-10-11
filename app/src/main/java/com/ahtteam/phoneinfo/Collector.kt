package com.ahtteam.phoneinfo

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Collector {

    // ------------------------------------------------------------ helpers
    fun dateStr(ms: Long): String {
        val g = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
        return try {
            val pc = android.icu.util.Calendar.getInstance(
                android.icu.util.ULocale("fa_IR@calendar=persian")
            )
            pc.timeInMillis = ms
            val y = pc.get(android.icu.util.Calendar.YEAR)
            val m = pc.get(android.icu.util.Calendar.MONTH) + 1
            val d = pc.get(android.icu.util.Calendar.DAY_OF_MONTH)
            String.format(Locale.US, "%04d/%02d/%02d", y, m, d) + "  " +
                SimpleDateFormat("HH:mm", Locale.US).format(Date(ms)) + "   (" + g.substring(0, 10) + ")"
        } catch (e: Throwable) {
            g
        }
    }

    private fun size(b: Long): String {
        var v = b.toDouble()
        var i = 0
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        while (v >= 1024 && i < u.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "${v.toLong()} B" else String.format(Locale.US, "%.2f %s", v, u[i])
    }

    private fun pct(part: Long, total: Long): String {
        if (total <= 0) return "0%"
        return String.format(Locale.US, "%.1f%%", part * 100.0 / total)
    }

    private fun yn(b: Boolean) = if (b) "بله" else "خیر"

    private fun readFile(path: String): String? = try {
        File(path).readText().trim()
    } catch (e: Throwable) {
        null
    }

    private fun dur(ms: Long): String {
        val totalMin = ms / 60000
        val d = totalMin / (60 * 24)
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        return "${d} روز، ${h} ساعت و ${m} دقیقه"
    }

    private fun safe(title: String, f: () -> InfoSection): InfoSection {
        return try {
            f()
        } catch (e: Throwable) {
            val s = InfoSection(title)
            s.add("وضعیت", "دریافت اطلاعات این بخش ممکن نشد")
            s
        }
    }

    // ------------------------------------------------------------ entry
    fun collect(ctx: Context): List<InfoSection> {
        val sum = LinkedHashMap<String, String>()
        val out = ArrayList<InfoSection>()
        out.add(safe("دستگاه") { device(sum) })
        out.add(safe("سیستم\u200Cعامل") { system(sum) })
        out.add(safe("پردازنده") { cpu(sum) })
        out.add(safe("کارت گرافیک") { gpu(ctx, sum) })
        out.add(safe("حافظه RAM") { memory(ctx, sum) })
        out.add(safe("ذخیره\u200Cسازی") { storage(ctx, sum) })
        out.add(safe("نمایشگر") { display(ctx, sum) })
        out.add(safe("باتری") { battery(ctx, sum) })
        out.add(safe("شبکه") { network(ctx, sum) })
        out.add(safe("سیم\u200Cکارت و اپراتور") { sim(ctx) })
        out.add(safe("دوربین\u200Cها") { camera(ctx) })
        out.add(safe("حسگرها") { sensors(ctx) })
        out.add(safe("امکانات سخت\u200Cافزاری") { features(ctx) })
        out.add(safe("امنیت") { security(ctx) })
        out.add(0, summary(sum))
        return out
    }

    private fun summary(sum: Map<String, String>): InfoSection {
        val s = InfoSection("خلاصه")
        s.add("مدل", sum["model"])
        s.add("اندروید", sum["android"])
        s.add("پردازنده", sum["cpu"])
        s.add("تعداد هسته", sum["cores"])
        s.add("پردازنده گرافیکی", sum["gpu"])
        s.add("حافظه RAM", sum["ram"])
        s.add("حافظه داخلی", sum["storage"])
        s.add("نمایشگر", sum["display"])
        s.add("باتری", sum["battery"])
        s.add("آدرس IP", sum["ip"])
        s.add("مدت روشن بودن", sum["uptime"])
        return s
    }

    // ------------------------------------------------------------ device
    private fun device(sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("دستگاه")
        s.add("سازنده", Build.MANUFACTURER)
        s.add("برند", Build.BRAND)
        s.add("مدل", Build.MODEL)
        s.add("نام دستگاه (Device)", Build.DEVICE)
        s.add("محصول (Product)", Build.PRODUCT)
        s.add("برد (Board)", Build.BOARD)
        s.add("سخت\u200Cافزار (Hardware)", Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= 31) {
            s.add("سازنده SoC", Build.SOC_MANUFACTURER)
            s.add("مدل SoC", Build.SOC_MODEL)
            sum["soc"] = Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL
        }
        s.add("نوع ساخت", Build.TYPE)
        s.add("Tags", Build.TAGS)
        s.add("Bootloader", Build.BOOTLOADER)
        s.add("Host", Build.HOST)
        s.add("زمان ساخت", dateStr(Build.TIME))
        s.add("Fingerprint", Build.FINGERPRINT)
        sum["model"] = Build.MANUFACTURER + " " + Build.MODEL
        return s
    }

    // ------------------------------------------------------------ system
    private fun system(sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("سیستم\u200Cعامل")
        s.add("نسخه اندروید", Build.VERSION.RELEASE)
        s.add("سطح API", Build.VERSION.SDK_INT)
        s.add("Codename", Build.VERSION.CODENAME)
        if (Build.VERSION.SDK_INT >= 23) s.add("وصله امنیتی", Build.VERSION.SECURITY_PATCH)
        s.add("Incremental", Build.VERSION.INCREMENTAL)
        s.add("Build ID", Build.ID)
        s.add("Build Display", Build.DISPLAY)
        s.add("هسته (Kernel)", System.getProperty("os.version"))
        s.add("نسخه Radio", try { Build.getRadioVersion() } catch (e: Throwable) { null })
        if (Build.VERSION.SDK_INT >= 23) s.add("سیستم ۶۴ بیتی", yn(android.os.Process.is64Bit()))
        s.add("Java VM", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"))
        s.add("زبان سیستم", Locale.getDefault().toString())
        s.add("منطقه زمانی", TimeZone.getDefault().id)
        val up = dur(SystemClock.elapsedRealtime())
        s.add("مدت روشن بودن", up)
        s.add("مدت بیداری (بدون خواب)", dur(SystemClock.uptimeMillis()))
        s.add("آخرین بوت", dateStr(System.currentTimeMillis() - SystemClock.elapsedRealtime()))
        sum["android"] = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        sum["uptime"] = up
        return s
    }

    // ------------------------------------------------------------ cpu
    private fun cpu(sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("پردازنده")
        val info = readFile("/proc/cpuinfo") ?: ""

        fun field(vararg names: String): String? {
            for (line in info.lines()) {
                val idx = line.indexOf(':')
                if (idx < 0) continue
                val k = line.substring(0, idx).trim()
                if (names.any { it.equals(k, true) }) return line.substring(idx + 1).trim()
            }
            return null
        }

        val hw = field("Hardware")
        val model = field("model name", "Processor")
        val cores = Runtime.getRuntime().availableProcessors()
        s.add("نام پردازنده", model)
        s.add("Hardware", hw)
        s.add("معماری اصلی (ABI)", Build.SUPPORTED_ABIS.firstOrNull())
        s.add("ABIهای پشتیبانی\u200Cشده", Build.SUPPORTED_ABIS.joinToString(", "))
        s.add("تعداد هسته\u200Cها", cores)

        var anyFreq = false
        for (i in 0 until cores) {
            val base = "/sys/devices/system/cpu/cpu${i}/cpufreq/"
            val max = readFile(base + "cpuinfo_max_freq")?.toLongOrNull()
            val min = readFile(base + "cpuinfo_min_freq")?.toLongOrNull()
            val cur = readFile(base + "scaling_cur_freq")?.toLongOrNull()
            if (max != null) {
                anyFreq = true
                var t = "حداکثر ${max / 1000} MHz"
                if (min != null) t += " | حداقل ${min / 1000} MHz"
                if (cur != null) t += " | فعلی ${cur / 1000} MHz"
                s.add("هسته ${i}", t)
            }
        }
        if (!anyFreq) s.add("فرکانس هسته\u200Cها", "توسط اندروید برای برنامه\u200Cها محدود شده است")
        val gov = readFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        if (gov != null) s.add("Governor", gov)
        val feat = field("Features")
        if (feat != null) s.add("ویژگی\u200Cهای CPU", feat.split(" ").filter { it.isNotBlank() }.joinToString(", "))

        sum["cpu"] = model ?: sum["soc"] ?: hw ?: Build.HARDWARE
        sum["cores"] = cores.toString()
        return s
    }

    // ------------------------------------------------------------ gpu
    private fun glInfo(): Map<String, String> {
        val r = LinkedHashMap<String, String>()
        var dpy = EGL14.EGL_NO_DISPLAY
        var ctx = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) return r
            val cfgAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0)
            val cfg = cfgs[0] ?: return r
            ctx = EGL14.eglCreateContext(
                dpy, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            surf = EGL14.eglCreatePbufferSurface(
                dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            GLES20.glGetString(GLES20.GL_RENDERER)?.let { r["renderer"] = it }
            GLES20.glGetString(GLES20.GL_VENDOR)?.let { r["vendor"] = it }
            GLES20.glGetString(GLES20.GL_VERSION)?.let { r["version"] = it }
            GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)?.let { r["glsl"] = it }
            GLES20.glGetString(GLES20.GL_EXTENSIONS)?.let { r["ext"] = it }
        } catch (e: Throwable) {
            // ignore
        } finally {
            try {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            } catch (e: Throwable) {
                // ignore
            }
        }
        return r
    }

    private fun gpu(ctx: Context, sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("کارت گرافیک")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        s.add("نسخه OpenGL ES (پشتیبانی\u200Cشده)", am.deviceConfigurationInfo.glEsVersion)
        val g = glInfo()
        s.add("پردازنده گرافیکی (Renderer)", g["renderer"])
        s.add("سازنده (Vendor)", g["vendor"])
        s.add("نسخه OpenGL", g["version"])
        s.add("نسخه GLSL", g["glsl"])
        for (f in ctx.packageManager.systemAvailableFeatures) {
            if (f.name == "android.hardware.vulkan.version") {
                val v = f.version
                s.add("نسخه Vulkan", "${v shr 22}.${(v shr 12) and 0x3ff}.${v and 0xfff}")
            }
            if (f.name == "android.hardware.vulkan.level") s.add("سطح Vulkan", f.version)
        }
        val ext = g["ext"]
        if (ext != null) {
            val list = ext.trim().split(" ").filter { it.isNotBlank() }
            s.add("تعداد افزونه\u200Cهای OpenGL", list.size)
            s.add("فهرست افزونه\u200Cها", list.joinToString(", "))
        }
        sum["gpu"] = g["renderer"] ?: "—"
        return s
    }

    // ------------------------------------------------------------ memory
    private fun memory(ctx: Context, sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("حافظه RAM")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val used = mi.totalMem - mi.availMem
        s.add("RAM کل", size(mi.totalMem))
        s.add("RAM در دسترس", size(mi.availMem))
        s.add("RAM استفاده\u200Cشده", size(used) + "  (" + pct(used, mi.totalMem) + ")")
        s.add("آستانه کمبود حافظه", size(mi.threshold))
        s.add("وضعیت کمبود حافظه", yn(mi.lowMemory))

        val meminfo = readFile("/proc/meminfo") ?: ""
        fun kb(name: String): Long? {
            for (line in meminfo.lines()) {
                if (line.startsWith("${name}:")) {
                    val digits = line.substring(name.length + 1).replace("kB", "").trim()
                    return digits.toLongOrNull()?.times(1024)
                }
            }
            return null
        }
        kb("Buffers")?.let { s.add("Buffers", size(it)) }
        kb("Cached")?.let { s.add("Cached", size(it)) }
        val swapT = kb("SwapTotal")
        val swapF = kb("SwapFree")
        if (swapT != null) {
            s.add("Swap کل", size(swapT))
            if (swapF != null) s.add("Swap آزاد", size(swapF))
        }
        s.add("کلاس حافظه هر برنامه", "${am.memoryClass} MB")
        s.add("کلاس حافظه (Large Heap)", "${am.largeMemoryClass} MB")
        s.add("دستگاه کم\u200Cرم (Low RAM)", yn(am.isLowRamDevice))
        s.add("حداکثر Java Heap", size(Runtime.getRuntime().maxMemory()))
        sum["ram"] = size(mi.totalMem) + " (مصرف: " + pct(used, mi.totalMem) + ")"
        return s
    }

    // ------------------------------------------------------------ storage
    private fun fs(path: String): Pair<Long, Long>? = try {
        val st = StatFs(path)
        Pair(st.totalBytes, st.availableBytes)
    } catch (e: Throwable) {
        null
    }

    private fun storage(ctx: Context, sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("ذخیره\u200Cسازی")
        val data = fs(Environment.getDataDirectory().path)
        if (data != null) {
            val used = data.first - data.second
            s.sub("حافظه داخلی (Data)")
            s.add("ظرفیت کل", size(data.first))
            s.add("فضای استفاده\u200Cشده", size(used) + "  (" + pct(used, data.first) + ")")
            s.add("فضای آزاد", size(data.second))
            sum["storage"] = "کل: " + size(data.first) + " | آزاد: " + size(data.second)
        }
        val root = fs(Environment.getRootDirectory().path)
        if (root != null) {
            s.sub("پارتیشن سیستم (System)")
            s.add("ظرفیت", size(root.first))
            s.add("فضای آزاد", size(root.second))
        }
        val dirs = ctx.getExternalFilesDirs(null)
        var n = 0
        for (d in dirs) {
            if (d == null) continue
            val e = fs(d.path) ?: continue
            n++
            s.sub("حافظه خارجی / کارت حافظه ${n}")
            s.add("مسیر", d.path)
            s.add("ظرفیت کل", size(e.first))
            s.add("فضای آزاد", size(e.second))
        }
        s.add("حافظه داخلی شبیه\u200Cسازی\u200Cشده", yn(Environment.isExternalStorageEmulated()))
        return s
    }

    // ------------------------------------------------------------ display
    @Suppress("DEPRECATION")
    private fun display(ctx: Context, sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("نمایشگر")
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        val disp = wm.defaultDisplay
        disp.getRealMetrics(dm)
        val res = "${dm.widthPixels} x ${dm.heightPixels}"
        s.add("رزولوشن", res + " px")
        s.add("چگالی (Density)", dm.density)
        s.add("DPI", dm.densityDpi)
        s.add("xdpi / ydpi", String.format(Locale.US, "%.1f / %.1f", dm.xdpi, dm.ydpi))
        if (dm.xdpi > 0 && dm.ydpi > 0) {
            val wIn = dm.widthPixels / dm.xdpi.toDouble()
            val hIn = dm.heightPixels / dm.ydpi.toDouble()
            s.add("اندازه قطری (تخمینی)", String.format(Locale.US, "%.2f inch", Math.sqrt(wIn * wIn + hIn * hIn)))
        }
        s.add("نرخ نوسازی فعلی", String.format(Locale.US, "%.0f Hz", disp.refreshRate))
        val modes = disp.supportedModes
        if (modes != null) {
            s.add(
                "حالت\u200Cهای پشتیبانی\u200Cشده",
                modes.joinToString("\n") {
                    "${it.physicalWidth} x ${it.physicalHeight} @ ${String.format(Locale.US, "%.0f", it.refreshRate)} Hz"
                }
            )
        }
        if (Build.VERSION.SDK_INT >= 26) {
            s.add("HDR", yn(disp.isHdr))
            s.add("طیف رنگی گسترده (Wide Color)", yn(disp.isWideColorGamut))
        }
        val cfg = ctx.resources.configuration
        s.add("جهت صفحه", if (cfg.orientation == 2) "افقی" else "عمودی")
        s.add("مقیاس فونت", cfg.fontScale)
        try {
            val b = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            s.add("روشنایی صفحه", "${b} از 255 (" + (b * 100 / 255) + "%)")
        } catch (e: Throwable) {
            // ignore
        }
        try {
            val t = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            s.add("زمان خاموش شدن صفحه", "${t / 1000} ثانیه")
        } catch (e: Throwable) {
            // ignore
        }
        sum["display"] = res + " | " + String.format(Locale.US, "%.0f Hz", disp.refreshRate)
        return s
    }

    // ------------------------------------------------------------ battery
    private fun battery(ctx: Context, sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("باتری")
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (i != null) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val p = if (level >= 0 && scale > 0) level * 100 / scale else -1
            s.add("درصد شارژ", "${p}%")
            sum["battery"] = "${p}%"

            val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "در حال شارژ"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "در حال مصرف"
                BatteryManager.BATTERY_STATUS_FULL -> "پر شده"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "شارژ نمی\u200Cشود"
                else -> "نامشخص"
            }
            s.add("وضعیت", status)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val src = ArrayList<String>()
            if (plugged and BatteryManager.BATTERY_PLUGGED_AC != 0) src.add("آداپتور برق")
            if (plugged and BatteryManager.BATTERY_PLUGGED_USB != 0) src.add("USB")
            if (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) src.add("شارژ بی\u200Cسیم")
            s.add("منبع شارژ", if (src.isEmpty()) "متصل نیست" else src.joinToString(" + "))
            val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "خوب"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "داغ شدن بیش از حد"
                BatteryManager.BATTERY_HEALTH_DEAD -> "خراب"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "ولتاژ بیش از حد"
                BatteryManager.BATTERY_HEALTH_COLD -> "سرد"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "خطای نامشخص"
                else -> "نامشخص"
            }
            s.add("سلامت باتری", health)
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp >= 0) s.add("دما", String.format(Locale.US, "%.1f °C", temp / 10.0))
            val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (volt > 0) s.add("ولتاژ", "${volt} mV")
            s.add("نوع (Technology)", i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
            if (Build.VERSION.SDK_INT >= 34) {
                val cycles = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                if (cycles >= 0) s.add("تعداد چرخه شارژ", cycles)
            }
        }
        val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (cc > 0) {
            s.add("شارژ فعلی", "${cc / 1000} mAh")
            val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (cap in 1..100) s.add("ظرفیت تقریبی باتری (تخمینی)", "${cc / 1000 * 100 / cap} mAh")
        }
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (cur != Int.MIN_VALUE && cur != 0) s.add("جریان لحظه\u200Cای", "${cur / 1000} mA")
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        s.add("حالت صرفه\u200Cجویی باتری", yn(pm.isPowerSaveMode))
        return s
    }

    // ------------------------------------------------------------ network
    @Suppress("DEPRECATION")
    private fun network(ctx: Context, sum: MutableMap<String, String>): InfoSection {
        val s = InfoSection("شبکه")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        val lp = if (net != null) cm.getLinkProperties(net) else null

        // all interfaces
        val ifaces = ArrayList<Pair<String, List<String>>>()
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            if (nis != null) {
                for (ni in Collections.list(nis)) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val addrs = ArrayList<String>()
                    for (a in Collections.list(ni.inetAddresses)) {
                        val h = a.hostAddress ?: continue
                        addrs.add(h.substringBefore('%'))
                    }
                    ifaces.add(Pair(ni.name, addrs))
                }
            }
        } catch (e: Throwable) {
            // ignore
        }

        var primary = ""
        val v4 = ArrayList<String>()
        val v6 = ArrayList<String>()
        if (lp != null) {
            for (la in lp.linkAddresses) {
                val a = la.address
                val h = a.hostAddress ?: continue
                if (a is Inet4Address) v4.add(h) else v6.add(h.substringBefore('%'))
            }
            primary = v4.firstOrNull() ?: ""
        }
        if (primary.isEmpty()) {
            for (f in ifaces) {
                val c = f.second.firstOrNull { it.contains('.') && !it.startsWith("169.254.") }
                if (c != null) {
                    primary = c
                    break
                }
            }
        }
        s.add("آدرس IP اصلی دستگاه (IPv4)", if (primary.isEmpty()) "اتصال فعالی یافت نشد" else primary)
        sum["ip"] = if (primary.isEmpty()) "—" else primary

        if (caps != null) {
            val types = ArrayList<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types.add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types.add("داده همراه")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types.add("Ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) types.add("Bluetooth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types.add("VPN")
            s.add("نوع اتصال فعال", types.joinToString(" + "))
            s.add("دسترسی به اینترنت", yn(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
            s.add("پهنای باند دریافت (تخمینی)", "${caps.linkDownstreamBandwidthKbps / 1000} Mbps")
            s.add("پهنای باند ارسال (تخمینی)", "${caps.linkUpstreamBandwidthKbps / 1000} Mbps")
            s.add("شبکه گران\u200Cقیمت (Metered)", yn(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))
        } else {
            s.add("وضعیت اتصال", "اتصال فعالی وجود ندارد")
        }
        if (lp != null) {
            s.add("نام اینترفیس", lp.interfaceName)
            s.add("آدرس IPv4", v4.joinToString(", "))
            s.add("آدرس IPv6", v6.joinToString(", "))
            val gw = lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }.distinct()
            s.add("Gateway", gw.joinToString(", "))
            s.add("DNS", lp.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }.joinToString(", "))
        }

        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            s.sub("Wi-Fi")
            s.add("وضعیت Wi-Fi", if (wm.isWifiEnabled) "روشن" else "خاموش")
            val wi = wm.connectionInfo
            if (wi != null && wi.rssi != -127) {
                s.add("قدرت سیگنال", "${wi.rssi} dBm")
                s.add("سرعت لینک", "${wi.linkSpeed} Mbps")
                s.add("فرکانس", "${wi.frequency} MHz")
                val ip = wi.ipAddress
                if (ip != 0) {
                    s.add("IP روی Wi-Fi", "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}")
                }
            }
        } catch (e: Throwable) {
            // ignore
        }

        if (ifaces.isNotEmpty()) {
            s.sub("همه اینترفیس\u200Cهای فعال")
            for (f in ifaces) s.add(f.first, f.second.joinToString(", "))
        }
        return s
    }

    // ------------------------------------------------------------ sim
    @Suppress("DEPRECATION")
    private fun sim(ctx: Context): InfoSection {
        val s = InfoSection("سیم\u200Cکارت و اپراتور")
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            s.add("وضعیت", "این دستگاه قابلیت تلفن همراه ندارد")
            return s
        }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        s.add("تعداد اسلات سیم\u200Cکارت", tm.phoneCount)
        s.add("نوع تلفن", when (tm.phoneType) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            else -> "نامشخص"
        })
        s.add("وضعیت سیم\u200Cکارت", when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "سیم\u200Cکارت قرار نگرفته"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "نیازمند PIN"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "نیازمند PUK"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "قفل شبکه"
            TelephonyManager.SIM_STATE_READY -> "آماده"
            TelephonyManager.SIM_STATE_NOT_READY -> "آماده نیست"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "غیرفعال دائمی"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "خطای کارت"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "محدودشده"
            else -> "نامشخص"
        })
        s.add("اپراتور شبکه", tm.networkOperatorName)
        s.add("کد اپراتور شبکه (MCC+MNC)", tm.networkOperator)
        s.add("کشور شبکه", tm.networkCountryIso)
        s.add("اپراتور سیم\u200Cکارت", tm.simOperatorName)
        s.add("کد اپراتور سیم\u200Cکارت (MCC+MNC)", tm.simOperator)
        s.add("کشور سیم\u200Cکارت", tm.simCountryIso)
        s.add("رومینگ", yn(tm.isNetworkRoaming))
        return s
    }

    // ------------------------------------------------------------ camera
    private fun camera(ctx: Context): InfoSection {
        val s = InfoSection("دوربین\u200Cها")
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.cameraIdList
        s.add("تعداد دوربین\u200Cها", ids.size)
        for (id in ids) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "جلو"
                    CameraCharacteristics.LENS_FACING_BACK -> "عقب"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "خارجی"
                    else -> "نامشخص"
                }
                s.sub("دوربین ${id} (${facing})")
                val pa = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                if (pa != null) {
                    s.add("رزولوشن سنسور", "${pa.width} x ${pa.height}")
                    s.add("مگاپیکسل", String.format(Locale.US, "%.1f MP", pa.width.toDouble() * pa.height / 1e6))
                }
                val ps = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (ps != null) s.add("اندازه فیزیکی سنسور", String.format(Locale.US, "%.2f x %.2f mm", ps.width, ps.height))
                val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (fl != null) s.add("فاصله کانونی", fl.joinToString(", ") { "${it} mm" })
                val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                if (ap != null) s.add("دیافراگم", ap.joinToString(", ") { "f/${it}" })
                s.add("فلش", yn(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true))
                val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                s.add("لرزش\u200Cگیر نوری (OIS)", yn(ois != null && ois.contains(1)))
                val lvl = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                s.add("سطح سخت\u200Cافزاری Camera2", when (lvl) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL 3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "نامشخص"
                })
                s.add("حداکثر زوم دیجیتال", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
                s.add("زاویه نصب سنسور", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
                if (jpeg != null) s.add("بزرگ\u200Cترین خروجی JPEG", "${jpeg.width} x ${jpeg.height}")
            } catch (e: Throwable) {
                s.add("دوربین ${id}", "دریافت مشخصات ممکن نشد")
            }
        }
        return s
    }

    // ------------------------------------------------------------ sensors
    private fun sensors(ctx: Context): InfoSection {
        val s = InfoSection("حسگرها")
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        s.add("تعداد حسگرها", list.size)
        var n = 0
        for (sen in list) {
            n++
            s.sub("${n}. ${sen.name}")
            s.add("سازنده", sen.vendor)
            s.add("نوع", sen.stringType)
            s.add("نسخه", sen.version)
            s.add("بیشترین مقدار", sen.maximumRange)
            s.add("دقت (Resolution)", sen.resolution)
            s.add("مصرف انرژی", "${sen.power} mA")
            s.add("حداقل تأخیر", "${sen.minDelay} µs")
        }
        return s
    }

    // ------------------------------------------------------------ features
    private fun features(ctx: Context): InfoSection {
        val s = InfoSection("امکانات سخت\u200Cافزاری")
        val pm = ctx.packageManager
        val list = listOf(
            "NFC" to "android.hardware.nfc",
            "Bluetooth" to "android.hardware.bluetooth",
            "Bluetooth Low Energy" to "android.hardware.bluetooth_le",
            "Wi-Fi" to "android.hardware.wifi",
            "Wi-Fi Direct" to "android.hardware.wifi.direct",
            "Wi-Fi Aware" to "android.hardware.wifi.aware",
            "GPS / موقعیت\u200Cیاب" to "android.hardware.location.gps",
            "تلفن همراه" to "android.hardware.telephony",
            "اثر انگشت" to "android.hardware.fingerprint",
            "تشخیص چهره" to "android.hardware.biometrics.face",
            "شتاب\u200Cسنج" to "android.hardware.sensor.accelerometer",
            "ژیروسکوپ" to "android.hardware.sensor.gyroscope",
            "قطب\u200Cنما" to "android.hardware.sensor.compass",
            "فشارسنج" to "android.hardware.sensor.barometer",
            "حسگر نور" to "android.hardware.sensor.light",
            "حسگر مجاورت" to "android.hardware.sensor.proximity",
            "گام\u200Cشمار" to "android.hardware.sensor.stepcounter",
            "ضربان\u200Cسنج" to "android.hardware.sensor.heartrate",
            "دوربین عقب" to "android.hardware.camera",
            "دوربین جلو" to "android.hardware.camera.front",
            "فلش دوربین" to "android.hardware.camera.flash",
            "USB Host" to "android.hardware.usb.host",
            "USB Accessory" to "android.hardware.usb.accessory",
            "فرستنده مادون قرمز (IR)" to "android.hardware.consumerir",
            "صدای کم\u200Cتأخیر" to "android.hardware.audio.low_latency",
            "میکروفن" to "android.hardware.microphone",
            "لرزش (Vibrator)" to "android.hardware.vibrator",
            "UWB" to "android.hardware.uwb",
            "پشتیبانی Always-on Display" to "android.software.always_on_display",
            "چند پنجره‌ای" to "android.software.picture_in_picture",
            "eSIM" to "android.hardware.telephony.euicc",
            "Vulkan" to "android.hardware.vulkan.version"
        )
        for (p in list) s.add(p.first, yn(pm.hasSystemFeature(p.second)))
        val all = pm.systemAvailableFeatures.mapNotNull { it.name }.sorted()
        s.add("تعداد کل ویژگی\u200Cهای سیستم", all.size)
        s.add("فهرست کامل ویژگی\u200Cها", all.joinToString(", "))
        return s
    }

    // ------------------------------------------------------------ security
    private fun security(ctx: Context): InfoSection {
        val s = InfoSection("امنیت")
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        s.add("قفل صفحه فعال است", yn(km.isDeviceSecure))
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        s.add("رمزنگاری حافظه", when (dpm.storageEncryptionStatus) {
            DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> "پشتیبانی نمی\u200Cشود"
            DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "غیرفعال"
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING -> "در حال فعال\u200Cسازی"
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "فعال"
            else -> "نامشخص"
        })
        val adb = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        val dev = Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        s.add("گزینه\u200Cهای توسعه\u200Cدهنده", if (dev) "فعال" else "غیرفعال")
        s.add("USB Debugging (ADB)", if (adb) "فعال" else "غیرفعال")
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/local/bin/su"
        )
        val rooted = suPaths.any { File(it).exists() } || (Build.TAGS?.contains("test-keys") == true)
        s.add("دسترسی روت (تخمینی)", if (rooted) "احتمالاً روت شده" else "روت تشخیص داده نشد")
        return s
    }
}
