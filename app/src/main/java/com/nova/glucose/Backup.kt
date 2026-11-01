package com.nova.glucose

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One backup file with all settings. The LibreLinkUp password and the API keys are locked with the
 * user's backup password (AES-256-GCM, key from PBKDF2). Without the password everything else still restores.
 * Readings, meals and alerts are restored from the Download/NovaGlucose folder files (see [Vault]).
 */
object Backup {
    const val FILE = "NovaGlucose-backup.json"
    private const val ITER = 120_000

    fun build(c: Context, password: String): String {
        val st = Store(c)
        val settings = JSONObject()
        for ((k, v) in st.raw.all) {
            if (Store.privateKey(k)) continue
            when (v) {
                is Boolean -> settings.put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> settings.put(k, JSONObject().put("t", "i").put("v", v))
                is Long -> settings.put(k, JSONObject().put("t", "l").put("v", v))
                is String -> settings.put(k, JSONObject().put("t", "s").put("v", v))
            }
        }
        val secrets = JSONObject().put("password", st.password)
        Providers.all.forEach { p -> st.key(p).takeIf { it.isNotBlank() }?.let { secrets.put("key_${p.id}", it) } }
        val root = JSONObject()
            .put("app", "Nova Glucose").put("format", 1).put("created", System.currentTimeMillis())
            .put("settings", settings)
        if (password.isNotBlank()) root.put("secrets", encrypt(secrets.toString(), password))
        return root.toString(1)
    }

    fun isBackup(text: String) = try { JSONObject(text).optString("app") == "Nova Glucose" } catch (_: Exception) { false }

    /** Restores settings; returns a summary line. Throws with a clear message on a bad file. */
    fun restore(c: Context, text: String, password: String): String {
        val root = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("This isn't a Nova Glucose backup file.") }
        if (root.optString("app") != "Nova Glucose") throw IllegalArgumentException("This isn't a Nova Glucose backup file.")
        val st = Store(c)
        val e = st.raw.edit()
        val settings = root.optJSONObject("settings") ?: JSONObject()
        for (k in settings.keys()) {
            if (Store.privateKey(k)) continue
            val o = settings.getJSONObject(k)
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "s" -> e.putString(k, o.optString("v"))
            }
        }
        e.commit()
        var note = "no passwords or API keys in the file"
        root.optJSONObject("secrets")?.let { enc ->
            note = try {
                val s = JSONObject(decrypt(enc, password))
                if (s.optString("password").isNotBlank()) st.password = s.optString("password")
                var n = 0
                Providers.all.forEach { p -> s.optString("key_${p.id}").takeIf { it.isNotBlank() }?.let { st.setKey(p, it); n++ } }
                st.backupPw = password
                "LibreLinkUp login and $n API key(s) restored"
            } catch (_: Exception) {
                throw IllegalArgumentException("Wrong backup password.")
            }
        }
        st.token = ""
        return "Settings restored · $note"
    }

    private fun keyFrom(password: String, salt: ByteArray): SecretKeySpec {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun encrypt(plain: String, password: String): JSONObject {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFrom(password, salt), GCMParameterSpec(128, iv))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        return JSONObject().put("alg", "AES-256-GCM/PBKDF2-SHA256").put("iter", ITER)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(out))
    }

    private fun decrypt(o: JSONObject, password: String): String {
        fun d(s: String) = Base64.decode(o.getString(s), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyFrom(password, d("salt")), GCMParameterSpec(128, d("iv")))
        return String(cipher.doFinal(d("data")), Charsets.UTF_8)
    }
}
