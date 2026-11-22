package com.plainapk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An app Plain APK keeps up to date. */
data class Tracked(
    val repo: String,
    var pkg: String? = null,
    var name: String? = null,
    var kind: String? = null,
    var asset: String? = null, // file name pattern when a release has several APKs
    var prerelease: Boolean = false,
    var signer: String? = null, // SHA-256 of the signing certificate, pinned at the first install
    var latestTag: String? = null, // newest release seen
    var handledTag: String? = null, // release already installed or definitively turned down
    var pendingTag: String? = null, // handed to the system installer, not confirmed yet
    var pendingCode: Long = 0,
    var error: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("repo", repo); put("pkg", pkg); put("name", name); put("kind", kind); put("asset", asset)
        put("prerelease", prerelease); put("signer", signer); put("latestTag", latestTag); put("handledTag", handledTag)
        put("pendingTag", pendingTag); put("pendingCode", pendingCode); put("error", error)
    }

    companion object {
        fun from(o: JSONObject) = Tracked(
            o.getString("repo"), o.str("pkg"), o.str("name"), o.str("kind"), o.str("asset"), o.optBoolean("prerelease"),
            o.str("signer"), o.str("latestTag"), o.str("handledTag"), o.str("pendingTag"), o.optLong("pendingCode"), o.str("error"),
        )
    }
}

/** A catalogue entry from plainapk.com/index.json. */
data class Listing(val pkg: String, val name: String, val repo: String, val summary: String, val signer: String?, val asset: String?)

fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

/** All state lives in two JSON files in the app's private storage. Only touched from [Work]'s thread or under its lock. */
class Store(private val ctx: Context) {
    private val file = File(ctx.filesDir, "apps.json")
    private val indexFile = File(ctx.filesDir, "index.json")
    // Changed on the work thread while the screen reads it on the main thread, so iteration never sees a half-made change
    val apps: MutableList<Tracked> = java.util.concurrent.CopyOnWriteArrayList(load())
    var catalogue: List<Listing> = parseIndex(indexFile.takeIf { it.exists() }?.readText())
        private set
    var indexEtag: String? = ctx.getSharedPreferences("p", 0).getString("indexEtag", null)
        private set

    private fun load(): MutableList<Tracked> {
        val list = runCatching { JSONArray(file.readText()) }.getOrNull()?.let { a -> (0 until a.length()).map { Tracked.from(a.getJSONObject(it)) } }
        return (list ?: emptyList()).toMutableList().also { l ->
            if (l.none { it.pkg == SELF_PKG }) l.add(Tracked(SELF_REPO, SELF_PKG, "Plain APK", Forge.GITHUB))
        }
    }

    @Synchronized
    fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(apps.map { it.toJson() }).toString())
        tmp.renameTo(file) // atomic: an update that kills the process never leaves half a file
    }

    fun find(repo: String) = apps.firstOrNull { it.repo.equals(repo, true) }
    fun findPkg(pkg: String) = apps.firstOrNull { it.pkg == pkg }

    fun setIndex(body: String, etag: String?) {
        catalogue = parseIndex(body)
        val tmp = File(indexFile.path + ".tmp")
        tmp.writeText(body)
        tmp.renameTo(indexFile)
        indexEtag = etag
        ctx.getSharedPreferences("p", 0).edit().putString("indexEtag", etag).apply()
    }

    companion object {
        const val SELF_PKG = "com.plainapk"
        const val SELF_REPO = "https://github.com/backmeupplz/plainapk"
        const val INDEX_URL = "https://plainapk.com/index.json"

        fun parseIndex(body: String?): List<Listing> {
            val a = runCatching { JSONObject(body!!).getJSONArray("apps") }.getOrNull() ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
                val repo = Forge.normalize(o.optString("repo")) ?: return@mapNotNull null
                Listing(o.getString("id"), o.optString("name", o.getString("id")), repo, o.optString("summary"), o.str("signer"), o.str("asset"))
            }.sortedBy { it.name.lowercase() }
        }
    }
}
