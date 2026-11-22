package com.plainapk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class Asset(val name: String, val url: String)
data class Release(val tag: String, val assets: List<Asset>)

/** Talks to GitHub, Forgejo/Gitea and GitLab directly. No API token: GitHub is read through its website, not its API. */
object Forge {
    const val GITHUB = "github"
    const val FORGEJO = "forgejo"
    const val GITLAB = "gitlab"

    /** "github.com/o/r", "https://gitlab.com/g/sub/r/-/releases", "...r.git" → "https://host/path" or null. */
    fun normalize(input: String): String? {
        var s = input.trim().removeSuffix("/")
        if (s.isEmpty() || s.contains(' ')) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains('.')) return null
        var parts = uri.path.split('/').filter { it.isNotEmpty() }
        val dash = parts.indexOf("-") // GitLab: /group/repo/-/releases
        if (dash >= 0) parts = parts.take(dash)
        parts = if (host == "github.com" || host == "codeberg.org" || host.startsWith("git.")) parts.take(2)
        else parts.takeWhile { it != "releases" && it != "tags" && it != "tree" && it != "src" }
        if (parts.size < 2) return null
        return "https://$host/" + parts.joinToString("/").removeSuffix(".git")
    }

    fun host(repo: String) = URI(repo).host
    fun path(repo: String) = URI(repo).path.trim('/')

    fun detect(repo: String): String = when (host(repo)) {
        "github.com" -> GITHUB
        "gitlab.com" -> GITLAB
        "codeberg.org" -> FORGEJO
        // Forgejo and Gitea answer /api/v1/version with JSON; GitLab redirects it to sign-in
        else -> if (runCatching { JSONObject(get("https://${host(repo)}/api/v1/version", follow = false)).has("version") }.getOrDefault(false)) FORGEJO else GITLAB
    }

    fun latest(repo: String, kind: String, prerelease: Boolean): Release = when (kind) {
        GITHUB -> {
            // releases/latest redirects to the newest full release (one cheap web request); prereleases need the feed.
            // Both also reveal where a renamed or transferred repository lives now, whose downloads sit under the new path.
            val found = (if (prerelease) null else releaseTagLink("$repo/releases/latest"))
                ?: atomFirstLink(get("$repo/releases.atom")) ?: throw IOException("No releases")
            val (path, tag) = found
            Release(tag, githubAssets(get("https://github.com/$path/releases/expanded_assets/${enc(tag)}"), path, tag))
        }
        FORGEJO -> {
            val api = "https://${host(repo)}/api/v1/repos/${path(repo)}/releases"
            forgejoRelease(if (prerelease) JSONArray(get("$api?limit=1")).getJSONObject(0) else JSONObject(get("$api/latest")))
        }
        else -> gitlabRelease(JSONObject(get("https://${host(repo)}/api/v4/projects/${enc(path(repo))}/releases/permalink/latest")))
    }

    fun atomFirstTag(atom: String): String? = atomFirstLink(atom)?.second

    /** (owner/repo, tag) of the newest release link in a releases feed. */
    fun atomFirstLink(atom: String): Pair<String, String>? = Regex("""https://github\.com/([^/"]+/[^/"]+)/releases/tag/([^"<\s]+)""").find(atom)
        ?.let { it.groupValues[1] to java.net.URLDecoder.decode(it.groupValues[2], "UTF-8") }

    /** Follows releases/latest through any rename redirects to (current owner/repo, tag), or null without releases. */
    private fun releaseTagLink(start: String): Pair<String, String>? {
        var url = start
        repeat(4) {
            val c = open(url, follow = false)
            val loc = try { c.getHeaderField("Location") } finally { c.disconnect() } ?: return null
            val abs = URI(url).resolve(loc).toString()
            Regex("""^https://github\.com/([^/]+/[^/]+)/releases/tag/(.+)$""").find(abs)?.let {
                return it.groupValues[1] to java.net.URLDecoder.decode(it.groupValues[2], "UTF-8")
            }
            if (!abs.startsWith("https://github.com/")) return null
            url = abs
        }
        return null
    }

    /** Only links into this repo's own release downloads count. */
    fun githubAssets(html: String, path: String, tag: String): List<Asset> {
        val prefix = "/$path/releases/download/"
        return Regex("""href="([^"]+)"""").findAll(html).map { it.groupValues[1] }
            .filter { it.startsWith(prefix) }.distinct()
            .map { Asset(java.net.URLDecoder.decode(it.substringAfterLast('/'), "UTF-8"), "https://github.com$it") }.toList()
    }

    fun forgejoRelease(o: JSONObject): Release {
        val a = o.optJSONArray("assets") ?: JSONArray()
        return Release(o.getString("tag_name"), (0 until a.length()).map { a.getJSONObject(it) }
            .map { Asset(it.getString("name"), it.getString("browser_download_url")) })
    }

    fun gitlabRelease(o: JSONObject): Release {
        val l = o.optJSONObject("assets")?.optJSONArray("links") ?: JSONArray()
        return Release(o.getString("tag_name"), (0 until l.length()).map { l.getJSONObject(it) }
            .map { Asset(it.getString("name"), it.optString("direct_asset_url").ifEmpty { it.getString("url") }) })
    }

    fun abiOf(name: String): String? {
        val s = name.lowercase()
        return when {
            "arm64" in s || "aarch64" in s || "armv8" in s -> "arm64-v8a"
            "armeabi" in s || "armv7" in s || "arm32" in s -> "armeabi-v7a"
            "x86_64" in s || "x86-64" in s || "amd64" in s || "x64" in s -> "x86_64"
            "x86" in s || "i686" in s -> "x86"
            else -> null
        }
    }

    private val unwanted = listOf("debug", "fdroid", "unsigned", "wear", "-tv", "_tv")

    /**
     * The APK for this device: its own ABI first, then universal, then another ABI it runs; flavors nobody wants last.
     * Among equals, a file named after the repository wins (gadgetbridge-1.apk over banglejs-1.apk), then the shortest name.
     */
    fun pick(assets: List<Asset>, abis: List<String>, glob: String? = null, repo: String? = null): Asset? {
        val pattern = glob?.let { Regex(it.split('*').joinToString(".*") { p -> Regex.escape(p) }, RegexOption.IGNORE_CASE) }
        val squash = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        val repoName = repo?.substringAfterLast('/')?.let(squash)
        return assets.filter { it.name.endsWith(".apk", true) && it.url.startsWith("https://") }
            .filter { pattern == null || pattern.matches(it.name) }
            .mapNotNull { a ->
                val abi = abiOf(a.name)
                val rank = when {
                    abi == null -> 1
                    abi == abis.firstOrNull() -> 0
                    abi in abis -> 2
                    else -> return@mapNotNull null
                }
                val named = repoName != null && repoName.isNotEmpty() && squash(a.name).contains(repoName)
                Triple(a, rank + 10 * unwanted.count { a.name.lowercase().contains(it) }, (if (named) 0 else 1000) + a.name.length)
            }
            .minWithOrNull(compareBy({ it.second }, { it.third }))?.first
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(url: String, follow: Boolean = true, etag: String? = null): HttpURLConnection {
        require(url.startsWith("https://")) { "Not HTTPS: $url" }
        return (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = follow
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "PlainAPK")
            etag?.let { setRequestProperty("If-None-Match", it) }
        }
    }

    fun get(url: String, follow: Boolean = true): String {
        val c = open(url, follow)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} from ${URI(url).host}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** Body or null when unchanged since [etag]; returns the new ETag alongside. */
    fun getCached(url: String, etag: String?): Pair<String?, String?> {
        val c = open(url, etag = etag)
        try {
            if (c.responseCode == 304) return null to etag
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} from ${URI(url).host}")
            return c.inputStream.bufferedReader().readText() to c.getHeaderField("ETag")
        } finally { c.disconnect() }
    }

    /** Streams [url] into [to], checking [cancelled] between chunks and reporting whole percents to [progress]. */
    fun download(url: String, to: File, cancelled: () -> Boolean, progress: (Int) -> Unit = {}) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} downloading")
            if (!c.url.toString().startsWith("https://")) throw IOException("Redirected off HTTPS")
            val total = c.contentLengthLong
            val part = File(to.path + ".part")
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        if (cancelled()) throw IOException("Stopped")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != shown) { shown = pct; progress(pct) }
                    }
                }
            }
            if (!part.renameTo(to)) throw IOException("Couldn't save the download")
        } finally { c.disconnect() }
    }
}
