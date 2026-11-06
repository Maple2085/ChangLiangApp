package com.changliang.screenon

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity(), IntroView.Host {

    companion object {
        private const val REQ_NOTIFY = 73
        private const val PREFS = "keep_on_ui"
        private const val KEY_GEAR = "selected_minutes"
        private const val KEY_INTRO_PLAYED = "intro_played"
        private const val KEY_INTRO_REPLAY = "intro_replay_next"
        private const val KEY_NOTIFY_ASKED = "notify_asked"
        private const val KEY_CUSTOM = "custom_minutes"
        private const val KEY_GEAR_CUSTOM = "gear_is_custom"
        private const val CUSTOM = GearStripView.CUSTOM

        /** 挡位 → 分钟；-1 表示 ∞ */
        private val GEARS = intArrayOf(5, 10, 15, 20, 30, 60, 120, -1)
        /** 表盘刻度 0..8 对应的分钟数 */
        private val TICKS = floatArrayOf(0f, 5f, 10f, 15f, 20f, 30f, 60f, 120f)

        /** 剩余分钟 → 表盘位置（分段线性，和刻度一致） */
        fun needleFor(minutes: Float): Float {
            if (minutes <= 0f) return 0f
            for (i in 1 until TICKS.size) {
                if (minutes <= TICKS[i]) {
                    return (i - 1) + (minutes - TICKS[i - 1]) / (TICKS[i] - TICKS[i - 1])
                }
            }
            // 2 小时以上：落在 2H 与 ∞ 之间的红线区，越长越靠近 ∞，但不碰到
            val over = ((minutes - 120f) / (RulerView.MAX - 120f)).coerceIn(0f, 1f)
            return 7f + 0.8f * over
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var bg: CarbonBackgroundView
    private lateinit var content: ViewGroup
    private lateinit var header: View
    private lateinit var accent: View
    private lateinit var settingsBtn: View
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var gaugeView: GaugeView
    private lateinit var telltales: View
    private lateinit var ttOverlay: TextView
    private lateinit var ttNotify: TextView
    private lateinit var ttEngine: TextView
    private lateinit var gearHeader: View
    private lateinit var gears: GearStripView
    private lateinit var ignition: IgnitionButton
    private lateinit var footer: TextView
    private lateinit var settingsLayer: View
    private lateinit var scrim: View
    private lateinit var sheet: View
    private lateinit var introToggle: ToggleView
    private lateinit var overlayState: TextView
    private lateinit var notifyState: TextView
    private lateinit var aboutState: TextView
    private lateinit var pitLayer: View
    private lateinit var pitScrim: View
    private lateinit var pitSheet: View
    private lateinit var pitValue: TextView
    private lateinit var pitUnit: TextView
    private lateinit var pitSub: TextView
    private lateinit var pitConfirm: TextView
    private lateinit var ruler: RulerView
    private lateinit var pitChips: List<Pair<TextView, Int>>

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var gearIndex = 4
    private var overlayOk = false
    private var notifyOk = false
    private var chromeKey = ""
    private var intro: IntroView? = null
    private var settingsOpen = false
    private var overlaySettingsOpen = false
    private var fatalShown = false
    private var startAttempt = 0
    private var customMinutes = 0
    private var pitOpen = false
    /** 调表板打开时表盘预览的值；null 表示不预览 */
    private var pitPreview: Int? = null

    override val gauge get() = gaugeView
    override val titleView get() = title
    override val subtitleView get() = subtitle

    private val ticker = object : Runnable {
        override fun run() {
            if (fatalShown) return
            try { render() } catch (t: Throwable) { showFatal(t); return }
            handler.postDelayed(this, 200L)
        }
    }

    // ---------------- 生命周期 ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { setup(savedInstanceState) } catch (t: Throwable) { showFatal(t) }
    }

    private fun setup(saved: Bundle?) {
        configureWindow()
        setContentView(R.layout.activity_main)
        configureSystemBars()
        bind()
        setupInsets()

        customMinutes = prefs.getInt(KEY_CUSTOM, 0).coerceIn(0, RulerView.MAX)
        gears.setCustom(customMinutes)
        val savedMinutes = prefs.getInt(KEY_GEAR, 30)
        gearIndex = if (prefs.getBoolean(KEY_GEAR_CUSTOM, false) && customMinutes > 0) CUSTOM
            else GEARS.indexOf(savedMinutes).takeIf { it >= 0 } ?: 4
        gears.setSelected(gearIndex, false)
        overlayOk = Settings.canDrawOverlays(this)
        notifyOk = notificationsEnabled()

        gears.onPick = { pickGear(it) }
        gears.onCustomTap = { already ->
            when {
                KeepOnService.running -> {}
                customMinutes <= 0 || already -> openPit()
                else -> { gears.setSelected(CUSTOM, true); pickGear(CUSTOM) }
            }
        }
        ignition.onTrigger = { onIgnition() }
        ignition.onHoldCancel = {
            toast(if (selectedMinutes() < 0) "∞ 档需要按住点火，防止误触" else "超过 2 小时需要按住点火，防止误触")
        }
        setupPit()
        settingsBtn.setOnClickListener { openSettings() }
        ttOverlay.setOnClickListener { if (!overlayOk) openOverlaySettings() }
        ttNotify.setOnClickListener { if (!notifyOk) requestNotify(false) }
        scrim.setOnClickListener { closeSettings() }
        findViewById<View>(R.id.closeBtn).setOnClickListener { closeSettings() }
        findViewById<View>(R.id.rowIntro).setOnClickListener { introToggle.performClick() }
        introToggle.onChange = { prefs.edit().putBoolean(KEY_INTRO_REPLAY, it).apply() }
        findViewById<View>(R.id.rowOverlay).setOnClickListener { openOverlaySettings() }
        findViewById<View>(R.id.rowNotify).setOnClickListener { requestNotify(true) }
        aboutState.text = "v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" })

        render()
        // 入场前先把指针放到最终位置，入场动画回落时读的就是它
        gaugeView.setNeedle(targetNeedle(), animate = false)

        val wantIntro = saved == null && ValueAnimator.areAnimatorsEnabled() &&
            (!prefs.getBoolean(KEY_INTRO_PLAYED, false) || prefs.getBoolean(KEY_INTRO_REPLAY, false))
        if (wantIntro) playIntro() else if (saved == null) quickEntrance()

        content.viewTreeObserver.addOnGlobalLayoutListener {
            val loc = IntArray(2); val rl = IntArray(2)
            gaugeView.getLocationInWindow(loc); root.getLocationInWindow(rl)
            bg.setGlowCenter(loc[0] - rl[0] + gaugeView.gaugeCx, loc[1] - rl[1] + gaugeView.gaugeCy)
        }
    }

    override fun onResume() {
        super.onResume()
        if (fatalShown) return
        try {
            val nowOverlay = Settings.canDrawOverlays(this)
            if (overlaySettingsOpen) {
                overlaySettingsOpen = false
                if (nowOverlay && !overlayOk) toast("悬浮窗权限已开启 · SYSTEM READY")
            }
            overlayOk = nowOverlay
            notifyOk = notificationsEnabled()
            if (!overlayOk && KeepOnService.running) {
                stopService(Intent(this, KeepOnService::class.java))
                toast("悬浮窗权限被关闭，已熄火")
            }
            chromeKey = ""
            gaugeView.setHostResumed(true)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            if (settingsOpen) refreshSettingsRows()
        } catch (t: Throwable) { showFatal(t) }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        if (::gaugeView.isInitialized) gaugeView.setHostResumed(false)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        intro?.let { it.skip(); return }
        if (pitOpen) { closePit(false); return }
        if (settingsOpen) { closeSettings(); return }
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != REQ_NOTIFY) return
        notifyOk = notificationsEnabled()
        chromeKey = ""
        if (pendingStart) { pendingStart = false; startKeepOn() }
        if (settingsOpen) refreshSettingsRows()
    }

    private fun bind() {
        root = findViewById(R.id.root)
        bg = findViewById(R.id.bg)
        content = findViewById(R.id.content)
        header = findViewById(R.id.header)
        accent = findViewById(R.id.accent)
        settingsBtn = findViewById(R.id.settingsBtn)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        gaugeView = findViewById(R.id.gauge)
        telltales = findViewById(R.id.telltales)
        ttOverlay = findViewById(R.id.ttOverlay)
        ttNotify = findViewById(R.id.ttNotify)
        ttEngine = findViewById(R.id.ttEngine)
        gearHeader = findViewById(R.id.gearHeader)
        gears = findViewById(R.id.gears)
        ignition = findViewById(R.id.ignition)
        footer = findViewById(R.id.footer)
        settingsLayer = findViewById(R.id.settingsLayer)
        scrim = findViewById(R.id.scrim)
        sheet = findViewById(R.id.sheet)
        introToggle = findViewById(R.id.introToggle)
        overlayState = findViewById(R.id.overlayState)
        notifyState = findViewById(R.id.notifyState)
        aboutState = findViewById(R.id.aboutState)
        pitLayer = findViewById(R.id.pitLayer)
        pitScrim = findViewById(R.id.pitScrim)
        pitSheet = findViewById(R.id.pitSheet)
        pitValue = findViewById(R.id.pitValue)
        pitUnit = findViewById(R.id.pitUnit)
        pitSub = findViewById(R.id.pitSub)
        pitConfirm = findViewById(R.id.pitConfirm)
        ruler = findViewById(R.id.ruler)
        pitChips = listOf(
            findViewById<TextView>(R.id.pitQ1) to 45, findViewById<TextView>(R.id.pitQ2) to 90,
            findViewById<TextView>(R.id.pitQ3) to 180, findViewById<TextView>(R.id.pitQ4) to 360
        )
    }

    // ---------------- 窗口 ----------------

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setBackgroundDrawable(ColorDrawable(Pal.BG))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    }

    /** 必须在 setContentView 之后：Android 16 上 DecorView 未创建时取 insetsController 会空指针 */
    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun setupInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = b.top; bottom = b.bottom
            } else {
                top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom
            }
            content.setPadding(dp(20f), top + dp(4f), dp(20f), bottom + dp(18f))
            sheet.setPadding(dp(20f), dp(10f), dp(20f), bottom + dp(20f))
            pitSheet.setPadding(dp(20f), dp(10f), dp(20f), bottom + dp(20f))
            insets
        }
        root.requestApplyInsets()
    }

    // ---------------- 渲染 ----------------

    private fun selectedMinutes() = if (gearIndex == CUSTOM) customMinutes else GEARS[gearIndex]

    /** 表盘要显示的待机时长：调表板打开时预览尺子上的值 */
    private fun displayMinutes() = pitPreview ?: selectedMinutes()

    /** ∞ 或超过 2 小时：按住点火 */
    private fun needsHold(m: Int) = m < 0 || m > 120

    private fun targetNeedle(): Float = when {
        KeepOnService.running && KeepOnService.alwaysOn -> 7.85f
        KeepOnService.running -> needleFor((KeepOnService.endAt - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000f)
        displayMinutes() < 0 -> 7.85f
        else -> needleFor(displayMinutes().toFloat())
    }

    private fun render() {
        val s = gaugeView.state
        val running = KeepOnService.running
        val always = KeepOnService.alwaysOn
        var flutter = false
        var pulse = false

        if (running && always) {
            s.label = "NO LIMIT"; s.value = "∞"; s.valueScale = 1f
            s.sub = "直到你手动熄火"; s.mode = 3; s.alert = true; s.glow = 0.9f
            flutter = true
        } else if (running) {
            val remain = (KeepOnService.endAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val secs = (remain + 999L) / 1000L
            s.label = "STINT"
            s.value = fmt(secs)
            s.valueScale = if (secs >= 3600) 0.74f else 1f
            s.sub = "预计 " + clock(KeepOnService.endAt) + " 熄火"
            s.mode = 2
            s.alert = secs <= 60
            s.glow = 0.75f
            pulse = secs <= 60
        } else {
            val m = displayMinutes()
            s.label = if (pitPreview != null) "PIT SET" else if (overlayOk) "READY" else "PRE-FLIGHT"
            s.value = if (m < 0) "∞" else fmt(m * 60L)
            s.valueScale = if (m >= 60) 0.74f else 1f
            s.sub = when {
                !overlayOk -> "先开启悬浮窗权限"
                m < 0 -> "不自动熄火"
                else -> "点火后 " + clock(System.currentTimeMillis() + m * 60_000L) + " 熄火"
            }
            s.mode = if (overlayOk) 1 else 0
            s.alert = !overlayOk
            s.glow = if (overlayOk) 0.35f else 0.15f
        }
        gaugeView.setNeedle(targetNeedle(), animate = intro == null)
        gaugeView.setEffects(flutter, pulse)
        gaugeView.invalidate()

        val key = "$running|$always|$overlayOk|$notifyOk|$gearIndex|${selectedMinutes()}"
        if (key != chromeKey) { chromeKey = key; applyChrome(running, always) }
    }

    private fun applyChrome(running: Boolean, always: Boolean) {
        gears.locked = running
        when {
            running -> {
                ignition.mode = IgnitionButton.Mode.STOP
                ignition.title = "ENGINE OFF"; ignition.subtitle = "熄火 · 恢复系统休眠"
            }
            !overlayOk -> {
                ignition.mode = IgnitionButton.Mode.PREFLIGHT
                ignition.title = "PRE-FLIGHT"; ignition.subtitle = "开启悬浮窗权限"
            }
            needsHold(selectedMinutes()) -> {
                ignition.mode = IgnitionButton.Mode.HOLD
                ignition.title = "HOLD TO IGNITE"
                ignition.subtitle = if (selectedMinutes() < 0) "按住点火 · 不自动熄火"
                    else "按住点火 · 常亮 " + gearText(selectedMinutes())
            }
            else -> {
                ignition.mode = IgnitionButton.Mode.IGNITE
                ignition.title = "IGNITION"; ignition.subtitle = "点火 · 常亮 " + gearText(selectedMinutes())
            }
        }
        telltale(ttOverlay, if (overlayOk) Pal.GREEN else Pal.RED_HOT, overlayOk)
        telltale(ttNotify, if (notifyOk) Pal.GREEN else Pal.AMBER, notifyOk)
        telltale(ttEngine, if (running) Pal.RED_HOT else Pal.STEEL_DIM, running)
        footer.text = when {
            !overlayOk -> "常亮靠一个 1×1 透明悬浮窗实现，不挡操作。\n点下方按钮，在系统设置里允许「显示在其他应用上层」。"
            running && always -> "可以切到任何应用。会一直亮到你回来熄火，\n或在通知栏点「熄火」。"
            running -> "可以切到任何应用，到点自动熄火。\n通知栏可随时结束。"
            selectedMinutes() < 0 -> "∞ 档不会自动结束，所以要按住点火。\n通知栏可随时熄火。"
            needsHold(selectedMinutes()) -> "超过 2 小时进入红线区，要按住点火。\n长按 SET 格可重新调表。"
            gearIndex == CUSTOM -> "自定义挡会记住这次的时长。\n再点一下或长按 SET 格可重新调表。"
            else -> "点火后切到任何应用都保持亮屏，到点自动恢复。\n不修改系统休眠设置。"
        }
        if (settingsOpen) refreshSettingsRows()
    }

    private fun telltale(v: TextView, color: Int, on: Boolean) {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(dp(7f), dp(7f))
        }
        v.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
        v.setTextColor(if (on) Pal.WHITE else Pal.STEEL)
    }

    private fun fmt(totalSecs: Long): String {
        val h = totalSecs / 3600; val m = (totalSecs % 3600) / 60; val s = totalSecs % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun clock(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))

    private fun gearText(m: Int) = when {
        m < 0 -> "∞"
        m < 60 -> "$m 分钟"
        m % 60 == 0 -> "${m / 60} 小时"
        else -> "${m / 60} 小时 ${m % 60} 分"
    }

    // ---------------- 操作 ----------------

    private fun pickGear(index: Int) {
        if (KeepOnService.running) return
        if (index == CUSTOM && customMinutes <= 0) { openPit(); return }
        gearIndex = index
        val e = prefs.edit().putBoolean(KEY_GEAR_CUSTOM, index == CUSTOM)
        if (index != CUSTOM) e.putInt(KEY_GEAR, GEARS[index])
        e.apply()
        render()
    }

    private var pendingStart = false

    private fun onIgnition() {
        if (KeepOnService.running) { stopKeepOn(); return }
        overlayOk = Settings.canDrawOverlays(this)
        if (!overlayOk) { openOverlaySettings(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(KEY_NOTIFY_ASKED, false)
        ) {
            prefs.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
            pendingStart = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        startKeepOn()
    }

    private fun startKeepOn() {
        if (KeepOnService.running) return
        val attempt = ++startAttempt
        val intent = Intent(this, KeepOnService::class.java)
            .setAction(KeepOnService.ACTION_START)
            .putExtra(KeepOnService.EXTRA_MINUTES, selectedMinutes())
        try { startForegroundService(intent) } catch (_: Exception) {
            toast("点火失败，请检查悬浮窗权限"); return
        }
        handler.postDelayed({
            if (attempt != startAttempt) return@postDelayed
            if (KeepOnService.running) {
                if (!needsHold(selectedMinutes())) ignition.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                chromeKey = ""
                render()
                gaugeView.blip()
            } else {
                toast("没有点着，请再确认一下悬浮窗权限")
            }
        }, 260L)
    }

    private fun stopKeepOn() {
        startAttempt++
        stopService(Intent(this, KeepOnService::class.java))
        handler.postDelayed({ chromeKey = ""; render() }, 220L)
    }

    private fun openOverlaySettings() {
        overlaySettingsOpen = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Exception) {
                overlaySettingsOpen = false
                toast("打不开悬浮窗设置，请在系统设置里手动授权")
            }
        }
    }

    private fun notificationsEnabled(): Boolean =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    private fun requestNotify(fromSettings: Boolean) {
        if (notificationsEnabled()) { if (fromSettings) toast("通知权限已开启"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.getBoolean(KEY_NOTIFY_ASKED, false)) {
            prefs.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) { toast("请在系统设置里开启通知") }
    }

    // ---------------- 设置面板 ----------------

    private fun openSettings() {
        if (settingsOpen) return
        settingsOpen = true
        introToggle.setChecked(prefs.getBoolean(KEY_INTRO_REPLAY, false), false)
        refreshSettingsRows()
        slideIn(settingsLayer, scrim, sheet)
    }

    private fun closeSettings() {
        if (!settingsOpen) return
        settingsOpen = false
        slideOut(settingsLayer, scrim, sheet) { settingsOpen }
    }

    private fun refreshSettingsRows() {
        overlayState.text = if (overlayOk) "ON  ●" else "OFF ›"
        overlayState.setTextColor(if (overlayOk) Pal.GREEN else Pal.RED_HOT)
        notifyState.text = if (notifyOk) "ON  ●" else "OFF ›"
        notifyState.setTextColor(if (notifyOk) Pal.GREEN else Pal.AMBER)
    }

    // ---------------- 调表板 ----------------

    private fun setupPit() {
        pitScrim.setOnClickListener { closePit(false) }
        findViewById<View>(R.id.pitClose).setOnClickListener { closePit(false) }
        pitConfirm.setOnClickListener { closePit(true) }
        ruler.onChange = { onPitValue(it) }
        findViewById<View>(R.id.pitMinus).setOnClickListener { nudge(-1) }
        findViewById<View>(R.id.pitPlus).setOnClickListener { nudge(1) }
        for ((v, m) in pitChips) v.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            ruler.setValue(m, true)
        }
    }

    /** −/+ 按当前所在区间的步长走：≤30 每 1 分钟太细，统一 5；120 以上 10 */
    private fun nudge(dir: Int) {
        val v = ruler.value
        val step = if (v >= 120 && dir > 0 || v > 120) 10 else 5
        val next = (v + dir * step).coerceIn(RulerView.MIN, RulerView.MAX)
        findViewById<View>(if (dir > 0) R.id.pitPlus else R.id.pitMinus)
            .performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        ruler.setValue(next, true)
    }

    private fun onPitValue(m: Int) {
        if (!pitOpen) return
        pitPreview = m
        if (m < 60) { pitValue.text = "$m"; pitUnit.text = "MIN" }
        else if (m % 60 == 0) { pitValue.text = "${m / 60}"; pitUnit.text = "HR" }
        else { pitValue.text = String.format(Locale.US, "%d:%02d", m / 60, m % 60); pitUnit.text = "H:MIN" }
        val red = m > 120
        pitValue.setTextColor(if (red) Pal.RED_HOT else Pal.WHITE)
        pitSub.text = "点火后约 " + clock(System.currentTimeMillis() + m * 60_000L) + " 熄火" +
            if (red) " · 红线区，需按住点火" else ""
        pitConfirm.text = "确认挡位 · " + gearText(m)
        val step = if (m > 120) 10 else 5
        (findViewById<TextView>(R.id.pitMinus)).text = "−$step"
        (findViewById<TextView>(R.id.pitPlus)).text = "+" + (if (m >= 120) 10 else 5)
        for ((v, cm) in pitChips) v.isSelected = cm == m
        render()
    }

    private fun openPit() {
        if (pitOpen || KeepOnService.running) return
        pitOpen = true
        val start = if (customMinutes > 0) customMinutes else selectedMinutes().takeIf { it > 0 } ?: 45
        ruler.setValue(start, false)
        onPitValue(ruler.value)
        slideIn(pitLayer, pitScrim, pitSheet)
    }

    private fun closePit(confirm: Boolean) {
        if (!pitOpen) return
        val v = ruler.value
        pitOpen = false
        pitPreview = null
        if (confirm) {
            customMinutes = v
            gears.setCustom(v)
            gears.setSelected(CUSTOM, true)
            gearIndex = CUSTOM
            prefs.edit().putInt(KEY_CUSTOM, v).putBoolean(KEY_GEAR_CUSTOM, true).apply()
            gears.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else {
            // 取消：挡位条回到原来的格
            gears.setSelected(gearIndex, true)
        }
        chromeKey = ""
        render()
        slideOut(pitLayer, pitScrim, pitSheet) { pitOpen }
    }

    private fun slideIn(layer: View, scrimV: View, sheetV: View) {
        layer.visibility = View.VISIBLE
        scrimV.alpha = 0f
        sheetV.translationY = resources.displayMetrics.heightPixels.toFloat()
        sheetV.post {
            sheetV.translationY = sheetV.height.toFloat()
            sheetV.animate().translationY(0f).setDuration(320L).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.2f, 1f)).start()
        }
        scrimV.animate().alpha(1f).setDuration(240L).start()
    }

    private fun slideOut(layer: View, scrimV: View, sheetV: View, stillOpen: () -> Boolean) {
        scrimV.animate().alpha(0f).setDuration(200L).start()
        sheetV.animate().translationY(sheetV.height.toFloat()).setDuration(220L)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction { if (!stillOpen()) layer.visibility = View.GONE }
            .start()
    }

    // ---------------- 入场 ----------------

    private fun cascadeViews(): List<View> = listOf(accent, settingsBtn, telltales, gearHeader, gears, ignition, footer)

    private fun playIntro() {
        gaugeView.alpha = 0f
        title.alpha = 0f
        subtitle.alpha = 0f
        for (v in cascadeViews()) { v.alpha = 0f; v.translationY = dp(18f).toFloat() }
        val iv = IntroView(this, this)
        intro = iv
        root.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        iv.post { iv.start() }
    }

    override fun onMorphStart() {
        cascadeViews().forEachIndexed { i, v ->
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(140L + i * 45L).setDuration(420L)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    override fun onIntroFinished() {
        // 同一帧：真实控件显形 + 移除入场层。两者此刻像素一致。
        gaugeView.alpha = 1f
        title.alpha = 1f
        subtitle.alpha = 1f
        intro?.let { root.removeView(it) }
        intro = null
        prefs.edit().putBoolean(KEY_INTRO_PLAYED, true).putBoolean(KEY_INTRO_REPLAY, false).apply()
        introToggle.setChecked(false, false)
        handler.postDelayed({
            for (v in cascadeViews()) { v.alpha = 1f; v.translationY = 0f }
        }, 700L)
    }

    private fun quickEntrance() {
        val views = listOf(header, gaugeView, telltales, gearHeader, gears, ignition, footer)
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(12f).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(30L + i * 40L)
                .setDuration(380L).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        gaugeView.sweepIn()
    }

    // ---------------- 兜底 ----------------

    private fun showFatal(error: Throwable) {
        if (fatalShown) return
        fatalShown = true
        handler.removeCallbacksAndMessages(null)
        val text = try { CrashLog.report(this, Thread.currentThread(), error) } catch (t: Throwable) {
            CrashLog.buildText(Thread.currentThread(), t)
        }
        val tv = TextView(this).apply {
            this.text = "常亮启动失败\n\n$text"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(24f), dp(72f), dp(24f), dp(24f))
            setTextIsSelectable(true)
        }
        try {
            setContentView(ScrollView(this).apply { setBackgroundColor(Pal.BG); addView(tv) })
        } catch (_: Throwable) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
