package com.nova.glucose

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

enum class Api { OPENAI, ANTHROPIC, GEMINI }

data class Provider(
    val id: String,
    val label: String,
    val api: Api,
    val baseUrl: String,
    val defaultModel: String,
    val keyPage: String,
    /** Regexes in order of preference, used to pick a model (that can see photos) from the account's list. */
    val prefer: List<String>,
    val skip: String = "audio|realtime|transcri|tts|image-gen|embed|search|moderation|instruct|codex|dall|whisper|computer|deep-research|live|exp|nano|-\\d{4}-\\d{2}-\\d{2}$"
)

class AiException(message: String, val code: Int = 0) : Exception(message)

object Providers {
    val all = listOf(
        Provider("gemini", "Gemini", Api.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
            "https://aistudio.google.com/app/apikey",
            listOf("^gemini-[0-9.]+-flash$", "^gemini-[0-9.]+-flash-lite$", "^gemini-[0-9.]+-pro$", "^gemini-[0-9.]+-flash", "^gemini-")),
        Provider("chatgpt", "ChatGPT", Api.OPENAI, "https://api.openai.com/v1", "gpt-4o-mini",
            "https://platform.openai.com/api-keys",
            listOf("^gpt-5(\\.\\d+)?-mini$", "^gpt-4\\.1-mini$", "^gpt-4o-mini$", "^gpt-5(\\.\\d+)?$", "^gpt-4o$", "^gpt-")),
        Provider("claude", "Claude", Api.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-5-5",
            "https://console.anthropic.com/settings/keys",
            listOf("^claude-sonnet-", "^claude-opus-", "^claude-haiku-", "^claude-"))
    )

    fun byId(id: String?): Provider? = all.firstOrNull { it.id == id }

    /**
     * Sends a question (with optional JPEG photos) and returns the text answer.
     * Busy errors are retried; an unknown model is replaced by the best model the account offers.
     */
    private val busy = setOf(429, 500, 502, 503, 504, 529)
    private val quotaRe = Regex("quota|exhausted|billing|limit: ?0|insufficient|credit", RegexOption.IGNORE_CASE)

    /**
     * Sends a question (with optional JPEG photos) and returns the text answer.
     * Busy errors are retried. When a model is missing, out of quota or unavailable, the other models of the
     * same AI are tried (lighter ones too) and the first that answers is kept as the new model.
     */
    fun ask(st: Store, p: Provider, system: String, user: String, images: List<ByteArray> = emptyList(),
            only: String? = null, status: (String) -> Unit = {}): String {
        val key = st.key(p)
        if (key.isBlank()) throw AiException("Add your ${p.label} API key in Settings first.")
        if (only != null) {
            // One specific model (Compare models): no switching, nothing saved.
            var tries = 0
            while (true) {
                try { return call(p, key, only, system, user, images).ifBlank { throw AiException("Empty answer") } }
                catch (e: java.io.IOException) { if (++tries > 1) throw AiException("No connection") }
                catch (e: AiException) {
                    val quota = e.code == 429 && quotaRe.containsMatchIn(e.message ?: "")
                    if (e.code in busy && !quota && ++tries <= 1) { Thread.sleep(3000); continue }
                    throw AiException(if (quota) "No quota left" else (e.message ?: "Error").take(120), e.code)
                }
            }
        }
        var model = st.model(p)
        val tried = linkedSetOf<String>()
        var candidates: List<String>? = null
        var busyTries = 0; var netTries = 0
        var firstError: AiException? = null
        while (true) {
            try {
                val a = call(p, key, model, system, user, images).ifBlank { throw AiException("Empty answer from ${p.label}") }
                if (model != st.model(p)) st.setModel(p, model)
                return a
            } catch (e: java.io.IOException) {
                if (++netTries > 2) throw AiException("No connection to ${p.label} — check the internet or VPN.")
                status("No connection — retrying…"); Thread.sleep(3000)
            } catch (e: AiException) {
                val quota = e.code == 429 && quotaRe.containsMatchIn(e.message ?: "")
                if (e.code in busy && !quota && ++busyTries <= 2) {
                    status("${p.label} is busy — retrying ($busyTries/2)…"); Thread.sleep(busyTries * 4000L); continue
                }
                val switchable = e.code == 404 || e.code in busy || (e.code == 400 && (e.message ?: "").contains("model", true))
                if (!switchable) throw e
                if (firstError == null) firstError = e
                tried += model
                if (candidates == null) candidates = try { ranked(p, listModels(p, key)) } catch (_: Exception) { emptyList() }
                val next = candidates!!.firstOrNull { it !in tried }
                if (next == null || tried.size >= 8) throw AiException(
                    (firstError!!.message ?: "Error") + if (quota || firstError!!.code == 429)
                        "\nTried: ${tried.joinToString()}. Free quota may be used up for today — wait, or add billing in Google AI Studio."
                    else "", firstError!!.code)
                status(if (quota) "$model: no quota left — trying $next…" else "$model unavailable — trying $next…")
                model = next; busyTries = 0
            }
        }
    }

    /** Save & test: checks the key, finds a model that answers, keeps it. Returns (model, short answer). */
    fun saveTest(st: Store, p: Provider, status: (String) -> Unit): Pair<String, String> {
        val a = ask(st, p, "Answer in one short sentence.", "Say hello to a teenager managing diabetes.", status = status)
        return st.model(p) to a
    }

