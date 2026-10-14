package com.mahirex.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Real network speed test. Nothing here is simulated: every number comes from timed HTTPS
 * transfers against a public test server chosen (or confirmed) by the user.
 *
 * Network is used ONLY while a test / server refresh is running, and only after the user asked.
 * No device data is sent; upload uses random bytes.
 */
class SpeedServer(
    val id: String,
    val name: String,
    val location: String,
    val kind: Int,
    val base: String,
    private val dlPath: String,
    private val ulPath: String,
    private val pingPath: String
) {
    @Volatile
    var pingMs: Double = -1.0

    @Volatile
    var checked: Boolean = false

    val reachable: Boolean get() = checked && pingMs >= 0

    private fun q(p: String) = if (p.contains('?')) "&" else "?"

    fun pingUrl(): String =
        if (kind == CLOUDFLARE) base + "__down?bytes=0"
        else base + pingPath + q(pingPath) + "r=" + System.nanoTime()

    fun downUrl(): String =
        if (kind == CLOUDFLARE) base + "__down?bytes=25000000"
        else base + dlPath + q(dlPath) + "ckSize=20&r=" + System.nanoTime()

    fun upUrl(): String =
        if (kind == CLOUDFLARE) base + "__up"
        else base + ulPath + q(ulPath) + "r=" + System.nanoTime()

    companion object {
        const val CLOUDFLARE = 0
        const val LIBRESPEED = 1
    }
}

class SpeedResult(
    val time: Long,
    val pingMs: Double,
    val jitterMs: Double,
    val downMbps: Double,
    val upMbps: Double,        // < 0 means: could not be measured
    val serverName: String,
    val conn: String,
    val complete: Boolean
)

class SpeedCancelled : Exception()

class SpeedError(val kind: Int) : Exception() {
    companion object {
        const val NO_NET = 1
        const val NO_SERVER = 2
        const val PING = 3
        const val DOWN = 4
    }
}

interface SpeedListener {
    fun phase(p: Int)
    fun server(s: SpeedServer)
    fun ping(ms: Double, jitter: Double)
    fun live(phase: Int, mbps: Double, fraction: Float)
}

object SpeedEngine {
    const val PH_CHECK = 0
    const val PH_SERVER = 1
    const val PH_PING = 2
    const val PH_DOWN = 3
    const val PH_UP = 4
    const val PH_DONE = 5

    private const val LIST_URL = "https://librespeed.org/backend-servers/servers_list.json"
    private const val STREAMS = 4
    private const val WARMUP_S = 2.0
    private const val TOTAL_S = 8.0
    private const val UP_CHUNK = 2 * 1024 * 1024

    @Volatile
    var servers: List<SpeedServer> = listOf(cloudflare(""))
        private set

    private fun cloudflare(loc: String) = SpeedServer(
        "cf", "Cloudflare", loc, SpeedServer.CLOUDFLARE, "https://speed.cloudflare.com/", "", "", ""
    )

    // ------------------------------------------------------------ http helper
    private fun open(url: String, post: Boolean, connectMs: Int = 6000, readMs: Int = 10000): HttpURLConnection {
        if (!url.startsWith("https://")) throw IOException("https only")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("User-Agent", "MAHIREX-Pulse")
        if (post) {
            c.requestMethod = "POST"
            c.doOutput = true
        }
        return c
    }

    private fun readAll(c: HttpURLConnection, limit: Int): String {
        val code = c.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { ins ->
            val b = ByteArray(8192)
            while (true) {
                val n = ins.read(b)
                if (n < 0) break
                out.write(b, 0, n)
                if (out.size() > limit) throw IOException("too large")
            }
        }
        return out.toString("UTF-8")
    }

    // ------------------------------------------------------------ connection info
    fun hasInternet(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val caps = if (n != null) cm.getNetworkCapabilities(n) else null
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        false
    }

    fun connType(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val caps = if (n != null) cm.getNetworkCapabilities(n) else null
        when {
            caps == null -> ""
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }

    // ------------------------------------------------------------ ping / jitter
    private fun pingOnce(s: SpeedServer, connectMs: Int, readMs: Int): Double {
        val t0 = System.nanoTime()
        val c = open(s.pingUrl(), false, connectMs, readMs)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            c.inputStream.use { ins ->
                val b = ByteArray(1024)
                while (ins.read(b) >= 0) {
                    // drain so the keep-alive connection can be reused by the next sample
                }
            }
        } catch (e: Exception) {
            try { c.disconnect() } catch (x: Exception) { /* ignore */ }
            throw e
        }
        return (System.nanoTime() - t0) / 1e6
    }

