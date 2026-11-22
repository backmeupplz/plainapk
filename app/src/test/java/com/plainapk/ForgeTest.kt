package com.plainapk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test fun normalize() {
        assertEquals("https://github.com/o/r", Forge.normalize("github.com/o/r"))
        assertEquals("https://github.com/o/r", Forge.normalize("https://github.com/o/r/releases/tag/v1"))
        assertEquals("https://github.com/o/r", Forge.normalize("https://github.com/o/r.git"))
        assertEquals("https://gitlab.com/g/sub/r", Forge.normalize("https://gitlab.com/g/sub/r/-/releases"))
        assertEquals("https://git.borodutch.com/backmeupplz/plainapk", Forge.normalize("git.borodutch.com/backmeupplz/plainapk/releases"))
        assertNull(Forge.normalize("newpipe"))
        assertNull(Forge.normalize("https://github.com/o"))
        assertNull(Forge.normalize("ftp://github.com/o/r"))
    }

    @Test fun githubAssetsOnlyFromThisRepo() {
        val assets = Forge.githubAssets(res("github-expanded.html"), "ImranR98/Obtainium", "v1.7.4")
        assertTrue(assets.isNotEmpty())
        assertTrue(assets.all { it.url.startsWith("https://github.com/ImranR98/Obtainium/releases/download/") })
        assertTrue(Forge.githubAssets("""<a href="/evil/repo/releases/download/v1/x.apk">""", "ImranR98/Obtainium", "v1").isEmpty())
    }

    @Test fun picksArm64ReleaseNotFdroidFlavor() {
        val assets = Forge.githubAssets(res("github-expanded.html"), "ImranR98/Obtainium", "v1.7.4")
        assertEquals("app-arm64-v8a-release.apk", Forge.pick(assets, arm64)!!.name)
        assertEquals("app-x86_64-release.apk", Forge.pick(assets, listOf("x86_64", "x86"))!!.name)
    }

    @Test fun picksUniversalOverOtherAbis() {
        val rel = Forge.gitlabRelease(JSONObject(res("gitlab-latest.json")))
        assertEquals("v160.0.1", rel.tag)
        // IronFox ships per-ABI APKs and a universal one; .apks bundles and checksum files never qualify
        val pick = Forge.pick(rel.assets, arm64)!!
        assertTrue(pick.name, pick.name.endsWith("-arm64-v8a.apk") || pick.name.endsWith("-universal.apk"))
        assertEquals("ironfox-160.0.1-universal.apk", Forge.pick(rel.assets.filter { "arm64" !in it.name }, arm64)!!.name)
    }

    @Test fun forgejo() {
        val rel = Forge.forgejoRelease(JSONObject(res("forgejo-latest.json")))
        assertEquals("v1.0.2", rel.tag)
        assertEquals("spinwell.apk", Forge.pick(rel.assets, arm64)!!.name)
    }

    @Test fun pickRules() {
        val a = { n: String -> Asset(n, "https://x/$n") }
        assertNull(Forge.pick(listOf(a("app-x86.apk")), arm64)) // never another architecture
        assertNull(Forge.pick(listOf(Asset("a.apk", "http://x/a.apk")), arm64)) // never plain HTTP
        assertEquals("app.apk", Forge.pick(listOf(a("app-debug.apk"), a("app.apk")), arm64)!!.name)
        assertEquals("b-foss.apk", Forge.pick(listOf(a("a-gplay.apk"), a("b-foss.apk")), arm64, "*foss*")!!.name)
        val gb = listOf(a("banglejs-2026.11.1.apk"), a("gadgetbridge-2026.11.1.apk"))
        assertEquals("gadgetbridge-2026.11.1.apk", Forge.pick(gb, arm64, repo = "https://codeberg.org/Freeyourgadget/Gadgetbridge")!!.name)
        assertEquals("v1.2", Forge.atomFirstTag("""<link rel="alternate" href="https://github.com/o/r/releases/tag/v1.2"/>"""))
    }
}
