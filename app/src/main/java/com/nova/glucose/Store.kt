package com.nova.glucose

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings kept privately on the phone. Password and API keys are encrypted with the phone's own key store. */
class Store(c: Context) {
    private val sp: SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw: SharedPreferences get() = sp

    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun puti(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putb(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // ---------------------------------------------------------------- LibreLinkUp
    var email: String get() = s("email"); set(v) = put("email", v.trim())
    var password: String get() = dec(s("pw")); set(v) = put("pw", enc(v))
    var apiBase: String get() = s("api_base", Libre.DEFAULT_BASE); set(v) = put("api_base", v)
    var appVersion: String get() = s("llu_version", Libre.DEFAULT_VERSION); set(v) = put("llu_version", v)
    var token: String get() = dec(s("token")); set(v) = put("token", enc(v))
    var tokenExpires: Long get() = sp.getLong("token_exp", 0); set(v) = sp.edit().putLong("token_exp", v).apply()
    var accountId: String get() = s("account_id"); set(v) = put("account_id", v)
    var patientId: String get() = s("patient_id"); set(v) = put("patient_id", v)
    var patientName: String get() = s("patient_name"); set(v) = put("patient_name", v)
    val loggedIn get() = email.isNotBlank() && password.isNotBlank()

    // ---------------------------------------------------------------- alerts (mg/dL)
    var monitorOn: Boolean get() = b("monitor", true); set(v) = putb("monitor", v)
    var alertsOn: Boolean get() = b("alerts", true); set(v) = putb("alerts", v)
    var urgentLow: Int get() = i("urgent_low", 55); set(v) = puti("urgent_low", v)
    var low: Int get() = i("low", 70); set(v) = puti("low", v)
    var high: Int get() = i("high", 180); set(v) = puti("high", v)
    var veryHigh: Int get() = i("very_high", 250); set(v) = puti("very_high", v)
    /** Minutes before the same alert repeats. */
    var repeatMin: Int get() = i("repeat", 20); set(v) = puti("repeat", v)
    var staleAlert: Boolean get() = b("stale_alert", true); set(v) = putb("stale_alert", v)
    /** Minutes without a new reading before the "no signal" alert. */
    var staleMin: Int get() = i("stale_min", 20); set(v) = puti("stale_min", v)
    var urgNoSignal: Boolean get() = b("urg_nosignal", false); set(v) = putb("urg_nosignal", v)
    /** Home chart range: "12", "24", "w", "m". */
    var chartRange: String get() = s("chart_range", "12"); set(v) = put("chart_range", v)

    // ---------------------------------------------------------------- carb plan (standard guidance, editable)
    var lowTreatG: Int get() = i("low_treat", 15); set(v) = puti("low_treat", v)
    var mealMin: Int get() = i("meal_min", 45); set(v) = puti("meal_min", v)
    var mealMax: Int get() = i("meal_max", 75); set(v) = puti("meal_max", v)
    var snackMin: Int get() = i("snack_min", 15); set(v) = puti("snack_min", v)
    var snackMax: Int get() = i("snack_max", 30); set(v) = puti("snack_max", v)
    var foodNotes: String get() = s("food_notes"); set(v) = put("food_notes", v)

    // ---------------------------------------------------------------- AI
    var aiProvider: String get() = s("ai", "gemini"); set(v) = put("ai", v)
    fun key(p: Provider): String = dec(s("key_${p.id}")).trim()
    fun setKey(p: Provider, v: String) = put("key_${p.id}", enc(v.trim()))
    fun model(p: Provider): String = s("model_${p.id}").ifBlank { p.defaultModel }
    fun setModel(p: Provider, v: String) = put("model_${p.id}", v.trim())
    fun current(): Provider = Providers.byId(aiProvider) ?: Providers.all[0]

    var tipsSeen: Boolean get() = b("tips_seen", false); set(v) = putb("tips_seen", v)

    // ---------------------------------------------------------------- latest reading (for widgets/notifications)
    var lastMg: Int get() = i("last_mg", 0); set(v) = puti("last_mg", v)
    var lastTrend: Int get() = i("last_trend", 0); set(v) = puti("last_trend", v)
    var lastTs: Long get() = sp.getLong("last_ts", 0); set(v) = sp.edit().putLong("last_ts", v).apply()
    var lastError: String get() = s("last_error"); set(v) = put("last_error", v)
    fun lastAlert(kind: String): Long = sp.getLong("alert_$kind", 0)
    fun setLastAlert(kind: String, t: Long) = sp.edit().putLong("alert_$kind", t).apply()

    // ---------------------------------------------------------------- encryption
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return kg.generateKey()
    }

    private fun enc(v: String): String {
        if (v.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secretKey())
            val out = c.iv + c.doFinal(v.toByteArray(Charsets.UTF_8))
            "e1:" + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (_: Exception) { v }
    }

    private fun dec(v: String): String {
        if (!v.startsWith("e1:")) return v
        return try {
            val raw = Base64.decode(v.substring(3), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    // ---------------------------------------------------------------- profile & insulin (reference only, as prescribed)
    var age: Int get() = i("age", 14); set(v) = puti("age", v)
    /** "girl", "boy" or "" */
    var gender: String get() = s("gender"); set(v) = put("gender", v)
    /** Brief free text about her, sent to the AI (never used for doses). */
    var aboutHer: String get() = s("about_her"); set(v) = put("about_her", v.trim())
    var settingsOpen: String get() = s("settings_open"); set(v) = put("settings_open", v)
    /** Short line for AI prompts. */
    fun aboutLine(): String = listOfNotNull(
        "age $age", gender.takeIf { it.isNotBlank() }, bodyLine().takeIf { it.isNotBlank() }, aboutHer.takeIf { it.isNotBlank() }).joinToString(", ")

    // ---------------------------------------------------------------- height & weight (latest of the growth history, cached)
    val heightCm: Double get() = s("body_h").toDoubleOrNull() ?: 0.0
    val weightKg: Double get() = s("body_w").toDoubleOrNull() ?: 0.0
    val bodyTs: Long get() = sp.getLong("body_ts", 0)
    /** "height 158 cm, weight 46.5 kg (measured 2 Nov 2026)" or "". */
    fun bodyLine(): String {
        if (heightCm <= 0 && weightKg <= 0) return ""
        val parts = listOfNotNull(if (heightCm > 0) "height ${num(heightCm)} cm" else null, if (weightKg > 0) "weight ${num(weightKg)} kg" else null)
        return parts.joinToString(", ") + if (bodyTs > 0) " (measured ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date(bodyTs))})" else ""
    }
    private fun num(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(java.util.Locale.US, v)
    /** Latest height and latest weight (each from the newest entry that has it). */
    fun bodyChanged(db: Db) {
        val l = db.bodies()
        val h = l.firstOrNull { it.height > 0 }; val w = l.firstOrNull { it.weight > 0 }
        sp.edit().putString("body_h", (h?.height ?: 0.0).toString()).putString("body_w", (w?.weight ?: 0.0).toString())
            .putLong("body_ts", maxOf(h?.ts ?: 0L, w?.ts ?: 0L)).apply()
    }

    // ---------------------------------------------------------------- Huawei watch data through Health Connect
    /** Health Connect permissions were given on this phone (it reads the watch). */
    var hcOn: Boolean get() = b("hc_on", false); set(v) = putb("hc_on", v)
    var hcLastRead: Long get() = sp.getLong("last_hc_read", 0); set(v) = sp.edit().putLong("last_hc_read", v).apply()
    /** Newest watch sample seen (on any phone of the family). */
    var hcLastData: Long get() = sp.getLong("last_hc_data", 0); set(v) = sp.edit().putLong("last_hc_data", v).apply()
    /** Data types received, e.g. "steps,ex,sleep,hr,rhr,spo2". */
    var hcGot: String get() = s("last_hc_got"); set(v) = put("last_hc_got", v)
    var hcSources: String get() = s("last_hc_src"); set(v) = put("last_hc_src", v)
    var hcInfo: String get() = s("last_hc_info"); set(v) = put("last_hc_info", v)
    var hcShare: Boolean get() = b("hc_share", true); set(v) = putb("hc_share", v)
    var hcAi: Boolean get() = b("hc_ai", true); set(v) = putb("hc_ai", v)
    var hcReport: Boolean get() = b("hc_report", true); set(v) = putb("hc_report", v)
    var hcBody: Boolean get() = b("hc_body", true); set(v) = putb("hc_body", v)

    // ---------------------------------------------------------------- food ideas (Ask AI) options on this phone
    var foodTime: Int get() = i("food_time", 0); set(v) = puti("food_time", v)
    var foodPeople: Int get() = i("food_people", 4); set(v) = puti("food_people", v)
    var longName: String get() = s("long_name"); set(v) = put("long_name", v.trim())
    /** Numbers only (an autofilled email or text is ignored). */
    var longDose: String get() = s("long_dose").takeIf { it.replace(',', '.').toDoubleOrNull() != null } ?: ""
        set(v) = put("long_dose", v.trim().replace(',', '.').takeIf { it.toDoubleOrNull() != null } ?: "")
    var longTime: String get() = s("long_time"); set(v) = put("long_time", v.trim())
    var rapidName: String get() = s("rapid_name"); set(v) = put("rapid_name", v.trim())
    /** Prescribed carb ratio: grams of carbs per 1 unit of rapid insulin (0 = not set). */
    var carbRatio: Int get() = i("carb_ratio", 0); set(v) = puti("carb_ratio", v)
    /** Target glucose (mg/dL) and prescribed correction factor (mg/dL lowered by 1 unit, 0 = not set): reference only. */
    var targetMg: Int get() = i("target_mg", 100); set(v) = puti("target_mg", v)
    var corrFactor: Int get() = i("corr_factor", 0); set(v) = puti("corr_factor", v)

    // ---------------------------------------------------------------- Google Drive backup
    var driveOn: Boolean get() = b("drive_on", false); set(v) = putb("drive_on", v)
    var driveAuto: Boolean get() = b("drive_auto", true); set(v) = putb("drive_auto", v)
    /** "d" every day, "3" every 3 days, "w" every week. */
    var driveFreq: String get() = s("drive_freq", "d"); set(v) = put("drive_freq", v)
    var driveWifi: Boolean get() = b("drive_wifi", true); set(v) = putb("drive_wifi", v)
    var drivePhotos: Boolean get() = b("drive_photos", false); set(v) = putb("drive_photos", v)
    var driveEmail: String get() = s("drive_email"); set(v) = put("drive_email", v)
    var driveLast: Long get() = sp.getLong("last_drive", 0); set(v) = sp.edit().putLong("last_drive", v).apply()
    var driveLastInfo: String get() = s("last_drive_info"); set(v) = put("last_drive_info", v)
    var driveMap: String get() = s("last_drive_map", "{}"); set(v) = put("last_drive_map", v)
    /** Minutes to wait between rapid insulin and eating (0 = not set yet: ask). */
    var eatWait: Int get() = i("eat_wait", 0); set(v) = puti("eat_wait", v)
    var eatAt: Long get() = sp.getLong("last_eat_at", 0); set(v) = sp.edit().putLong("last_eat_at", v).apply()

    // ---------------------------------------------------------------- urgent alarm
    var urgentOn: Boolean get() = b("urg_on", true); set(v) = putb("urg_on", v)
    var urgUrgentLow: Boolean get() = b("urg_ul", true); set(v) = putb("urg_ul", v)
    var urgLow: Boolean get() = b("urg_l", true); set(v) = putb("urg_l", v)
    var urgHigh: Boolean get() = b("urg_h", false); set(v) = putb("urg_h", v)
    var urgVeryHigh: Boolean get() = b("urg_vh", true); set(v) = putb("urg_vh", v)
    var alarmSound: String get() = s("alarm_sound"); set(v) = put("alarm_sound", v)
    var alarmSoundName: String get() = s("alarm_sound_name", "Default alarm"); set(v) = put("alarm_sound_name", v)
    /** "off", "normal", "strong". */
    var alarmVibrate: String get() = s("alarm_vib", "strong"); set(v) = put("alarm_vib", v)
    /** Sound per alert type (urgent_low, low, high, very_high, stale); falls back to the single alarm sound. */
    fun soundFor(kind: String): String = s("snd_$kind")
    fun soundNameFor(kind: String): String = s("snd_${kind}_name").ifBlank { "Sound" }
    /** Per notification line: vibrate and pop up on screen (this phone). */
    fun vibFor(kind: String): Boolean = b("nvib_$kind", true)
    fun setVib(kind: String, v: Boolean) = putb("nvib_$kind", v)
    fun popFor(kind: String): Boolean = b("npop_$kind", true)
    fun setPop(kind: String, v: Boolean) = putb("npop_$kind", v)
    fun setSound(kind: String, uri: String, name: String) { sp.edit().putString("snd_$kind", uri).putString("snd_${kind}_name", name).apply() }
    var ringSec: Int get() = i("ring_sec", 60); set(v) = puti("ring_sec", v)
    var urgentRepeat: Int get() = i("urg_repeat", 5); set(v) = puti("urg_repeat", v)
    var alarmVolume: Int get() = i("alarm_vol", 80); set(v) = puti("alarm_vol", v)
    fun urgentFor(kind: String) = urgentOn && when (kind) {
        "urgent_low" -> urgUrgentLow; "low" -> urgLow; "high" -> urgHigh; "very_high" -> urgVeryHigh; "stale" -> urgNoSignal; else -> false
    }
    fun snoozedUntil(kind: String): Long = sp.getLong("last_snooze_$kind", 0)
    fun snooze(kind: String, until: Long) = sp.edit().putLong("last_snooze_$kind", until).apply()

    /** First name of the person followed, safe for file names. */
    val fileName: String get() = patientName.trim().split(" ").firstOrNull().orEmpty()
        .replace(Regex("[^A-Za-z0-9\\u00C0-\\u024F\\u0600-\\u06FF]"), "").ifBlank { "Nova" }

    // ---------------------------------------------------------------- Download/NovaGlucose folder & backup
    var folderUri: String get() = s("folder_uri"); set(v) = put("folder_uri", v)
    var backupPw: String get() = dec(s("backup_pw")); set(v) = put("backup_pw", enc(v))
    var lastExport: Long get() = sp.getLong("last_export", 0); set(v) = sp.edit().putLong("last_export", v).apply()
    var lastExportReading: Long get() = sp.getLong("last_export_reading", 0); set(v) = sp.edit().putLong("last_export_reading", v).apply()
    var lastBackupInfo: String get() = s("last_backup_info"); set(v) = put("last_backup_info", v)
    var lastBackupTs: Long get() = sp.getLong("last_backup", 0); set(v) = sp.edit().putLong("last_backup", v).apply()
    var resumeAsked: Boolean get() = b("resume_asked", false); set(v) = putb("resume_asked", v)
    // parental control
    var pinOn: Boolean get() = b("pin_on", false); set(v) = putb("pin_on", v)
    var pinHash: String get() = s("pin_hash"); set(v) = put("pin_hash", v)
    var pinFails: Int get() = i("last_pin_fails", 0); set(v) = puti("last_pin_fails", v)
    var pinBlockUntil: Long get() = sp.getLong("last_pin_block", 0); set(v) = sp.edit().putLong("last_pin_block", v).apply()

    // ---------------------------------------------------------------- family sync (per phone, never in backups)
    /** Firestore id of the family ("" = not joined). */
    var syncFam: String get() = s("sync_fam"); set(v) = put("sync_fam", v)
    var syncCode: String get() = dec(s("sync_code")); set(v) = put("sync_code", enc(v))
    var syncPw: String get() = dec(s("sync_pw")); set(v) = put("sync_pw", enc(v))
    /** Encryption key derived from the family password (saved so it isn't recomputed every minute). */
    var syncKey: String get() = dec(s("sync_key")); set(v) = put("sync_key", enc(v))
    var syncUid: String get() = s("sync_uid"); set(v) = put("sync_uid", v)
    var syncRefresh: String get() = dec(s("sync_rt")); set(v) = put("sync_rt", enc(v))
    var syncIdToken: String get() = dec(s("sync_it")); set(v) = put("sync_it", enc(v))
    var syncIdExp: Long get() = sp.getLong("sync_it_exp", 0); set(v) = sp.edit().putLong("sync_it_exp", v).apply()
    /** Phone name shown to the family, e.g. "Dad's phone". */
    var syncName: String get() = s("sync_name"); set(v) = put("sync_name", v.trim())
    /** Server time of the newest change received (RFC 3339). */
    var syncSince: String get() = s("sync_since"); set(v) = put("sync_since", v)
    var syncLast: Long get() = sp.getLong("sync_last", 0); set(v) = sp.edit().putLong("sync_last", v).apply()
    var syncInfo: String get() = s("sync_info"); set(v) = put("sync_info", v)
    var syncSeen: Long get() = sp.getLong("sync_seen", 0); set(v) = sp.edit().putLong("sync_seen", v).apply()
    /** Edit time of the eat timer last applied (family-wide). */
    var syncEatU: Long get() = sp.getLong("sync_eat_u", 0); set(v) = sp.edit().putLong("sync_eat_u", v).apply()
    /** This phone keeps its own LibreLinkUp login (not shared with the family). */
    var syncOwnLibre: Boolean get() = b("sync_own_llu", false); set(v) = putb("sync_own_llu", v)
    /** This phone keeps its own AI keys, AI choice and models (not shared with the family). */
    var syncOwnAi: Boolean get() = b("sync_own_ai", false); set(v) = putb("sync_own_ai", v)
    /** Family-shared settings: time of the last change applied/sent, and a fingerprint to notice local changes. */
    var syncSetU: Long get() = sp.getLong("sync_set_u", 0); set(v) = sp.edit().putLong("sync_set_u", v).apply()
    var syncSetHash: String get() = s("sync_set_hash"); set(v) = put("sync_set_hash", v)

    // ---------------------------------------------------------------- notifications & watch (this phone only)
    var notifyEat: Boolean get() = b("notify_eat", true); set(v) = putb("notify_eat", v)
    var watchAlerts: Boolean get() = b("watch_alerts", true); set(v) = putb("watch_alerts", v)
    var watchUrgent: Boolean get() = b("watch_urgent", true); set(v) = putb("watch_urgent", v)
    var watchStale: Boolean get() = b("watch_stale", true); set(v) = putb("watch_stale", v)
    var watchEat: Boolean get() = b("watch_eat", true); set(v) = putb("watch_eat", v)
    /** Glucose message for the watch every N minutes (0 = off). */
    var watchGlucose: Int get() = i("watch_glucose", 0); set(v) = puti("watch_glucose", v)
    /** App language on this phone: "en", "fr" or "ar". */
    var lang: String get() = s("app_lang", "en"); set(v) = put("app_lang", v)
    /** Language of the AI review (its own ▾ button). */
    var aiReviewDoneLang: String get() = s("ai_review_done_lang", "en"); set(v) = put("ai_review_done_lang", v)
    var aiReviewLang: String get() = s("ai_review_lang", ""); set(v) = put("ai_review_lang", v)
    /** Short AI food tip a few seconds after a high/low alert (this phone). */
    var aiTips: Boolean get() = b("ai_tips", true); set(v) = putb("ai_tips", v)
    var aiReview: String get() = s("ai_review"); set(v) = put("ai_review", v)
    var aiReviewTs: Long get() = sp.getLong("ai_review_ts", 0); set(v) = sp.edit().putLong("ai_review_ts", v).apply()
    var aiReviewDays: Int get() = i("ai_review_days", 7); set(v) = puti("ai_review_days", v)
    var lastWatchPost: Long get() = sp.getLong("last_watch", 0); set(v) = sp.edit().putLong("last_watch", v).apply()

    var syncOwner: Boolean get() = b("sync_owner", false); set(v) = putb("sync_owner", v)

    var layout: Int get() = i("folder_layout", 1); set(v) = puti("folder_layout", v)
    var lastPdf: Long get() = sp.getLong("last_pdf", 0); set(v) = sp.edit().putLong("last_pdf", v).apply()

    companion object {
        /** LibreLinkUp settings shared in the family (unless this phone keeps its own login). */
        val LIBRE_KEYS = setOf("email", "api_base", "llu_version", "patient_id", "patient_name")
        /** Plain settings shared in the family. Alerts, alarms, watch, folder, Drive and PIN stay on each phone. */
        val SHARED_KEYS = setOf("ai", "age", "gender", "about_her", "long_name", "long_dose", "long_time", "rapid_name", "carb_ratio",
            "target_mg", "corr_factor", "eat_wait", "low_treat", "meal_min", "meal_max", "snack_min", "snack_max", "food_notes")
        fun shared(k: String) = k in SHARED_KEYS || k.startsWith("model_")
        fun aiKey(k: String) = k == "ai" || k.startsWith("model_")
        private const val ALIAS = "nova_glucose_secret"
        const val PREFS = "nova_glucose"
        /** Never copied as plain settings (secrets, per-phone state). */
        fun privateKey(k: String) = k in setOf("pw", "token", "token_exp", "account_id", "backup_pw", "folder_uri", "resume_asked", "folder_layout") ||
            k.startsWith("key_") || k.startsWith("last_") || k.startsWith("alert_") || k.startsWith("sync_") || k == "hc_on" || k.startsWith("body_")
    }
}