    /** returns Pair(median latency ms, jitter ms). The first sample (TCP+TLS setup) is discarded. */
    private fun measurePing(s: SpeedServer, count: Int, cancel: AtomicBoolean, connectMs: Int, readMs: Int): Pair<Double, Double> {
        val samples = ArrayList<Double>()
        var fails = 0
        for (i in 0..count) {
            if (cancel.get()) throw SpeedCancelled()
            try {
                val v = pingOnce(s, connectMs, readMs)
                if (i > 0) samples.add(v)
            } catch (e: SpeedCancelled) {
                throw e
            } catch (e: Exception) {
                fails++
                if (fails > 2) break
            }
        }
        if (samples.size < 3) throw IOException("ping failed")
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        var jitter = 0.0
        for (i in 1 until samples.size) jitter += Math.abs(samples[i] - samples[i - 1])
        jitter /= (samples.size - 1).toDouble()
        return Pair(median, jitter)
    }

    private fun probe(s: SpeedServer, cancel: AtomicBoolean) {
        try {
            s.pingMs = measurePing(s, 3, cancel, 3000, 3000).first
        } catch (e: SpeedCancelled) {
            throw e
        } catch (e: Exception) {
            s.pingMs = -1.0
        }
        s.checked = true
    }

    private fun probeAll(list: List<SpeedServer>, cancel: AtomicBoolean) {
        val pool = Executors.newFixedThreadPool(6)
        try {
            for (s in list) pool.execute { try { probe(s, cancel) } catch (e: Exception) { s.checked = true } }
        } finally {
            pool.shutdown()
            try {
                if (!pool.awaitTermination(25, TimeUnit.SECONDS)) pool.shutdownNow()
            } catch (e: InterruptedException) {
                pool.shutdownNow()
            }
        }
    }

    // ------------------------------------------------------------ server list
    /** Downloads the public LibreSpeed server list (user-triggered) and measures real latency to each. */
    fun refreshServers(cancel: AtomicBoolean): List<SpeedServer> {
        val out = ArrayList<SpeedServer>()
        out.add(cloudflare(cloudflareLocation()))
        try {
            val c = open(LIST_URL, false, 6000, 8000)
            val arr = JSONArray(readAll(c, 2_000_000))
            for (i in 0 until arr.length()) {
                if (out.size >= 41) break
                val o = arr.optJSONObject(i) ?: continue
                var srv = o.optString("server", "")
                if (srv.startsWith("//")) srv = "https:$srv"
                if (!srv.startsWith("https://")) continue
                if (!srv.endsWith("/")) srv += "/"
                val dl = o.optString("dlURL", "garbage.php")
                val ul = o.optString("ulURL", "empty.php")
                val pg = o.optString("pingURL", "empty.php")
                if (!safePath(dl) || !safePath(ul) || !safePath(pg)) continue
                val nm = o.optString("name", "").trim()
                if (nm.isEmpty()) continue
                out.add(SpeedServer("ls" + o.optInt("id", i), nm, "", SpeedServer.LIBRESPEED, srv, dl, ul, pg))
            }
        } catch (e: SpeedCancelled) {
            throw e
        } catch (e: Exception) {
            // list could not be fetched: keep what we have (built-in server)
        }
        probeAll(out, cancel)
        if (cancel.get()) throw SpeedCancelled()
        val good = out.filter { it.reachable }.sortedBy { it.pingMs }.take(15)
        servers = if (good.isEmpty()) listOf(cloudflare("")) else good
        return servers
    }

    private fun safePath(p: String): Boolean = p.isNotEmpty() && !p.contains("://") && !p.contains("..") && !p.startsWith("/")

    /** colo/loc of the nearest Cloudflare edge. The reply also holds the caller's IP; it is never read or kept. */
    private fun cloudflareLocation(): String = try {
        val c = open("https://speed.cloudflare.com/cdn-cgi/trace", false, 4000, 4000)
        val txt = readAll(c, 4096)
        var colo = ""
        var loc = ""
        for (line in txt.lines()) {
            if (line.startsWith("colo=")) colo = line.substring(5).trim()
            if (line.startsWith("loc=")) loc = line.substring(4).trim()
        }
        if (colo.isEmpty() && loc.isEmpty()) "" else (colo + (if (loc.isNotEmpty()) " / $loc" else "")).trim()
    } catch (e: Exception) {
        ""
    }

