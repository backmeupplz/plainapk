package com.plainapk

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Catalogue icons: 96 px WebP files next to index.json on plainapk.com, fetched once on a background thread and kept in
 * the cache folder. [get] returns null until an icon is here, and calls [onLoaded] when one arrives.
 */
class Icons(private val ctx: Context, private val onLoaded: () -> Unit) {
    private val memory = LruCache<String, Drawable>(300)
    private val pending = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val dir = File(ctx.cacheDir, "icons").apply { mkdirs() }

    fun get(l: Listing): Drawable? {
        val path = l.icon ?: return null
        memory.get(l.pkg)?.let { return it }
        synchronized(pending) { if (l.pkg in failed || !pending.add(l.pkg)) return null }
        pool.execute {
            val file = File(dir, l.pkg + ".webp")
            try {
                if (!file.exists()) Forge.download(Store.INDEX_URL.substringBeforeLast('/') + "/" + path, file, { false })
                val bmp = BitmapFactory.decodeFile(file.path) ?: throw IllegalStateException("bad icon")
                memory.put(l.pkg, BitmapDrawable(ctx.resources, bmp))
            } catch (_: Exception) {
                file.delete()
                synchronized(pending) { failed += l.pkg }
            } finally {
                synchronized(pending) { pending -= l.pkg }
                onLoaded()
            }
        }
        return null
    }
}
