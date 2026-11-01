package com.nova.glucose

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Family-sync self-test for the automatic build test: three fresh anonymous "phones" against the real Firebase
 * project and its security rules, plus the merge logic on this (blank) install. Never runs on a phone in use.
 */
object SyncTest {
    fun run(c: Context): String {
        if (!SelfTest.canRun(c)) return "SKIPPED (not a blank install)"
        val db = Db.get(c); val st = Store(c)
        var fam = ""
        var a: FamilySync.Session? = null; var b: FamilySync.Session? = null
        val gids = mutableListOf<String>()
        val done = mutableListOf<String>()
        try {
            val code = FamilySync.newCode(); val pw = "Family#2026"
            val key = FamilySync.deriveKey(code, pw)
            fam = FamilySync.famId(code)
            val sa = FamilySync.signUp(); val sb = FamilySync.signUp(); val x = FamilySync.signUp()
            a = sa; b = sb
            check(sa.uid != sb.uid) { "same identity twice" }
            done += "3 phones signed in ✓"

            // Phone A creates the family and sends a meal (with AI correction), a dose and the eat timer
            FamilySync.createFamily(sa, fam, key, "Phone A")
            val now = System.currentTimeMillis()
            val mg = "t" + now.toString(36) + "m"; val dg = "t" + now.toString(36) + "d"; val ig = "t" + now.toString(36) + "i"
            gids += listOf(mg, dg, "eat", ig)
            val meal = JSONObject().put("t", "meal").put("g", mg).put("u", now).put("ts", now - 3600_000L).put("title", "Couscous")
                .put("items", "[{\"name\":\"Couscous\",\"portion\":\"1 cup\",\"carbs\":45}]").put("carbs", 45).put("low", 40).put("high", 50)
                .put("mgAt", 140).put("note", "").put("ai", "[{\"name\":\"Couscous\",\"portion\":\"1 cup\",\"carbs\":40}]").put("aiC", 40)
            val dose = JSONObject().put("t", "dose").put("g", dg).put("u", now).put("ts", now - 3500_000L).put("kind", "rapid")
                .put("units", 4.5).put("carbs", 45).put("wait", 15).put("mgAt", 140).put("note", "NovoRapid before lunch")
            val eatAt = now + 15 * 60_000L
            val eat = JSONObject().put("t", "eat").put("g", "eat").put("u", now).put("at", eatAt)
            val rx = android.util.Base64.encodeToString(ByteArray(200_000) { (it % 251).toByte() }, android.util.Base64.NO_WRAP)
            val inc = JSONObject().put("t", "inc").put("g", ig).put("u", now).put("ts", now - 5 * 3600_000L).put("end", 0).put("kind", "Flu / cold")
                .put("details", "Fever 38.4").put("meds", "Paracetamol 250 mg x3/day").put("by", "Phone A").put("photo", rx)
            FamilySync.push(sa, fam, key, listOf(mg to meal, dg to dose, "eat" to eat, ig to inc))
            done += "family created, 4 items sent (incl. incident + prescription photo) ✓"

            // A stranger can't read, can't join with a wrong password, and a wrong code is refused
            val denied = try { FamilySync.pull(x, fam, key, ""); "allowed" } catch (e: FamilySync.SyncError) { if (e.code == 403) "refused" else "error ${e.code}" }
            check(denied == "refused") { "stranger could read the family: $denied" }
            val wj = try { FamilySync.joinFamily(x, fam, FamilySync.deriveKey(code, "wrong-pass"), "Stranger"); "joined" } catch (e: Exception) { e.message ?: "" }
            check(wj.contains("Wrong family password")) { "wrong password not refused: $wj" }
            val after = try { FamilySync.pull(x, fam, key, ""); "allowed" } catch (e: FamilySync.SyncError) { if (e.code == 403) "refused" else "error ${e.code}" }
            check(after == "refused") { "failed join was not undone: $after" }
            val nf = try { FamilySync.joinFamily(x, FamilySync.famId(FamilySync.newCode()), key, "X"); "joined" } catch (e: Exception) { e.message ?: "" }
            check(nf.contains("not found")) { "unknown code not refused: $nf" }
            done += "stranger refused ✓ · wrong password refused ✓ · wrong code refused ✓"

            // Phone B joins and receives everything, encrypted at rest
            FamilySync.joinFamily(sb, fam, key, "Phone B")
            val p = FamilySync.pull(sb, fam, key, "")
            check(p.items.size == 4) { "phone B received ${p.items.size} of 4 items" }
            val raw = FamilySync.rawItems(sb, fam)
            check(!raw.contains("Couscous") && !raw.contains("NovoRapid") && !raw.contains("Paracetamol")) { "readable text stored in Firebase" }
            done += "phone B joined, received 4/4 ✓ · stored encrypted ✓"

            // Merge on this phone: no duplicates on repeat or twins
            p.items.forEach { FamilySync.apply(c, it) }
            p.items.forEach { FamilySync.apply(c, it) }
            check(db.meals(10).size == 1 && db.doses().size == 1) { "after merge: ${db.meals(10).size} meals, ${db.doses().size} doses" }
            check(db.ai(db.meals(1).first().id)?.second == 40) { "AI correction lost" }
            check(db.doses().first().note == "NovoRapid before lunch") { "dose note lost" }
            check(st.eatAt == eatAt) { "eat timer not started" }
            val gotInc = db.incidents().firstOrNull()
            check(gotInc != null && gotInc.meds.contains("Paracetamol") && gotInc.ongoing) { "incident not received" }
            check(java.io.File(gotInc.photo).length() == 200_000L) { "prescription photo not received" }
            FamilySync.apply(c, JSONObject(meal.toString()).put("g", "zz-twin"))
            check(db.meals(10).size == 1) { "same meal under another id was duplicated" }
            done += "merged here, no duplicates ✓ · AI correction ✓ · eat timer ✓ · incident + photo ✓"

            // An edit and a deletion from B reach A
            FamilySync.push(sb, fam, key, listOf(dg to JSONObject(dose.toString()).put("u", now + 1000).put("units", 5.0),
                mg to JSONObject().put("t", "del").put("g", mg).put("u", now + 1000)))
            val p2 = FamilySync.pull(sa, fam, key, p.since)
            check(p2.items.any { it.optString("t") == "del" && it.optString("g") == mg }) { "deletion did not reach phone A" }
            p2.items.forEach { FamilySync.apply(c, it) }
            check(db.meals(10).isEmpty()) { "deleted meal still here" }
            check(db.doses().first().units == 5.0) { "edit not applied" }
            FamilySync.apply(c, meal)
            check(db.meals(10).isEmpty()) { "deleted meal came back" }
            done += "edit ✓ · deletion ✓ · deleted stays deleted ✓"

            val names = FamilySync.members(sa, fam, key).map { it.first }
            check(names.containsAll(listOf("Phone A", "Phone B")) && "Stranger" !in names) { "members: $names" }
            done += "phones listed: ${names.joinToString(", ")} ✓"

            // Family update file (WhatsApp) round trip
            val file = FamilySync.exportFile(c, pw)
            check(Backup.isEncrypted(file) && Backup.kind(file) == "family") { "update file not encrypted" }
            check(!String(file, Charsets.ISO_8859_1).contains("NovoRapid")) { "readable text in update file" }
            db.clearAll(); st.eatAt = 0
            val m = FamilySync.mergeText(c, Backup.unpack(file, pw))
            check(db.doses().size == 1 && db.meals(10).isEmpty() && db.deletedAt(mg) > 0) { "update file merge wrong: $m" }
            val wrong = try { Backup.unpack(file, "nope"); "opened" } catch (_: Exception) { "refused" }
            check(wrong == "refused") { "update file opened with a wrong password" }
            done += "update file: encrypted ✓ · merged ✓ · wrong password refused ✓"
            return "PASS · " + done.joinToString(" · ")
        } catch (e: Throwable) {
            return "FAIL · ${e.message} (after: ${done.joinToString(" · ")})"
        } finally {
            try { EatTimer.cancel(c, share = false) } catch (_: Exception) { }
            try { a?.let { FamilySync.deleteItems(it, fam, gids) } } catch (_: Exception) { }
            try { a?.let { FamilySync.leaveFamily(it, fam) }; b?.let { FamilySync.leaveFamily(it, fam) } } catch (_: Exception) { }
            st.raw.edit().clear().commit(); db.clearAll()
        }
    }

    fun log(c: Context) { Log.i("NOVA_SYNCTEST", run(c)) }
}
