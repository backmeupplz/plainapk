package com.plainapk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** The one screen: banners for missing permissions, your apps, apps on this phone we could update, the catalogue. */
class MainActivity : Activity() {
    private class Row(
        val title: String,
        val subtitle: String? = null,
        val icon: Drawable? = null,
        val button: String? = null,
        val onButton: (() -> Unit)? = null,
        val onClick: (() -> Unit)? = null,
        val header: Boolean = false,
        val app: Boolean = icon != null, // shows an icon, the default one when the app isn't installed
    )

    private var rows = listOf<Row>()
    private lateinit var search: EditText
    private lateinit var list: ListView
    private val adapter = Adapter()
    private val refresh = { render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Work.init(this)
        UpdateJob.schedule(this)

        val title = TextView(this).apply {
            text = "Plain APK"; textSize = 22f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val check = Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
            text = "Check now"
            setOnClickListener { Work.stopRequested = false; Work.run { Work.checkAll(install = true) } }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), 0)
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(check)
        }
        search = EditText(this).apply {
            hint = "Search, or paste a GitHub, Forgejo or GitLab link"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = render()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        list = ListView(this).apply { adapter = this@MainActivity.adapter; divider = null; clipToPadding = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(4)) })
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        // Edge to edge: keep content clear of the status bar, navigation bar and keyboard
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, 0)
            list.setPadding(0, 0, 0, i.bottom + dp(8))
            insets
        }
        setContentView(root)
        Work.run { Work.checkAll(install = false) }
    }

    override fun onResume() {
        super.onResume()
        Work.foreground = true
        Work.listen(refresh)
        Work.run { Work.reconcile() }
        render()
    }

    override fun onPause() {
        super.onPause()
        Work.foreground = false
        Work.unlisten(refresh)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) = render()

    private fun render() {
        val store = Work.store
        val pm = packageManager
        val q = search.text.toString().trim()
        val url = Forge.normalize(q)?.takeIf { q.contains('/') }
        fun matches(vararg s: String?) = url != null || q.isEmpty() || s.any { it?.contains(q, true) == true }
        val out = mutableListOf<Row>()

        if (!pm.canRequestPackageInstalls()) out += Row("Allow installing apps", "Plain APK needs this to install and update anything.", button = "Allow",
            onButton = { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) })
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            out += Row("Allow notifications", "So an update that needs your tap can let you know.", button = "Allow",
                onButton = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) })

        if (url != null && store.find(url) == null) out += Row("Add ${url.removePrefix("https://")}", "Installs the APK from its latest release and keeps it updated.",
            button = "Add", onButton = { add(Tracked(url)) })

        val apps = store.apps.filter { matches(it.name, it.pkg, it.repo) }
        val updatable = apps.filter { newRelease(it) }
        out += Row("Your apps", header = true, button = if (updatable.size > 1) "Update all" else null, onButton = {
            Work.stopRequested = false
            Work.run { updatable.sortedBy { it.pkg == Store.SELF_PKG }.forEach { Work.check(it, install = true) } }
        })
        for (t in apps.sortedWith(compareBy({ !newRelease(it) }, { (it.name ?: it.repo).lowercase() }))) {
            val info = Work.installed(t.pkg)
            val status = Work.busy[t.repo] ?: t.error?.let { "⚠ $it" } ?: when {
                t.pendingTag != null -> "Installing ${t.pendingTag} when Android lets it (or waiting for your OK)"
                info == null -> "Not installed" + (t.latestTag?.let { " · $it" } ?: "")
                newRelease(t) -> "${info.versionName} → ${t.latestTag}"
                else -> "${info.versionName} · up to date"
            }
            out += Row(t.name ?: t.repo.substringAfterLast('/'), status, icon(t.pkg),
                button = when { Work.busy.containsKey(t.repo) -> null; info == null -> "Install"; newRelease(t) -> "Update"; else -> null },
                onButton = { Work.stopRequested = false; Work.run { Work.check(t, install = true, force = true) } },
                onClick = { menu(t) }, app = true)
        }

        val installed = pm.getInstalledPackages(0).map { it.packageName }.toSet()
        val untracked = store.catalogue.filter { store.findPkg(it.pkg) == null && store.find(it.repo) == null && matches(it.name, it.pkg, it.summary) }
        val onPhone = untracked.filter { it.pkg in installed }
        if (onPhone.isNotEmpty()) {
            val ok = onPhone.filter { canUpdate(it) }
            out += Row("On this phone", header = true, button = if (ok.size > 1) "Track all" else null, onButton = { ok.forEach { track(it) } })
            for (l in onPhone) out += if (canUpdate(l)) Row(l.name, "Can update from ${Forge.host(l.repo)}", icon(l.pkg), "Track", { track(l) })
            else Row(l.name, "Installed from somewhere with a different signature, so it can't be updated from ${Forge.host(l.repo)}", icon(l.pkg))
        }
        val rest = untracked.filter { it.pkg !in installed }
        if (rest.isNotEmpty() || store.catalogue.isEmpty()) out += Row("Catalogue", header = true)
        if (store.catalogue.isEmpty()) out += Work.catalogueError?.let { Row("Couldn't load the catalogue", "$it. Tap Check now to try again, or paste a repository link above.") }
            ?: Row("Loading the catalogue…", "Or paste a repository link above.")
        for (l in rest) out += Row(l.name, l.summary.ifEmpty { Forge.host(l.repo) }, button = "Install", onButton = { track(l) },
            onClick = { open(l.repo) }, app = true)

        rows = out
        adapter.notifyDataSetChanged()
    }

    private fun newRelease(t: Tracked) = Work.installed(t.pkg) != null && t.latestTag != null && t.latestTag != t.handledTag

    private fun canUpdate(l: Listing) = l.signer == null || l.signer in Work.certs(Work.installed(l.pkg)?.signingInfo)

    private fun icon(pkg: String?): Drawable? = pkg?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }

    private fun add(t: Tracked) {
        search.setText("")
        Work.stopRequested = false
        Work.run { Work.store.apps.add(t); Work.store.save(); Work.check(t, install = true) }
    }

    private fun track(l: Listing) = add(Tracked(l.repo, l.pkg, l.name, asset = l.asset, signer = l.signer))

    private fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    private fun menu(t: Tracked) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        packageManager.getLaunchIntentForPackage(t.pkg ?: "")?.let { i -> actions += "Open app" to { startActivity(i) } }
        actions += "Open repository" to { open(t.repo) }
        actions += (if (t.prerelease) "Stop installing prereleases" else "Install prereleases too") to {
            Work.run { t.prerelease = !t.prerelease; t.handledTag = null; Work.store.save(); Work.check(t, install = false) }
        }
        if (t.pkg != Store.SELF_PKG) actions += "Stop updating" to { Work.run { Work.store.apps.remove(t); Work.store.save() } }
        AlertDialog.Builder(this).setTitle(t.name ?: t.repo).setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p].header) 0 else 1
        override fun isEnabled(p: Int) = rows[p].onClick != null

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val r = rows[p]
            val v = (convert as? LinearLayout) ?: make(r.header)
            val icon = v.getChildAt(0) as ImageView
            val col = v.getChildAt(1) as LinearLayout
            val button = v.getChildAt(2) as Button
            icon.visibility = if (r.app) View.VISIBLE else View.GONE
            if (r.icon != null) icon.setImageDrawable(r.icon) else icon.setImageResource(android.R.drawable.sym_def_app_icon)
            (col.getChildAt(0) as TextView).text = r.title
            (col.getChildAt(1) as TextView).apply { text = r.subtitle; visibility = if (r.subtitle == null) View.GONE else View.VISIBLE }
            button.text = r.button
            button.visibility = if (r.button == null) View.GONE else View.VISIBLE
            button.setOnClickListener { r.onButton?.invoke() }
            v.setOnClickListener(r.onClick?.let { c -> View.OnClickListener { c() } })
            v.isClickable = r.onClick != null
            return v
        }

        private fun make(header: Boolean) = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), if (header) dp(20) else dp(8), dp(8), dp(8))
            if (!header) setBackgroundResource(android.R.drawable.list_selector_background)
            addView(ImageView(context), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    if (header) { textSize = 13f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAllCaps = true }
                    else { textSize = 16f }
                })
                addView(TextView(context).apply { textSize = 13f; alpha = 0.7f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored))
        }
    }
}
