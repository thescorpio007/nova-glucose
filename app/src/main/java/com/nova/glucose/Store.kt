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
    private val sp: SharedPreferences = c.applicationContext.getSharedPreferences("nova_glucose", Context.MODE_PRIVATE)

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

    companion object { private const val ALIAS = "nova_glucose_secret" }
}
