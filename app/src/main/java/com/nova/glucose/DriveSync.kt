package com.nova.glucose

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Google Drive backup: mirrors the Download/NovaGlucose folder (photos optional) to
 * My Drive › NovaGlucose › <name>. Only the drive.file permission is used: the app sees only files it created.
 */
object DriveSync {
    const val REQ_CONNECT = 8801
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val FOLDER = "application/vnd.google-apps.folder"
    @Volatile var running = false

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    // ---------------------------------------------------------------- connect (from Settings)

    fun connect(a: Activity, done: (String?) -> Unit) {
        Identity.getAuthorizationClient(a).authorize(request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    try { a.startIntentSenderForResult(r.pendingIntent!!.intentSender, REQ_CONNECT, null, 0, 0, 0, null) }
                    catch (e: Exception) { done("Couldn't open Google sign-in: ${e.message}") }
                } else connected(a, r, done)
            }
            .addOnFailureListener { done(explain(it)) }
    }

    /** Call from onActivityResult. */
    fun onResult(a: Activity, req: Int, data: Intent?, done: (String?) -> Unit): Boolean {
        if (req != REQ_CONNECT) return false
        try { connected(a, Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data), done) }
        catch (e: Exception) { done(explain(e)) }
        return true
    }

    private fun connected(a: Activity, r: AuthorizationResult, done: (String?) -> Unit) {
        val token = r.accessToken ?: return done("Google didn't give access")
        Thread {
            val err = try {
                val about = get(token, "$API/about?fields=user(emailAddress)")
                val st = Store(a)
                st.driveEmail = about.optJSONObject("user")?.optString("emailAddress").orEmpty()
                st.driveOn = true; null
            } catch (e: Exception) { explain(e) }
            a.runOnUiThread { done(err) }
        }.start()
    }

    fun disconnect(c: Context) {
        val st = Store(c)
        Thread { try { token(c)?.let { t -> post("https://oauth2.googleapis.com/revoke?token=$t", ByteArray(0), "application/x-www-form-urlencoded", null) } } catch (_: Exception) { } }.start()
        st.driveOn = false; st.driveEmail = ""; st.driveMap = "{}"
    }

    private fun explain(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("10:") || m.contains("DEVELOPER_ERROR") -> "Google setup not finished (Android client / SHA-1). It can take a few hours after creating it."
            m.contains("16:") || m.contains("CANCELED", true) -> "Cancelled"
            m.contains("7:") || m.contains("NETWORK") -> "No internet"
            else -> m.take(160)
        }
    }

    /** Access token without showing anything (null when the user must tap Connect again). */
    private fun token(c: Context): String? {
        val r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request()), 60, TimeUnit.SECONDS)
        return if (r.hasResolution()) null else r.accessToken
    }

    // ---------------------------------------------------------------- schedule (called every minute by the monitor)

    fun periodMs(st: Store) = when (st.driveFreq) { "3" -> 3; "w" -> 7; else -> 1 } * 24 * 3600_000L

    fun nextRun(st: Store): Long {
        val base = if (st.driveLast == 0L) System.currentTimeMillis() - periodMs(st) else st.driveLast
        val c = Calendar.getInstance().apply { timeInMillis = base + periodMs(st) - 3 * 3600_000L; set(Calendar.HOUR_OF_DAY, 2); set(Calendar.MINUTE, 0) }
        if (c.timeInMillis < base + periodMs(st) - 6 * 3600_000L) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun maybeRun(c: Context) {
        val st = Store(c)
        if (!st.driveOn || !st.driveAuto || running) return
        val now = System.currentTimeMillis()
        if (now < nextRun(st)) return
        if (st.driveWifi && !onWifi(c)) return
        Thread { backup(c, auto = true) }.start()
    }

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ---------------------------------------------------------------- backup

    /** Uploads everything new or changed. Returns a summary line. */
    fun backup(c: Context, auto: Boolean = false, status: (String) -> Unit = {}): String {
        if (running) return "A Drive backup is already running"
        running = true
        val st = Store(c)
        try {
            if (!Vault.ready(c)) throw IllegalStateException("Choose the Download/NovaGlucose folder first (Settings › Backup).")
            status("Updating the Download folder…")
            Vault.exportAll(c, forceBackup = true, pdfNow = true)
            status("Connecting to Google Drive…")
            val t = token(c) ?: run { if (auto) reconnectNote(c); throw IllegalStateException("Tap Connect again to allow Google Drive.") }
            val root = folder(t, "NovaGlucose", "root")
            val home = folder(t, st.fileName, root)
            val map = JSONObject(st.driveMap)
            var up = 0; var bytes = 0L; var skipped = 0
            val folders = HashMap<String, String>()
            fun walk(d: DocumentFile, path: String, parent: String) {
                for (f in d.listFiles()) {
                    val n = f.name ?: continue
                    val rel = if (path.isEmpty()) n else "$path/$n"
                    if (f.isDirectory) {
                        if (!st.drivePhotos && rel == "Meals/Photos") continue
                        val id = folders.getOrPut(rel) { folder(t, n, parent) }
                        walk(f, rel, id)
                    } else {
                        val sig = "${f.lastModified()}|${f.length()}"
                        val prev = map.optJSONObject(rel)
                        if (prev != null && prev.optString("sig") == sig) { skipped++; continue }
                        status("Uploading $rel…")
                        val data = c.contentResolver.openInputStream(f.uri)!!.use { it.readBytes() }
                        val mime = f.type ?: "application/octet-stream"
                        val id = upload(t, prev?.optString("id"), n, parent, mime, data)
                        map.put(rel, JSONObject().put("id", id).put("sig", sig))
                        up++; bytes += data.size
                        st.driveMap = map.toString()
                    }
                }
            }
            walk(Vault.root(c)!!, "", home)
            st.driveLast = System.currentTimeMillis()
            val mb = String.format(java.util.Locale.US, "%.1f", bytes / 1_048_576.0)
            st.driveLastInfo = "$up file(s) · $mb MB" + if (skipped > 0) " · $skipped unchanged" else ""
            return "Drive backup done: ${st.driveLastInfo}"
        } finally { running = false }
    }

    // ---------------------------------------------------------------- restore

    /** Downloads My Drive › NovaGlucose › <name> into the Download/NovaGlucose folder. */
    fun download(c: Context, status: (String) -> Unit): String {
        val st = Store(c)
        val dest = Vault.root(c) ?: throw IllegalStateException("Choose the Download/NovaGlucose folder first.")
        val t = token(c) ?: throw IllegalStateException("Tap Connect again to allow Google Drive.")
        val root = find(t, "NovaGlucose", "root", true) ?: throw IllegalStateException("No NovaGlucose folder found in this Drive.")
        val names = list(t, root).filter { it.optString("mimeType") == FOLDER }
        val home = names.firstOrNull { it.optString("name") == st.fileName } ?: names.firstOrNull()
            ?: throw IllegalStateException("The Drive NovaGlucose folder is empty.")
        var n = 0
        fun pull(id: String, d: DocumentFile) {
            for (o in list(t, id)) {
                val name = o.optString("name")
                if (o.optString("mimeType") == FOLDER) {
                    pull(o.optString("id"), d.findFile(name)?.takeIf { it.isDirectory } ?: d.createDirectory(name)!!)
                } else {
                    status("Downloading $name…")
                    val bytes = getBytes(t, "$API/files/${o.optString("id")}?alt=media")
                    val f = d.findFile(name) ?: d.createFile(o.optString("mimeType", "application/octet-stream"), name)!!
                    c.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(bytes) }; n++
                }
            }
        }
        pull(home.optString("id"), dest)
        return "$n file(s) downloaded from Drive"
    }

    // ---------------------------------------------------------------- Drive REST

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun find(t: String, name: String, parent: String, folderOnly: Boolean): String? {
        val query = "name = '${name.replace("'", "\\'")}' and '$parent' in parents and trashed = false" +
            if (folderOnly) " and mimeType = '$FOLDER'" else ""
        val r = get(t, "$API/files?q=${q(query)}&fields=files(id,name)&spaces=drive")
        return r.optJSONArray("files")?.optJSONObject(0)?.optString("id")
    }

    private fun list(t: String, parent: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>(); var page: String? = null
        do {
            val r = get(t, "$API/files?q=${q("'$parent' in parents and trashed = false")}&fields=nextPageToken,files(id,name,mimeType)&pageSize=1000" +
                (page?.let { "&pageToken=$it" } ?: ""))
            val a = r.optJSONArray("files"); if (a != null) for (i in 0 until a.length()) out += a.getJSONObject(i)
            page = r.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    private fun folder(t: String, name: String, parent: String): String =
        find(t, name, parent, true) ?: post(t = t, url = "$API/files?fields=id",
            body = JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", org.json.JSONArray().put(parent)).toString().toByteArray(),
            type = "application/json").getString("id")

    private fun upload(t: String, existing: String?, name: String, parent: String, mime: String, data: ByteArray): String {
        if (!existing.isNullOrBlank()) {
            try {
                return send("PATCH", "$UPLOAD/files/$existing?uploadType=media&fields=id", t, mime, data).getString("id")
            } catch (e: DriveError) { if (e.code != 404) throw e }
        }
        val boundary = "nova${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parent)).toString()
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            write(data); write("\r\n--$boundary--".toByteArray())
        }.toByteArray()
        return send("POST", "$UPLOAD/files?uploadType=multipart&fields=id", t, "multipart/related; boundary=$boundary", body).getString("id")
    }

    class DriveError(val code: Int, msg: String) : Exception(msg)

    private fun conn(url: String, t: String?) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30000; readTimeout = 120000
        t?.let { setRequestProperty("Authorization", "Bearer $it") }
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        if (code !in 200..299) throw DriveError(code, "Google Drive error $code: " +
            (try { JSONObject(txt).optJSONObject("error")?.optString("message") } catch (_: Exception) { null } ?: txt.take(150)))
        return if (txt.isBlank()) JSONObject() else JSONObject(txt)
    }

    private fun get(t: String, url: String) = readJson(conn(url, t))

    private fun getBytes(t: String, url: String): ByteArray {
        val c = conn(url, t)
        if (c.responseCode !in 200..299) return readJson(c).let { ByteArray(0) }
        return c.inputStream.use { it.readBytes() }.also { c.disconnect() }
    }

    private fun send(method: String, url: String, t: String, type: String, body: ByteArray): JSONObject {
        val c = conn(url, t)
        if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
        c.doOutput = true; c.setRequestProperty("Content-Type", type)
        c.outputStream.use { it.write(body) }
        return readJson(c)
    }

    private fun post(url: String, body: ByteArray, type: String, t: String?): JSONObject {
        val c = conn(url, t); c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", type)
        c.outputStream.use { it.write(body) }
        return readJson(c)
    }

    // ---------------------------------------------------------------- reminder

    private fun reconnectNote(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("drive", "Google Drive backup", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 61, Intent(c, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "drive") else @Suppress("DEPRECATION") Notification.Builder(c)
        nm.notify(6, b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle("Google Drive backup paused")
            .setContentText("Open Settings › Google Drive backup and tap Connect.").setContentIntent(pi).setAutoCancel(true).build())
    }
}
