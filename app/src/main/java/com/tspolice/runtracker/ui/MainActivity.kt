package com.tspolice.runtracker.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.ForecastStatus
import com.tspolice.runtracker.domain.HealthSyncStatus
import com.tspolice.runtracker.domain.IntervalPhase
import com.tspolice.runtracker.domain.LiveTelemetry
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.PocketAudioMode
import com.tspolice.runtracker.domain.ProgressionForecaster
import com.tspolice.runtracker.domain.PtTrialPhase
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SamsungHealthComparator
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import com.tspolice.runtracker.health.SamsungHealthSyncManager
import com.tspolice.runtracker.service.RunTrackingService
import com.tspolice.runtracker.update.AppUpdater
import com.tspolice.runtracker.update.UpdateCheckResult
import com.tspolice.runtracker.update.UpdateReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var repository: RunRepository
    private lateinit var appUpdater: AppUpdater
    private lateinit var healthSyncManager: SamsungHealthSyncManager
    private var trackingService: RunTrackingService? = null
    private var serviceBound = false
    private var lastHandledFinishId: Long? = null
    private var latestDiscoveredRelease: UpdateReleaseInfo? = null
    private var currentlyRecording = false
    private var activityVisible = false
    private var lastAutoUpdateCheckElapsedMs = -10_000_000L
    private var activeUpdateDialog: AlertDialog? = null

    // Samsung Health Sync & Calibration state
    private var healthSyncStatus: HealthSyncStatus = HealthSyncStatus.PERMISSION_REQUIRED
    private var latestSamsungSnapshot: SamsungDailySnapshot? = null
    private var latestDailySummaryForCompare: DailyActivitySummary = DailyActivitySummary()
    private var isHealthSyncInFlight = false
    private var lastHealthSyncElapsedMs = -120_000L
    private var lastSamsungRenderKey = ""

    // Tracker sub-tab: 0 = Today (All-Day Auto), 1 = 1.6K PT Trial, 2 = 400m Intervals
    private var activeTrackerSubTab = 0
    private var selectedIntervalReps = 6
    private var lastRenderedPtPhase: PtTrialPhase = PtTrialPhase.IDLE
    private var lastRenderedIntervalPhase: IntervalPhase = IntervalPhase.IDLE
    private var cachedPastDailyLogs: List<DailyActivitySummary> = emptyList()
    private var lastDailyHistoryRenderMs = -60_000L
    private var lastDailyHistoryKey = ""

    // Header & Root Navigation views
    private lateinit var headerSubtitleText: TextView
    private lateinit var headerAudioPill: LinearLayout
    private lateinit var headerAudioIcon: VectorIconView
    private lateinit var headerAudioLabel: TextView
    private lateinit var updateBanner: TextView
    private lateinit var tabTrackerContainer: ScrollView
    private lateinit var tabProgressContainer: ScrollView
    private lateinit var tabGuideContainer: ScrollView
    private val navTabViews = mutableListOf<BottomNavTabView>()

    // Tab 1: Banners, Sub-Tab Switcher & Containers
    private lateinit var permissionBanner: LinearLayout
    private lateinit var batteryGuardBanner: LinearLayout
    private lateinit var subTabTodayBtn: TextView
    private lateinit var subTabPtBtn: TextView
    private lateinit var subTabIntervalsBtn: TextView
    private lateinit var openAllDayContainer: LinearLayout
    private lateinit var ptTrialContainer: LinearLayout
    private lateinit var intervalsContainer: LinearLayout

    // Tab 1A: Today (Always-On All-Day Auto) views
    private lateinit var allDayLiveChip: TextView
    private lateinit var dailyRingsView: DailyActivityRingsView
    private lateinit var dailyJogRunTimeVal: TextView
    private lateinit var dailyJogRunDistSub: TextView
    private lateinit var dailyWalkTimeVal: TextView
    private lateinit var dailyWalkDistSub: TextView
    private lateinit var allDayGaitPill: TextView
    private lateinit var launchPtFromOpenBtn: TextView
    private lateinit var dailyMaxPaceVal: TextView
    private lateinit var dailyMaxPaceSub: TextView
    private lateinit var dailyMaxCadenceVal: TextView
    private lateinit var dailyMaxCadenceSub: TextView
    private lateinit var openLivePaceVal: TextView
    private lateinit var openLivePaceSub: TextView
    private lateinit var dailyTotalDistVal: TextView
    private lateinit var dailyTotalDistSub: TextView
    private lateinit var dailyGaitBar: GaitBreakdownBarView
    private lateinit var dailyGaitLegendText: TextView

    // Samsung Health Comparison Card views
    private lateinit var samsungSyncBadge: TextView
    private lateinit var samsungHeadlineText: TextView
    private lateinit var samsungDetailText: TextView
    private lateinit var samsungCompareTable: LinearLayout
    private lateinit var samRowStepsTracker: TextView
    private lateinit var samRowStepsSamsung: TextView
    private lateinit var samRowStepsDelta: TextView
    private lateinit var samRowDistTracker: TextView
    private lateinit var samRowDistSamsung: TextView
    private lateinit var samRowDistDelta: TextView
    private lateinit var samRowWalkTracker: TextView
    private lateinit var samRowWalkSamsung: TextView
    private lateinit var samRowWalkDelta: TextView
    private lateinit var samRowRunTracker: TextView
    private lateinit var samRowRunSamsung: TextView
    private lateinit var samRowRunDelta: TextView
    private lateinit var samRowStrideTracker: TextView
    private lateinit var samRowStrideSamsung: TextView
    private lateinit var samRowStrideDelta: TextView
    private lateinit var samsungPrimaryBtn: TextView
    private lateinit var samsungSecondaryBtn: TextView
    private lateinit var dailyHistoryListLayout: LinearLayout

    // Tab 1B: 1.6 km PT Trial views
    private lateinit var ptGpsReadinessPill: TextView
    private lateinit var paceArcGauge: PaceArcGaugeView
    private lateinit var primaryActionBtn: TextView
    private lateinit var ptArmHintText: TextView
    private lateinit var ptRunningControlsRow: LinearLayout
    private lateinit var snapLapBtn: TextView
    private lateinit var finishEarlyBtn: TextView
    private lateinit var secondaryActionBtn: TextView
    private lateinit var metricPaceVal: TextView
    private lateinit var metricPaceSub: TextView
    private lateinit var metricCadenceVal: TextView
    private lateinit var metricCadenceSub: TextView
    private lateinit var metricProjVal: TextView
    private lateinit var metricProjSub: TextView
    private lateinit var metricSensorVal: TextView
    private lateinit var metricSensorSub: TextView
    private lateinit var gaitBar: GaitBreakdownBarView
    private lateinit var gaitLegendText: TextView
    private lateinit var splitsListLayout: LinearLayout

    // Tab 1C: Guided 400m Intervals views
    private lateinit var intervalDialView: IntervalDialView
    private val intervalRepPresetBtns = mutableListOf<Pair<Int, TextView>>()
    private lateinit var intervalPrimaryBtn: TextView
    private lateinit var intervalSecondaryBtn: TextView
    private lateinit var intervalSplitsLayout: LinearLayout

    // Tab 2: Progress, Merit Score & ETA views
    private lateinit var etaHeadlineText: TextView
    private lateinit var meritBadgeText: TextView
    private lateinit var etaSubText: TextView
    private lateinit var coachPrescriptionText: TextView
    private lateinit var kpiPbVal: TextView
    private lateinit var kpiGapVal: TextView
    private lateinit var kpiRateVal: TextView
    private lateinit var kpiImprovedVal: TextView
    private lateinit var trendChartView: ProgressTrendChartView
    private lateinit var demoToggleBtn: TextView
    private lateinit var sessionsListLayout: LinearLayout

    // Tab 3: Settings, Profile & Lab views
    private lateinit var batteryStatusBadge: TextView
    private lateinit var batteryStatusBtn: TextView
    private lateinit var heightValText: TextView
    private lateinit var targetPresetButtons: List<Pair<Double, TextView>>
    private lateinit var thresholdPresetButtons: List<Pair<Double, TextView>>
    private lateinit var audioModeButtons: List<Pair<PocketAudioMode, TextView>>
    private lateinit var allDayAutoRow: ModernSettingRow
    private lateinit var voiceCoachRow: ModernSettingRow
    private lateinit var stepFallbackRow: ModernSettingRow
    private lateinit var autoCalSamsungRow: ModernSettingRow
    private lateinit var autoImportSamsungRow: ModernSettingRow
    private lateinit var diagGpsText: TextView
    private lateinit var diagStepText: TextView
    private lateinit var diagStrideCalText: TextView
    private lateinit var diagStateText: TextView
    private lateinit var updateStatusText: TextView
    private lateinit var checkUpdateBtn: TextView

    private val healthPermissionLauncher = registerForActivityResult(
        SamsungHealthSyncManager.createPermissionRequestContract()
    ) {
        triggerSamsungHealthSync(userTriggered = true)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateBanners()
        startTrackingServiceSafely()
        trackingService?.refreshPermissionsAndSensors()
    }

    private val telemetryListener: (LiveTelemetry) -> Unit = { snap ->
        runOnUiThread { renderLiveTelemetry(snap) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as? RunTrackingService.LocalBinder ?: return
            trackingService = local.getService()
            serviceBound = true
            trackingService?.setUiForegroundVisible(activityVisible)
            trackingService?.addTelemetryListener(telemetryListener)
            trackingService?.refreshPermissionsAndSensors()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            trackingService = null
        }
    }

    private fun dp(v: Float): Int = AthleticTheme.dp(this, v)
    private fun dp(v: Int): Int = AthleticTheme.dp(this, v.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RunRepository(this)
        appUpdater = AppUpdater(this)
        healthSyncManager = SamsungHealthSyncManager(this, repository)
        latestSamsungSnapshot = repository.getCachedSamsungSnapshot(LocalDate.now().toString())
        if (latestSamsungSnapshot != null) {
            healthSyncStatus = HealthSyncStatus.SYNCED
        }

        setContentView(buildRootLayout())
        selectTab(0)
        selectTrackerSubTab(0)
        refreshSettingsHighlights()
        refreshCachedDailyHistory(forceRender = true)
        renderSamsungComparisonCard(force = true)
        refreshProgressDashboard()
        updateBanners()

        val svcIntent = startTrackingServiceSafely()
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        requestNeededPermissionsIfMissing(silentCheckOnly = false)
        appUpdater.syncBackgroundUpdateSchedule()
        maybeShowHealthPrivacyRationale(intent)
    }

    private fun startTrackingServiceSafely(): Intent {
        val svcIntent = Intent(this, RunTrackingService::class.java)
        runCatching {
            if (hasCorePermissions() && (repository.allDayTrackingEnabled || repository.stateChangeAudioEnabled)) {
                ContextCompat.startForegroundService(this, svcIntent)
            } else {
                startService(svcIntent)
            }
        }.onFailure {
            runCatching { startService(svcIntent) }
        }
        return svcIntent
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeInstallFromNotificationIntent(intent)
        maybeShowHealthPrivacyRationale(intent)
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        trackingService?.setUiForegroundVisible(true)
    }

    override fun onResume() {
        super.onResume()
        updateBanners()
        refreshBatterySettingsState()
        if (appUpdater.resumePendingInstallIfPermitted()) {
            Toast.makeText(this, "Launching update installer...", Toast.LENGTH_SHORT).show()
            return
        }
        if (consumeInstallFromNotificationIntent(intent)) {
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!currentlyRecording && (nowElapsed - lastHealthSyncElapsedMs) >= 45_000L) {
            triggerSamsungHealthSync(userTriggered = false)
        }
        if (appUpdater.autoCheckOnLaunch && !currentlyRecording && (nowElapsed - lastAutoUpdateCheckElapsedMs) >= 120_000L) {
            lastAutoUpdateCheckElapsedMs = nowElapsed
            window.decorView.postDelayed({
                if (!isFinishing && !currentlyRecording) {
                    performUpdateCheck(manualUserTrigger = false)
                }
            }, 900L)
        }
    }

    private fun consumeInstallFromNotificationIntent(targetIntent: Intent?): Boolean {
        if (targetIntent?.getBooleanExtra(AppUpdater.EXTRA_INSTALL_UPDATE_ON_OPEN, false) == true) {
            targetIntent.removeExtra(AppUpdater.EXTRA_INSTALL_UPDATE_ON_OPEN)
            lastAutoUpdateCheckElapsedMs = SystemClock.elapsedRealtime()
            performUpdateCheck(manualUserTrigger = false, autoInstallWhenFound = true)
            return true
        }
        return false
    }

    override fun onStop() {
        activityVisible = false
        trackingService?.setUiForegroundVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        activeUpdateDialog?.dismiss()
        trackingService?.removeTelemetryListener(telemetryListener)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    // =========================================================================
    // ROOT SHELL: SLEEK HEADER + CONTENT FRAME + ICON BOTTOM NAV
    // =========================================================================
    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AthleticColors.BG_CANVAS)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Athletic Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(AthleticColors.BG_HEADER)
        }

        val logoIcon = VectorIconView(this, IconKind.TRACK_EMBLEM, AthleticColors.EMERALD_BRIGHT, 36f).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(11)
            }
        }
        header.addView(logoIcon)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "TS POLICE 1.6K"
            textSize = 16.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            letterSpacing = 0.03f
            maxLines = 1
        })
        headerSubtitleText = TextView(this).apply {
            text = "PET Coach  •  Cutoff < ${TsPoliceBenchmark.formatDuration(repository.targetTimeSec)}"
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            maxLines = 1
        }
        titleCol.addView(headerSubtitleText)
        header.addView(titleCol)

        // Sleek 3-Mode Pocket Audio Capsule
        headerAudioPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(7), dp(12), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val next = repository.pocketAudioMode.nextMode()
                repository.pocketAudioMode = next
                trackingService?.syncPocketForegroundAndWakeLock()
                refreshSettingsHighlights()
                trackingService?.previewStateChangeAudio()
                Toast.makeText(this@MainActivity, "${next.shortLabel}: ${next.description}", Toast.LENGTH_SHORT).show()
            }
        }
        headerAudioIcon = VectorIconView(this, IconKind.AUDIO_SMART, AthleticColors.CYAN_BRIGHT, 16f).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
        }
        headerAudioLabel = TextView(this).apply {
            text = "Smart"
            textSize = 11.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
        }
        headerAudioPill.addView(headerAudioIcon)
        headerAudioPill.addView(headerAudioLabel)
        header.addView(headerAudioPill)

        root.addView(header)
        root.addView(View(this).apply {
            setBackgroundColor(AthleticColors.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f).coerceAtLeast(1))
        })

        // OTA Update Banner
        updateBanner = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 12.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            setBackgroundColor(AthleticColors.CYAN_BRIGHT)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener {
                latestDiscoveredRelease?.let { rel ->
                    val (_, curName) = appUpdater.getInstalledVersion()
                    showUpdateAvailableDialog(curName, rel)
                }
            }
        }
        root.addView(updateBanner)

        // Content Frame
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        tabTrackerContainer = buildTrackerTab()
        tabProgressContainer = buildProgressTab()
        tabGuideContainer = buildGuideAndSensorsTab()

        contentFrame.addView(tabTrackerContainer)
        contentFrame.addView(tabProgressContainer)
        contentFrame.addView(tabGuideContainer)
        root.addView(contentFrame)

        // Top hairline above Bottom Navigation Bar
        root.addView(View(this).apply {
            setBackgroundColor(AthleticColors.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f).coerceAtLeast(1))
        })

        // Sleek Icon Bottom Navigation Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(AthleticColors.BG_NAV)
            setPadding(dp(8), dp(2), dp(8), dp(4))
        }
        val navSpec = listOf(
            IconKind.NAV_TRACKER to "Tracker",
            IconKind.NAV_PROGRESS to "Progress",
            IconKind.NAV_SETTINGS to "Coach & Lab"
        )
        navSpec.forEachIndexed { index, (icon, label) ->
            val tabView = BottomNavTabView(this, icon, label).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(index) }
            }
            navTabViews.add(tabView)
            bottomBar.addView(tabView)
        }
        root.addView(bottomBar)
        return root
    }

    private fun selectTab(index: Int) {
        tabTrackerContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabProgressContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabGuideContainer.visibility = if (index == 2) View.VISIBLE else View.GONE

        navTabViews.forEachIndexed { i, tv -> tv.setActive(i == index) }

        if (index == 0) {
            refreshCachedDailyHistory(forceRender = true)
            if (!currentlyRecording && (SystemClock.elapsedRealtime() - lastHealthSyncElapsedMs) >= 45_000L) {
                triggerSamsungHealthSync(userTriggered = false)
            }
        } else if (index == 1) {
            refreshProgressDashboard()
        } else if (index == 2) {
            refreshBatterySettingsState()
            refreshSettingsHighlights()
        }
    }

    private fun selectTrackerSubTab(subIndex: Int) {
        activeTrackerSubTab = subIndex.coerceIn(0, 2)
        openAllDayContainer.visibility = if (activeTrackerSubTab == 0) View.VISIBLE else View.GONE
        ptTrialContainer.visibility = if (activeTrackerSubTab == 1) View.VISIBLE else View.GONE
        intervalsContainer.visibility = if (activeTrackerSubTab == 2) View.VISIBLE else View.GONE

        fun styleSeg(btn: TextView, active: Boolean, activeColor: Int) {
            if (active) {
                btn.background = AthleticTheme.solidPill(this, activeColor, activeColor, 11f, 0f)
                btn.setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            } else {
                btn.background = null
                btn.setTextColor(AthleticColors.TEXT_SECONDARY)
            }
        }
        styleSeg(subTabTodayBtn, activeTrackerSubTab == 0, AthleticColors.EMERALD_BRIGHT)
        styleSeg(subTabPtBtn, activeTrackerSubTab == 1, AthleticColors.CYAN_BRIGHT)
        styleSeg(subTabIntervalsBtn, activeTrackerSubTab == 2, AthleticColors.AMBER_BRIGHT)
    }

    // =========================================================================
    // TAB 1: TODAY (ALL-DAY AUTO)  |  1.6K PT TRIAL  |  400M INTERVALS
    // =========================================================================
    private fun buildTrackerTab(): ScrollView {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        // 1. Sensor/GPS Permission Banner
        permissionBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(12), dp(11))
            background = AthleticTheme.gradientCard(
                this@MainActivity,
                AthleticColors.HERO_AMBER_TOP,
                AthleticColors.HERO_AMBER_BOTTOM,
                AthleticColors.HERO_AMBER_BORDER,
                14f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        permissionBanner.addView(TextView(this).apply {
            text = "Grant GPS & Step Sensor access for accurate 1.6 km & daily tracking"
            textSize = 12f
            setTextColor(0xFFFDE68A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        })
        permissionBanner.addView(
            AthleticTheme.createButton(this, "Enable", AthleticTheme.ButtonStyle.WARNING_AMBER, 36f).apply {
                textSize = 12f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { requestNeededPermissionsIfMissing(silentCheckOnly = false) }
            }
        )
        col.addView(permissionBanner)

        // 2. Samsung / OneUI Background Battery Guard Banner (hidden once unrestricted)
        batteryGuardBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = AthleticTheme.gradientCard(
                this@MainActivity,
                AthleticColors.HERO_CYAN_TOP,
                AthleticColors.HERO_CYAN_BOTTOM,
                AthleticColors.HERO_CYAN_BORDER,
                14f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        batteryGuardBanner.addView(TextView(this).apply {
            text = "Keep pocket tracking alive on Samsung One UI (prevent Deep Sleep)"
            textSize = 12f
            setTextColor(AthleticColors.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        })
        batteryGuardBanner.addView(
            AthleticTheme.createButton(this, "Allow", AthleticTheme.ButtonStyle.PRIMARY_CYAN, 36f).apply {
                textSize = 12f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { requestIgnoreBatteryOptimizationsSafely() }
            }
        )
        col.addView(batteryGuardBanner)

        // 3. Recessed 3-Segment Capsule Switcher: [Today] | [1.6K Trial] | [Intervals]
        val segTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = AthleticTheme.solidPill(
                this@MainActivity,
                AthleticColors.SURFACE_RECESSED,
                AthleticColors.BORDER_SUBTLE,
                14f
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        fun segItem(label: String, idx: Int) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(dp(6), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTrackerSubTab(idx) }
        }
        subTabTodayBtn = segItem("Today", 0)
        subTabPtBtn = segItem("1.6K Trial", 1)
        subTabIntervalsBtn = segItem("400m Coach", 2)
        segTrack.addView(subTabTodayBtn)
        segTrack.addView(subTabPtBtn)
        segTrack.addView(subTabIntervalsBtn)
        col.addView(segTrack)

        openAllDayContainer = buildOpenAllDaySubView()
        ptTrialContainer = buildPtTrialSubView()
        intervalsContainer = buildIntervalsSubView()

        col.addView(openAllDayContainer)
        col.addView(ptTrialContainer)
        col.addView(intervalsContainer)

        scroll.addView(col)
        return scroll
    }

    /**
     * Sub-View 0: Today — Concentric Activity Rings + Bento Grid + Samsung Health Calibration.
     */
    private fun buildOpenAllDaySubView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Hero Concentric Rings Card
        val heroCard = cardContainer(
            topColor = AthleticColors.HERO_EMERALD_TOP,
            bottomColor = AthleticColors.HERO_EMERALD_BOTTOM,
            borderColor = AthleticColors.HERO_EMERALD_BORDER
        )

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(AthleticTheme.sectionLabel(this, "TODAY'S MOVEMENT  •  AUTO-DETECT", AthleticColors.EMERALD_BRIGHT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        allDayLiveChip = TextView(this).apply {
            text = "SMART 24/7"
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f))
            background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.EMERALD_BRIGHT, AthleticColors.EMERALD_BRIGHT, 10f, 0f)
        }
        topRow.addView(allDayLiveChip)
        heroCard.addView(topRow)

        // Rings + Metrics Split Row
        val ringsSplit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(10))
        }
        dailyRingsView = DailyActivityRingsView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(126), dp(126)).apply {
                marginEnd = dp(16)
            }
        }
        ringsSplit.addView(dailyRingsView)

        val rightStats = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Jog + Run block
        rightStats.addView(TextView(this).apply {
            text = "JOG + RUN COMBINED"
            textSize = 10.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            letterSpacing = 0.05f
        })
        dailyJogRunTimeVal = TextView(this).apply {
            text = "0:00"
            textSize = 27f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
        }
        dailyJogRunDistSub = TextView(this).apply {
            text = "0 m  •  Jog 0:00 / Run 0:00"
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(10))
        }
        rightStats.addView(dailyJogRunTimeVal)
        rightStats.addView(dailyJogRunDistSub)

        // Walking block
        rightStats.addView(TextView(this).apply {
            text = "WALKING TIME"
            textSize = 10.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.CYAN_BRIGHT)
            letterSpacing = 0.05f
        })
        dailyWalkTimeVal = TextView(this).apply {
            text = "0:00"
            textSize = 23f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
        }
        dailyWalkDistSub = TextView(this).apply {
            text = "0 m walked today"
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
        }
        rightStats.addView(dailyWalkTimeVal)
        rightStats.addView(dailyWalkDistSub)

        ringsSplit.addView(rightStats)
        heroCard.addView(ringsSplit)

        // Live Gait + Battery Saver Status Pill inside Hero Card
        allDayGaitPill = TextView(this).apply {
            text = "Standing  •  Smart Battery Saver Ready"
            textSize = 12f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = AthleticTheme.solidPill(this@MainActivity, 0x33000000, AthleticColors.BORDER_SUBTLE, 11f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        heroCard.addView(allDayGaitPill)
        box.addView(heroCard)

        // 2. Immediate 1-Tap Track Trial CTA (placed high for instant thumb access!)
        launchPtFromOpenBtn = AthleticTheme.createButton(
            this,
            "Arm 1.6 km PT Trial (Launches on Jog/Run)  →",
            AthleticTheme.ButtonStyle.PRIMARY_EMERALD,
            52f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            setOnClickListener {
                selectTrackerSubTab(1)
                if (lastRenderedPtPhase == PtTrialPhase.IDLE) {
                    requestNeededPermissionsIfMissing(silentCheckOnly = false)
                    trackingService?.armPtTrial()
                }
            }
        }
        box.addView(launchPtFromOpenBtn)

        // 3. Clean 2x2 Bento Grid (Peak Pace, Peak Cadence, Live Speed, Total Distance)
        val bentoRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val maxPaceTile = metricTile("PEAK PACE TODAY", "--:-- /km", "Max 0.0 km/h", AthleticColors.EMERALD_BRIGHT)
        dailyMaxPaceVal = maxPaceTile.second
        dailyMaxPaceSub = maxPaceTile.third
        val maxCadTile = metricTile("PEAK CADENCE", "0 spm", "Goal ≥168 spm", AthleticColors.CYAN_BRIGHT)
        dailyMaxCadenceVal = maxCadTile.second
        dailyMaxCadenceSub = maxCadTile.third
        bentoRow1.addView(maxPaceTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        bentoRow1.addView(maxCadTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        box.addView(bentoRow1)

        val bentoRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val livePaceTile = metricTile("LIVE PACE NOW", "--:-- /km", "0 spm live")
        openLivePaceVal = livePaceTile.second
        openLivePaceSub = livePaceTile.third
        val totDistTile = metricTile("TOTAL DISTANCE", "0 m", "Step-Stride Ready")
        dailyTotalDistVal = totDistTile.second
        dailyTotalDistSub = totDistTile.third
        bentoRow2.addView(livePaceTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        bentoRow2.addView(totDistTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        box.addView(bentoRow2)

        // 4. Whole-Day Gait Share Card
        val dayGaitCard = cardContainer()
        dayGaitCard.addView(AthleticTheme.sectionLabel(this, "MOVEMENT INTENSITY SHARE"))
        dailyGaitBar = GaitBreakdownBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply {
                topMargin = dp(10); bottomMargin = dp(9)
            }
        }
        dayGaitCard.addView(dailyGaitBar)
        dailyGaitLegendText = TextView(this).apply {
            text = "Run 0:00  •  Jog 0:00  •  Walk 0:00"
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
        }
        dayGaitCard.addView(dailyGaitLegendText)
        box.addView(dayGaitCard)

        // 5. Samsung Health Sync & Personal Stride Calibration Card
        box.addView(buildSamsungComparisonCard())

        // 6. Recent Whole-Day Activity Timeline
        val dailyLogCard = cardContainer()
        dailyLogCard.addView(AthleticTheme.sectionLabel(this, "RECENT DAILY TOTALS"))
        dailyHistoryListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        dailyLogCard.addView(dailyHistoryListLayout)
        box.addView(dailyLogCard)

        return box
    }

    private fun buildSamsungComparisonCard(): LinearLayout {
        val card = cardContainer(
            topColor = AthleticColors.HERO_CYAN_TOP,
            bottomColor = AthleticColors.HERO_CYAN_BOTTOM,
            borderColor = AthleticColors.HERO_CYAN_BORDER
        )

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(AthleticTheme.sectionLabel(this, "SAMSUNG HEALTH CALIBRATION", AthleticColors.CYAN_BRIGHT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        samsungSyncBadge = TextView(this).apply {
            text = "CONNECT"
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f))
            background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.CYAN_BRIGHT, AthleticColors.CYAN_BRIGHT, 10f, 0f)
        }
        headerRow.addView(samsungSyncBadge)
        card.addView(headerRow)

        samsungHeadlineText = TextView(this).apply {
            text = "Sync Samsung Health for Ground-Truth Stride"
            textSize = 15f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(8), 0, dp(3))
        }
        samsungDetailText = TextView(this).apply {
            text = "Compares Steps, Distance & Runs via Health Connect (backfills 14 days of Watch/Samsung runs)."
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(samsungHeadlineText)
        card.addView(samsungDetailText)

        samsungCompareTable = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val tblHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        fun hdrCell(txt: String, weight: Float, alignEnd: Boolean = false) = TextView(this).apply {
            text = txt
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            gravity = if (alignEnd) Gravity.END else Gravity.START
            setTextColor(AthleticColors.TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        tblHeader.addView(hdrCell("METRIC", 1.05f))
        tblHeader.addView(hdrCell("TRACKER", 0.95f, alignEnd = true))
        tblHeader.addView(hdrCell("SAMSUNG", 0.95f, alignEnd = true))
        tblHeader.addView(hdrCell("MATCH", 1.15f, alignEnd = true))
        samsungCompareTable.addView(tblHeader)

        fun buildCompareRow(label: String): Triple<TextView, TextView, TextView> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val lblTv = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(AthleticColors.TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f)
            }
            val trkTv = TextView(this).apply {
                text = "--"
                textSize = 12f
                gravity = Gravity.END
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(AthleticColors.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f)
            }
            val samTv = TextView(this).apply {
                text = "--"
                textSize = 12f
                gravity = Gravity.END
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(AthleticColors.CYAN_BRIGHT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f)
            }
            val dltTv = TextView(this).apply {
                text = "--"
                textSize = 11f
                gravity = Gravity.END
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(AthleticColors.EMERALD_BRIGHT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f)
            }
            row.addView(lblTv)
            row.addView(trkTv)
            row.addView(samTv)
            row.addView(dltTv)
            samsungCompareTable.addView(row)
            return Triple(trkTv, samTv, dltTv)
        }

        val rSteps = buildCompareRow("Steps")
        samRowStepsTracker = rSteps.first; samRowStepsSamsung = rSteps.second; samRowStepsDelta = rSteps.third
        val rDist = buildCompareRow("Distance")
        samRowDistTracker = rDist.first; samRowDistSamsung = rDist.second; samRowDistDelta = rDist.third
        val rWalk = buildCompareRow("Walk")
        samRowWalkTracker = rWalk.first; samRowWalkSamsung = rWalk.second; samRowWalkDelta = rWalk.third
        val rRun = buildCompareRow("Jog + Run")
        samRowRunTracker = rRun.first; samRowRunSamsung = rRun.second; samRowRunDelta = rRun.third
        val rStride = buildCompareRow("Stride")
        samRowStrideTracker = rStride.first; samRowStrideSamsung = rStride.second; samRowStrideDelta = rStride.third

        card.addView(samsungCompareTable)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        samsungPrimaryBtn = AthleticTheme.createButton(this, "Connect Samsung Health", AthleticTheme.ButtonStyle.PRIMARY_EMERALD, 44f).apply {
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(5) }
        }
        samsungSecondaryBtn = AthleticTheme.createButton(this, "Settings", AthleticTheme.ButtonStyle.SECONDARY_SLATE, 44f).apply {
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply { marginStart = dp(5) }
        }
        btnRow.addView(samsungPrimaryBtn)
        btnRow.addView(samsungSecondaryBtn)
        card.addView(btnRow)

        return card
    }

    /**
     * Sub-View 1: Official 1.6 km TSLPRB PT Trial Race Cockpit (Unpaused RFID Clock + Pocket Protection).
     */
    private fun buildPtTrialSubView(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val gaugeCard = cardContainer().apply {
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }

        // Top Bar inside Gauge Card: Official Rules Label + Live GPS Lock Readiness Pill
        val gaugeTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gaugeTopRow.addView(AthleticTheme.sectionLabel(this, "OFFICIAL 1,600M RFID CLOCK").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        ptGpsReadinessPill = TextView(this).apply {
            text = "CHECKING GPS..."
            textSize = 10.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f))
            background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 10f)
            setTextColor(AthleticColors.CYAN_BRIGHT)
        }
        gaugeTopRow.addView(ptGpsReadinessPill)
        gaugeCard.addView(gaugeTopRow)

        paceArcGauge = PaceArcGaugeView(this)
        gaugeCard.addView(paceArcGauge)
        col.addView(gaugeCard)

        // Primary Action Button (IDLE & ARMED states)
        primaryActionBtn = AthleticTheme.createButton(
            this,
            "ARM 1.6 KM PT TRIAL (Starts on Jog/Run)",
            AthleticTheme.ButtonStyle.PRIMARY_EMERALD,
            54f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        col.addView(primaryActionBtn)

        // Active Running Controls Row: [+ Snap 400m Lap] & [Finish Early] (Pocket-Safe Confirmation!)
        ptRunningControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        snapLapBtn = AthleticTheme.createButton(this, "+ Snap 400m Lap", AthleticTheme.ButtonStyle.PRIMARY_CYAN, 50f).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
            setOnClickListener {
                val snapped = trackingService?.snapNext400mLap() == true
                if (!snapped) {
                    Toast.makeText(
                        this@MainActivity,
                        "Snap Lap works within 90m of the next 400m line.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        finishEarlyBtn = AthleticTheme.createButton(this, "Finish & Save", AthleticTheme.ButtonStyle.DANGER_CRIMSON, 50f).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) }
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Finish 1.6 km PT Trial Early?")
                    .setMessage("Save your partial trial now, or keep running until 1,600 meters?")
                    .setPositiveButton("Finish & Save") { _, _ ->
                        val id = trackingService?.finishWorkout(forceSave = true)
                        if (id != null) {
                            Toast.makeText(this@MainActivity, "PT Trial saved to Progress!", Toast.LENGTH_LONG).show()
                            refreshProgressDashboard()
                            selectTab(1)
                        } else {
                            Toast.makeText(this@MainActivity, "Trial too short to save (<50m).", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Keep Running", null)
                    .show()
            }
        }
        ptRunningControlsRow.addView(snapLapBtn)
        ptRunningControlsRow.addView(finishEarlyBtn)
        col.addView(ptRunningControlsRow)

        ptArmHintText = TextView(this).apply {
            text = "Put phone in pocket & walk to start line — clock stays at 0:00 until you start jogging or running."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        col.addView(ptArmHintText)

        secondaryActionBtn = AthleticTheme.createButton(this, "← Back to Today Overview", AthleticTheme.ButtonStyle.SUBTLE_OUTLINE, 44f).apply {
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        col.addView(secondaryActionBtn)

        // 2x2 Live Telemetry Grid
        val gridRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        val paceTile = metricTile("LIVE PACE", "--:-- /km", "0.0 km/h", AthleticColors.EMERALD_BRIGHT)
        metricPaceVal = paceTile.second; metricPaceSub = paceTile.third
        val cadTile = metricTile("STEP CADENCE", "0 spm", "Target ≥168 spm", AthleticColors.CYAN_BRIGHT)
        metricCadenceVal = cadTile.second; metricCadenceSub = cadTile.third
        gridRow1.addView(paceTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        gridRow1.addView(cadTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        col.addView(gridRow1)

        val gridRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(14) }
        }
        val projTile = metricTile("PROJ. 1.6K FINISH", "--:--", "Target < 7:15")
        metricProjVal = projTile.second; metricProjSub = projTile.third
        val sensTile = metricTile("CURVE / GPS FUSION", "0.0 m/s²", "Checking sky lock...")
        metricSensorVal = sensTile.second; metricSensorSub = sensTile.third
        gridRow2.addView(projTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        gridRow2.addView(sensTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        col.addView(gridRow2)

        // PT Trial Gait Breakdown Card
        val gaitCard = cardContainer()
        gaitCard.addView(AthleticTheme.sectionLabel(this, "TRIAL GAIT & STOPPED PENALTY"))
        gaitBar = GaitBreakdownBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
                .apply { topMargin = dp(10); bottomMargin = dp(9) }
        }
        gaitCard.addView(gaitBar)
        gaitLegendText = TextView(this).apply {
            text = "Run 0:00  •  Jog 0:00  •  Walk 0:00  •  Stopped 0:00"
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
        }
        gaitCard.addView(gaitLegendText)
        col.addView(gaitCard)

        // Live 400m Track Lap Splits Card
        val splitsCard = cardContainer()
        splitsCard.addView(AthleticTheme.sectionLabel(this, "400M TRACK LAP SPLITS"))
        splitsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        splitsCard.addView(splitsListLayout)
        col.addView(splitsCard)

        return col
    }

    /**
     * Sub-View 2: Guided 400m Speed-Endurance Interval Coach (4x400m / 6x400m / 8x400m + 90s Recovery).
     */
    private fun buildIntervalsSubView(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val dialCard = cardContainer(
            topColor = AthleticColors.HERO_AMBER_TOP,
            bottomColor = AthleticColors.SURFACE_BOTTOM,
            borderColor = AthleticColors.HERO_AMBER_BORDER
        )
        dialCard.addView(AthleticTheme.sectionLabel(this, "GUIDED 400M TRACK SPEED WORKOUT", AthleticColors.AMBER_BRIGHT))
        dialCard.addView(TextView(this).apply {
            text = "Builds VO2max & prevents shin splints from running all-out 1.6 km trials every day."
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(8))
        })

        // Rep count selector: 4x400m | 6x400m | 8x400m
        val repRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 11f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        }
        intervalRepPresetBtns.clear()
        for (reps in listOf(4, 6, 8)) {
            val tv = TextView(this).apply {
                text = "$reps × 400m"
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    selectedIntervalReps = reps
                    refreshIntervalRepPresets()
                }
            }
            intervalRepPresetBtns.add(reps to tv)
            repRow.addView(tv)
        }
        dialCard.addView(repRow)
        refreshIntervalRepPresets()

        intervalDialView = IntervalDialView(this)
        dialCard.addView(intervalDialView)

        intervalPrimaryBtn = AthleticTheme.createButton(
            this,
            "Start 6 × 400m Interval Session",
            AthleticTheme.ButtonStyle.WARNING_AMBER,
            52f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            setOnClickListener {
                requestNeededPermissionsIfMissing(silentCheckOnly = false)
                trackingService?.startIntervalWorkout(selectedIntervalReps, 90.0)
            }
        }
        dialCard.addView(intervalPrimaryBtn)

        intervalSecondaryBtn = AthleticTheme.createButton(
            this,
            "Stop Intervals",
            AthleticTheme.ButtonStyle.SUBTLE_OUTLINE,
            44f
        ).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        dialCard.addView(intervalSecondaryBtn)
        col.addView(dialCard)

        val repSplitsCard = cardContainer()
        repSplitsCard.addView(AthleticTheme.sectionLabel(this, "COMPLETED 400M REP SPLITS"))
        intervalSplitsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        repSplitsCard.addView(intervalSplitsLayout)
        col.addView(repSplitsCard)

        return col
    }

    private fun refreshIntervalRepPresets() {
        for ((reps, tv) in intervalRepPresetBtns) {
            val active = reps == selectedIntervalReps
            if (active) {
                tv.background = AthleticTheme.solidPill(this, AthleticColors.AMBER_BRIGHT, AthleticColors.AMBER_BRIGHT, 8f, 0f)
                tv.setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            } else {
                tv.background = null
                tv.setTextColor(AthleticColors.TEXT_SECONDARY)
            }
        }
        if (::intervalPrimaryBtn.isInitialized && lastRenderedIntervalPhase == IntervalPhase.IDLE) {
            intervalPrimaryBtn.text = "Start $selectedIntervalReps × 400m Interval Session"
        }
    }

    // =========================================================================
    // TAB 2: PROGRESS, TSLPRB MERIT SCORE & DAYS-TO-TARGET ETA
    // =========================================================================
    private fun buildProgressTab(): ScrollView {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        // Hero Forecast & Merit Card
        val heroCard = cardContainer(
            topColor = AthleticColors.HERO_EMERALD_TOP,
            bottomColor = AthleticColors.HERO_EMERALD_BOTTOM,
            borderColor = AthleticColors.HERO_EMERALD_BORDER
        )
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(AthleticTheme.sectionLabel(this, "TARGET ETA & TSLPRB MERIT", AthleticColors.EMERALD_BRIGHT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        meritBadgeText = TextView(this).apply {
            text = "0 / 100 PTS"
            textSize = 10.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f))
            background = AthleticTheme.solidPill(this@MainActivity, 0x3310B981, AthleticColors.EMERALD_BRIGHT, 10f)
            setTextColor(AthleticColors.EMERALD_BRIGHT)
        }
        topRow.addView(meritBadgeText)
        heroCard.addView(topRow)

        etaHeadlineText = TextView(this).apply {
            text = "-- Days to 7:15"
            textSize = 27f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(8), 0, dp(4))
        }
        etaSubText = TextView(this).apply {
            text = "Loading progression..."
            textSize = 13f
            setTextColor(AthleticColors.TEXT_SECONDARY)
        }
        heroCard.addView(etaHeadlineText)
        heroCard.addView(etaSubText)

        coachPrescriptionText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(AthleticColors.CYAN_BRIGHT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = AthleticTheme.solidPill(this@MainActivity, 0x2238BDF8, AthleticColors.HERO_CYAN_BORDER, 11f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }
        heroCard.addView(coachPrescriptionText)
        col.addView(heroCard)

        // 2x2 KPI Bento Grid
        val kpiRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        val pbTile = metricTile("1.6K PERSONAL BEST", "--:--", "Best 1,600m time", AthleticColors.EMERALD_BRIGHT)
        kpiPbVal = pbTile.second
        val gapTile = metricTile("GAP TO CUTOFF", "--", "Seconds to shave", AthleticColors.AMBER_BRIGHT)
        kpiGapVal = gapTile.second
        kpiRow1.addView(pbTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        kpiRow1.addView(gapTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        col.addView(kpiRow1)

        val kpiRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(14) }
        }
        val rateTile = metricTile("WEEKLY GAIN", "--", "Pace improvement", AthleticColors.CYAN_BRIGHT)
        kpiRateVal = rateTile.second
        val impTile = metricTile("TOTAL SHAVED", "--", "Since Day 1")
        kpiImprovedVal = impTile.second
        kpiRow2.addView(rateTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        kpiRow2.addView(impTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        col.addView(kpiRow2)

        // Cubic-Bezier Trend Chart Card
        val chartCard = cardContainer()
        chartCard.addView(AthleticTheme.sectionLabel(this, "1.6 KM PROGRESSION & TARGET FORECAST"))
        trendChartView = ProgressTrendChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(228))
                .apply { topMargin = dp(6) }
        }
        chartCard.addView(trendChartView)
        chartCard.addView(TextView(this).apply {
            text = "Cyan = 1.6K Trials  •  Amber = Auto/Intervals  •  Green = Qualified  •  Dashed = ETA"
            textSize = 11f
            setTextColor(AthleticColors.TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        })
        col.addView(chartCard)

        // 3-Button Action Strip: [+ Log Run] | [Sample Data] | [Backup]
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(14) }
        }
        val manualBtn = AthleticTheme.createButton(this, "+ Log Time", AthleticTheme.ButtonStyle.PRIMARY_CYAN, 44f).apply {
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f).apply { marginEnd = dp(4) }
            setOnClickListener {
                SessionDialogs.showManualRunDialog(this@MainActivity, repository) {
                    refreshProgressDashboard()
                }
            }
        }
        demoToggleBtn = AthleticTheme.createButton(this, "Sample Data", AthleticTheme.ButtonStyle.SECONDARY_SLATE, 44f).apply {
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            }
            setOnClickListener {
                if (repository.hasDemoSessions()) {
                    repository.clearDemoSessions()
                    Toast.makeText(this@MainActivity, "Cleared sample demo runs", Toast.LENGTH_SHORT).show()
                } else {
                    repository.seedSampleProgressionIfRequested()
                    Toast.makeText(this@MainActivity, "Loaded 2-week sample progression!", Toast.LENGTH_SHORT).show()
                }
                refreshCachedDailyHistory(forceRender = true)
                refreshProgressDashboard()
            }
        }
        val backupBtn = AthleticTheme.createButton(this, "Backup", AthleticTheme.ButtonStyle.SUBTLE_OUTLINE, 44f).apply {
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f).apply { marginStart = dp(4) }
            setOnClickListener {
                SessionDialogs.showBackupAndRestoreDialog(this@MainActivity, repository) {
                    refreshCachedDailyHistory(forceRender = true)
                    refreshProgressDashboard()
                }
            }
        }
        actionsRow.addView(manualBtn)
        actionsRow.addView(demoToggleBtn)
        actionsRow.addView(backupBtn)
        col.addView(actionsRow)

        // Recorded Sessions History Card (Tap any row for 4x400m Splits & Merit Score!)
        val historyCard = cardContainer()
        historyCard.addView(AthleticTheme.sectionLabel(this, "TRAINING LOG  •  TAP ANY RUN FOR SPLITS"))
        sessionsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        historyCard.addView(sessionsListLayout)
        col.addView(historyCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 3: COACH, RUNNER PROFILE, POCKET AUDIO & SENSOR LAB
    // =========================================================================
    private fun buildGuideAndSensorsTab(): ScrollView {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        // 1. Samsung / Android Background Protection Card
        val batCard = cardContainer()
        val batTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        batTop.addView(AthleticTheme.sectionLabel(this, "SAMSUNG / ANDROID BACKGROUND GUARD").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        batteryStatusBadge = TextView(this).apply {
            text = "CHECKING"
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(dp(8), dp(3f), dp(8), dp(3f))
        }
        batTop.addView(batteryStatusBadge)
        batCard.addView(batTop)
        batCard.addView(TextView(this).apply {
            text = "Prevents One UI 'Deep Sleeping Apps' from killing pocket tracking while smart idle duty-cycling saves battery."
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(6), 0, dp(10))
        })
        batteryStatusBtn = AthleticTheme.createButton(
            this,
            "Allow Unrestricted Background Tracking",
            AthleticTheme.ButtonStyle.PRIMARY_CYAN,
            44f
        ).apply {
            textSize = 12.5f
            setOnClickListener { requestIgnoreBatteryOptimizationsSafely() }
        }
        batCard.addView(batteryStatusBtn)
        col.addView(batCard)

        // 2. Runner Profile, TSLPRB Target & Height Calibration Card
        val profileCard = cardContainer()
        profileCard.addView(AthleticTheme.sectionLabel(this, "RUNNER PROFILE & TSLPRB TARGET CUTOFF"))

        profileCard.addView(TextView(this).apply {
            text = "1,600m Target Cutoff / Merit Goal:"
            textSize = 12.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(10), 0, dp(6))
        })
        val targetPresetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
        }
        val presets = listOf(
            435.0 to "7:15 PET",
            390.0 to "6:30 Merit",
            360.0 to "6:00 Elite",
            570.0 to "9:30 Ex-S"
        )
        val tBtns = mutableListOf<Pair<Double, TextView>>()
        for ((sec, label) in presets) {
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11.5f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                setOnClickListener {
                    repository.targetTimeSec = sec
                    trackingService?.syncThresholdsFromPrefs()
                    refreshSettingsHighlights()
                    refreshProgressDashboard()
                }
            }
            tBtns.add(sec to btn)
            targetPresetRow.addView(btn)
        }
        targetPresetButtons = tBtns
        profileCard.addView(targetPresetRow)

        // Runner Height Stepper Row (Biomechanical Stride Baseline)
        val heightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
        }
        val heightLblCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        heightLblCol.addView(TextView(this).apply {
            text = "Runner Height (Stride Baseline)"
            textSize = 13.5f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.TEXT_PRIMARY)
        })
        heightLblCol.addView(TextView(this).apply {
            text = "Sets natural stride before Samsung Health calibration"
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
        })
        heightRow.addView(heightLblCol)

        fun stepBtn(symbol: String, delta: Int) = AthleticTheme.createButton(
            this,
            symbol,
            AthleticTheme.ButtonStyle.SECONDARY_SLATE,
            36f
        ).apply {
            minWidth = dp(38)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                repository.runnerHeightCm = (repository.runnerHeightCm + delta).coerceIn(145, 205)
                heightValText.text = "${repository.runnerHeightCm} cm"
                trackingService?.syncThresholdsFromPrefs()
                renderSamsungComparisonCard(force = true)
            }
        }
        heightValText = TextView(this).apply {
            text = "${repository.runnerHeightCm} cm"
            textSize = 13.5f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            setPadding(dp(10), 0, dp(10), 0)
        }
        heightRow.addView(stepBtn("−", -2))
        heightRow.addView(heightValText)
        heightRow.addView(stepBtn("+", +2))
        profileCard.addView(heightRow)

        profileCard.addView(TextView(this).apply {
            text = "Jogging → Running Speed Threshold:"
            textSize = 12.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(6))
        })
        val threshRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val threshPresets = listOf(
            2.85 to "5:50 /km",
            3.05 to "5:28 /km (Std)",
            3.33 to "5:00 /km"
        )
        val thBtns = mutableListOf<Pair<Double, TextView>>()
        for ((mps, label) in threshPresets) {
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11.5f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setPadding(dp(6), dp(9), dp(6), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                setOnClickListener {
                    repository.runningThresholdMps = mps
                    trackingService?.syncThresholdsFromPrefs()
                    refreshSettingsHighlights()
                }
            }
            thBtns.add(mps to btn)
            threshRow.addView(btn)
        }
        thresholdPresetButtons = thBtns
        profileCard.addView(threshRow)
        col.addView(profileCard)

        // 3. Pocket Audio 3-Mode Selector & Modern Pill Toggles Card
        val audioAndTogglesCard = cardContainer()
        audioAndTogglesCard.addView(AthleticTheme.sectionLabel(this, "POCKET AUDIO & SMART TRACKING"))
        audioAndTogglesCard.addView(TextView(this).apply {
            text = "Pocket Voice Mode (Smart Audio stays quiet during indoor walks):"
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(8), 0, dp(6))
        })

        val audioSegRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 12f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        val aBtns = mutableListOf<Pair<PocketAudioMode, TextView>>()
        for (mode in PocketAudioMode.values()) {
            val tv = TextView(this).apply {
                text = mode.shortLabel
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setPadding(dp(6), dp(9), dp(6), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    repository.pocketAudioMode = mode
                    trackingService?.syncPocketForegroundAndWakeLock()
                    refreshSettingsHighlights()
                    trackingService?.previewStateChangeAudio()
                }
            }
            aBtns.add(mode to tv)
            audioSegRow.addView(tv)
        }
        audioModeButtons = aBtns
        audioAndTogglesCard.addView(audioSegRow)

        audioAndTogglesCard.addView(
            AthleticTheme.createButton(this, "Test Voice Coach Cue", AthleticTheme.ButtonStyle.SECONDARY_SLATE, 42f).apply {
                textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
                setOnClickListener { trackingService?.previewStateChangeAudio(MotionState.TARGET_PACE) }
            }
        )

        audioAndTogglesCard.addView(AthleticTheme.hairlineDivider(this, 4f))

        allDayAutoRow = ModernSettingRow(
            this,
            "24/7 Smart All-Day Auto Tracking",
            "Counts Walk & Jog+Run automatically; sleeps GPS when stationary",
            repository.allDayTrackingEnabled
        ) { checked ->
            repository.allDayTrackingEnabled = checked
            trackingService?.syncPocketForegroundAndWakeLock()
        }
        audioAndTogglesCard.addView(allDayAutoRow)
        audioAndTogglesCard.addView(AthleticTheme.hairlineDivider(this, 2f))

        voiceCoachRow = ModernSettingRow(
            this,
            "Track Split & Final-200m Kick Coach",
            "Calls out 400m splits, 1,400m sprint kick & 1,600m finish verdict",
            repository.voiceCoachEnabled
        ) { checked -> repository.voiceCoachEnabled = checked }
        audioAndTogglesCard.addView(voiceCoachRow)
        audioAndTogglesCard.addView(AthleticTheme.hairlineDivider(this, 2f))

        stepFallbackRow = ModernSettingRow(
            this,
            "Step-Stride Indoor & Curve Assist",
            "Uses calibrated stride length indoors or when stadium GPS weakens",
            repository.indoorStepFallbackEnabled
        ) { checked -> repository.indoorStepFallbackEnabled = checked }
        audioAndTogglesCard.addView(stepFallbackRow)
        audioAndTogglesCard.addView(AthleticTheme.hairlineDivider(this, 2f))

        autoCalSamsungRow = ModernSettingRow(
            this,
            "Samsung Health Stride Auto-Calibration",
            "Tunes personal stride multiplier from Samsung Health ground truth",
            repository.autoCalibrateFromSamsung
        ) { checked ->
            repository.autoCalibrateFromSamsung = checked
            trackingService?.syncThresholdsFromPrefs()
            renderSamsungComparisonCard(force = true)
        }
        audioAndTogglesCard.addView(autoCalSamsungRow)
        audioAndTogglesCard.addView(AthleticTheme.hairlineDivider(this, 2f))

        autoImportSamsungRow = ModernSettingRow(
            this,
            "14-Day Samsung / Watch Run Sync",
            "Imports non-overlapping ≥600m runs into 7:15 ETA forecast",
            repository.autoImportSamsungRuns
        ) { checked -> repository.autoImportSamsungRuns = checked }
        audioAndTogglesCard.addView(autoImportSamsungRow)

        col.addView(audioAndTogglesCard)

        // 4. Even-Split Pacing Reference Table Card
        val tableCard = cardContainer()
        tableCard.addView(AthleticTheme.sectionLabel(this, "EVEN-SPLIT CHECKPOINTS FOR 7:15 (435s)"))
        val splitRows = listOf(
            Triple("200 m (Half Lap)", "0:54.4", "13.24 km/h"),
            Triple("400 m (Lap 1)", "1:48.8", "4:31.9 /km"),
            Triple("800 m (Lap 2 • Half)", "3:37.5", "Controlled rhythm"),
            Triple("1,200 m (Lap 3)", "5:26.3", "Hold ≥168 spm"),
            Triple("1,400 m (Kick Bell)", "6:20.6", "All-out 200m sprint!"),
            Triple("1,600 m (Finish)", "7:15.0", "QUALIFIED (40+ pts)")
        )
        val tableWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        for ((dist, split, note) in splitRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(8), dp(11), dp(8))
                background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 9f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(5) }
            }
            row.addView(TextView(this).apply {
                text = dist
                textSize = 12f
                typeface = AthleticTheme.FONT_SANS_MEDIUM
                setTextColor(AthleticColors.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f)
            })
            row.addView(TextView(this).apply {
                text = split
                textSize = 12.5f
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(AthleticColors.EMERALD_BRIGHT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f)
            })
            row.addView(TextView(this).apply {
                text = note
                textSize = 11f
                gravity = Gravity.END
                setTextColor(AthleticColors.TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f)
            })
            tableWrap.addView(row)
        }
        tableCard.addView(tableWrap)
        col.addView(tableCard)

        // 5. Live Sensor Diagnostics & Maintenance Card
        val diagCard = cardContainer()
        diagCard.addView(AthleticTheme.sectionLabel(this, "SENSOR FUSION DIAGNOSTICS & DATA"))
        diagGpsText = TextView(this).apply {
            text = "GPS Engine: Checking..."
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(8), 0, dp(3))
        }
        diagStepText = TextView(this).apply {
            text = "Step / dt-Filter Engine: Checking..."
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(2), 0, dp(3))
        }
        diagStrideCalText = TextView(this).apply {
            text = String.format("Effective Stride Scale: %.2fx", repository.effectiveStrideScale())
            textSize = 12f
            setTextColor(AthleticColors.CYAN_BRIGHT)
            setPadding(0, dp(2), 0, dp(3))
        }
        diagStateText = TextView(this).apply {
            text = "Live Gait Verdict: STANDING"
            textSize = 12f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            setPadding(0, dp(2), 0, dp(10))
        }
        diagCard.addView(diagGpsText)
        diagCard.addView(diagStepText)
        diagCard.addView(diagStrideCalText)
        diagCard.addView(diagStateText)

        val maintRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val backupBtn2 = AthleticTheme.createButton(this, "Backup / Restore", AthleticTheme.ButtonStyle.SECONDARY_SLATE, 42f).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
            setOnClickListener {
                SessionDialogs.showBackupAndRestoreDialog(this@MainActivity, repository) {
                    refreshCachedDailyHistory(forceRender = true)
                    refreshProgressDashboard()
                }
            }
        }
        val resetDailyBtn = AthleticTheme.createButton(this, "Reset Today", AthleticTheme.ButtonStyle.SUBTLE_OUTLINE, 42f).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) }
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset Today's Daily Counters?")
                    .setMessage("Resets today's Walk & Jog+Run counters to 0:00 (saved 1.6 km runs are untouched).")
                    .setPositiveButton("Reset Today") { _, _ ->
                        trackingService?.resetTodayDailySummary()
                        refreshCachedDailyHistory(forceRender = true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        maintRow.addView(backupBtn2)
        maintRow.addView(resetDailyBtn)
        diagCard.addView(maintRow)
        col.addView(diagCard)

        // 6. GitHub OTA Auto-Update Card
        val updateCard = cardContainer()
        updateCard.addView(AthleticTheme.sectionLabel(this, "APP VERSION & GITHUB OTA AUTO-UPDATE"))
        val (instCode, instName) = appUpdater.getInstalledVersion()
        updateStatusText = TextView(this).apply {
            text = "Installed: v$instName (Build $instCode)  •  GitHub Releases OTA"
            textSize = 12.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(8), 0, dp(6))
        }
        updateCard.addView(updateStatusText)

        val autoUpdateRow = ModernSettingRow(
            this,
            "Automatic OTA Update Checks",
            "Checks GitHub Releases in background & notifies on new builds",
            appUpdater.autoCheckOnLaunch
        ) { checked -> appUpdater.autoCheckOnLaunch = checked }
        updateCard.addView(autoUpdateRow)

        checkUpdateBtn = AthleticTheme.createButton(
            this,
            "Check for App Updates Now",
            AthleticTheme.ButtonStyle.PRIMARY_CYAN,
            46f
        ).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            setOnClickListener { performUpdateCheck(manualUserTrigger = true) }
        }
        updateCard.addView(checkUpdateBtn)
        col.addView(updateCard)

        refreshBatterySettingsState()
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // LIVE TELEMETRY RENDERING
    // =========================================================================
    private fun formatDistanceShort(meters: Double): String =
        if (meters >= 1000.0) String.format("%.2f km", meters / 1000.0) else "${meters.toInt()} m"

    private fun refreshCachedDailyHistory(forceRender: Boolean = false) {
        val today = LocalDate.now().toString()
        cachedPastDailyLogs = repository.getRecentDailyActivities(10).filter { it.dateIso != today }
        if (forceRender) {
            lastDailyHistoryRenderMs = -60_000L
            lastDailyHistoryKey = ""
        }
    }

    private fun renderDailyHistoryCards(todaySummary: DailyActivitySummary) {
        val nowMs = SystemClock.elapsedRealtime()
        val key = "${todaySummary.dateIso}:${todaySummary.walkingSec.toInt()}:${todaySummary.jogRunCombinedSec.toInt()}:${todaySummary.totalSteps}:${cachedPastDailyLogs.size}"
        if (key == lastDailyHistoryKey && (nowMs - lastDailyHistoryRenderMs) < 4000L) return
        if ((nowMs - lastDailyHistoryRenderMs) >= 15_000L) {
            val today = LocalDate.now().toString()
            cachedPastDailyLogs = repository.getRecentDailyActivities(10).filter { it.dateIso != today }
        }
        lastDailyHistoryKey = key
        lastDailyHistoryRenderMs = nowMs

        val rows = mutableListOf(todaySummary).apply { addAll(cachedPastDailyLogs) }
        dailyHistoryListLayout.removeAllViews()
        for ((idx, day) in rows.withIndex()) {
            val isToday = idx == 0
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = AthleticTheme.solidPill(
                    this@MainActivity,
                    if (isToday) 0xFF0E1D30.toInt() else AthleticColors.SURFACE_RECESSED,
                    if (isToday) AthleticColors.HERO_CYAN_BORDER else AthleticColors.BORDER_SUBTLE,
                    11f
                )
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(7) }
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = when {
                    isToday -> "TODAY  •  ${day.dateIso}"
                    day.isDemo -> "${day.dateIso}  •  SAMPLE"
                    else -> day.dateIso
                }
                textSize = 12f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(if (isToday) AthleticColors.CYAN_BRIGHT else AthleticColors.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this).apply {
                text = "${formatDistanceShort(day.totalDistanceMeters)}  •  ${day.totalSteps} steps"
                textSize = 12f
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(AthleticColors.TEXT_PRIMARY)
            })
            card.addView(top)
            card.addView(TextView(this).apply {
                text = "Jog+Run ${TsPoliceBenchmark.formatDuration(day.jogRunCombinedSec)}   •   " +
                    "Walk ${TsPoliceBenchmark.formatDuration(day.walkingSec)}   •   " +
                    "Peak ${TsPoliceBenchmark.formatPacePerKm(day.maxSpeedMps)}"
                textSize = 11.5f
                setTextColor(AthleticColors.TEXT_SECONDARY)
                setPadding(0, dp(4), 0, 0)
            })
            dailyHistoryListLayout.addView(card)
        }
    }

    private fun renderLiveTelemetry(snap: LiveTelemetry) {
        currentlyRecording = snap.isRecording
        refreshHeaderAudioPill()
        val targetSec = repository.targetTimeSec
        val daily = snap.dailySummary

        // Auto-switch sub-tab when PT Trial or Interval session activates
        if (snap.ptTrialPhase != lastRenderedPtPhase) {
            if (snap.ptTrialPhase != PtTrialPhase.IDLE) selectTrackerSubTab(1)
            lastRenderedPtPhase = snap.ptTrialPhase
        }
        if (snap.intervalState.phase != lastRenderedIntervalPhase) {
            if (snap.intervalState.phase != IntervalPhase.IDLE) selectTrackerSubTab(2)
            lastRenderedIntervalPhase = snap.intervalState.phase
        }

        subTabPtBtn.text = when (snap.ptTrialPhase) {
            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> "1.6K (Armed)"
            PtTrialPhase.RUNNING_TRIAL -> "1.6K (LIVE)"
            PtTrialPhase.IDLE -> "1.6K Trial"
        }
        subTabIntervalsBtn.text = if (snap.intervalState.phase != IntervalPhase.IDLE) {
            "400m (${snap.intervalState.currentRep}/${snap.intervalState.totalReps})"
        } else {
            "400m Coach"
        }

        // =====================================================================
        // A. RENDER TODAY (SUB-VIEW 0)
        // =====================================================================
        val allDayOn = repository.allDayTrackingEnabled
        allDayLiveChip.text = when {
            !allDayOn -> "PAUSED"
            snap.isLowPowerStandby -> "BATTERY SAVER"
            snap.isGpsLocked -> "GPS ACTIVE"
            else -> "24/7 ACTIVE"
        }
        val chipCol = if (allDayOn) AthleticColors.EMERALD_BRIGHT else AthleticColors.TEXT_MUTED
        allDayLiveChip.background = AthleticTheme.solidPill(this, chipCol, chipCol, 10f, 0f)

        dailyRingsView.updateMetrics(daily.jogRunCombinedSec, daily.totalSteps)
        dailyJogRunTimeVal.text = TsPoliceBenchmark.formatDuration(daily.jogRunCombinedSec)
        dailyJogRunDistSub.text = "${formatDistanceShort(daily.jogRunCombinedMeters)}  •  Jog ${TsPoliceBenchmark.formatDuration(daily.joggingSec)} / Run ${TsPoliceBenchmark.formatDuration(daily.runningSec)}"
        dailyWalkTimeVal.text = TsPoliceBenchmark.formatDuration(daily.walkingSec)
        dailyWalkDistSub.text = "${formatDistanceShort(daily.walkingMeters)} walked today"

        allDayGaitPill.text = when {
            snap.ptTrialPhase == PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN ->
                "1.6 km PT Trial Armed  •  Waiting for first Jog/Run stride"
            snap.ptTrialPhase == PtTrialPhase.RUNNING_TRIAL ->
                "1.6 km PT Trial Live: ${snap.distanceMeters.toInt()}m / 1,600m"
            snap.isLowPowerStandby ->
                "Standing  •  GPS Sleeping (0% Battery Drain — Wakes on Steps)"
            snap.motionState == MotionState.STANDING ->
                "Standing (Auto-Paused)  •  Step-Stride Ready"
            else ->
                "${snap.motionState.label}  •  ${TsPoliceBenchmark.formatPacePerKm(snap.currentSpeedMps)} (${snap.cadenceSpm.roundToInt()} spm)"
        }
        allDayGaitPill.setTextColor(
            if (snap.motionState == MotionState.STANDING) AthleticColors.TEXT_SECONDARY else snap.motionState.colorArgb
        )

        launchPtFromOpenBtn.text = when (snap.ptTrialPhase) {
            PtTrialPhase.IDLE -> "Arm 1.6 km PT Trial (Launches on Jog/Run)  →"
            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> "1.6 km PT Trial Armed  •  Open Cockpit  →"
            PtTrialPhase.RUNNING_TRIAL -> "View Live 1.6 km PT Trial (${snap.distanceMeters.toInt()}m)  →"
        }

        dailyMaxPaceVal.text = TsPoliceBenchmark.formatPacePerKm(daily.maxSpeedMps)
        dailyMaxPaceSub.text = String.format("Max %.1f km/h today", daily.maxSpeedMps * 3.6)
        dailyMaxCadenceVal.text = "${daily.maxCadenceSpm.roundToInt()} spm"
        dailyMaxCadenceSub.text = if (daily.maxCadenceSpm >= 168.0) "Target stride hit!" else "Peak stride rate"
        openLivePaceVal.text = TsPoliceBenchmark.formatPacePerKm(snap.currentSpeedMps)
        openLivePaceSub.text = "${snap.cadenceSpm.roundToInt()} spm live cadence"
        dailyTotalDistVal.text = formatDistanceShort(daily.totalDistanceMeters)
        dailyTotalDistSub.text = if (snap.isGpsLocked) {
            "GPS ±${snap.gpsAccuracyMeters?.toInt() ?: 0}m + Stride"
        } else {
            "Calibrated Stride (${String.format("%.2fx", repository.effectiveStrideScale())})"
        }

        dailyGaitBar.setDurations(daily.runningSec, daily.joggingSec, daily.walkingSec, 0.0)
        dailyGaitLegendText.text =
            "Run ${TsPoliceBenchmark.formatDuration(daily.runningSec)}   •   " +
                "Jog ${TsPoliceBenchmark.formatDuration(daily.joggingSec)}   •   " +
                "Walk ${TsPoliceBenchmark.formatDuration(daily.walkingSec)}"

        latestDailySummaryForCompare = daily
        renderSamsungComparisonCard(force = false)
        renderDailyHistoryCards(daily)

        // =====================================================================
        // B. RENDER 1.6 KM PT TRIAL COCKPIT (SUB-VIEW 1)
        // =====================================================================
        val acc = snap.gpsAccuracyMeters
        when {
            snap.isGpsLocked && acc != null && acc <= 12f -> {
                ptGpsReadinessPill.text = "TRACK GPS READY ±${acc.toInt()}m"
                ptGpsReadinessPill.setTextColor(AthleticColors.EMERALD_BRIGHT)
                ptGpsReadinessPill.background = AthleticTheme.solidPill(this, 0x2210B981, AthleticColors.HERO_EMERALD_BORDER, 10f)
            }
            acc != null && acc <= 26f -> {
                ptGpsReadinessPill.text = "GPS WARMING ±${acc.toInt()}m"
                ptGpsReadinessPill.setTextColor(AthleticColors.AMBER_BRIGHT)
                ptGpsReadinessPill.background = AthleticTheme.solidPill(this, 0x22F59E0B, AthleticColors.HERO_AMBER_BORDER, 10f)
            }
            else -> {
                ptGpsReadinessPill.text = "STRIDE FUSION READY"
                ptGpsReadinessPill.setTextColor(AthleticColors.CYAN_BRIGHT)
                ptGpsReadinessPill.background = AthleticTheme.solidPill(this, 0x2238BDF8, AthleticColors.HERO_CYAN_BORDER, 10f)
            }
        }

        paceArcGauge.updateState(
            distanceMeters = snap.distanceMeters,
            activeDurationSec = snap.activeDurationSec,
            targetSec = targetSec,
            motionState = snap.motionState,
            recording = snap.ptTrialPhase == PtTrialPhase.RUNNING_TRIAL,
            armed = snap.ptTrialPhase == PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN,
            pausedPenaltySec = snap.pausedDurationSec
        )

        metricPaceVal.text = TsPoliceBenchmark.formatPacePerKm(snap.currentSpeedMps)
        metricPaceSub.text = String.format("%.1f km/h ground speed", snap.currentSpeedMps * 3.6)
        metricCadenceVal.text = "${snap.cadenceSpm.roundToInt()} spm"
        metricCadenceSub.text = "${snap.totalSteps} steps today"

        val projSec = if (snap.ptTrialPhase == PtTrialPhase.RUNNING_TRIAL) {
            TsPoliceBenchmark.projected1600mFinishSec(snap.distanceMeters, snap.activeDurationSec, snap.currentSpeedMps)
        } else null
        metricProjVal.text = if (projSec != null) TsPoliceBenchmark.formatDuration(projSec) else "--:--"
        metricProjVal.setTextColor(
            when {
                projSec == null -> AthleticColors.TEXT_PRIMARY
                projSec <= targetSec -> AthleticColors.EMERALD_BRIGHT
                else -> AthleticColors.AMBER_BRIGHT
            }
        )
        metricProjSub.text = "Cutoff < ${TsPoliceBenchmark.formatDuration(targetSec)}"

        metricSensorVal.text = String.format("%.1f m/s²", snap.dynamicAccelRms)
        metricSensorSub.text = if (snap.isGpsLocked && acc != null) {
            "Curve-Adjusted GPS (±${acc.toInt()}m)"
        } else {
            "Calibrated Stride Mode"
        }

        gaitBar.setDurations(snap.runningSec, snap.joggingSec, snap.walkingSec, snap.pausedDurationSec)
        gaitLegendText.text =
            "Run ${TsPoliceBenchmark.formatDuration(snap.runningSec)}  •  " +
                "Jog ${TsPoliceBenchmark.formatDuration(snap.joggingSec)}  •  " +
                "Walk ${TsPoliceBenchmark.formatDuration(snap.walkingSec)}  •  " +
                "Stopped ${TsPoliceBenchmark.formatDuration(snap.pausedDurationSec)}"

        when (snap.ptTrialPhase) {
            PtTrialPhase.IDLE -> {
                primaryActionBtn.visibility = View.VISIBLE
                ptRunningControlsRow.visibility = View.GONE
                primaryActionBtn.text = "ARM 1.6 KM PT TRIAL (Starts on Jog/Run)"
                AthleticTheme.applyButtonStyle(this, primaryActionBtn, AthleticTheme.ButtonStyle.PRIMARY_EMERALD)
                primaryActionBtn.setOnClickListener {
                    requestNeededPermissionsIfMissing(silentCheckOnly = false)
                    trackingService?.armPtTrial()
                }
                ptArmHintText.text =
                    "Arm trial, pocket your phone & walk to the line — official unpaused clock starts at 0:00 on your first jog/run stride."
                ptArmHintText.setTextColor(AthleticColors.TEXT_SECONDARY)

                secondaryActionBtn.visibility = View.VISIBLE
                secondaryActionBtn.text = "← Back to Today Overview"
                secondaryActionBtn.setOnClickListener { selectTrackerSubTab(0) }
            }

            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> {
                primaryActionBtn.visibility = View.VISIBLE
                ptRunningControlsRow.visibility = View.GONE
                primaryActionBtn.text = "ARMED • WAITING FOR JOG / RUN..."
                AthleticTheme.applyButtonStyle(this, primaryActionBtn, AthleticTheme.ButtonStyle.WARNING_AMBER)
                // Pocket Mis-Tap Protection: confirm before disarming!
                primaryActionBtn.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Disarm 1.6 km PT Trial?")
                        .setMessage("Keep the trial armed while your phone is in your pocket, or cancel?")
                        .setPositiveButton("Cancel Trial") { _, _ -> trackingService?.cancelPtTrial() }
                        .setNegativeButton("Keep Armed", null)
                        .show()
                }
                ptArmHintText.text =
                    "Live Gait: ${snap.motionState.label}  •  Standing & Walking ignored. Start jogging or running to launch!"
                ptArmHintText.setTextColor(AthleticColors.AMBER_BRIGHT)
                secondaryActionBtn.visibility = View.GONE
            }

            PtTrialPhase.RUNNING_TRIAL -> {
                primaryActionBtn.visibility = View.GONE
                ptRunningControlsRow.visibility = View.VISIBLE
                ptArmHintText.text =
                    "Auto-finishes at 1,600m  •  400m splits + 1,400m kick bell  •  Official unpaused clock"
                ptArmHintText.setTextColor(AthleticColors.EMERALD_BRIGHT)

                secondaryActionBtn.visibility = View.VISIBLE
                secondaryActionBtn.text = "Discard Trial Without Saving"
                secondaryActionBtn.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Discard Active 1.6 km PT Trial?")
                        .setMessage("This aborts the current 1.6 km trial without saving.")
                        .setPositiveButton("Discard Trial") { _, _ -> trackingService?.cancelPtTrial() }
                        .setNegativeButton("Keep Running", null)
                        .show()
                }
            }
        }

        // Auto-jump to Progress tab when a PT Trial or Interval session completes hands-free!
        if (snap.ptTrialPhase == PtTrialPhase.IDLE &&
            snap.intervalState.phase == IntervalPhase.IDLE &&
            snap.justFinishedSessionId != null &&
            snap.justFinishedSessionId != lastHandledFinishId
        ) {
            lastHandledFinishId = snap.justFinishedSessionId
            refreshProgressDashboard()
            Toast.makeText(this, "Session Complete! Saved to Progress & Merit Forecast.", Toast.LENGTH_LONG).show()
            selectTab(1)
        }

        // Render Live 400m Lap Splits
        splitsListLayout.removeAllViews()
        if (snap.lapSplits.isEmpty()) {
            splitsListLayout.addView(TextView(this).apply {
                text = "Splits record automatically at 400m • 800m • 1,200m • 1,600m (or tap '+ Snap 400m Lap' at the chalk line)."
                textSize = 12f
                setTextColor(AthleticColors.TEXT_MUTED)
            })
        } else {
            val targetLap = TsPoliceBenchmark.targetLap400mSec(targetSec)
            for (sp in snap.lapSplits) {
                val fast = sp.lapDurationSec <= targetLap
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 9f)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = dp(5) }
                }
                row.addView(TextView(this).apply {
                    text = "Lap ${sp.lapNumber} (${sp.distanceMeters.toInt()}m)"
                    textSize = 12.5f
                    typeface = AthleticTheme.FONT_SANS_MEDIUM
                    setTextColor(AthleticColors.TEXT_PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                })
                row.addView(TextView(this).apply {
                    text = TsPoliceBenchmark.formatDuration(sp.lapDurationSec, true)
                    textSize = 13f
                    typeface = AthleticTheme.FONT_TABULAR_BOLD
                    setTextColor(if (fast) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
                })
                row.addView(TextView(this).apply {
                    text = "Cum ${TsPoliceBenchmark.formatDuration(sp.cumulativeSec)}"
                    textSize = 11.5f
                    gravity = Gravity.END
                    setTextColor(AthleticColors.TEXT_SECONDARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
                })
                splitsListLayout.addView(row)
            }
        }

        // =====================================================================
        // C. RENDER 400M INTERVAL COACH (SUB-VIEW 2)
        // =====================================================================
        val iv = snap.intervalState
        intervalDialView.update(iv)
        when (iv.phase) {
            IntervalPhase.IDLE, IntervalPhase.COMPLETED -> {
                intervalPrimaryBtn.text = "Start $selectedIntervalReps × 400m Interval Session"
                AthleticTheme.applyButtonStyle(this, intervalPrimaryBtn, AthleticTheme.ButtonStyle.WARNING_AMBER)
                intervalPrimaryBtn.setOnClickListener {
                    requestNeededPermissionsIfMissing(silentCheckOnly = false)
                    trackingService?.startIntervalWorkout(selectedIntervalReps, 90.0)
                }
                intervalSecondaryBtn.visibility = View.GONE
            }
            IntervalPhase.RUNNING_REP -> {
                intervalPrimaryBtn.text = "Rep ${iv.currentRep}/${iv.totalReps} in Progress (${iv.repDistanceMeters.toInt()}m / 400m)"
                AthleticTheme.applyButtonStyle(this, intervalPrimaryBtn, AthleticTheme.ButtonStyle.PRIMARY_EMERALD)
                intervalPrimaryBtn.setOnClickListener { }
                intervalSecondaryBtn.visibility = View.VISIBLE
                intervalSecondaryBtn.text = "Stop Interval Workout"
                intervalSecondaryBtn.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Stop 400m Interval Session?")
                        .setPositiveButton("Stop") { _, _ -> trackingService?.cancelIntervalWorkout() }
                        .setNegativeButton("Keep Going", null)
                        .show()
                }
            }
            IntervalPhase.REST_COUNTDOWN -> {
                intervalPrimaryBtn.text = "Start Rep ${iv.currentRep + 1} Now (Skip Rest)"
                AthleticTheme.applyButtonStyle(this, intervalPrimaryBtn, AthleticTheme.ButtonStyle.PRIMARY_CYAN)
                intervalPrimaryBtn.setOnClickListener { trackingService?.skipIntervalRestNow() }
                intervalSecondaryBtn.visibility = View.VISIBLE
                intervalSecondaryBtn.text = "End Session"
                intervalSecondaryBtn.setOnClickListener { trackingService?.cancelIntervalWorkout() }
            }
        }

        intervalSplitsLayout.removeAllViews()
        if (iv.completedRepSplitsSec.isEmpty()) {
            intervalSplitsLayout.addView(TextView(this).apply {
                text = "Completed 400m rep times appear here (target ${TsPoliceBenchmark.formatDuration(iv.targetRepSec, true)} / 400m)."
                textSize = 12f
                setTextColor(AthleticColors.TEXT_MUTED)
            })
        } else {
            iv.completedRepSplitsSec.forEachIndexed { idx, sec ->
                val fast = sec <= iv.targetRepSec + 2.0
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 9f)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = dp(5) }
                }
                r.addView(TextView(this).apply {
                    text = "Rep ${idx + 1} (400m)"
                    textSize = 12.5f
                    setTextColor(AthleticColors.TEXT_PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                r.addView(TextView(this).apply {
                    text = TsPoliceBenchmark.formatDuration(sec, true)
                    textSize = 13f
                    typeface = AthleticTheme.FONT_TABULAR_BOLD
                    setTextColor(if (fast) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT)
                })
                intervalSplitsLayout.addView(r)
            }
        }

        // =====================================================================
        // D. TAB 3 DIAGNOSTICS
        // =====================================================================
        diagGpsText.text = when {
            snap.isLowPowerStandby -> "GPS Engine: Sleeping (Smart Stationary Battery Saver — 0% GPS Drain)"
            snap.isGpsLocked -> "GPS Engine: LOCKED (±${snap.gpsAccuracyMeters?.toInt() ?: 0}m) • Curve Compensation Active"
            else -> "GPS Engine: Standby / Indoor Stride Estimation Active"
        }
        diagStepText.text = if (snap.hasHardwareStepSensor) {
            "Step Engine: Hardware Step Detector + dt-Normalized 3-Axis Accel (${snap.cadenceSpm.roundToInt()} spm)"
        } else {
            "Step Engine: dt-Normalized Bipolar Gait Peak Detector (${snap.cadenceSpm.roundToInt()} spm)"
        }
        diagStrideCalText.text = String.format(
            "Effective Stride Scale: %.2fx (%s)",
            repository.effectiveStrideScale(),
            if (repository.hasSamsungStrideCalibration && repository.autoCalibrateFromSamsung) {
                "Samsung Health Calibrated"
            } else {
                "Height ${repository.runnerHeightCm} cm Baseline"
            }
        )
        diagStateText.text = "Live Gait Verdict: ${snap.motionState.badgeText}"
        diagStateText.setTextColor(snap.motionState.colorArgb)
    }

    // =========================================================================
    // SAMSUNG HEALTH RENDERING & SYNC
    // =========================================================================
    private fun formatSignedSeconds(deltaSec: Double): String {
        val rounded = deltaSec.roundToInt()
        if (abs(rounded) <= 3) return "Exact"
        val sign = if (rounded > 0) "+" else "-"
        val a = abs(rounded)
        return String.format("%s%d:%02d", sign, a / 60, a % 60)
    }

    private fun renderSamsungComparisonCard(force: Boolean) {
        if (!::samsungHeadlineText.isInitialized) return
        val daily = latestDailySummaryForCompare
        val sam = latestSamsungSnapshot
        val scale = repository.effectiveStrideScale()

        val key = "${healthSyncStatus}:${isHealthSyncInFlight}:${daily.totalSteps}:${daily.totalDistanceMeters.toInt()}:" +
            "${daily.walkingSec.toInt()}:${daily.jogRunCombinedSec.toInt()}:${sam?.steps}:${sam?.distanceMeters?.toInt()}:${String.format("%.3f", scale)}"
        if (!force && key == lastSamsungRenderKey) return
        lastSamsungRenderKey = key

        if (isHealthSyncInFlight) {
            samsungSyncBadge.text = "SYNCING..."
            samsungSyncBadge.background = AthleticTheme.solidPill(this, AthleticColors.CYAN_BRIGHT, AthleticColors.CYAN_BRIGHT, 10f, 0f)
            samsungPrimaryBtn.isEnabled = false
            samsungPrimaryBtn.text = "Syncing Health Connect..."
            return
        }
        samsungPrimaryBtn.isEnabled = true

        when (healthSyncStatus) {
            HealthSyncStatus.NOT_INSTALLED -> {
                samsungCompareTable.visibility = View.GONE
                samsungSyncBadge.text = "INSTALL HC"
                samsungSyncBadge.background = AthleticTheme.solidPill(this, AthleticColors.AMBER_BRIGHT, AthleticColors.AMBER_BRIGHT, 10f, 0f)
                samsungHeadlineText.text = "Android Health Connect Needed"
                samsungDetailText.text = "Install or update Health Connect so Samsung Health can share steps, distance & 14-day runs."
                samsungPrimaryBtn.text = "Get Health Connect"
                samsungPrimaryBtn.setOnClickListener { healthSyncManager.openSamsungHealthOrHealthConnectSettings() }
                samsungSecondaryBtn.visibility = View.VISIBLE
                samsungSecondaryBtn.text = "Retry"
                samsungSecondaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }
            }

            HealthSyncStatus.PERMISSION_REQUIRED -> {
                samsungCompareTable.visibility = View.GONE
                samsungSyncBadge.text = "CONNECT"
                samsungSyncBadge.background = AthleticTheme.solidPill(this, AthleticColors.CYAN_BRIGHT, AthleticColors.CYAN_BRIGHT, 10f, 0f)
                samsungHeadlineText.text = "Connect Samsung Health for Ground-Truth Accuracy"
                samsungDetailText.text =
                    "Compares Tracker vs Samsung Health side-by-side, calibrates your personal stride & imports past 14 days of Watch/Samsung runs."
                samsungPrimaryBtn.text = "Connect Samsung Health"
                samsungPrimaryBtn.setOnClickListener { requestSamsungPermissionsSafely() }
                samsungSecondaryBtn.visibility = View.VISIBLE
                samsungSecondaryBtn.text = "Samsung App"
                samsungSecondaryBtn.setOnClickListener {
                    if (!healthSyncManager.openSamsungHealthAppDirectly()) {
                        healthSyncManager.openSamsungHealthOrHealthConnectSettings()
                    }
                }
            }

            HealthSyncStatus.WAITING_FOR_SAMSUNG_SYNC -> {
                samsungCompareTable.visibility = View.GONE
                samsungSyncBadge.text = "ENABLE SYNC"
                samsungSyncBadge.background = AthleticTheme.solidPill(this, AthleticColors.AMBER_BRIGHT, AthleticColors.AMBER_BRIGHT, 10f, 0f)
                samsungHeadlineText.text = "Turn ON Health Connect inside Samsung Health"
                samsungDetailText.text =
                    "In Samsung Health → ⋮ → Settings → Health Connect → Allow all, then tap 'Sync Now'."
                samsungPrimaryBtn.text = "Open Samsung Health"
                samsungPrimaryBtn.setOnClickListener {
                    if (!healthSyncManager.openSamsungHealthAppDirectly()) {
                        healthSyncManager.openSamsungHealthOrHealthConnectSettings()
                    }
                }
                samsungSecondaryBtn.visibility = View.VISIBLE
                samsungSecondaryBtn.text = "Sync Now"
                samsungSecondaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }
            }

            HealthSyncStatus.SYNCED, HealthSyncStatus.ERROR -> {
                if (sam == null) {
                    samsungCompareTable.visibility = View.GONE
                    samsungSyncBadge.text = "SYNC"
                    samsungHeadlineText.text = "Tap Sync Now to Pull Samsung Health Data"
                    samsungDetailText.text = "Ensure Health Connect permissions are active for Samsung Health and TS Police 1.6K."
                    samsungPrimaryBtn.text = "Sync & Calibrate"
                    samsungPrimaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }
                    samsungSecondaryBtn.visibility = View.VISIBLE
                    samsungSecondaryBtn.text = "Settings"
                    samsungSecondaryBtn.setOnClickListener { healthSyncManager.openSamsungHealthOrHealthConnectSettings() }
                    return
                }

                val report = SamsungHealthComparator.compare(daily, sam, scale)
                samsungCompareTable.visibility = View.VISIBLE

                val matchBadge = report.overallMatchPercent?.let { String.format("%.0f%% MATCH", it) }
                    ?: String.format("STRIDE %.2fx", scale)
                val highMatch = (report.overallMatchPercent ?: 100.0) >= 88.0
                val badgeCol = if (highMatch) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
                samsungSyncBadge.text = matchBadge
                samsungSyncBadge.background = AthleticTheme.solidPill(this, badgeCol, badgeCol, 10f, 0f)

                samsungHeadlineText.text = report.headline
                samsungDetailText.text = report.detailAdvice

                samRowStepsTracker.text = "${daily.totalSteps}"
                samRowStepsSamsung.text = "${sam.steps}"
                val stSign = if (report.stepsDelta > 0) "+${report.stepsDelta}" else "${report.stepsDelta}"
                samRowStepsDelta.text = report.stepsMatchPercent?.let {
                    String.format("%.0f%% (%s)", it, if (report.stepsDelta == 0) "0" else stSign)
                } ?: stSign
                samRowStepsDelta.setTextColor(
                    if ((report.stepsMatchPercent ?: 100.0) >= 88.0) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
                )

                samRowDistTracker.text = formatDistanceShort(daily.totalDistanceMeters)
                samRowDistSamsung.text = formatDistanceShort(sam.distanceMeters)
                val dMeters = report.distanceDeltaMeters.roundToInt()
                val dSign = if (dMeters > 0) "+${dMeters}m" else "${dMeters}m"
                samRowDistDelta.text = report.distanceMatchPercent?.let {
                    String.format("%.0f%% (%s)", it, if (abs(dMeters) <= 3) "0m" else dSign)
                } ?: dSign
                samRowDistDelta.setTextColor(
                    if ((report.distanceMatchPercent ?: 100.0) >= 88.0) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
                )

                samRowWalkTracker.text = TsPoliceBenchmark.formatDuration(daily.walkingSec)
                samRowWalkSamsung.text = TsPoliceBenchmark.formatDuration(sam.walkingSec)
                samRowWalkDelta.text = formatSignedSeconds(report.walkSecDelta)

                samRowRunTracker.text = TsPoliceBenchmark.formatDuration(daily.jogRunCombinedSec)
                samRowRunSamsung.text = TsPoliceBenchmark.formatDuration(sam.jogRunSec)
                samRowRunDelta.text = formatSignedSeconds(report.jogRunSecDelta)

                samRowStrideTracker.text = report.trackerStrideM?.let { String.format("%.2f m", it) } ?: "--"
                samRowStrideSamsung.text = report.samsungStrideM?.let { String.format("%.2f m", it) } ?: "--"
                samRowStrideDelta.text = String.format("Cal %.2fx", scale)
                samRowStrideDelta.setTextColor(AthleticColors.CYAN_BRIGHT)

                samsungPrimaryBtn.text = "Sync & Calibrate"
                samsungPrimaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }

                val samsungHasHigherTotals = sam.steps > daily.totalSteps + 25 ||
                    sam.distanceMeters > daily.totalDistanceMeters + 25.0
                samsungSecondaryBtn.visibility = View.VISIBLE
                if (samsungHasHigherTotals) {
                    val extraSteps = max(0, sam.steps - daily.totalSteps)
                    samsungSecondaryBtn.text = if (extraSteps > 0) "Align (+$extraSteps)" else "Align Today"
                    samsungSecondaryBtn.setTextColor(AthleticColors.CYAN_BRIGHT)
                    samsungSecondaryBtn.setOnClickListener {
                        trackingService?.alignTodayWithSamsungHealth(sam)
                        refreshCachedDailyHistory(forceRender = true)
                        renderSamsungComparisonCard(force = true)
                        Toast.makeText(this, "Aligned today's floor with ${sam.sourceLabel}!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    samsungSecondaryBtn.text = "Health Connect"
                    samsungSecondaryBtn.setTextColor(AthleticColors.TEXT_PRIMARY)
                    samsungSecondaryBtn.setOnClickListener {
                        healthSyncManager.openSamsungHealthOrHealthConnectSettings()
                    }
                }
            }
        }
    }

    private fun requestSamsungPermissionsSafely() {
        runCatching {
            healthPermissionLauncher.launch(SamsungHealthSyncManager.ALL_PERMISSIONS)
        }.onFailure {
            healthSyncManager.openSamsungHealthOrHealthConnectSettings()
        }
    }

    private fun triggerSamsungHealthSync(userTriggered: Boolean) {
        if (isHealthSyncInFlight || !::healthSyncManager.isInitialized) return
        isHealthSyncInFlight = true
        lastHealthSyncElapsedMs = SystemClock.elapsedRealtime()
        renderSamsungComparisonCard(force = true)

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                healthSyncManager.syncTodayAndCalibrate()
            }
            isHealthSyncInFlight = false
            healthSyncStatus = outcome.status
            if (outcome.snapshot != null) {
                latestSamsungSnapshot = outcome.snapshot
            }
            trackingService?.syncThresholdsFromPrefs()
            if (outcome.importedRunCount > 0) {
                refreshProgressDashboard()
                Toast.makeText(
                    this@MainActivity,
                    "Imported ${outcome.importedRunCount} Samsung Health run(s) into Progress!",
                    Toast.LENGTH_LONG
                ).show()
            } else if (userTriggered) {
                Toast.makeText(this@MainActivity, outcome.statusMessage, Toast.LENGTH_SHORT).show()
            }
            renderSamsungComparisonCard(force = true)
        }
    }

    private fun maybeShowHealthPrivacyRationale(incomingIntent: Intent?) {
        val action = incomingIntent?.action ?: return
        if (action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            action == Intent.ACTION_VIEW_PERMISSION_USAGE
        ) {
            AlertDialog.Builder(this)
                .setTitle("Samsung Health & Health Connect Privacy")
                .setMessage(
                    "TS Police 1.6K reads Steps, Distance, Exercise Sessions, Speed, and Cadence locally on-device to:\n\n" +
                        "1. Compare daily steps, distance, walk & run time side-by-side with Samsung Health.\n" +
                        "2. Auto-calibrate your personal stride length and backfill 14-day run history.\n\n" +
                        "100% of your health data stays strictly on your phone."
                )
                .setPositiveButton("Got It", null)
                .show()
        }
    }

    // =========================================================================
    // PROGRESS, MERIT GRADATION & INTERACTIVE SESSION HISTORY
    // =========================================================================
    private fun refreshProgressDashboard() {
        val sessions = repository.getAllSessions()
        val targetSec = repository.targetTimeSec
        val forecast = ProgressionForecaster.analyze(
            sessions = sessions,
            targetTimeSec = targetSec,
            todayIso = LocalDate.now().toString()
        )

        val targetFmt = TsPoliceBenchmark.formatDuration(targetSec)
        val bestMerit = forecast.personalBestSec?.let { TsPoliceBenchmark.evaluateMerit1600m(it, targetSec) }
        if (bestMerit != null) {
            meritBadgeText.text = "${bestMerit.points} / 100 MERIT PTS"
            val mCol = if (bestMerit.qualified) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
            meritBadgeText.setTextColor(mCol)
            meritBadgeText.background = AthleticTheme.solidPill(this, (mCol and 0x00FFFFFF) or 0x26000000, mCol, 10f)
        } else {
            meritBadgeText.text = "NO BASELINE"
            meritBadgeText.setTextColor(AthleticColors.TEXT_SECONDARY)
            meritBadgeText.background = AthleticTheme.solidPill(this, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 10f)
        }

        when (forecast.status) {
            ForecastStatus.NO_DATA -> {
                etaHeadlineText.text = "Run Day 1 Baseline"
                etaHeadlineText.setTextColor(AthleticColors.TEXT_PRIMARY)
            }
            ForecastStatus.SINGLE_DAY_BASELINE -> {
                etaHeadlineText.text = "1 More Run Unlocks ETA"
                etaHeadlineText.setTextColor(AthleticColors.CYAN_BRIGHT)
            }
            ForecastStatus.QUALIFIED -> {
                etaHeadlineText.text = "QUALIFIED (< $targetFmt!)"
                etaHeadlineText.setTextColor(AthleticColors.EMERALD_BRIGHT)
            }
            ForecastStatus.ON_TRACK -> {
                etaHeadlineText.text = "~${forecast.estimatedDaysToTarget} Days to $targetFmt"
                etaHeadlineText.setTextColor(AthleticColors.EMERALD_BRIGHT)
            }
            ForecastStatus.PLATEAU_OR_SLOWER -> {
                etaHeadlineText.text = "${String.format("%.0fs", forecast.gapToTargetSec ?: 0.0)} Above $targetFmt"
                etaHeadlineText.setTextColor(AthleticColors.AMBER_BRIGHT)
            }
        }

        etaSubText.text = forecast.coachSummary + (bestMerit?.nextTierTimeSec?.let {
            "\nTSLPRB Merit: ${bestMerit.tierLabel} • Next tier (${bestMerit.nextTierPoints} pts) at ${TsPoliceBenchmark.formatDuration(it)}."
        } ?: "")
        coachPrescriptionText.text = "Coach Plan: ${forecast.workoutPrescription}"

        kpiPbVal.text = forecast.personalBestSec?.let { TsPoliceBenchmark.formatDuration(it, true) } ?: "--:--"
        kpiGapVal.text = forecast.gapToTargetSec?.let {
            if (it <= 0.0) "0.0s (Met!)" else String.format("-%.1fs", it)
        } ?: "--"
        kpiRateVal.text = forecast.improvementSecPerWeek?.let {
            if (it >= 0.0) String.format("-%.1fs /wk", it) else String.format("+%.1fs /wk", -it)
        } ?: "--"
        kpiImprovedVal.text = forecast.totalImprovementSec?.let {
            if (it >= 0.0) String.format("%.1fs faster", it) else String.format("%.1fs", it)
        } ?: "--"

        trendChartView.setForecast(forecast)
        demoToggleBtn.text = if (repository.hasDemoSessions()) "Clear Sample" else "Sample Data"

        sessionsListLayout.removeAllViews()
        if (sessions.isEmpty()) {
            sessionsListLayout.addView(TextView(this).apply {
                text = "No runs yet. Arm a 1.6 km PT Trial, run 400m Intervals, sync Samsung Health, or tap 'Sample Data'."
                textSize = 12.5f
                setTextColor(AthleticColors.TEXT_MUTED)
            })
            return
        }

        for (s in sessions) {
            val merit = TsPoliceBenchmark.evaluateMerit1600m(s.equiv1600mSec, targetSec)
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(13), dp(11), dp(13), dp(11))
                background = AthleticTheme.rippleSurface(
                    this@MainActivity,
                    AthleticColors.SURFACE_RECESSED,
                    AthleticColors.SURFACE_RECESSED,
                    if (merit.qualified) AthleticColors.HERO_EMERALD_BORDER else AthleticColors.BORDER_SUBTLE,
                    12f
                )
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
                setOnClickListener {
                    SessionDialogs.showSessionDetailDialog(this@MainActivity, s, targetSec) {
                        repository.deleteSession(s.id)
                        refreshProgressDashboard()
                    }
                }
            }

            val topLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val modeBadge = when (s.mode) {
                RunMode.TIMED_1600M -> "1.6K TRIAL"
                RunMode.INTERVAL_400M -> "INTERVALS"
                RunMode.MANUAL_LOG -> "STOPWATCH"
                RunMode.OPEN_PRACTICE -> if (s.notes.startsWith("Synced")) "SAMSUNG" else "AUTO RUN"
            }
            topLine.addView(TextView(this).apply {
                val demoTag = if (s.isDemo) " • SAMPLE" else ""
                text = "${s.dateIso}  •  $modeBadge$demoTag"
                textSize = 12f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(AthleticColors.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            topLine.addView(TextView(this).apply {
                val suffix = if (abs(s.distanceMeters - 1600.0) <= 50.0) "1.6K" else "1.6K eq"
                text = "${TsPoliceBenchmark.formatDuration(s.equiv1600mSec, true)} ($suffix)"
                textSize = 13.5f
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(if (merit.qualified) AthleticColors.EMERALD_BRIGHT else AthleticColors.CYAN_BRIGHT)
            })
            rowCard.addView(topLine)

            val bottomLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, 0)
            }
            val splitsHint = if (s.lapSplits.isNotEmpty()) "${s.lapSplits.size} splits • " else ""
            bottomLine.addView(TextView(this).apply {
                text = "${String.format("%.2f km", s.distanceMeters / 1000.0)} in ${TsPoliceBenchmark.formatDuration(s.activeDurationSec)}  •  ${splitsHint}${merit.points} pts"
                textSize = 11.5f
                setTextColor(AthleticColors.TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            // Safe Delete trash button (always asks confirmation!)
            val delHitbox = FrameLayout(this).apply {
                setPadding(dp(8), dp(4), dp(2), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    SessionDialogs.confirmDeleteSession(this@MainActivity, s) {
                        repository.deleteSession(s.id)
                        refreshProgressDashboard()
                    }
                }
                addView(VectorIconView(this@MainActivity, IconKind.TRASH, AthleticColors.CRIMSON, 15f))
            }
            bottomLine.addView(delHitbox)
            rowCard.addView(bottomLine)
            sessionsListLayout.addView(rowCard)
        }
    }

    // =========================================================================
    // SETTINGS, BATTERY OPTIMIZATION & PERMISSIONS
    // =========================================================================
    private fun isIgnoringBatteryOpt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizationsSafely() {
        if (isIgnoringBatteryOpt()) {
            Toast.makeText(this, "Unrestricted background battery is already active!", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun refreshBatterySettingsState() {
        if (!::batteryStatusBadge.isInitialized) return
        val unrestricted = isIgnoringBatteryOpt()
        if (unrestricted) {
            batteryStatusBadge.text = "UNRESTRICTED ✓"
            batteryStatusBadge.setTextColor(AthleticColors.EMERALD_BRIGHT)
            batteryStatusBadge.background = AthleticTheme.solidPill(this, 0x2210B981, AthleticColors.HERO_EMERALD_BORDER, 9f)
            batteryStatusBtn.text = "Background Battery Protection Active"
            AthleticTheme.applyButtonStyle(this, batteryStatusBtn, AthleticTheme.ButtonStyle.SECONDARY_SLATE)
        } else {
            batteryStatusBadge.text = "RESTRICTED"
            batteryStatusBadge.setTextColor(AthleticColors.AMBER_BRIGHT)
            batteryStatusBadge.background = AthleticTheme.solidPill(this, 0x22F59E0B, AthleticColors.HERO_AMBER_BORDER, 9f)
            batteryStatusBtn.text = "Allow Unrestricted Background Tracking"
            AthleticTheme.applyButtonStyle(this, batteryStatusBtn, AthleticTheme.ButtonStyle.PRIMARY_CYAN)
        }
    }

    private fun refreshSettingsHighlights() {
        val curTarget = repository.targetTimeSec
        headerSubtitleText.text = "PET Coach  •  Cutoff < ${TsPoliceBenchmark.formatDuration(curTarget)}"

        if (::targetPresetButtons.isInitialized) {
            for ((sec, btn) in targetPresetButtons) {
                val active = abs(curTarget - sec) < 1.0
                btn.background = AthleticTheme.solidPill(
                    this,
                    if (active) AthleticColors.EMERALD_BRIGHT else AthleticColors.SURFACE_RECESSED,
                    if (active) AthleticColors.EMERALD_BRIGHT else AthleticColors.BORDER_SUBTLE,
                    10f
                )
                btn.setTextColor(if (active) AthleticColors.TEXT_ON_DARK_ACCENT else AthleticColors.TEXT_PRIMARY)
            }
        }

        if (::thresholdPresetButtons.isInitialized) {
            val curThresh = repository.runningThresholdMps
            for ((mps, btn) in thresholdPresetButtons) {
                val active = abs(curThresh - mps) < 0.05
                btn.background = AthleticTheme.solidPill(
                    this,
                    if (active) AthleticColors.CYAN_BRIGHT else AthleticColors.SURFACE_RECESSED,
                    if (active) AthleticColors.CYAN_BRIGHT else AthleticColors.BORDER_SUBTLE,
                    10f
                )
                btn.setTextColor(if (active) AthleticColors.TEXT_ON_DARK_ACCENT else AthleticColors.TEXT_PRIMARY)
            }
        }

        if (::audioModeButtons.isInitialized) {
            val curAudio = repository.pocketAudioMode
            for ((mode, btn) in audioModeButtons) {
                val active = mode == curAudio
                if (active) {
                    val c = when (mode) {
                        PocketAudioMode.SMART_TRACK -> AthleticColors.EMERALD_BRIGHT
                        PocketAudioMode.ALL_STATES -> AthleticColors.CYAN_BRIGHT
                        PocketAudioMode.MUTED -> AthleticColors.BORDER_STRONG
                    }
                    btn.background = AthleticTheme.solidPill(this, c, c, 9f, 0f)
                    btn.setTextColor(
                        if (mode == PocketAudioMode.MUTED) AthleticColors.TEXT_PRIMARY else AthleticColors.TEXT_ON_DARK_ACCENT
                    )
                } else {
                    btn.background = null
                    btn.setTextColor(AthleticColors.TEXT_SECONDARY)
                }
            }
        }
        refreshHeaderAudioPill()
    }

    private fun refreshHeaderAudioPill() {
        if (!::headerAudioPill.isInitialized) return
        val mode = repository.pocketAudioMode
        when (mode) {
            PocketAudioMode.SMART_TRACK -> {
                headerAudioIcon.update(IconKind.AUDIO_SMART, AthleticColors.EMERALD_BRIGHT)
                headerAudioLabel.text = "Smart"
                headerAudioLabel.setTextColor(AthleticColors.EMERALD_BRIGHT)
                headerAudioPill.background = AthleticTheme.solidPill(this, 0x2210B981, AthleticColors.HERO_EMERALD_BORDER, 18f)
            }
            PocketAudioMode.ALL_STATES -> {
                headerAudioIcon.update(IconKind.AUDIO_ALL, AthleticColors.CYAN_BRIGHT)
                headerAudioLabel.text = "All Audio"
                headerAudioLabel.setTextColor(AthleticColors.CYAN_BRIGHT)
                headerAudioPill.background = AthleticTheme.solidPill(this, 0x2238BDF8, AthleticColors.HERO_CYAN_BORDER, 18f)
            }
            PocketAudioMode.MUTED -> {
                headerAudioIcon.update(IconKind.AUDIO_MUTED, AthleticColors.TEXT_MUTED)
                headerAudioLabel.text = "Muted"
                headerAudioLabel.setTextColor(AthleticColors.TEXT_MUTED)
                headerAudioPill.background = AthleticTheme.solidPill(this, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 18f)
            }
        }
    }

    private fun hasCorePermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val act = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        return fine && act
    }

    private fun updateBanners() {
        if (!::permissionBanner.isInitialized) return
        val permsOk = hasCorePermissions()
        permissionBanner.visibility = if (permsOk) View.GONE else View.VISIBLE
        batteryGuardBanner.visibility = if (permsOk && !isIgnoringBatteryOpt()) View.VISIBLE else View.GONE
    }

    private fun requestNeededPermissionsIfMissing(silentCheckOnly: Boolean) {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && !silentCheckOnly) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // =========================================================================
    // GITHUB OTA AUTO-UPDATE FLOW
    // =========================================================================
    private fun performUpdateCheck(manualUserTrigger: Boolean, autoInstallWhenFound: Boolean = false) {
        if (manualUserTrigger) {
            lastAutoUpdateCheckElapsedMs = SystemClock.elapsedRealtime()
            checkUpdateBtn.isEnabled = false
            checkUpdateBtn.text = "Checking GitHub Releases..."
        }
        appUpdater.checkForUpdates { result ->
            if (isFinishing) return@checkForUpdates
            checkUpdateBtn.isEnabled = true
            checkUpdateBtn.text = "Check for App Updates Now"

            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    latestDiscoveredRelease = result.release
                    updateBanner.text = "UPDATE v${result.release.versionName} READY  •  TAP TO INSTALL"
                    updateBanner.visibility = View.VISIBLE
                    updateStatusText.text = "Update ready: v${result.installedVersionName} → v${result.release.versionName}"
                    updateStatusText.setTextColor(AthleticColors.CYAN_BRIGHT)

                    if (autoInstallWhenFound && !currentlyRecording) {
                        startUpdateDownloadAndInstall(result.release)
                    } else if (manualUserTrigger || !currentlyRecording) {
                        showUpdateAvailableDialog(result.installedVersionName, result.release)
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    updateBanner.visibility = View.GONE
                    updateStatusText.text = "Up to date: v${result.installedVersionName} (Build ${result.installedVersionCode})"
                    updateStatusText.setTextColor(AthleticColors.EMERALD_BRIGHT)
                    if (manualUserTrigger) {
                        Toast.makeText(this, "You're on the latest version (v${result.installedVersionName})!", Toast.LENGTH_SHORT).show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (manualUserTrigger) {
                        Toast.makeText(this, "Update check: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(currentVersion: String, release: UpdateReleaseInfo) {
        activeUpdateDialog?.dismiss()
        activeUpdateDialog = AlertDialog.Builder(this)
            .setTitle("New App Update: v${release.versionName}")
            .setMessage(
                "Installed: v$currentVersion\nAvailable: v${release.versionName}\n\n" +
                    "What's New:\n${release.changelog}\n\n" +
                    "Your 1.6 km run history and Samsung calibration stay intact."
            )
            .setPositiveButton("Download & Install") { _, _ -> startUpdateDownloadAndInstall(release) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdateDownloadAndInstall(release: UpdateReleaseInfo) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        val statusTv = TextView(this).apply {
            text = "Connecting to GitHub Releases..."
            textSize = 13.5f
            setPadding(0, 0, 0, dp(10))
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        box.addView(statusTv)
        box.addView(progressBar)

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Downloading Update v${release.versionName}")
            .setView(box)
            .setCancelable(false)
            .create()
        progressDialog.show()

        appUpdater.downloadAndInstall(
            release = release,
            onProgress = { pct, downloaded, total ->
                if (pct >= 0 && total > 0L) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = pct
                    statusTv.text = String.format(
                        "Downloading APK... %d%% (%.1f / %.1f MB)",
                        pct,
                        downloaded / (1024.0 * 1024.0),
                        total / (1024.0 * 1024.0)
                    )
                } else {
                    statusTv.text = String.format("Downloading APK... %.1f MB", downloaded / (1024.0 * 1024.0))
                }
            },
            onNeedsInstallPermission = {
                progressDialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Allow App Self-Update")
                    .setMessage(
                        "Update v${release.versionName} is downloaded and SHA-256 verified!\n\n" +
                            "Toggle 'Allow from this source' on the next screen and press Back."
                    )
                    .setPositiveButton("Open Settings") { _, _ -> appUpdater.openUnknownSourcesSettings(this) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onError = { msg ->
                progressDialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Update Failed")
                    .setMessage(msg)
                    .setPositiveButton("Retry") { _, _ -> startUpdateDownloadAndInstall(release) }
                    .setNegativeButton("Close", null)
                    .show()
            },
            onInstallerLaunched = { progressDialog.dismiss() }
        )
    }

    // =========================================================================
    // REUSABLE CARD & BENTO TILE BUILDERS
    // =========================================================================
    private fun cardContainer(
        topColor: Int = AthleticColors.SURFACE_TOP,
        bottomColor: Int = AthleticColors.SURFACE_BOTTOM,
        borderColor: Int = AthleticColors.BORDER_SUBTLE
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = AthleticTheme.gradientCard(this@MainActivity, topColor, bottomColor, borderColor, 18f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }

    private fun metricTile(
        label: String,
        initialVal: String,
        initialSub: String,
        accentLabelColor: Int = AthleticColors.TEXT_SECONDARY
    ): Triple<LinearLayout, TextView, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = AthleticTheme.gradientCard(
                this@MainActivity,
                AthleticColors.SURFACE_TOP,
                AthleticColors.SURFACE_BOTTOM,
                AthleticColors.BORDER_SUBTLE,
                15f
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val lbl = TextView(this).apply {
            text = label
            textSize = 10.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(accentLabelColor)
            letterSpacing = 0.06f
        }
        val valueTv = TextView(this).apply {
            text = initialVal
            textSize = 20f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(4), 0, dp(2))
        }
        val subTv = TextView(this).apply {
            text = initialSub
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_MUTED)
        }
        box.addView(lbl)
        box.addView(valueTv)
        box.addView(subTv)
        return Triple(box, valueTv, subTv)
    }
}