    // ------------------------------------------------------------ transfer phases
    private fun runLoop(total: AtomicLong, errors: AtomicLong, cancel: AtomicBoolean, stop: AtomicBoolean, phase: Int, l: SpeedListener): Double {
        val t0 = System.nanoTime()
        var tw = 0L
        var bw = 0L
        var warm = false
        var lastT = t0
        var lastB = 0L
        var smooth = -1.0
        var result = -1.0
        while (true) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                stop.set(true)
                throw SpeedCancelled()
            }
            if (cancel.get()) {
                stop.set(true)
                throw SpeedCancelled()
            }
            val now = System.nanoTime()
            val b = total.get()
            val el = (now - t0) / 1e9
            val dt = (now - lastT) / 1e9
            if (dt > 0) {
                val inst = (b - lastB) * 8.0 / dt / 1e6
                smooth = if (smooth < 0) inst else smooth * 0.6 + inst * 0.4
            }
            lastT = now
            lastB = b
            if (!warm && el >= WARMUP_S) {
                warm = true
                tw = now
                bw = b
            }
            l.live(phase, if (smooth < 0) 0.0 else smooth, Math.min(1.0, el / TOTAL_S).toFloat())
            if (el >= TOTAL_S) {
                val dtw = (now - tw) / 1e9
                if (warm && dtw > 0.5 && b > bw) result = (b - bw) * 8.0 / dtw / 1e6
                break
            }
            if (b == 0L && errors.get() >= 6 && el > 2.0) break
        }
        stop.set(true)
        return result
    }

    private fun download(s: SpeedServer, cancel: AtomicBoolean, l: SpeedListener): Double {
        val total = AtomicLong(0)
        val errors = AtomicLong(0)
        val stop = AtomicBoolean(false)
        for (n in 0 until STREAMS) {
            val th = Thread {
                val buf = ByteArray(64 * 1024)
                while (!stop.get() && !cancel.get()) {
                    var c: HttpURLConnection? = null
                    try {
                        c = open(s.downUrl(), false)
                        if (c.responseCode !in 200..299) throw IOException("HTTP")
                        val ins = c.inputStream
                        while (!stop.get() && !cancel.get()) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            total.addAndGet(r.toLong())
                        }
                    } catch (e: Exception) {
                        if (!stop.get() && !cancel.get()) {
                            errors.incrementAndGet()
                            try { Thread.sleep(250) } catch (x: InterruptedException) { /* ignore */ }
                        }
                    } finally {
                        try { c?.disconnect() } catch (x: Exception) { /* ignore */ }
                    }
                }
            }
            th.isDaemon = true
            th.start()
        }
        return runLoop(total, errors, cancel, stop, PH_DOWN, l)
    }

    private fun upload(s: SpeedServer, cancel: AtomicBoolean, l: SpeedListener): Double {
        val total = AtomicLong(0)
        val errors = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val payload = ByteArray(64 * 1024)
        Random().nextBytes(payload)
        for (n in 0 until STREAMS) {
            val th = Thread {
                while (!stop.get() && !cancel.get()) {
                    var c: HttpURLConnection? = null
                    try {
                        c = open(s.upUrl(), true)
                        c.setFixedLengthStreamingMode(UP_CHUNK)
                        val out = c.outputStream
                        var sent = 0
                        while (sent < UP_CHUNK) {
                            if (stop.get() || cancel.get()) throw IOException("stopped")
                            out.write(payload)
                            sent += payload.size
                            total.addAndGet(payload.size.toLong())
                        }
                        out.flush()
                        out.close()
                        if (c.responseCode !in 200..299) throw IOException("HTTP")
                        c.inputStream.use { ins ->
                            val b = ByteArray(1024)
                            while (ins.read(b) >= 0) {
                                // drain
                            }
                        }
                    } catch (e: Exception) {
                        if (!stop.get() && !cancel.get()) {
                            errors.incrementAndGet()
                            try { Thread.sleep(250) } catch (x: InterruptedException) { /* ignore */ }
                        }
                    } finally {
                        try { c?.disconnect() } catch (x: Exception) { /* ignore */ }
                    }
                }
            }
            th.isDaemon = true
            th.start()
        }
        return runLoop(total, errors, cancel, stop, PH_UP, l)
    }

    // ------------------------------------------------------------ full test
    /** Blocking. Call from a worker thread. Throws SpeedCancelled / SpeedError. */
    fun run(ctx: Context, chosen: SpeedServer?, cancel: AtomicBoolean, l: SpeedListener): SpeedResult {
        l.phase(PH_CHECK)
        if (!hasInternet(ctx)) throw SpeedError(SpeedError.NO_NET)
        val conn = connType(ctx)

        l.phase(PH_SERVER)
        val server: SpeedServer
        if (chosen != null) {
            server = chosen
        } else {
            val list = servers
            probeAll(list, cancel)
            if (cancel.get()) throw SpeedCancelled()
            server = list.filter { it.reachable }.minByOrNull { it.pingMs } ?: throw SpeedError(SpeedError.NO_SERVER)
        }
        l.server(server)

        l.phase(PH_PING)
        val pj: Pair<Double, Double> = try {
            measurePing(server, 10, cancel, 5000, 6000)
        } catch (e: SpeedCancelled) {
            throw e
        } catch (e: Exception) {
            throw SpeedError(SpeedError.PING)
        }
        server.pingMs = pj.first
        l.ping(pj.first, pj.second)

        l.phase(PH_DOWN)
        val down = download(server, cancel, l)
        if (down < 0) throw SpeedError(SpeedError.DOWN)

        l.phase(PH_UP)
        var up = -1.0
        try {
            up = upload(server, cancel, l)
        } catch (e: SpeedCancelled) {
            throw e
        } catch (e: Exception) {
            up = -1.0
        }

        l.phase(PH_DONE)
        val label = if (server.location.isNotEmpty()) server.name + " · " + server.location else server.name
        return SpeedResult(System.currentTimeMillis(), pj.first, pj.second, down, up, label, conn, up >= 0)
    }
}

