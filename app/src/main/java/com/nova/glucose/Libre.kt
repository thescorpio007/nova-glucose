package com.nova.glucose

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One glucose reading. ts = epoch milliseconds, mg = mg/dL, trend 1..5 (0 = unknown). */
data class Reading(val ts: Long, val mg: Int, val trend: Int = 0)

data class Patient(val id: String, val name: String)

data class Snapshot(
    val patient: Patient,
    val latest: Reading?,
    val graph: List<Reading>,
    val targetLow: Int,
    val targetHigh: Int
)

class LibreException(message: String, val kind: Kind = Kind.OTHER) : Exception(message) {
    enum class Kind { LOGIN, TERMS, NETWORK, LOCKED, NO_PATIENT, OTHER }
}

/**
 * LibreLinkUp follower connection (the same servers the LibreLinkUp app uses).
 * Abbott does not publish this connection, so it is written to heal itself where it can:
 * region redirects and "app version too old" answers are followed automatically.
 */
class Libre(private val st: Store) {

    companion object {
        const val DEFAULT_BASE = "https://api.libreview.io"
        const val DEFAULT_VERSION = "4.16.0"

        fun trendArrow(t: Int) = when (t) { 1 -> "↓"; 2 -> "↘"; 3 -> "→"; 4 -> "↗"; 5 -> "↑"; else -> "" }
        fun trendText(t: Int) = when (t) {
            1 -> "falling fast"; 2 -> "falling"; 3 -> "steady"; 4 -> "rising"; 5 -> "rising fast"; else -> "trend unknown"
        }

        private val tsFmt = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() =
                SimpleDateFormat("M/d/yyyy h:mm:ss a", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        fun parseTs(s: String): Long = try { tsFmt.get()!!.parse(s)?.time ?: 0L } catch (_: Exception) { 0L }

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- public

    /** Logs in fresh (used by Settings "Connect"). Returns the people this account follows. */
    fun login(): List<Patient> {
        doLogin()
        return connections().map { it.first }
    }

    /** Latest reading + last 12 hours for the chosen person. */
    fun fetch(): Snapshot {
        if (!st.loggedIn) throw LibreException("Enter your LibreLinkUp email and password in Settings.", LibreException.Kind.LOGIN)
        ensureToken()
        val pid = st.patientId.ifBlank {
            val first = connections().firstOrNull()?.first
                ?: throw LibreException("This LibreLinkUp account doesn't follow anyone yet.", LibreException.Kind.NO_PATIENT)
            st.patientId = first.id; st.patientName = first.name; first.id
        }
        val r = authed { get("/llu/connections/$pid/graph") }
        val data = r.getJSONObject("data")
        val conn = data.optJSONObject("connection") ?: JSONObject()
        val name = listOf(conn.optString("firstName"), conn.optString("lastName")).filter { it.isNotBlank() }.joinToString(" ")
        val gm = conn.optJSONObject("glucoseMeasurement")
        val latest = gm?.let { reading(it) }
        val arr = data.optJSONArray("graphData")
        val graph = mutableListOf<Reading>()
        if (arr != null) for (i in 0 until arr.length()) reading(arr.getJSONObject(i))?.let { graph += it }
        if (latest != null && graph.none { it.ts == latest.ts }) graph += latest
        graph.sortBy { it.ts }
        val tl = conn.optInt("targetLow", 70).takeIf { it in 40..150 } ?: 70
        val th = conn.optInt("targetHigh", 180).takeIf { it in 100..400 } ?: 180
        if (name.isNotBlank()) st.patientName = name
        return Snapshot(Patient(pid, name.ifBlank { st.patientName }), latest, graph, tl, th)
    }

    // ---------------------------------------------------------------- internals

    private fun reading(o: JSONObject): Reading? {
        val mg = when {
            o.has("ValueInMgPerDl") -> o.optDouble("ValueInMgPerDl").toInt()
            o.has("Value") -> o.optDouble("Value").toInt()
            else -> 0
        }
        if (mg <= 0) return null
        val ts = parseTs(o.optString("FactoryTimestamp")).takeIf { it > 0 } ?: return null
        return Reading(ts, mg, o.optInt("TrendArrow", 0))
    }

    private fun connections(): List<Pair<Patient, JSONObject>> {
        val r = authed { get("/llu/connections") }
        val arr = r.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val name = listOf(o.optString("firstName"), o.optString("lastName")).filter { s -> s.isNotBlank() }.joinToString(" ")
            Patient(o.optString("patientId"), name) to o
        }
    }

    private fun ensureToken() {
        if (st.token.isBlank() || System.currentTimeMillis() > st.tokenExpires - 60_000) doLogin()
    }

    /** Runs a call; if the login expired (401) logs in again once and repeats it. */
    private fun authed(call: () -> JSONObject): JSONObject = try {
        call()
    } catch (e: HttpError) {
        if (e.code == 401) { doLogin(); try { call() } catch (e2: HttpError) { throw e2.toLibre() } } else throw e.toLibre()
    }

    private fun doLogin() {
        var tries = 0
        while (true) {
            if (++tries > 4) throw LibreException("LibreLinkUp login kept redirecting — try again later.")
            val body = JSONObject().put("email", st.email).put("password", st.password)
            val r = try { post("/llu/auth/login", body, auth = false) } catch (e: HttpError) {
                // Some refusals (old app version, lockout) arrive with an error code but a readable body.
                val o = runCatching { JSONObject(e.body) }.getOrNull()
                if (o != null && (o.has("status") || o.has("data"))) o else throw e.toLibre()
            }
            val status = r.optInt("status", 0)
            val data = r.optJSONObject("data")
            when {
                status == 920 || data?.has("minimumVersion") == true -> {
                    val v = data?.optString("minimumVersion").orEmpty()
                    if (v.isBlank() || v == st.appVersion) throw LibreException("Abbott asks for a newer app version. Update Nova Glucose.")
                    st.appVersion = v; continue
                }
                data?.optBoolean("redirect") == true -> {
                    val region = data.optString("region")
                    st.apiBase = if (region.isBlank()) DEFAULT_BASE else "https://api-$region.libreview.io"
                    continue
                }
                status == 2 -> throw LibreException("Wrong LibreLinkUp email or password.", LibreException.Kind.LOGIN)
                status == 4 -> throw LibreException(
                    "Open the official LibreLinkUp app, sign in and accept the new terms, then try again.",
                    LibreException.Kind.TERMS)
                data?.has("lockout") == true || status == 429 -> throw LibreException(
                    "Too many login attempts — LibreLinkUp has paused this account for a few minutes.", LibreException.Kind.LOCKED)
                status != 0 || data == null -> throw LibreException(
                    "LibreLinkUp login failed (status $status): ${r.optJSONObject("error")?.optString("message").orEmpty()}")
            }
            val ticket = data!!.getJSONObject("authTicket")
            st.token = ticket.getString("token")
            st.tokenExpires = ticket.optLong("expires", 0) * 1000
            st.accountId = sha256(data.getJSONObject("user").getString("id"))
            return
        }
    }

    // ---------------------------------------------------------------- http

    private class HttpError(val code: Int, val body: String) : Exception("HTTP $code") {
        fun toLibre(): LibreException = when (code) {
            -1 -> LibreException("No connection to LibreLinkUp — check the internet.", LibreException.Kind.NETWORK)
            401, 403 -> LibreException("LibreLinkUp refused the login (error $code). Check email/password in Settings.", LibreException.Kind.LOGIN)
            429 -> LibreException("LibreLinkUp says too many requests — waiting a bit.", LibreException.Kind.LOCKED)
            else -> LibreException("LibreLinkUp error $code ${body.take(160)}")
        }
    }

    private fun open(path: String, auth: Boolean): HttpURLConnection =
        (URL(st.apiBase + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 30000
            setRequestProperty("product", "llu.android")
            setRequestProperty("version", st.appVersion)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LibreLinkUp")
            if (auth) {
                setRequestProperty("Authorization", "Bearer ${st.token}")
                setRequestProperty("Account-Id", st.accountId)
            }
        }

    private fun get(path: String): JSONObject = read(open(path, true))

    private fun post(path: String, body: JSONObject, auth: Boolean): JSONObject {
        val c = open(path, auth)
        c.requestMethod = "POST"; c.doOutput = true
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        } catch (e: java.io.IOException) { c.disconnect(); throw HttpError(-1, e.message ?: "") }
        return read(c)
    }

    private fun read(c: HttpURLConnection): JSONObject {
        try {
            val code = try { c.responseCode } catch (e: java.io.IOException) { throw HttpError(-1, e.message ?: "") }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } } ?: ""
            if (code !in 200..299) throw HttpError(code, txt)
            return try { JSONObject(txt) } catch (_: Exception) { throw HttpError(code, "unexpected answer: ${txt.take(120)}") }
        } finally {
            c.disconnect()
        }
    }
}