    /** Usable models, best first (for Gemini: flash, then flash-lite, then pro …). */
    fun ranked(p: Provider, ids: List<String>): List<String> {
        val skip = Regex(p.skip, RegexOption.IGNORE_CASE)
        val usable = ids.filter { !skip.containsMatchIn(it) }
        val stable = usable.filter { !it.contains("preview") }
        val out = LinkedHashSet<String>()
        for (pool in listOf(stable, usable)) for (pat in p.prefer) {
            val re = Regex(pat)
            pool.filter { re.containsMatchIn(it) }.sortedDescending().forEach { out += it }
        }
        return out.toList()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun call(p: Provider, key: String, model: String, system: String, user: String, images: List<ByteArray>): String =
        when (p.api) {
            Api.OPENAI -> {
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", user))
                images.forEach {
                    content.put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${b64(it)}")))
                }
                val msgs = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", content))
                val r = post("${p.baseUrl}/chat/completions", JSONObject().put("model", model).put("messages", msgs),
                    mapOf("Authorization" to "Bearer $key"))
                r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
            }
            Api.ANTHROPIC -> {
                val content = JSONArray()
                images.forEach {
                    content.put(JSONObject().put("type", "image").put("source", JSONObject()
                        .put("type", "base64").put("media_type", "image/jpeg").put("data", b64(it))))
                }
                content.put(JSONObject().put("type", "text").put("text", user))
                val body = JSONObject().put("model", model).put("max_tokens", 4000).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                val r = post("${p.baseUrl}/messages", body, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
                val parts = r.getJSONArray("content")
                buildString {
                    for (i in 0 until parts.length()) parts.getJSONObject(i).let { if (it.optString("type") == "text") append(it.optString("text")) }
                }.trim()
            }
            Api.GEMINI -> {
                val parts = JSONArray()
                images.forEach {
                    parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(it))))
                }
                parts.put(JSONObject().put("text", user))
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                val r = post("${p.baseUrl}/models/$model:generateContent", body, mapOf("x-goog-api-key" to key))
                val cands = r.optJSONArray("candidates")
                if (cands == null || cands.length() == 0) {
                    val why = r.optJSONObject("promptFeedback")?.optString("blockReason") ?: "no answer"
                    throw AiException("Gemini returned no text ($why)")
                }
                val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                buildString {
                    for (i in 0 until ps.length()) ps.getJSONObject(i).let { if (!it.optBoolean("thought")) append(it.optString("text")) }
                }.trim()
            }
        }

    // ---------------------------------------------------------------- models

    fun listModels(p: Provider, key: String): List<String> {
        val ids = mutableListOf<String>()
        when (p.api) {
            Api.OPENAI -> {
                val d = get("${p.baseUrl}/models", mapOf("Authorization" to "Bearer $key")).getJSONArray("data")
                for (i in 0 until d.length()) ids += d.getJSONObject(i).getString("id")
            }
            Api.ANTHROPIC -> {
                val d = get("${p.baseUrl}/models?limit=100", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")).getJSONArray("data")
                for (i in 0 until d.length()) ids += d.getJSONObject(i).getString("id")
            }
            Api.GEMINI -> {
                val d = get("${p.baseUrl}/models?pageSize=200", mapOf("x-goog-api-key" to key)).optJSONArray("models") ?: JSONArray()
                for (i in 0 until d.length()) {
                    val o = d.getJSONObject(i)
                    if ((o.optJSONArray("supportedGenerationMethods")?.toString() ?: "").contains("generateContent"))
                        ids += o.getString("name").removePrefix("models/")
                }
            }
        }
        return ids.sorted()
    }

    fun pickModel(p: Provider, ids: List<String>): String {
        val skip = Regex(p.skip, RegexOption.IGNORE_CASE)
        val usable = ids.filter { !skip.containsMatchIn(it) && !it.contains("preview") }.ifEmpty { ids.filter { !skip.containsMatchIn(it) } }
        for (pat in p.prefer) {
            val hit = usable.filter { Regex(pat).containsMatchIn(it) }.sortedDescending()
            if (hit.isNotEmpty()) return hit.first()
        }
        return usable.firstOrNull() ?: p.defaultModel
    }

    // ---------------------------------------------------------------- http

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 120000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val c = open(url, headers)
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return read(c)
    }

    private fun get(url: String, headers: Map<String, String>): JSONObject = read(open(url, headers))

    private fun read(c: HttpURLConnection): JSONObject {
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } } ?: ""
            if (code !in 200..299) throw AiException(errorText(code, txt), code)
            return JSONObject(txt)
        } finally { c.disconnect() }
    }

    private fun errorText(code: Int, body: String): String {
        val msg = try {
            val o = JSONObject(body)
            when (val e = o.opt("error")) {
                is JSONObject -> e.optString("message", body)
                is String -> e
                else -> o.optString("message", body)
            }
        } catch (_: Exception) { body.take(300) }
        val hint = when (code) {
            401, 403 -> " — check the API key in Settings."
            402 -> " — the AI account has no credit left."
            429 -> " — too many requests or no credit; try again later."
            else -> ""
        }
        return "Error $code: ${msg.take(300)}$hint"
    }
}
