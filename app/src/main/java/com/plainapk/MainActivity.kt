package com.plainapk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView

/** The one screen: missing permissions, updates, your apps, apps on this phone we could update, and the catalogue. */
class MainActivity : Activity() {
    private sealed class Row
    private class Header(val title: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Row()
    private class Banner(val title: String, val text: String, val button: String, val onDismiss: (() -> Unit)? = null, val onButton: () -> Unit) : Row()
    private class Note(val title: String, val text: String) : Row()
    private class Chips(val names: List<String>, val selected: String?) : Row()
    private class Item(
        val title: String,
        val subtitle: String,
        val icon: Drawable?,
        val seed: String,
        val button: String? = null,
        val filled: Boolean = false,
        val onButton: (() -> Unit)? = null,
        val onClick: (() -> Unit)? = null,
        val progress: Int? = null, // null: none, -1: indeterminate, 0..100
        val listing: Listing? = null, // catalogue entry whose icon to show once it has downloaded
        val accent: Boolean = false, // subtitle in the accent color ("update available")
        val warn: Boolean = false,
    ) : Row() {
        var first = false
        var last = false
    }

    private var rows = listOf<Row>()
    private var installedCache = emptySet<String>()
    private var installedAt = 0L
    private val certCache = HashMap<String, Set<String>>() // installed package → signing certs, dropped with installedCache
    private var category: String? = null
    private var topCategories = emptyList<String>()
    private var categoriesOf: List<Listing>? = null
    private val iconsChanged = Runnable { adapter.notifyDataSetChanged() }
    private val icons by lazy { Icons(this) { runOnUiThread { list.removeCallbacks(iconsChanged); list.postDelayed(iconsChanged, 120) } } }
    private lateinit var p: Palette
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var refreshButton: ImageView
    private lateinit var list: ListView
    private lateinit var frame: FrameLayout
    private val adapter = Adapter()
    private val refresh = { render() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Work.init(this)
        UpdateJob.schedule(this)
        p = Palette(this)
        window.decorView.setBackgroundColor(p.background)
        window.insetsController?.setSystemBarsAppearance(
            if (p.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val title = TextView(this).apply {
            text = s(R.string.app_name); textSize = 30f; setTextColor(p.onSurface)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        status = TextView(this).apply { textSize = 14f; setTextColor(p.muted); setPadding(0, dp(2), 0, 0) }
        refreshButton = iconButton(R.drawable.ic_refresh, s(R.string.check_for_updates)) {
            Work.stopRequested = false
            Work.run { Work.checkAll(install = true) }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(12), dp(16))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(status)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(refreshButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val clear = iconButton(R.drawable.ic_close, s(R.string.clear)) { search.setText("") }.apply { visibility = View.GONE }
        search = EditText(this).apply {
            hint = s(R.string.search_hint)
            setHintTextColor(p.muted); setTextColor(p.onSurface); textSize = 16f
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, _, _ -> getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0); v.clearFocus(); true }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE; render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val searchBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.field, dp(28f))
            setPadding(dp(18), 0, dp(4), 0)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(p.muted) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(search, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(12) })
            addView(clear, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            selector = ColorDrawable(0)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(searchBar, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(16), 0, dp(16), dp(4)) })
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        // Edge to edge: keep content clear of the status bar, navigation bar and keyboard
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, 0)
            list.setPadding(0, 0, 0, i.bottom + dp(24))
            insets
        }
        frame = FrameLayout(this).apply { addView(root) }
        setContentView(frame)
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

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(p.muted)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = label
        background = ripple(p, rounded(0, dp(24f)))
        setOnClickListener { onClick() }
    }

    // ---- what the list shows --------------------------------------------------------------------------------------

    private fun render() {
        val store = Work.store
        val pm = packageManager
        val q = search.text.toString().trim()
        val url = Forge.normalize(q)?.takeIf { q.contains('/') }
        // A pasted link shows just that repository; anything else searches names and descriptions
        fun matches(repo: String, vararg s: String?) = if (url != null) repo.equals(url, true) else q.isEmpty() || s.any { it?.contains(q, true) == true }
        val out = mutableListOf<Row>()

        if (!pm.canRequestPackageInstalls()) out += Banner(s(R.string.banner_install_title), s(R.string.banner_install_text), s(R.string.allow)) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            out += Banner(s(R.string.banner_notifications_title), s(R.string.banner_notifications_text), s(R.string.turn_on)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }

        if (url != null && store.find(url) == null && store.catalogue.none { it.repo.equals(url, true) }) {
            out += Header(s(R.string.add_from, Forge.host(url)))
            out += Item(url.substringAfter("://").substringAfter('/'), s(R.string.add_text),
                null, url.substringAfterLast('/'), s(R.string.add), filled = true, onButton = { add(Tracked(url)) })
        }

        val apps = store.apps.filter { matches(it.repo, it.name, it.pkg, it.repo) }
        val (updates, others) = apps.partition { newRelease(it) || it.pendingTag != null }
        if (updates.isNotEmpty()) {
            val ready = updates.filter { !Work.busy.containsKey(it.repo) && it.pendingTag == null }
            out += Header(plural(R.plurals.updates_header, updates.size), if (ready.size > 1) s(R.string.update_all) else null) {
                Work.stopRequested = false
                Work.run { ready.sortedBy { it.pkg == Store.SELF_PKG }.forEach { Work.check(it, install = true, force = true) } }
            }
            updates.sortedBy { label(it).lowercase() }.forEach { out += appItem(it) }
        }
        if (others.isNotEmpty()) {
            out += Header(s(R.string.your_apps))
            others.sortedWith(compareBy({ Work.installed(it.pkg) == null }, { label(it).lowercase() })).forEach { out += appItem(it) }
        }

        // The installed list is costly, so it's read at most every few seconds rather than on every keystroke or percent
        if (System.currentTimeMillis() - installedAt > 3000) {
            installedCache = pm.getInstalledPackages(0).map { it.packageName }.toSet(); installedAt = System.currentTimeMillis()
            certCache.clear()
        }
        val installed = installedCache
        val untracked = store.catalogue.filter { store.findPkg(it.pkg) == null && store.find(it.repo) == null && matches(it.repo, it.name, it.pkg, it.summary) }
        val onPhone = untracked.filter { it.pkg in installed }
        if (onPhone.isNotEmpty()) {
            val ok = onPhone.filter { canUpdate(it) }
            out += Header(s(R.string.found_on_phone), if (ok.size > 1) s(R.string.keep_all_updated) else null) { ok.forEach { track(it) } }
            onPhone.forEach { l ->
                out += if (canUpdate(l)) Item(l.name, s(R.string.updates_on, Forge.host(l.repo)), icon(l.pkg), l.name, s(R.string.keep_updated), onButton = { track(l) }, onClick = { listing(l) })
                else Item(l.name, s(R.string.installed_from_signed_differently, installerName(l.pkg)), icon(l.pkg), l.name, s(R.string.switch_button), onButton = { listing(l) }, onClick = { listing(l) })
            }
        }

        // Discover: newest releases first (the catalogue's order), narrowed by a category chip
        if (categoriesOf !== store.catalogue) {
            categoriesOf = store.catalogue
            topCategories = store.catalogue.flatMap { it.categories }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(14).map { it.key }
        }
        val rest = untracked.filter { it.pkg !in installed && (category == null || category in it.categories) }
        if (rest.isNotEmpty() || store.catalogue.isEmpty() || category != null) {
            out += Header(if (store.catalogue.size > 100 && q.isEmpty()) plural(R.plurals.discover_count, store.catalogue.size) else s(R.string.discover))
            if (topCategories.isNotEmpty() && url == null) out += Chips(topCategories, category)
        }
        if (store.catalogue.isEmpty()) out += Work.catalogueError
            ?.let { Note(s(R.string.catalogue_error_title), s(R.string.catalogue_error_text, it)) }
            ?: Note(s(R.string.catalogue_loading_title), s(R.string.catalogue_loading_text))
        rest.forEach { l -> out += Item(l.name, l.summary.ifEmpty { Forge.host(l.repo) }, null, l.name, s(R.string.install), onButton = { track(l) }, onClick = { listing(l) }, listing = l) }

        if (out.none { it is Item } && q.isNotEmpty()) out += Note(s(R.string.nothing_found_title, q), s(R.string.nothing_found_text))

        // Rows between headers form one rounded group
        out.forEachIndexed { i, r ->
            if (r !is Item) return@forEachIndexed
            r.first = out.getOrNull(i - 1) !is Item
            r.last = out.getOrNull(i + 1) !is Item
        }
        rows = out
        adapter.notifyDataSetChanged()

        val busy = Work.busy.isNotEmpty()
        val n = store.apps.count { newRelease(it) }
        status.text = when {
            busy -> s(R.string.status_checking)
            n > 0 -> plural(R.plurals.status_updates, n)
            Work.lastCheck > 0 -> s(R.string.status_up_to_date, ago(this, Work.lastCheck))
            else -> s(R.string.status_intro)
        }
        refreshButton.isEnabled = !busy
        refreshButton.alpha = if (busy) 0.4f else 1f
    }

    private fun s(id: Int, vararg args: Any): String = getString(id, *args)

    /** A catalogue category in the phone's language when it's one of the common ones. */
    private fun categoryName(c: String) = categoryNames[c]?.let { s(it) } ?: c
    private val categoryNames = mapOf(
        "System" to R.string.cat_system,
        "Internet" to R.string.cat_internet,
        "Multimedia" to R.string.cat_multimedia,
        "Connectivity" to R.string.cat_connectivity,
        "Science & Education" to R.string.cat_science_education,
        "Security" to R.string.cat_security,
        "Sports & Health" to R.string.cat_sports_health,
        "Writing" to R.string.cat_writing,
        "Reading" to R.string.cat_reading,
        "Navigation" to R.string.cat_navigation,
        "Development" to R.string.cat_development,
        "Graphics" to R.string.cat_graphics,
        "Phone & SMS" to R.string.cat_phone_sms,
        "Messaging" to R.string.cat_messaging,
        "Note" to R.string.cat_note,
        "Puzzle Game" to R.string.cat_puzzle_game,
        "Task" to R.string.cat_task,
        "Time" to R.string.cat_time,
        "Finance Manager" to R.string.cat_finance_manager,
        "Theming" to R.string.cat_theming,
        "VPN & Proxy" to R.string.cat_vpn_proxy,
        "Online Media Player" to R.string.cat_online_media_player,
        "Timer" to R.string.cat_timer,
        "Office" to R.string.cat_office,
        "Keyboard & IME" to R.string.cat_keyboard_ime,
        "Calendar & Agenda" to R.string.cat_calendar_agenda,
        "Game Helper" to R.string.cat_game_helper,
        "Launcher" to R.string.cat_launcher,
        "Password & 2FA" to R.string.cat_password_2fa,
        "Games" to R.string.cat_games,
        "Money" to R.string.cat_money,
    )
    private fun plural(id: Int, n: Int): String = resources.getQuantityString(id, n, n)

    private fun label(t: Tracked) = t.name ?: t.repo.substringAfterLast('/')

    private fun appItem(t: Tracked): Item {
        val info = Work.installed(t.pkg)
        val busy = Work.busy[t.repo]
        val pct = Work.progress[t.repo]
        val subtitle = when {
            busy != null && pct != null && pct >= 0 -> s(R.string.busy_percent, busy, pct)
            busy != null -> "$busy…"
            t.error != null -> t.error!!
            Work.foreign(t) -> s(R.string.installed_from_signed_differently_than, installerName(t.pkg), Forge.host(t.repo))
            t.pendingTag != null && t.repo in Work.needsTap -> s(R.string.waiting_for_ok, t.pendingTag!!)
            t.pendingTag != null -> s(R.string.installing_when_free, t.pendingTag!!)
            info == null -> t.latestTag?.let { s(R.string.not_installed_available, it) } ?: s(R.string.not_installed)
            newRelease(t) -> "${info.versionName} → ${t.latestTag}"
            else -> "${info.versionName}"
        }
        val foreign = busy == null && Work.foreign(t)
        val update = busy == null && !foreign && info != null && (newRelease(t) || t.pendingTag != null)
        val button = when {
            busy != null -> null
            foreign -> s(R.string.switch_button)
            info == null -> s(R.string.install)
            update -> s(R.string.update)
            else -> null
        }
        return Item(label(t), subtitle, icon(t.pkg), label(t), button, filled = update,
            onButton = { if (foreign) sheet(t) else { Work.stopRequested = false; Work.run { Work.check(t, install = true, force = true) } } },
            onClick = { sheet(t) }, progress = if (busy != null && pct != null) pct else null,
            accent = busy == null && t.error == null && newRelease(t), warn = busy == null && t.error != null)
    }

    private fun newRelease(t: Tracked) = Work.installed(t.pkg) != null && t.latestTag != null && t.latestTag != t.handledTag

    private fun canUpdate(l: Listing) = l.signer == null ||
        l.signer in certCache.getOrPut(l.pkg) { Work.certs(Work.installed(l.pkg)?.signingInfo) }

    private fun icon(pkg: String?): Drawable? = pkg?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }

    private fun add(t: Tracked) {
        search.setText("")
        Work.stopRequested = false
        Work.run { Work.store.apps.add(t); Work.store.save(); Work.check(t, install = true) }
    }

    private fun track(l: Listing) = add(Tracked(l.repo, l.pkg, l.name, asset = l.asset, signer = l.signer))

    private fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    // ---- detail sheets --------------------------------------------------------------------------------------------

    /** A sheet drawn over the list inside this window (edge to edge, unlike a Dialog's window); back or a tap above closes it. */
    private inner class Sheet {
        lateinit var layer: View
        private val back: Any? = if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { dismiss() } else null
        fun show() {
            frame.addView(layer)
            openSheet = this
            layer.alpha = 0f; layer.animate().alpha(1f).setDuration(150).start()
            if (Build.VERSION.SDK_INT >= 33)
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, back as android.window.OnBackInvokedCallback)
        }
        fun dismiss() {
            if (openSheet !== this) return
            openSheet = null
            if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back as android.window.OnBackInvokedCallback)
            layer.animate().alpha(0f).setDuration(120).withEndAction { frame.removeView(layer) }.start()
        }
    }

    private var openSheet: Sheet? = null

    @Deprecated("Android 12 only; 13+ uses the OnBackInvokedCallback above")
    @android.annotation.SuppressLint("GestureBackNavigation") // only Android 12 lands here, which has no OnBackInvokedDispatcher
    override fun onBackPressed() {
        openSheet?.dismiss() ?: @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun bottomSheet(build: LinearLayout.(Sheet) -> Unit) {
        val d = Sheet()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.surface, dp(28f), dp(28f), 0f, 0f)
            setPadding(dp(24), dp(12), dp(24), dp(24))
            addView(View(context).apply { background = rounded(p.muted, dp(2f)); alpha = 0.4f },
                LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20) })
            build(d)
        }
        box.setPadding(dp(24), dp(12), dp(24), dp(24) + (frame.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0))
        d.layer = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { d.dismiss() }
            addView(box.apply { isClickable = true }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        d.show()
    }

    private fun LinearLayout.sheetHeader(title: String, subtitle: String, icon: Drawable?) {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageDrawable(icon ?: letter(title)) }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(TextView(context).apply { text = title; textSize = 22f; setTextColor(p.onSurface); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(TextView(context).apply { text = subtitle; textSize = 14f; setTextColor(p.muted) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun LinearLayout.sheetText(text: String, color: Int = p.muted) = addView(TextView(context).apply {
        this.text = text; textSize = 15f; setTextColor(color); setLineSpacing(0f, 1.15f)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

    private fun LinearLayout.sheetAction(label: String, color: Int = p.onSurface, onClick: () -> Unit) = addView(TextView(context).apply {
        text = label; textSize = 16f; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        background = ripple(p, rounded(0, dp(12f)))
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, dp(52)))

    private fun LinearLayout.sheetButtons(vararg buttons: Pair<Pair<String, Boolean>, () -> Unit>) = addView(LinearLayout(context).apply {
        gravity = Gravity.END
        buttons.forEach { (b, onClick) ->
            addView(context.pill(p, b.second).apply { text = b.first; setOnClickListener { onClick() } },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })

    private fun sheet(t: Tracked) = bottomSheet { d ->
        val info = Work.installed(t.pkg)
        sheetHeader(label(t), t.repo.substringAfter("://"), icon(t.pkg))
        val lines = mutableListOf<String>()
        lines += if (info != null) s(R.string.sheet_installed, info.versionName ?: "") else s(R.string.not_installed)
        t.latestTag?.let { lines += s(if (t.prerelease) R.string.sheet_latest_pre else R.string.sheet_latest, it) }
        if (t.pkg == Store.SELF_PKG) lines += s(R.string.sheet_self)
        sheetText(lines.joinToString("\n"))
        val foreign = Work.foreign(t)
        if (foreign) signatures(t.pkg!!, t.signer, Forge.host(t.repo)) else t.error?.let { sheetText(it, p.error) }
        val buttons = mutableListOf<Pair<Pair<String, Boolean>, () -> Unit>>()
        packageManager.getLaunchIntentForPackage(t.pkg ?: "")?.takeIf { t.pkg != Store.SELF_PKG }?.let { i -> buttons += (s(R.string.open) to false) to { d.dismiss(); startActivity(i) } }
        if (foreign) buttons += (s(R.string.switch_button) to true) to { d.dismiss(); confirmSwitch(t) }
        else if (info == null || newRelease(t)) buttons += (s(if (info == null) R.string.install else R.string.update) to true) to {
            d.dismiss(); Work.stopRequested = false; Work.run { Work.check(t, install = true, force = true) }
        }
        if (buttons.isNotEmpty()) sheetButtons(*buttons.toTypedArray())
        sheetAction(s(R.string.view_source, Forge.host(t.repo))) { d.dismiss(); open(t.repo) }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            addView(TextView(context).apply { text = s(R.string.include_prereleases); textSize = 16f; setTextColor(p.onSurface) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(context).apply {
                isChecked = t.prerelease
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.onPrimary, p.muted))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.primary, (p.muted and 0xFFFFFF) or 0x55000000))
                setOnCheckedChangeListener { _, on -> Work.run { t.prerelease = on; t.handledTag = null; Work.store.save(); Work.check(t, install = false) } }
            })
        }, LinearLayout.LayoutParams(-1, dp(52)))
        if (t.pkg != Store.SELF_PKG) sheetAction(s(R.string.stop_updating), p.error) {
            d.dismiss(); Work.run { Work.store.apps.remove(t); Work.store.save() }
        }
    }

    /** Who installed [pkg]: "Aurora Store", "Google Play Store", or "a file or adb" when nobody is recorded. */
    private fun installerName(pkg: String?): String {
        val by = pkg?.let { Work.installerOf(it) } ?: return s(R.string.installer_unknown)
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(by, 0)).toString() }.getOrDefault(by)
    }

    /** The evidence: which keys signed the copy on the phone and the repository's build. */
    private fun LinearLayout.signatures(pkg: String, expected: String?, host: String) {
        val have = Work.certs(Work.installed(pkg)?.signingInfo)
        fun short(h: String) = h.take(16).chunked(2).joinToString(":").uppercase() + "…"
        sheetText(s(R.string.signatures, installerName(pkg), have.joinToString { short(it) }, host, expected?.let { short(it) } ?: s(R.string.unknown)))
    }

    private fun confirmSwitch(t: Tracked) = bottomSheet { d ->
        val name = label(t)
        val host = Forge.host(t.repo)
        sheetHeader(s(R.string.switch_title, name, host), t.repo.substringAfter("://"), icon(t.pkg))
        sheetText(s(R.string.switch_steps, name, host))
        sheetText(s(R.string.switch_warning, name), p.error)
        sheetButtons((s(R.string.cancel) to false) to { d.dismiss() }, (s(R.string.switch_confirm) to true) to {
            d.dismiss(); Work.stopRequested = false; Work.run { Work.switchSource(t) }
        })
    }

    private fun listing(l: Listing) = bottomSheet { d ->
        val here = Work.installed(l.pkg) != null
        sheetHeader(l.name, l.repo.substringAfter("://"), icon(l.pkg) ?: icons.get(l))
        if (l.summary.isNotEmpty()) sheetText(l.summary, p.onSurface)
        sheetText(when {
            !here -> s(R.string.listing_install, Forge.host(l.repo))
            canUpdate(l) -> s(R.string.listing_can_update)
            else -> s(R.string.listing_signed_differently, installerName(l.pkg), Forge.host(l.repo))
        })
        if (here && !canUpdate(l)) signatures(l.pkg, l.signer, Forge.host(l.repo))
        val buttons = mutableListOf<Pair<Pair<String, Boolean>, () -> Unit>>()
        if (!here) buttons += (s(R.string.install) to true) to { d.dismiss(); track(l) }
        else if (canUpdate(l)) buttons += (s(R.string.keep_updated) to true) to { d.dismiss(); track(l) }
        else buttons += (s(R.string.switch_button) to true) to { d.dismiss(); confirmSwitch(Tracked(l.repo, l.pkg, l.name, asset = l.asset, signer = l.signer)) }
        if (buttons.isNotEmpty()) sheetButtons(*buttons.toTypedArray())
        sheetAction(s(R.string.view_source, Forge.host(l.repo))) { d.dismiss(); open(l.repo) }
    }

    private fun letter(seed: String): Drawable {
        val (bg, fg) = p.avatar(seed)
        return LetterDrawable(seed.trim().take(1).uppercase().ifEmpty { "?" }, bg, fg)
    }

    // ---- rows -----------------------------------------------------------------------------------------------------

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getViewTypeCount() = 5
        override fun getItemViewType(i: Int) = when (rows[i]) { is Header -> 0; is Item -> 1; is Banner -> 2; is Note -> 3; is Chips -> 4 }
        override fun areAllItemsEnabled() = false
        override fun isEnabled(i: Int) = false

        override fun getView(i: Int, convert: View?, parent: ViewGroup): View = when (val r = rows[i]) {
            is Header -> header(r, convert)
            is Item -> item(r, convert)
            is Banner -> banner(r, convert)
            is Note -> note(r, convert)
            is Chips -> chips(r, convert)
        }

        private fun header(r: Header, convert: View?): View {
            val v = convert as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(28), dp(20), dp(16), dp(8))
                addView(TextView(context).apply { textSize = 14f; setTextColor(p.primary); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply {
                    textSize = 14f; setTextColor(p.primary); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(12), dp(6), dp(12), dp(6)); background = ripple(p, rounded(0, dp(16f)))
                })
            }
            (v.getChildAt(0) as TextView).text = r.title
            (v.getChildAt(1) as TextView).apply {
                text = r.action; visibility = if (r.action == null) View.GONE else View.VISIBLE
                setOnClickListener { r.onAction?.invoke() }
            }
            return v
        }

        private fun item(r: Item, convert: View?): View {
            val v = convert as? FrameLayout ?: FrameLayout(this@MainActivity).apply {
                setPadding(dp(16), 0, dp(16), dp(2))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(76)
                    setPadding(dp(16), dp(12), dp(12), dp(12))
                    // Catalogue icons are plain squares, so round them like the launcher's
                    addView(ImageView(context).apply {
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * 0.28f)
                        }
                        clipToOutline = true
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), 0, dp(8), 0)
                        addView(TextView(context).apply {
                            textSize = 16f; setTextColor(p.onSurface); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                        })
                        addView(TextView(context).apply { textSize = 14f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
                        addView(ProgressBar(context, null, 0, android.R.style.Widget_Material_ProgressBar_Horizontal).apply {
                            progressTintList = ColorStateList.valueOf(p.primary)
                            progressBackgroundTintList = ColorStateList.valueOf(p.field)
                            indeterminateTintList = ColorStateList.valueOf(p.primary)
                        }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(context.pill(p, false))
                }, FrameLayout.LayoutParams(-1, -2))
            }
            val card = v.getChildAt(0) as LinearLayout
            val big = dp(24f); val small = dp(6f)
            card.background = ripple(p, rounded(p.surface, if (r.first) big else small, if (r.first) big else small, if (r.last) big else small, if (r.last) big else small))
            card.setOnClickListener(r.onClick?.let { c -> View.OnClickListener { c() } })
            card.isClickable = r.onClick != null
            (card.getChildAt(0) as ImageView).setImageDrawable(r.icon ?: r.listing?.let { icons.get(it) } ?: letter(r.seed))
            val col = card.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = r.title
            (col.getChildAt(1) as TextView).apply {
                text = r.subtitle
                setTextColor(when { r.warn -> p.error; r.accent -> p.primary; else -> p.muted })
            }
            (col.getChildAt(2) as ProgressBar).apply {
                visibility = if (r.progress == null) View.GONE else View.VISIBLE
                isIndeterminate = r.progress == -1
                if (r.progress != null && r.progress >= 0) progress = r.progress
            }
            (card.getChildAt(2) as TextView).apply {
                text = r.button
                visibility = if (r.button == null) View.GONE else View.VISIBLE
                style(p, r.filled)
                setOnClickListener { r.onButton?.invoke() }
            }
            return v
        }

        private fun banner(r: Banner, convert: View?): View {
            val v = convert as? FrameLayout ?: FrameLayout(this@MainActivity).apply {
                setPadding(dp(16), dp(12), dp(16), dp(4))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(p.primaryContainer, dp(24f))
                    setPadding(dp(20), dp(18), dp(20), dp(16))
                    addView(TextView(context).apply { textSize = 17f; setTextColor(p.onPrimaryContainer); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                    addView(TextView(context).apply { textSize = 14f; setTextColor(p.onPrimaryContainer); alpha = 0.85f; setPadding(0, dp(4), 0, dp(12)) })
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.END
                        addView(TextView(context).apply {
                            text = s(R.string.not_now); textSize = 14f; setTextColor(p.onPrimaryContainer); gravity = Gravity.CENTER
                            minHeight = dp(40); setPadding(dp(16), 0, dp(16), 0); background = ripple(p, rounded(0, dp(20f)))
                        })
                        addView(context.pill(p, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                    }, LinearLayout.LayoutParams(-1, -2))
                })
            }
            val box = v.getChildAt(0) as LinearLayout
            (box.getChildAt(0) as TextView).text = r.title
            (box.getChildAt(1) as TextView).text = r.text
            val buttons = box.getChildAt(2) as LinearLayout
            (buttons.getChildAt(0) as TextView).apply {
                visibility = if (r.onDismiss == null) View.GONE else View.VISIBLE
                setOnClickListener { r.onDismiss?.invoke() }
            }
            (buttons.getChildAt(1) as TextView).apply { text = r.button; setOnClickListener { r.onButton() } }
            return v
        }

        private fun chips(r: Chips, convert: View?): View {
            val v = convert as? android.widget.HorizontalScrollView ?: android.widget.HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(16), dp(4), dp(16), dp(10))
                addView(LinearLayout(context))
            }
            val row = v.getChildAt(0) as LinearLayout
            row.removeAllViews()
            (listOf<String?>(null) + r.names).forEach { name ->
                row.addView(pill(p, name == r.selected).apply {
                    text = name?.let { categoryName(it) } ?: s(R.string.all_categories)
                    minHeight = dp(36)
                    setOnClickListener { category = name; render() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            return v
        }

        private fun note(r: Note, convert: View?): View {
            val v = convert as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(12), dp(28), dp(12))
                addView(TextView(context).apply { textSize = 16f; setTextColor(p.onSurface) })
                addView(TextView(context).apply { textSize = 14f; setTextColor(p.muted); setPadding(0, dp(4), 0, 0) })
            }
            (v.getChildAt(0) as TextView).text = r.title
            (v.getChildAt(1) as TextView).text = r.text
            return v
        }
    }
}
