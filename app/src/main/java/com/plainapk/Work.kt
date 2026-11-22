package com.plainapk

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Everything that touches the network, the installer or the stored state runs on one background thread, in order. */
object Work {
    private val thread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    lateinit var store: Store
        private set
    private lateinit var ctx: Context

    /** repo → what's happening right now ("Downloading…"), shown in the list. */
    val busy = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** repo → download progress in percent, -1 while the size is unknown. */
    val progress = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Repos whose install waits for the user's confirmation in Android's dialog. */
    val needsTap: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val lastCheck get() = ctx.getSharedPreferences("p", 0).getLong("lastCheck", 0)
    @Volatile var foreground = false
    @Volatile var catalogueError: String? = null
    @Volatile var stopRequested = false

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        store = Store(ctx)
        run { store.loadIndex() }
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { it() } }
    fun ping() = changed()

    fun run(task: () -> Unit) = thread.execute {
        try { task() } catch (e: Exception) { android.util.Log.w("plainapk", "task failed", e) }
        changed()
    }

    // ---- installed apps -------------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    fun installed(pkg: String?): PackageInfo? = pkg?.let {
        runCatching { ctx.packageManager.getPackageInfo(it, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
    }

    fun certs(si: SigningInfo?): Set<String> {
        si ?: return emptySet()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun installerOf(pkg: String): String? = runCatching { ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName }.getOrNull()

    /** Marks installs the system finished (also ones whose result broadcast we missed, like our own update). */
    fun reconcile() {
        var dirty = false
        for (t in store.apps) {
            val p = t.pendingTag ?: continue
            val info = installed(t.pkg) ?: continue
            if (info.longVersionCode >= t.pendingCode) {
                t.handledTag = p; t.pendingTag = null; t.error = null; dirty = true
                if (t.signer == null) t.signer = certs(info.signingInfo).firstOrNull()
            }
        }
        if (dirty) store.save()
    }

    // ---- checking and updating ------------------------------------------------------------------------------------

    fun refreshCatalogue() {
        val (body, etag) = Forge.getCached(Store.INDEX_URL, store.indexEtag.takeIf { store.catalogue.isNotEmpty() })
        if (body != null) store.setIndex(body, etag)
    }

    /** Looks for new releases of every app and, when [install] is set, installs the ones that are on this phone. */
    fun checkAll(install: Boolean) {
        reconcile()
        catalogueError = runCatching { refreshCatalogue() }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        // Our own update restarts this process, so it goes last
        for (t in store.apps.sortedBy { it.pkg == Store.SELF_PKG }) {
            if (stopRequested) return
            if (t.pkg == Store.SELF_PKG) ctx.getSharedPreferences("p", 0).edit().putLong("lastCheck", System.currentTimeMillis()).commit()
            check(t, install = install && installed(t.pkg) != null)
        }
    }

    /** [force]: a tap on Update, which retries a release still waiting on the system installer. */
    fun check(t: Tracked, install: Boolean, force: Boolean = false) {
        busy[t.repo] = "Checking…"; changed()
        try {
            val kind = t.kind ?: Forge.detect(t.repo).also { t.kind = it }
            val rel = Forge.latest(t.repo, kind, t.prerelease)
            t.latestTag = rel.tag
            t.error = null
            // Already running this release (installed elsewhere, or before we tracked it): nothing to download
            if (t.handledTag == null && installed(t.pkg)?.versionName?.removePrefix("v") == rel.tag.removePrefix("v")) t.handledTag = rel.tag
            if (install && t.handledTag != rel.tag && (force || t.pendingTag != rel.tag)) update(t, rel)
        } catch (e: Exception) {
            t.error = e.message ?: e.javaClass.simpleName
        } finally {
            busy.remove(t.repo)
            store.save()
        }
    }

    /** Downloads, checks and hands the release to the system installer. */
    fun update(t: Tracked, rel: Release) {
        val asset = Forge.pick(rel.assets, Build.SUPPORTED_ABIS.toList(), t.asset, t.repo)
        if (asset == null) { t.error = "No APK for this phone in ${rel.tag}"; t.handledTag = rel.tag; return }
        busy[t.repo] = "Downloading ${rel.tag}…"; changed()
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // one download at a time; leftovers are from killed runs
        val file = File(dir, "download.apk")
        try {
            progress[t.repo] = -1
            Forge.download(asset.url, file, { stopRequested }) { pct -> progress[t.repo] = pct; changed() }
            progress.remove(t.repo)
            @Suppress("DEPRECATION")
            val apk = ctx.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return reject(t, rel, "${asset.name} isn't a valid APK")
            val apkCerts = certs(apk.signingInfo)
            if (apkCerts.isEmpty()) return reject(t, rel, "${asset.name} isn't signed")
            if (t.pkg != null && apk.packageName != t.pkg) return reject(t, rel, "${asset.name} is ${apk.packageName}, not ${t.pkg}")
            t.signer?.let { if (it !in apkCerts) return reject(t, rel, "Signed with a different key than before") }
            val have = installed(apk.packageName)
            if (have != null) {
                if (certs(have.signingInfo).intersect(apkCerts).isEmpty()) {
                    if (t.signer == null) t.signer = apkCerts.first() // so the screen can offer to switch to this source
                    t.handledTag = rel.tag; t.error = null; return // the screen shows it as signed differently and offers to switch
                }
                if (apk.longVersionCode <= have.longVersionCode) { t.handledTag = rel.tag; return } // nothing newer
            }
            t.pkg = apk.packageName
            if (t.name == null) t.name = apk.applicationInfo?.let { ai -> ai.sourceDir = file.path; ai.publicSourceDir = file.path; ctx.packageManager.getApplicationLabel(ai).toString() }
            busy[t.repo] = "Installing ${rel.tag}…"; changed()
            install(t, rel.tag, apk.longVersionCode, file, gentle = !foreground)
        } finally {
            progress.remove(t.repo)
            if (!file.delete()) file.deleteOnExit()
        }
    }

    private fun reject(t: Tracked, rel: Release, why: String) { t.error = why; t.handledTag = rel.tag }

    // ---- switching an app to its repository's build -----------------------------------------------------------------

    /** True when the copy on this phone is signed by someone other than the repository's developer (a Play build, say). */
    fun foreign(t: Tracked): Boolean {
        if (t.pkg == Store.SELF_PKG) return false
        val have = installed(t.pkg) ?: return false
        return t.signer != null && t.signer !in certs(have.signingInfo)
    }

    private class Switch(val tag: String, val code: Long, val file: File)
    private val switching = java.util.concurrent.ConcurrentHashMap<String, Switch>()

    /**
     * Moves an app installed elsewhere with another signature over to its repository's build. Android can't update across
     * signatures, so it has to be uninstalled (losing its data) and installed again. The new APK is downloaded and checked
     * first, so a failed download never costs the user the app; Android asks before uninstalling and before installing.
     */
    fun switchSource(t: Tracked) {
        if (store.find(t.repo) == null) { store.apps.add(t); store.save() } // so progress and any error show in the list
        busy[t.repo] = "Checking…"; changed()
        try {
            val kind = t.kind ?: Forge.detect(t.repo).also { t.kind = it }
            val rel = Forge.latest(t.repo, kind, t.prerelease)
            val asset = Forge.pick(rel.assets, Build.SUPPORTED_ABIS.toList(), t.asset, t.repo) ?: throw IOException("No APK for this phone in ${rel.tag}")
            busy[t.repo] = "Downloading ${rel.tag}…"; progress[t.repo] = -1; changed()
            val file = File(File(ctx.cacheDir, "switch").apply { mkdirs() }, "${t.pkg}.apk")
            Forge.download(asset.url, file, { stopRequested }) { pct -> progress[t.repo] = pct; changed() }
            progress.remove(t.repo)
            @Suppress("DEPRECATION")
            val apk = ctx.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: throw IOException("${asset.name} isn't a valid APK")
            val apkCerts = certs(apk.signingInfo)
            if (apkCerts.isEmpty()) throw IOException("${asset.name} isn't signed")
            if (apk.packageName != t.pkg) throw IOException("${asset.name} is ${apk.packageName}, not ${t.pkg}")
            t.signer?.let { if (it !in apkCerts) throw IOException("${asset.name} isn't signed with the key the catalogue lists") }
            t.signer = t.signer ?: apkCerts.first()
            t.latestTag = rel.tag; t.error = null
            switching[t.pkg!!] = Switch(rel.tag, apk.longVersionCode, file)
            if (store.find(t.repo) == null) store.apps.add(t)
            store.save()
            busy[t.repo] = "Waiting for you to confirm the uninstall"; changed()
            val status = PendingIntent.getBroadcast(ctx, t.repo.hashCode(),
                Intent(ctx, InstallReceiver::class.java).putExtra("repo", t.repo).putExtra("uninstall", true),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
            ctx.packageManager.packageInstaller.uninstall(t.pkg!!, status)
        } catch (e: Exception) {
            busy.remove(t.repo)
            t.error = e.message ?: e.javaClass.simpleName
            store.save()
        } finally {
            progress.remove(t.repo)
        }
    }

    fun onUninstallResult(repo: String, status: Int, confirm: Intent?) {
        val t = store.find(repo) ?: return
        val pkg = t.pkg ?: return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {
                val s = switching.remove(pkg) ?: return
                busy[repo] = "Installing ${s.tag}…"
                Work.run { try { install(t, s.tag, s.code, s.file, gentle = false) } finally { s.file.delete() } }
            }
            else -> {
                switching.remove(pkg)?.file?.delete()
                busy.remove(repo)
                t.error = "Not uninstalled, so nothing changed"
                store.save()
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun install(t: Tracked, tag: String, code: Long, file: File, gentle: Boolean) {
        val pi = ctx.packageManager.packageInstaller
        val pkg = t.pkg!!
        pi.mySessions.filter { it.appPackageName == pkg }.forEach { runCatching { pi.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setSize(file.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
        }
        val status = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, InstallReceiver::class.java).putExtra("repo", t.repo).putExtra("tag", tag),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ).intentSender
        t.pendingTag = tag; t.pendingCode = code; store.save()
        // Android 14+: wait until the app isn't in use, but only works for apps we already install (else it throws)
        val mine = pkg == Store.SELF_PKG || installerOf(pkg) == ctx.packageName
        if (gentle && mine && Build.VERSION.SDK_INT >= 34) {
            try {
                pi.commitSessionAfterInstallConstraintsAreMet(id, status, PackageInstaller.InstallConstraints.GENTLE_UPDATE, 6 * 3600_000L)
                return
            } catch (_: Exception) { }
        }
        pi.openSession(id).use { it.commit(status) }
    }

    // ---- results from the system installer ------------------------------------------------------------------------

    fun onResult(repo: String, tag: String, status: Int, message: String?, confirm: Intent?) {
        val t = store.find(repo) ?: return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) needsTap += repo else { needsTap -= repo; busy.remove(repo) }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) return
                if (foreground) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else notify(t, "Tap to update ${t.name ?: t.pkg}", "Android wants you to confirm this one; later updates are automatic.",
                    PendingIntent.getActivity(ctx, repo.hashCode(), confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                t.handledTag = tag; t.pendingTag = null; t.error = null
                if (t.signer == null) t.signer = certs(installed(t.pkg)?.signingInfo).firstOrNull()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                t.pendingTag = null // asked and declined or still in use: try again on the next check
                if (status == PackageInstaller.STATUS_FAILURE_BLOCKED) t.error = message
            }
            else -> {
                t.pendingTag = null; t.error = message ?: "Install failed"
                if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE || status == PackageInstaller.STATUS_FAILURE_CONFLICT) t.handledTag = tag
            }
        }
        store.save()
    }

    fun notify(t: Tracked, title: String, text: String, tap: PendingIntent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", "Updates that need you", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(t.repo.hashCode(), Notification.Builder(ctx, "updates").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setContentIntent(tap).setAutoCancel(true).build())
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Work.init(c)
        val repo = i.getStringExtra("repo") ?: return
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
        if (i.getBooleanExtra("uninstall", false)) {
            synchronized(Work.store) { Work.onUninstallResult(repo, status, confirm) }
            Work.ping()
            return
        }
        val tag = i.getStringExtra("tag") ?: return
        // Right here rather than on the work thread, which may be busy with a long download
        synchronized(Work.store) { Work.onResult(repo, tag, status, msg, confirm) }
        Work.ping()
    }
}

/** Keeps the hourly check scheduled after a reboot or our own update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = UpdateJob.schedule(c)
}