// ====================================================================== units
object SpeedFmt {
    const val MBPS = 0
    const val MBYTES = 1

    /** 1 MB/s = 8 Mbps */
    fun value(mbps: Double, unit: Int): Double = if (unit == MBYTES) mbps / 8.0 else mbps

    fun unitLabel(unit: Int): String = if (unit == MBYTES) "MB/s" else "Mbps"

    fun text(mbps: Double, unit: Int): String {
        if (mbps < 0) return "—"
        val v = value(mbps, unit)
        return when {
            v >= 100 -> String.format(java.util.Locale.US, "%.0f", v)
            v >= 10 -> String.format(java.util.Locale.US, "%.1f", v)
            else -> String.format(java.util.Locale.US, "%.2f", v)
        }
    }

    fun ms(v: Double): String = if (v < 0) "—" else String.format(java.util.Locale.US, "%.0f", v)
    fun ms1(v: Double): String = if (v < 0) "—" else String.format(java.util.Locale.US, "%.1f", v)
}

// ====================================================================== local history
object SpeedStore {
    private const val KEY = "speed_hist"
    private const val MAX = 100

    fun load(): MutableList<SpeedResult> {
        val out = ArrayList<SpeedResult>()
        try {
            val a = JSONArray(Prefs.getStr(KEY, "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(
                    SpeedResult(
                        o.optLong("t"), o.optDouble("p", -1.0), o.optDouble("j", -1.0),
                        o.optDouble("d", -1.0), o.optDouble("u", -1.0),
                        o.optString("s", ""), o.optString("c", ""), o.optBoolean("ok", true)
                    )
                )
            }
        } catch (e: Exception) {
            // corrupted history: start empty
        }
        return out
    }

    private fun save(list: List<SpeedResult>) {
        val a = JSONArray()
        for (r in list.take(MAX)) {
            val o = JSONObject()
            o.put("t", r.time)
            o.put("p", r.pingMs)
            o.put("j", r.jitterMs)
            o.put("d", r.downMbps)
            o.put("u", r.upMbps)
            o.put("s", r.serverName)
            o.put("c", r.conn)
            o.put("ok", r.complete)
            a.put(o)
        }
        Prefs.putStr(KEY, a.toString())
    }

    fun add(r: SpeedResult) {
        val l = load()
        l.add(0, r)
        save(l)
    }

    fun remove(time: Long) {
        val l = load()
        l.removeAll { it.time == time }
        save(l)
    }

    fun clear() = Prefs.putStr(KEY, "[]")
}
