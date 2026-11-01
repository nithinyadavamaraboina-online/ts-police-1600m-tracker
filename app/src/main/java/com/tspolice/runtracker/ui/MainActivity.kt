package com.tspolice.runtracker.ui

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.domain.ComparisonDayScope
import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.DeadlineGoalCoach
import com.tspolice.runtracker.domain.DeadlineGoalPlan
import com.tspolice.runtracker.domain.ForecastStatus
import com.tspolice.runtracker.domain.HealthSyncStatus
import com.tspolice.runtracker.domain.IntervalPhase
import com.tspolice.runtracker.domain.LiveTelemetry
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.PocketAudioMode
import com.tspolice.runtracker.domain.ProgressionForecaster
import com.tspolice.runtracker.domain.PtTrialPhase
import com.tspolice.runtracker.domain.PushAccentKind
import com.tspolice.runtracker.domain.RecommendedWorkoutAction
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SamsungHealthComparator
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import com.tspolice.runtracker.health.SamsungHealthSyncManager
import com.tspolice.runtracker.service.RunTrackingService
import com.tspolice.runtracker.telemetry.TuningTelemetryManager
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
    private lateinit var tuningTelemetryManager: TuningTelemetryManager
    private var trackingService: RunTrackingService? = null
    private var serviceBound = false
    private var lastHandledFinishId: Long? = null
    private var latestDiscoveredRelease: UpdateReleaseInfo? = null
    private var currentlyRecording = false
    private var activityVisible = false
    private var lastAutoUpdateCheckElapsedMs = -10_000_000L
    private var activeUpdateDialog: Dialog? = null

    // Samsung Health Sync & Calibration state (Yesterday + Today)
    private var healthSyncStatus: HealthSyncStatus = HealthSyncStatus.PERMISSION_REQUIRED
    private var latestSamsungSnapshot: SamsungDailySnapshot? = null
    private var yesterdaySamsungSnapshot: SamsungDailySnapshot? = null
    private var activeComparisonScope: ComparisonDayScope = ComparisonDayScope.TODAY
    private var latestDailySummaryForCompare: DailyActivitySummary = DailyActivitySummary()
    private var isHealthSyncInFlight = false
    private var isTuningUploadInFlight = false
    private var lastHealthSyncElapsedMs = -120_000L
    private var lastSamsungRenderKey = ""

    // Main tab & Tracker sub-tab: 0 = Today (All-Day Auto), 1 = 1.6K PT Trial, 2 = 400m Intervals
    private var activeMainTab = 0
    private var pagerScrollState = ViewPager2.SCROLL_STATE_IDLE
    private var pendingTabRefreshIndex = -1
    private var activeTrackerSubTab = 0
    private var selectedIntervalReps = 6
    private var lastRenderedPtPhase: PtTrialPhase = PtTrialPhase.IDLE
    private var lastRenderedIntervalPhase: IntervalPhase = IntervalPhase.IDLE
    private var cachedPastDailyLogs: List<DailyActivitySummary> = emptyList()
    private var lastDailyHistoryRenderMs = -60_000L
    private var lastDailyHistoryKey = ""
    private var lastGoalCoachRenderMs = -60_000L
    private var lastGoalCoachKey = ""

    // Header & Root Navigation views
    private lateinit var headerSubtitleText: TextView
    private lateinit var headerAudioPill: LinearLayout
    private lateinit var headerAudioIcon: VectorIconView
    private lateinit var headerAudioLabel: TextView
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateBannerTitleText: TextView
    private lateinit var mainViewPager: ViewPager2
    private lateinit var bottomNavDock: SlidingBottomNavDock
    private lateinit var backNavCallback: OnBackPressedCallback
    private lateinit var tabTrackerContainer: ScrollView
    private lateinit var tabProgressContainer: ScrollView
    private lateinit var tabGuideContainer: ScrollView
    private val navTabViews = mutableListOf<BottomNavTabView>()

    // Tab 1: Banners, Sub-Tab Switcher & Containers
    private lateinit var permissionBanner: LinearLayout
    private lateinit var batteryGuardBanner: LinearLayout
    private lateinit var trackerSegmentedBar: SlidingSegmentedBar
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
    private lateinit var openTodaySummaryContainer: LinearLayout
    private lateinit var goalCoachCard: LinearLayout
    private lateinit var goalHeaderLabel: TextView
    private lateinit var goalPushBadge: TextView
    private lateinit var goalReadinessBar: NeonUpdateProgressBarView
    private lateinit var goalStatusText: TextView
    private lateinit var goalDirectiveText: TextView
    private lateinit var goalMiniMilestonesLayout: LinearLayout
    private lateinit var goalActionBtn: TextView
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

    // Samsung Health Comparison Card views (Yesterday + Today + 2-Day Combined)
    private lateinit var samsungSyncBadge: TextView
    private lateinit var samsungHeadlineText: TextView
    private lateinit var samsungDetailText: TextView
    private lateinit var samsungScopeBar: LinearLayout
    private lateinit var samScopeTodayBtn: TextView
    private lateinit var samScopeYesterdayBtn: TextView
    private lateinit var samScopeTwoDayBtn: TextView
    private lateinit var samsungTwoDaySummaryStrip: TextView
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
    private lateinit var samRowPaceTracker: TextView
    private lateinit var samRowPaceSamsung: TextView
    private lateinit var samRowPaceDelta: TextView
    private lateinit var samRowCadTracker: TextView
    private lateinit var samRowCadSamsung: TextView
    private lateinit var samRowCadDelta: TextView
    private lateinit var samRowStrideTracker: TextView
    private lateinit var samRowStrideSamsung: TextView
    private lateinit var samRowStrideDelta: TextView
    private lateinit var samsungPrimaryBtn: TextView
    private lateinit var samsungSecondaryBtn: TextView
    private lateinit var samsungReportBtn: TextView
    private lateinit var samsungSendTuningBtn: TextView
    private lateinit var samsungTuningStatusText: TextView
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
    private lateinit var progressTodayHeaderLabel: TextView
    private lateinit var progressTodayCountBadge: TextView
    private lateinit var progressTodayDailyStripLayout: LinearLayout
    private lateinit var todaySessionsListLayout: LinearLayout
    private lateinit var etaHeadlineText: TextView
    private lateinit var meritBadgeText: TextView
    private lateinit var etaSubText: TextView
    private lateinit var coachPrescriptionText: TextView
    private lateinit var progressGoalHeaderLabel: TextView
    private lateinit var progressGoalReadinessPill: TextView
    private lateinit var progressGoalWeeklyMixText: TextView
    private lateinit var progressMilestonesLayout: LinearLayout
    private lateinit var kpiPbVal: TextView
    private lateinit var kpiGapVal: TextView
    private lateinit var kpiRateVal: TextView
    private lateinit var kpiImprovedVal: TextView
    private lateinit var trendChartView: ProgressTrendChartView
    private lateinit var demoToggleBtn: TextView
    private lateinit var pastSessionsHeaderLabel: TextView
    private lateinit var sessionsListLayout: LinearLayout

    // Tab 3: Settings, Profile & Lab views
    private lateinit var batteryStatusBadge: TextView
    private lateinit var batteryStatusBtn: TextView
    private lateinit var goalDeadlineBtn: TextView
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
    private lateinit var labTuningStatusText: TextView
    private lateinit var labSendTuningBtn: TextView
    private lateinit var updateCardBadge: TextView
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
        tuningTelemetryManager = TuningTelemetryManager(this, repository, healthSyncManager)
        val todayNow = LocalDate.now()
        latestDailySummaryForCompare = repository.getDailyActivity(todayNow.toString())
        latestSamsungSnapshot = repository.getCachedSamsungSnapshot(todayNow.toString())
        yesterdaySamsungSnapshot = repository.getCachedSamsungSnapshot(todayNow.minusDays(1).toString())
        if (latestSamsungSnapshot != null || yesterdaySamsungSnapshot != null) {
            healthSyncStatus = HealthSyncStatus.SYNCED
        }

        backNavCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (activeMainTab != 0) {
                    selectTab(0, smooth = true)
                } else if (activeTrackerSubTab != 0 && !currentlyRecording) {
                    selectTrackerSubTab(0, animate = true)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backNavCallback)

        setContentView(buildRootLayout())
        selectTab(0, smooth = false)
        selectTrackerSubTab(0, animate = false)
        refreshSettingsHighlights()
        refreshTuningStatusLabels()
        refreshCachedDailyHistory(forceRender = true)
        renderSamsungComparisonCard(force = true)
        renderGoalCoachCard(force = true)
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
        renderGoalCoachCard(force = true)
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = "TS POLICE 1.6K"
            textSize = 16.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            letterSpacing = 0.03f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        headerSubtitleText = TextView(this).apply {
            text = "PET Coach  •  Cutoff < ${TsPoliceBenchmark.formatDuration(repository.targetTimeSec)}"
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
            maxLines = 1
        }
        headerAudioPill.addView(headerAudioIcon)
        headerAudioPill.addView(headerAudioLabel)
        header.addView(headerAudioPill)

        root.addView(header)
        root.addView(View(this).apply {
            setBackgroundColor(AthleticColors.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f).coerceAtLeast(1))
        })

        // Sleek Contoured OTA Update Capsule Banner
        updateBanner = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = AthleticTheme.gradientCard(
                this@MainActivity,
                AthleticColors.HERO_CYAN_TOP,
                AthleticColors.HERO_CYAN_BOTTOM,
                AthleticColors.HERO_CYAN_BORDER,
                16f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(8)
                bottomMargin = dp(2)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                latestDiscoveredRelease?.let { rel ->
                    val (_, curName) = appUpdater.getInstalledVersion()
                    showUpdateAvailableDialog(curName, rel)
                }
            }
        }
        updateBanner.addView(
            VectorIconView(this, IconKind.TRACK_EMBLEM, AthleticColors.EMERALD_BRIGHT, 28f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
            }
        )
        val bannerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        updateBannerTitleText = TextView(this).apply {
            text = "Update Ready"
            textSize = 12.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            maxLines = 1
        }
        bannerTextCol.addView(updateBannerTitleText)
        bannerTextCol.addView(TextView(this).apply {
            text = "SHA-256 verified  •  Tap to review & install"
            textSize = 10.5f
            setTextColor(AthleticColors.CYAN_BRIGHT)
            maxLines = 1
        })
        updateBanner.addView(bannerTextCol)
        updateBanner.addView(TextView(this).apply {
            text = "Update →"
            textSize = 11.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                AthleticColors.EMERALD_BRIGHT,
                AthleticColors.EMERALD_BRIGHT,
                14f,
                0f
            )
        })
        root.addView(updateBanner)

        // Fluid Horizontal Page Pager (Tracker <-> Progress <-> Coach & Lab)
        tabTrackerContainer = buildTrackerTab()
        tabProgressContainer = buildProgressTab()
        tabGuideContainer = buildGuideAndSensorsTab()
        val pages = listOf(tabTrackerContainer, tabProgressContainer, tabGuideContainer)

        mainViewPager = ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            offscreenPageLimit = 2
            adapter = object : RecyclerView.Adapter<MainPageViewHolder>() {
                override fun getItemCount(): Int = pages.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainPageViewHolder {
                    val host = FrameLayout(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    return MainPageViewHolder(host)
                }

                override fun onBindViewHolder(holder: MainPageViewHolder, position: Int) {
                    val pageView = pages[position]
                    (pageView.parent as? ViewGroup)?.removeView(pageView)
                    pageView.visibility = View.VISIBLE
                    pageView.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    holder.host.removeAllViews()
                    holder.host.addView(pageView)
                }
            }
            (getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            setPageTransformer { page, position ->
                val clamped = abs(position).coerceIn(0f, 1f)
                page.alpha = 1f - 0.14f * clamped
            }
        }
        root.addView(mainViewPager)

        // Contoured AMOLED Stadium-Capsule Bottom Navigation Dock (120Hz sliding pill + thumb scrubbing)
        val bottomDockHost = FrameLayout(this).apply {
            setBackgroundColor(AthleticColors.BG_CANVAS)
            setPadding(dp(16), dp(6), dp(16), dp(10))
        }
        val navSpec = listOf(
            IconKind.NAV_TRACKER to "Tracker",
            IconKind.NAV_PROGRESS to "Progress",
            IconKind.NAV_SETTINGS to "Coach & Lab"
        )
        bottomNavDock = SlidingBottomNavDock(this, navSpec) { index ->
            selectTab(index, smooth = true)
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            onScrubStart = {
                if (!mainViewPager.isFakeDragging) {
                    mainViewPager.beginFakeDrag()
                }
            }
            onScrubDelta = { deltaProgress ->
                if (mainViewPager.isFakeDragging && mainViewPager.width > 0) {
                    val cur = currentProgress
                    val clampedNext = (cur + deltaProgress).coerceIn(0f, (pages.size - 1).toFloat())
                    val effectiveDelta = clampedNext - cur
                    if (abs(effectiveDelta) > 0.0005f) {
                        mainViewPager.fakeDragBy(-effectiveDelta * mainViewPager.width)
                    }
                }
            }
            onScrubEnd = { targetIndex ->
                if (mainViewPager.isFakeDragging) {
                    mainViewPager.endFakeDrag()
                }
                selectTab(targetIndex, smooth = true)
            }
        }
        navTabViews.clear()
        navTabViews.addAll(bottomNavDock.tabViews)

        mainViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                bottomNavDock.setScrollProgress(position + positionOffset)
            }

            override fun onPageSelected(position: Int) {
                activeMainTab = position
                syncBackCallbackEnabled()
                if (pagerScrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    performTabDataRefresh(position)
                } else {
                    pendingTabRefreshIndex = position
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                pagerScrollState = state
                if (state == ViewPager2.SCROLL_STATE_IDLE && pendingTabRefreshIndex >= 0) {
                    val idx = pendingTabRefreshIndex
                    pendingTabRefreshIndex = -1
                    performTabDataRefresh(idx)
                }
            }
        })

        bottomDockHost.addView(bottomNavDock)
        root.addView(bottomDockHost)
        return root
    }

    private class MainPageViewHolder(val host: FrameLayout) : RecyclerView.ViewHolder(host)

    private fun syncBackCallbackEnabled() {
        if (::backNavCallback.isInitialized) {
            backNavCallback.isEnabled = activeMainTab != 0 || (activeTrackerSubTab != 0 && !currentlyRecording)
        }
    }

    private fun selectTab(index: Int, smooth: Boolean = true) {
        val clamped = index.coerceIn(0, 2)
        activeMainTab = clamped
        syncBackCallbackEnabled()
        if (mainViewPager.currentItem != clamped) {
            mainViewPager.setCurrentItem(clamped, smooth)
            if (!smooth) {
                bottomNavDock.setScrollProgress(clamped.toFloat())
                performTabDataRefresh(clamped)
            }
        } else if (!smooth || pagerScrollState == ViewPager2.SCROLL_STATE_IDLE) {
            bottomNavDock.setScrollProgress(clamped.toFloat())
            performTabDataRefresh(clamped)
        }
    }

    private fun performTabDataRefresh(index: Int) {
        if (index == 0) {
            refreshTuningStatusLabels()
            refreshCachedDailyHistory(forceRender = true)
            if (!currentlyRecording && (SystemClock.elapsedRealtime() - lastHealthSyncElapsedMs) >= 45_000L) {
                triggerSamsungHealthSync(userTriggered = false)
            }
        } else if (index == 1) {
            refreshProgressDashboard()
        } else if (index == 2) {
            refreshBatterySettingsState()
            refreshSettingsHighlights()
            refreshTuningStatusLabels()
        }
    }

    private fun selectTrackerSubTab(subIndex: Int, animate: Boolean = true, fromBar: Boolean = false) {
        val nextIndex = subIndex.coerceIn(0, 2)
        val prevIndex = activeTrackerSubTab
        activeTrackerSubTab = nextIndex
        syncBackCallbackEnabled()

        if (!fromBar) {
            trackerSegmentedBar.selectSegment(nextIndex, animate = animate)
        }

        val views = listOf(openAllDayContainer, ptTrialContainer, intervalsContainer)
        views.forEach { it.animate().cancel() }

        if (!animate || prevIndex == nextIndex || !tabTrackerContainer.isLaidOut) {
            views.forEachIndexed { idx, v ->
                v.translationX = 0f
                v.alpha = 1f
                v.visibility = if (idx == nextIndex) View.VISIBLE else View.GONE
            }
            return
        }

        val dir = if (nextIndex > prevIndex) 1f else -1f
        val incoming = views[nextIndex]
        val outgoing = views[prevIndex]
        views.forEachIndexed { idx, v ->
            if (idx != nextIndex && idx != prevIndex) {
                v.visibility = View.GONE
                v.translationX = 0f
                v.alpha = 1f
            }
        }

        val slideInPx = dp(34f).toFloat() * dir
        val slideOutPx = -dp(24f).toFloat() * dir

        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationX = slideInPx
        incoming.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(230L)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .withEndAction {
                incoming.translationX = 0f
                incoming.alpha = 1f
            }
            .start()

        outgoing.animate()
            .translationX(slideOutPx)
            .alpha(0f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationX = 0f
                outgoing.alpha = 1f
            }
            .start()

        if (tabTrackerContainer.scrollY > dp(100)) {
            tabTrackerContainer.smoothScrollTo(0, 0)
        }
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
            setTextColor(0xFFFFD08A.toInt())
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

        // 3. Fluid Sliding 3-Segment Capsule Switcher: [Today] | [1.6K Trial] | [400m Coach]
        trackerSegmentedBar = SlidingSegmentedBar(
            this,
            labels = listOf("Today", "1.6K Trial", "400m Coach"),
            accentColors = intArrayOf(
                AthleticColors.EMERALD_BRIGHT,
                AthleticColors.CYAN_BRIGHT,
                AthleticColors.AMBER_BRIGHT
            ),
            onSegmentSelected = { idx ->
                selectTrackerSubTab(idx, animate = true, fromBar = true)
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        subTabTodayBtn = trackerSegmentedBar.segmentViews[0]
        subTabPtBtn = trackerSegmentedBar.segmentViews[1]
        subTabIntervalsBtn = trackerSegmentedBar.segmentViews[2]
        col.addView(trackerSegmentedBar)

        val subPagesFrame = FrameLayout(this).apply {
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        openAllDayContainer = buildOpenAllDaySubView()
        ptTrialContainer = buildPtTrialSubView()
        intervalsContainer = buildIntervalsSubView()

        subPagesFrame.addView(openAllDayContainer)
        subPagesFrame.addView(ptTrialContainer)
        subPagesFrame.addView(intervalsContainer)
        col.addView(subPagesFrame)

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
        topRow.addView(AthleticTheme.sectionLabel(this, "TODAY'S MOVEMENT", AthleticColors.EMERALD_BRIGHT).apply {
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
            "Arm 1.6 km PT Trial  •  Auto-Start  →",
            AthleticTheme.ButtonStyle.PRIMARY_EMERALD,
            52f
        ).apply {
            textSize = 13.5f
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

        // 2A. Today's Activity Logs (Top Part — Instant access to Today's summary & recorded runs/splits)
        val openTodayCard = cardContainer(
            topColor = AthleticColors.HERO_EMERALD_TOP,
            bottomColor = AthleticColors.SURFACE_BOTTOM,
            borderColor = AthleticColors.HERO_EMERALD_BORDER
        )
        openTodayCard.addView(
            AthleticTheme.sectionLabel(
                this,
                "TODAY'S ACTIVITY LOGS  •  TAP FOR SPLITS",
                AthleticColors.EMERALD_BRIGHT
            )
        )
        openTodaySummaryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        openTodayCard.addView(openTodaySummaryContainer)
        box.addView(openTodayCard)

        // 2B. Nov 30 Goal Countdown & Dynamic Daily Push Coach Card
        box.addView(buildTodayGoalCoachCard())

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

        // 6. Past Whole-Day Activity Timeline (Bottom Part — Previous Days Only)
        val dailyLogCard = cardContainer()
        dailyLogCard.addView(AthleticTheme.sectionLabel(this, "PAST ACTIVITY LOGS  •  PREVIOUS DAYS"))
        dailyHistoryListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        dailyLogCard.addView(dailyHistoryListLayout)
        box.addView(dailyLogCard)

        return box
    }

    private fun buildTodayGoalCoachCard(): LinearLayout {
        goalCoachCard = cardContainer(
            topColor = AthleticColors.HERO_AMBER_TOP,
            bottomColor = AthleticColors.HERO_AMBER_BOTTOM,
            borderColor = AthleticColors.HERO_AMBER_BORDER
        )

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        goalHeaderLabel = AthleticTheme.sectionLabel(
            this,
            "NOV 30 GOAL  •  29D LEFT  ✎",
            AthleticColors.AMBER_BRIGHT
        ).apply {
            isClickable = true
            isFocusable = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showGoalDeadlinePicker() }
        }
        goalPushBadge = TextView(this).apply {
            text = "PUSH 8/10"
            textSize = 10.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0x2EFF9F1C,
                AthleticColors.AMBER_BRIGHT,
                10f
            )
            setTextColor(AthleticColors.AMBER_BRIGHT)
        }
        headerRow.addView(goalHeaderLabel)
        headerRow.addView(goalPushBadge)
        goalCoachCard.addView(headerRow)

        goalReadinessBar = NeonUpdateProgressBarView(this).apply {
            isIndeterminateMode = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(9)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        }
        goalCoachCard.addView(goalReadinessBar)

        goalStatusText = TextView(this).apply {
            text = "Loading Nov 30 goal readiness..."
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setLineSpacing(0f, 1.15f)
        }
        goalCoachCard.addView(goalStatusText)

        goalDirectiveText = TextView(this).apply {
            text = ""
            textSize = 12.5f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setLineSpacing(0f, 1.18f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0x33000000,
                AthleticColors.BORDER_STRONG,
                12f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        goalCoachCard.addView(goalDirectiveText)

        goalMiniMilestonesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
        goalCoachCard.addView(goalMiniMilestonesLayout)

        goalActionBtn = AthleticTheme.createButton(
            this,
            "Arm Zero-Stop 1.6 km Trial  →",
            AthleticTheme.ButtonStyle.WARNING_AMBER,
            46f
        ).apply {
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        goalCoachCard.addView(goalActionBtn)

        return goalCoachCard
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
        headerRow.addView(AthleticTheme.sectionLabel(this, "SAMSUNG HEALTH  •  YESTERDAY & TODAY", AthleticColors.CYAN_BRIGHT).apply {
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
            text = "Pull Yesterday & Today vs Samsung Health"
            textSize = 15f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(8), 0, dp(3))
        }
        samsungDetailText = TextView(this).apply {
            text = "Pulls Yesterday's full 24h & Today's live Steps, Distance, Walk/Run, Pace & Cadence via Health Connect."
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(samsungHeadlineText)
        card.addView(samsungDetailText)

        // 3-Way Segmented Day Switcher: Today | Yesterday | 2-Day Total
        samsungScopeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = AthleticTheme.solidPill(this@MainActivity, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 11f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        fun makeScopePill(scope: ComparisonDayScope): TextView = TextView(this).apply {
            text = scope.tabLabel
            textSize = 11.5f
            gravity = Gravity.CENTER
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(dp(6), dp(7), dp(6), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                activeComparisonScope = scope
                renderSamsungComparisonCard(force = true)
            }
        }
        samScopeTodayBtn = makeScopePill(ComparisonDayScope.TODAY)
        samScopeYesterdayBtn = makeScopePill(ComparisonDayScope.YESTERDAY)
        samScopeTwoDayBtn = makeScopePill(ComparisonDayScope.TWO_DAY_COMBINED)
        samsungScopeBar.addView(samScopeTodayBtn)
        samsungScopeBar.addView(samScopeYesterdayBtn)
        samsungScopeBar.addView(samScopeTwoDayBtn)
        card.addView(samsungScopeBar)

        // At-a-glance Yesterday + Today Quick Match Strip
        samsungTwoDaySummaryStrip = TextView(this).apply {
            text = ""
            visibility = View.GONE
            textSize = 11.5f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.CYAN_BRIGHT)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = AthleticTheme.solidPill(this@MainActivity, 0x1A9D6BFF, AthleticColors.HERO_CYAN_BORDER, 9f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
        }
        card.addView(samsungTwoDaySummaryStrip)

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
                setPadding(0, dp(4.5f), 0, dp(4.5f))
            }
            val lblTv = TextView(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(AthleticColors.TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f)
            }
            val trkTv = TextView(this).apply {
                text = "--"
                textSize = 11.5f
                gravity = Gravity.END
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(AthleticColors.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f)
            }
            val samTv = TextView(this).apply {
                text = "--"
                textSize = 11.5f
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
        val rPace = buildCompareRow("Max Pace")
        samRowPaceTracker = rPace.first; samRowPaceSamsung = rPace.second; samRowPaceDelta = rPace.third
        val rCad = buildCompareRow("Max Cad.")
        samRowCadTracker = rCad.first; samRowCadSamsung = rCad.second; samRowCadDelta = rCad.third
        val rStride = buildCompareRow("Stride")
        samRowStrideTracker = rStride.first; samRowStrideSamsung = rStride.second; samRowStrideDelta = rStride.third

        card.addView(samsungCompareTable)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        samsungPrimaryBtn = AthleticTheme.createButton(this, "Pull Yesterday & Today", AthleticTheme.ButtonStyle.PRIMARY_EMERALD, 44f).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { marginEnd = dp(4) }
        }
        samsungSecondaryBtn = AthleticTheme.createButton(this, "Settings", AthleticTheme.ButtonStyle.SECONDARY_SLATE, 44f).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
        samsungReportBtn = AthleticTheme.createButton(this, "Report", AthleticTheme.ButtonStyle.PRIMARY_CYAN, 44f).apply {
            textSize = 12f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.72f).apply { marginStart = dp(4) }
            setOnClickListener { openTwoDaySamsungReportModal() }
        }
        btnRow.addView(samsungPrimaryBtn)
        btnRow.addView(samsungSecondaryBtn)
        btnRow.addView(samsungReportBtn)
        card.addView(btnRow)

        samsungSendTuningBtn = AthleticTheme.createButton(
            this,
            "Pull & Send Fine-Tuning Data (7-Day + Hourly Logs)",
            AthleticTheme.ButtonStyle.SUBTLE_OUTLINE,
            42f
        ).apply {
            textSize = 12f
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { triggerFineTuningUpload() }
        }
        card.addView(samsungSendTuningBtn)

        samsungTuningStatusText = TextView(this).apply {
            text = ""
            visibility = View.GONE
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(5), 0, 0)
        }
        card.addView(samsungTuningStatusText)

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

        // 1. TOP PART: Today's Activity Logs Card (Immediate zero-scroll access to today's runs & splits!)
        val todayLogsCard = cardContainer(
            topColor = AthleticColors.HERO_EMERALD_TOP,
            bottomColor = AthleticColors.SURFACE_BOTTOM,
            borderColor = AthleticColors.HERO_EMERALD_BORDER
        )
        val todayTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progressTodayHeaderLabel = AthleticTheme.sectionLabel(
            this,
            "TODAY'S ACTIVITY LOGS  •  TAP FOR SPLITS",
            AthleticColors.EMERALD_BRIGHT
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        progressTodayCountBadge = TextView(this).apply {
            text = "TODAY"
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0x2EC6F432,
                AthleticColors.EMERALD_BRIGHT,
                10f
            )
            setTextColor(AthleticColors.EMERALD_BRIGHT)
        }
        todayTopRow.addView(progressTodayHeaderLabel)
        todayTopRow.addView(progressTodayCountBadge)
        todayLogsCard.addView(todayTopRow)

        progressTodayDailyStripLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        todayLogsCard.addView(progressTodayDailyStripLayout)

        todaySessionsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        todayLogsCard.addView(todaySessionsListLayout)
        col.addView(todayLogsCard)

        // 2. Hero Forecast & Merit Card
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
            background = AthleticTheme.solidPill(this@MainActivity, 0x33C6F432, AthleticColors.EMERALD_BRIGHT, 10f)
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
            background = AthleticTheme.solidPill(this@MainActivity, 0x229D6BFF, AthleticColors.HERO_CYAN_BORDER, 11f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }
        heroCard.addView(coachPrescriptionText)
        col.addView(heroCard)

        // Nov 30 Goal Countdown & 4-Week Milestone Ladder Card
        val goalRoadmapCard = cardContainer(
            topColor = AthleticColors.HERO_CYAN_TOP,
            bottomColor = AthleticColors.HERO_CYAN_BOTTOM,
            borderColor = AthleticColors.HERO_CYAN_BORDER
        )
        val roadmapTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progressGoalHeaderLabel = AthleticTheme.sectionLabel(
            this,
            "EXAM COUNTDOWN  •  NOV 30  ✎",
            AthleticColors.CYAN_BRIGHT
        ).apply {
            isClickable = true
            isFocusable = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { showGoalDeadlinePicker() }
        }
        progressGoalReadinessPill = TextView(this).apply {
            text = "0% READY"
            textSize = 10.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0x269D6BFF,
                AthleticColors.CYAN_BRIGHT,
                10f
            )
            setTextColor(AthleticColors.CYAN_BRIGHT)
        }
        roadmapTopRow.addView(progressGoalHeaderLabel)
        roadmapTopRow.addView(progressGoalReadinessPill)
        goalRoadmapCard.addView(roadmapTopRow)

        progressGoalWeeklyMixText = TextView(this).apply {
            text = ""
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, dp(10))
        }
        goalRoadmapCard.addView(progressGoalWeeklyMixText)

        progressMilestonesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        goalRoadmapCard.addView(progressMilestonesLayout)
        col.addView(goalRoadmapCard)

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

        // BOTTOM PART: Past Recorded Sessions History Card (Tap any row for 4x400m Splits & Merit Score!)
        val historyCard = cardContainer()
        pastSessionsHeaderLabel = AthleticTheme.sectionLabel(
            this,
            "PAST ACTIVITY LOGS  •  TAP ANY RUN FOR SPLITS"
        )
        historyCard.addView(pastSessionsHeaderLabel)
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

        // Goal Exam / PET Deadline Date Row
        val deadlineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
        }
        val deadlineLblCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        deadlineLblCol.addView(TextView(this).apply {
            text = "PET Goal Deadline Date"
            textSize = 13.5f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.TEXT_PRIMARY)
        })
        deadlineLblCol.addView(TextView(this).apply {
            text = "Tracks countdown & daily push intensity to exam day"
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
        })
        deadlineRow.addView(deadlineLblCol)

        goalDeadlineBtn = AthleticTheme.createButton(
            this,
            "Nov 30, 2026  ✎",
            AthleticTheme.ButtonStyle.SECONDARY_SLATE,
            36f
        ).apply {
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showGoalDeadlinePicker() }
        }
        deadlineRow.addView(goalDeadlineBtn)
        profileCard.addView(deadlineRow)

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
        profileCard.addView(TextView(this).apply {
            text = "Gait Guide: Walk 68–130 spm (0.65–0.85m/step) • Slow-Jog 133+ spm + bounce (0.50–0.72m) • Jog 133–162 spm (0.85–1.08m) • Run/7:15 162–190+ spm (1.15–1.45m)"
            textSize = 11f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(10), 0, dp(2))
        })
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

        // 5. App Fine-Tuning Telemetry Pull & Cloud Sync Card
        val tuningCard = cardContainer(
            topColor = AthleticColors.HERO_EMERALD_TOP,
            bottomColor = AthleticColors.HERO_EMERALD_BOTTOM,
            borderColor = AthleticColors.HERO_EMERALD_BORDER
        )
        tuningCard.addView(AthleticTheme.sectionLabel(this, "FINE-TUNING DATA PULL  •  SAMSUNG + DSP LOGS", AthleticColors.EMERALD_BRIGHT))
        tuningCard.addView(TextView(this).apply {
            text = "Pulls 7-day Samsung Health totals, 48h Yesterday & Today hourly step/distance buckets, hardware sensor FIFO specs & last 300 DSP gait transitions so thresholds & stride scale can be fine-tuned."
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(6), 0, dp(8))
        })
        labTuningStatusText = TextView(this).apply {
            text = "Ready to pull & upload fine-tuning telemetry."
            textSize = 11.5f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.CYAN_BRIGHT)
            setPadding(0, 0, 0, dp(9))
        }
        tuningCard.addView(labTuningStatusText)
        labSendTuningBtn = AthleticTheme.createButton(
            this,
            "Pull & Send Fine-Tuning Bundle",
            AthleticTheme.ButtonStyle.PRIMARY_EMERALD,
            46f
        ).apply {
            textSize = 13f
            setOnClickListener { triggerFineTuningUpload() }
        }
        tuningCard.addView(labSendTuningBtn)
        col.addView(tuningCard)

        // 6. Live Sensor Diagnostics & Maintenance Card
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

        // 6. GitHub OTA Software Update Hub Card
        val updateCard = cardContainer(
            topColor = AthleticColors.HERO_CYAN_TOP,
            bottomColor = AthleticColors.HERO_CYAN_BOTTOM,
            borderColor = AthleticColors.HERO_CYAN_BORDER
        )
        val updateTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        updateTopRow.addView(
            AthleticTheme.sectionLabel(this, "SOFTWARE UPDATE  •  OTA", AthleticColors.CYAN_BRIGHT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        updateCardBadge = TextView(this).apply {
            text = "UP TO DATE"
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                AthleticColors.EMERALD_BRIGHT,
                AthleticColors.EMERALD_BRIGHT,
                10f,
                0f
            )
        }
        updateTopRow.addView(updateCardBadge)
        updateCard.addView(updateTopRow)

        val (instCode, instName) = appUpdater.getInstalledVersion()
        val versionShowcase = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                AthleticColors.SURFACE_RECESSED,
                AthleticColors.BORDER_SUBTLE,
                14f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(6)
            }
        }
        versionShowcase.addView(
            VectorIconView(this, IconKind.TRACK_EMBLEM, AthleticColors.EMERALD_BRIGHT, 38f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
            }
        )
        val verCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        verCol.addView(TextView(this).apply {
            text = "TS Police 1.6K  •  v$instName"
            textSize = 14f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
        })
        updateStatusText = TextView(this).apply {
            text = "Build $instCode  •  SHA-256 Verified GitHub Releases"
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, dp(2), 0, 0)
        }
        verCol.addView(updateStatusText)
        versionShowcase.addView(verCol)
        updateCard.addView(versionShowcase)

        val autoUpdateRow = ModernSettingRow(
            this,
            "Automatic OTA Update Checks",
            "Pre-downloads & verifies new releases silently in background",
            appUpdater.autoCheckOnLaunch
        ) { checked -> appUpdater.autoCheckOnLaunch = checked }
        updateCard.addView(autoUpdateRow)

        checkUpdateBtn = AthleticTheme.createButton(
            this,
            "Check for App Updates",
            AthleticTheme.ButtonStyle.PRIMARY_EMERALD,
            48f
        ).apply {
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            setOnClickListener {
                val cachedRel = latestDiscoveredRelease
                if (cachedRel != null) {
                    val (_, curName) = appUpdater.getInstalledVersion()
                    showUpdateAvailableDialog(curName, cachedRel)
                } else {
                    performUpdateCheck(manualUserTrigger = true)
                }
            }
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

    private fun buildDailySummaryCardView(
        day: DailyActivitySummary,
        isToday: Boolean,
        isYesterday: Boolean,
        samSnap: SamsungDailySnapshot?
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = isToday || isYesterday
            isFocusable = isToday || isYesterday
            background = AthleticTheme.solidPill(
                this@MainActivity,
                if (isToday) 0xFF161D09.toInt() else AthleticColors.SURFACE_RECESSED,
                when {
                    isToday -> AthleticColors.HERO_EMERALD_BORDER
                    isYesterday && samSnap != null -> AthleticColors.HERO_CYAN_BORDER
                    else -> AthleticColors.BORDER_SUBTLE
                },
                11f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
            if (isToday || isYesterday) {
                setOnClickListener { openTwoDaySamsungReportModal() }
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = when {
                isToday -> "TODAY WHOLE-DAY  •  ${day.dateIso}"
                isYesterday -> "YESTERDAY  •  ${day.dateIso}"
                day.isDemo -> "${day.dateIso}  •  SAMPLE"
                else -> day.dateIso
            }
            textSize = 11.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(
                when {
                    isToday -> AthleticColors.EMERALD_BRIGHT
                    isYesterday -> AthleticColors.CYAN_BRIGHT
                    else -> AthleticColors.TEXT_PRIMARY
                }
            )
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
        if (samSnap != null) {
            val rep = SamsungHealthComparator.compare(day, samSnap, repository.effectiveStrideScale())
            val matchTxt = rep.overallMatchPercent?.let { String.format("%.0f%% Match", it) } ?: "Synced"
            card.addView(TextView(this).apply {
                text = "${samSnap.sourceLabel}: ${samSnap.steps} steps (${formatDistanceShort(samSnap.distanceMeters)})  •  $matchTxt"
                textSize = 11f
                typeface = AthleticTheme.FONT_SANS_MEDIUM
                setTextColor(AthleticColors.CYAN_BRIGHT)
                setPadding(0, dp(3), 0, 0)
            })
        }
        return card
    }

    private fun renderDailyHistoryCards(todaySummary: DailyActivitySummary) {
        val nowMs = SystemClock.elapsedRealtime()
        val todayIso = todaySummary.dateIso.ifBlank { LocalDate.now().toString() }
        val yesterdayIso = LocalDate.now().minusDays(1).toString()
        val key = "$todayIso:${todaySummary.walkingSec.toInt()}:${todaySummary.jogRunCombinedSec.toInt()}:" +
            "${todaySummary.totalSteps}:${cachedPastDailyLogs.size}:${latestSamsungSnapshot?.steps}:${yesterdaySamsungSnapshot?.steps}"
        if (key == lastDailyHistoryKey && (nowMs - lastDailyHistoryRenderMs) < 4000L) return
        if ((nowMs - lastDailyHistoryRenderMs) >= 15_000L) {
            cachedPastDailyLogs = repository.getRecentDailyActivities(10).filter { it.dateIso != todayIso }
        }
        lastDailyHistoryKey = key
        lastDailyHistoryRenderMs = nowMs

        // 1. TOP OF TODAY TAB: Today's whole-day movement card + Today's recorded run sessions
        if (::openTodaySummaryContainer.isInitialized) {
            openTodaySummaryContainer.removeAllViews()
            openTodaySummaryContainer.addView(
                buildDailySummaryCardView(
                    day = todaySummary,
                    isToday = true,
                    isYesterday = false,
                    samSnap = latestSamsungSnapshot
                )
            )
            val todaySessions = repository.getAllSessions().filter { it.dateIso == todayIso }
            val targetSec = repository.targetTimeSec
            for (s in todaySessions) {
                openTodaySummaryContainer.addView(
                    buildSessionLogRow(s, targetSec, highlightToday = true)
                )
            }
        }

        // Keep Progress tab's top Today whole-day summary strip in sync as well
        if (::progressTodayDailyStripLayout.isInitialized) {
            progressTodayDailyStripLayout.removeAllViews()
            progressTodayDailyStripLayout.addView(
                buildDailySummaryCardView(
                    day = todaySummary,
                    isToday = true,
                    isYesterday = false,
                    samSnap = latestSamsungSnapshot
                )
            )
        }

        // 2. BOTTOM OF TODAY TAB: Past Days ONLY (Yesterday + Earlier)
        val pastRows = mutableListOf<DailyActivitySummary>()
        val hasYesterdayInPast = cachedPastDailyLogs.any { it.dateIso == yesterdayIso }
        if (!hasYesterdayInPast) {
            val yestSummary = repository.getDailyActivity(yesterdayIso)
            if (yestSummary.totalSteps > 0 || yesterdaySamsungSnapshot != null) {
                pastRows.add(yestSummary)
            }
        }
        pastRows.addAll(cachedPastDailyLogs.filter { it.dateIso != todayIso })

        dailyHistoryListLayout.removeAllViews()
        if (pastRows.isEmpty()) {
            dailyHistoryListLayout.addView(TextView(this).apply {
                text = "No past daily activity logs before today yet."
                textSize = 12f
                setTextColor(AthleticColors.TEXT_MUTED)
            })
            return
        }

        for (day in pastRows) {
            val isYesterday = day.dateIso == yesterdayIso
            val samSnap = if (isYesterday) {
                yesterdaySamsungSnapshot ?: repository.getCachedSamsungSnapshot(day.dateIso)
            } else {
                repository.getCachedSamsungSnapshot(day.dateIso)
            }
            dailyHistoryListLayout.addView(
                buildDailySummaryCardView(
                    day = day,
                    isToday = false,
                    isYesterday = isYesterday,
                    samSnap = samSnap
                )
            )
        }
    }

    private fun renderLiveTelemetry(snap: LiveTelemetry) {
        currentlyRecording = snap.isRecording
        syncBackCallbackEnabled()
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
            else -> {
                val stridePart = if (snap.liveStrideLengthMeters >= 0.35) {
                    String.format(" • %.2fm/step", snap.liveStrideLengthMeters)
                } else ""
                "${snap.motionState.label}  •  ${TsPoliceBenchmark.formatPacePerKm(snap.currentSpeedMps)} (${snap.cadenceSpm.roundToInt()} spm$stridePart)"
            }
        }
        allDayGaitPill.setTextColor(
            if (snap.motionState == MotionState.STANDING) AthleticColors.TEXT_SECONDARY else snap.motionState.colorArgb
        )

        launchPtFromOpenBtn.text = when (snap.ptTrialPhase) {
            PtTrialPhase.IDLE -> "Arm 1.6 km PT Trial  •  Auto-Start  →"
            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> "1.6 km Trial Armed  •  Open Cockpit  →"
            PtTrialPhase.RUNNING_TRIAL -> "Live 1.6 km Trial (${snap.distanceMeters.toInt()}m)  •  View  →"
        }

        dailyMaxPaceVal.text = TsPoliceBenchmark.formatPacePerKm(daily.maxSpeedMps)
        dailyMaxPaceSub.text = String.format("Max %.1f km/h today", daily.maxSpeedMps * 3.6)
        dailyMaxCadenceVal.text = "${daily.maxCadenceSpm.roundToInt()} spm"
        dailyMaxCadenceSub.text = if (daily.maxCadenceSpm >= 168.0) "Target stride hit!" else "Peak stride rate"
        openLivePaceVal.text = TsPoliceBenchmark.formatPacePerKm(snap.currentSpeedMps)
        openLivePaceSub.text = if (snap.liveStrideLengthMeters >= 0.35) {
            String.format("%d spm • %.2fm stride", snap.cadenceSpm.roundToInt(), snap.liveStrideLengthMeters)
        } else {
            "${snap.cadenceSpm.roundToInt()} spm live cadence"
        }
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
        renderGoalCoachCard(dailyOverride = daily, force = false)

        // =====================================================================
        // B. RENDER 1.6 KM PT TRIAL COCKPIT (SUB-VIEW 1)
        // =====================================================================
        val acc = snap.gpsAccuracyMeters
        when {
            snap.isGpsLocked && acc != null && acc <= 12f -> {
                ptGpsReadinessPill.text = "TRACK GPS READY ±${acc.toInt()}m"
                ptGpsReadinessPill.setTextColor(AthleticColors.EMERALD_BRIGHT)
                ptGpsReadinessPill.background = AthleticTheme.solidPill(this, 0x22C6F432, AthleticColors.HERO_EMERALD_BORDER, 10f)
            }
            acc != null && acc <= 26f -> {
                ptGpsReadinessPill.text = "GPS WARMING ±${acc.toInt()}m"
                ptGpsReadinessPill.setTextColor(AthleticColors.AMBER_BRIGHT)
                ptGpsReadinessPill.background = AthleticTheme.solidPill(this, 0x22FF9F1C, AthleticColors.HERO_AMBER_BORDER, 10f)
            }
            else -> {
                ptGpsReadinessPill.text = "STRIDE FUSION READY"
                ptGpsReadinessPill.setTextColor(AthleticColors.CYAN_BRIGHT)
                ptGpsReadinessPill.background = AthleticTheme.solidPill(this, 0x229D6BFF, AthleticColors.HERO_CYAN_BORDER, 10f)
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
        metricCadenceSub.text = if (snap.liveStrideLengthMeters >= 0.35) {
            String.format("Stride %.2f m/step • %d steps", snap.liveStrideLengthMeters, snap.totalSteps)
        } else {
            "${snap.totalSteps} steps today"
        }

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

    private fun openTwoDaySamsungReportModal() {
        val yesterdayIso = LocalDate.now().minusDays(1).toString()
        val yesterdayTrk = repository.getDailyActivity(yesterdayIso)
        val todayTrk = latestDailySummaryForCompare
        SessionDialogs.showTwoDayComparisonDialog(
            ctx = this,
            yesterdayTracker = yesterdayTrk,
            yesterdaySamsung = yesterdaySamsungSnapshot ?: repository.getCachedSamsungSnapshot(yesterdayIso),
            todayTracker = todayTrk,
            todaySamsung = latestSamsungSnapshot,
            strideScale = repository.effectiveStrideScale()
        )
    }

    private fun renderSamsungComparisonCard(force: Boolean) {
        if (!::samsungHeadlineText.isInitialized) return
        val todayTrk = latestDailySummaryForCompare
        val yesterdayIso = LocalDate.now().minusDays(1).toString()
        val yesterdayTrk = repository.getDailyActivity(yesterdayIso)
        val todaySam = latestSamsungSnapshot
        val yesterdaySam = yesterdaySamsungSnapshot
        val scale = repository.effectiveStrideScale()

        val key = "${healthSyncStatus}:${isHealthSyncInFlight}:${activeComparisonScope}:" +
            "${todayTrk.totalSteps}:${todayTrk.totalDistanceMeters.toInt()}:${yesterdayTrk.totalSteps}:" +
            "${todaySam?.steps}:${todaySam?.distanceMeters?.toInt()}:" +
            "${yesterdaySam?.steps}:${yesterdaySam?.distanceMeters?.toInt()}:${String.format("%.3f", scale)}"
        if (!force && key == lastSamsungRenderKey) return
        lastSamsungRenderKey = key

        if (isHealthSyncInFlight) {
            samsungSyncBadge.text = "PULLING 2 DAYS..."
            samsungSyncBadge.background = AthleticTheme.solidPill(this, AthleticColors.CYAN_BRIGHT, AthleticColors.CYAN_BRIGHT, 10f, 0f)
            samsungPrimaryBtn.isEnabled = false
            samsungPrimaryBtn.text = "Pulling Yesterday & Today..."
            return
        }
        samsungPrimaryBtn.isEnabled = true

        // Style the 3 scope pills (Today | Yesterday | 2-Day Total)
        val scopePairs = listOf(
            ComparisonDayScope.TODAY to samScopeTodayBtn,
            ComparisonDayScope.YESTERDAY to samScopeYesterdayBtn,
            ComparisonDayScope.TWO_DAY_COMBINED to samScopeTwoDayBtn
        )
        for ((sc, btn) in scopePairs) {
            val active = sc == activeComparisonScope
            if (active) {
                btn.background = AthleticTheme.solidPill(this, AthleticColors.CYAN_BRIGHT, AthleticColors.CYAN_BRIGHT, 8.5f, 0f)
                btn.setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            } else {
                btn.background = null
                btn.setTextColor(AthleticColors.TEXT_SECONDARY)
            }
        }

        when (healthSyncStatus) {
            HealthSyncStatus.NOT_INSTALLED -> {
                samsungScopeBar.visibility = View.GONE
                samsungTwoDaySummaryStrip.visibility = View.GONE
                samsungCompareTable.visibility = View.GONE
                samsungReportBtn.visibility = View.GONE
                samsungSyncBadge.text = "INSTALL HC"
                samsungSyncBadge.background = AthleticTheme.solidPill(this, AthleticColors.AMBER_BRIGHT, AthleticColors.AMBER_BRIGHT, 10f, 0f)
                samsungHeadlineText.text = "Android Health Connect Needed"
                samsungDetailText.text = "Install or update Health Connect so Samsung Health can share Yesterday & Today steps, distance & runs."
                samsungPrimaryBtn.text = "Get Health Connect"
                samsungPrimaryBtn.setOnClickListener { healthSyncManager.openSamsungHealthOrHealthConnectSettings() }
                samsungSecondaryBtn.visibility = View.VISIBLE
                samsungSecondaryBtn.text = "Retry"
                samsungSecondaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }
            }

            HealthSyncStatus.PERMISSION_REQUIRED -> {
                samsungScopeBar.visibility = View.GONE
                samsungTwoDaySummaryStrip.visibility = View.GONE
                samsungCompareTable.visibility = View.GONE
                samsungReportBtn.visibility = View.GONE
                samsungSyncBadge.text = "CONNECT"
                samsungSyncBadge.background = AthleticTheme.solidPill(this, AthleticColors.CYAN_BRIGHT, AthleticColors.CYAN_BRIGHT, 10f, 0f)
                samsungHeadlineText.text = "Connect Samsung Health (Yesterday & Today)"
                samsungDetailText.text =
                    "Pulls Yesterday's completed 24h & Today's live Samsung Health data to compare accuracy side-by-side and calibrate your stride."
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
                samsungScopeBar.visibility = View.GONE
                samsungTwoDaySummaryStrip.visibility = View.GONE
                samsungCompareTable.visibility = View.GONE
                samsungReportBtn.visibility = View.GONE
                samsungSyncBadge.text = "ENABLE SYNC"
                samsungSyncBadge.background = AthleticTheme.solidPill(this, AthleticColors.AMBER_BRIGHT, AthleticColors.AMBER_BRIGHT, 10f, 0f)
                samsungHeadlineText.text = "Turn ON Health Connect inside Samsung Health"
                samsungDetailText.text =
                    "In Samsung Health → ⋮ → Settings → Health Connect → Allow all, then tap 'Pull Now'."
                samsungPrimaryBtn.text = "Open Samsung Health"
                samsungPrimaryBtn.setOnClickListener {
                    if (!healthSyncManager.openSamsungHealthAppDirectly()) {
                        healthSyncManager.openSamsungHealthOrHealthConnectSettings()
                    }
                }
                samsungSecondaryBtn.visibility = View.VISIBLE
                samsungSecondaryBtn.text = "Pull Now"
                samsungSecondaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }
            }

            HealthSyncStatus.SYNCED, HealthSyncStatus.ERROR -> {
                if (todaySam == null && yesterdaySam == null) {
                    samsungScopeBar.visibility = View.GONE
                    samsungTwoDaySummaryStrip.visibility = View.GONE
                    samsungCompareTable.visibility = View.GONE
                    samsungReportBtn.visibility = View.GONE
                    samsungSyncBadge.text = "SYNC"
                    samsungHeadlineText.text = "Tap to Pull Yesterday & Today Data"
                    samsungDetailText.text = "Pulls both Yesterday ($yesterdayIso) and Today from Samsung Health via Health Connect."
                    samsungPrimaryBtn.text = "Pull Yesterday & Today"
                    samsungPrimaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }
                    samsungSecondaryBtn.visibility = View.VISIBLE
                    samsungSecondaryBtn.text = "Settings"
                    samsungSecondaryBtn.setOnClickListener { healthSyncManager.openSamsungHealthOrHealthConnectSettings() }
                    return
                }

                samsungScopeBar.visibility = View.VISIBLE
                samsungReportBtn.visibility = View.VISIBLE

                // Render the 2-Day Quick Summary Strip so both Yesterday & Today match % are always visible
                val yestMatchStr = yesterdaySam?.let {
                    val r = SamsungHealthComparator.compare(yesterdayTrk, it, scale, "yesterday")
                    r.overallMatchPercent?.let { p -> String.format("Yesterday %.0f%% (%d/%d)", p, yesterdayTrk.totalSteps, it.steps) }
                        ?: "Yesterday: ${it.steps} steps"
                } ?: "Yesterday: --"
                val todayMatchStr = todaySam?.let {
                    val r = SamsungHealthComparator.compare(todayTrk, it, scale, "today")
                    r.overallMatchPercent?.let { p -> String.format("Today %.0f%% (%d/%d)", p, todayTrk.totalSteps, it.steps) }
                        ?: "Today: ${it.steps} steps"
                } ?: "Today: --"
                samsungTwoDaySummaryStrip.visibility = View.VISIBLE
                samsungTwoDaySummaryStrip.text = "$yestMatchStr   •   $todayMatchStr"

                val (selectedTrk, selectedSam, dayLabel) = when (activeComparisonScope) {
                    ComparisonDayScope.TODAY -> Triple(
                        todayTrk,
                        todaySam ?: SamsungDailySnapshot(dateIso = todayTrk.dateIso),
                        "today"
                    )
                    ComparisonDayScope.YESTERDAY -> Triple(
                        yesterdayTrk,
                        yesterdaySam ?: SamsungDailySnapshot(dateIso = yesterdayIso),
                        "yesterday ($yesterdayIso)"
                    )
                    ComparisonDayScope.TWO_DAY_COMBINED -> Triple(
                        SamsungHealthComparator.combineDailySummaries(yesterdayTrk, todayTrk),
                        SamsungHealthComparator.combineSamsungSnapshots(yesterdaySam, todaySam)
                            ?: SamsungDailySnapshot(dateIso = "$yesterdayIso..${todayTrk.dateIso}"),
                        "yesterday + today"
                    )
                }

                val report = SamsungHealthComparator.compare(selectedTrk, selectedSam, scale, dayLabel)
                samsungCompareTable.visibility = View.VISIBLE

                val matchBadge = report.overallMatchPercent?.let { String.format("%.0f%% MATCH", it) }
                    ?: String.format("STRIDE %.2fx", scale)
                val highMatch = (report.overallMatchPercent ?: 100.0) >= 88.0
                val badgeCol = if (highMatch) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
                samsungSyncBadge.text = matchBadge
                samsungSyncBadge.background = AthleticTheme.solidPill(this, badgeCol, badgeCol, 10f, 0f)

                samsungHeadlineText.text = report.headline
                samsungDetailText.text = report.detailAdvice

                samRowStepsTracker.text = "${selectedTrk.totalSteps}"
                samRowStepsSamsung.text = "${selectedSam.steps}"
                val stSign = if (report.stepsDelta > 0) "+${report.stepsDelta}" else "${report.stepsDelta}"
                samRowStepsDelta.text = report.stepsMatchPercent?.let {
                    String.format("%.0f%% (%s)", it, if (report.stepsDelta == 0) "0" else stSign)
                } ?: stSign
                samRowStepsDelta.setTextColor(
                    if ((report.stepsMatchPercent ?: 100.0) >= 88.0) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
                )

                samRowDistTracker.text = formatDistanceShort(selectedTrk.totalDistanceMeters)
                samRowDistSamsung.text = formatDistanceShort(selectedSam.distanceMeters)
                val dMeters = report.distanceDeltaMeters.roundToInt()
                val dSign = if (dMeters > 0) "+${dMeters}m" else "${dMeters}m"
                samRowDistDelta.text = report.distanceMatchPercent?.let {
                    String.format("%.0f%% (%s)", it, if (abs(dMeters) <= 3) "0m" else dSign)
                } ?: dSign
                samRowDistDelta.setTextColor(
                    if ((report.distanceMatchPercent ?: 100.0) >= 88.0) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
                )

                samRowWalkTracker.text = TsPoliceBenchmark.formatDuration(selectedTrk.walkingSec)
                samRowWalkSamsung.text = TsPoliceBenchmark.formatDuration(selectedSam.walkingSec)
                samRowWalkDelta.text = formatSignedSeconds(report.walkSecDelta)

                samRowRunTracker.text = TsPoliceBenchmark.formatDuration(selectedTrk.jogRunCombinedSec)
                samRowRunSamsung.text = TsPoliceBenchmark.formatDuration(selectedSam.jogRunSec)
                samRowRunDelta.text = formatSignedSeconds(report.jogRunSecDelta)

                samRowPaceTracker.text = TsPoliceBenchmark.formatPacePerKm(selectedTrk.maxSpeedMps)
                samRowPaceSamsung.text = TsPoliceBenchmark.formatPacePerKm(selectedSam.maxSpeedMps)
                samRowPaceDelta.text = if (selectedTrk.maxSpeedMps >= 0.4 && selectedSam.maxSpeedMps >= 0.4) {
                    val trkSecKm = 1000.0 / selectedTrk.maxSpeedMps
                    val samSecKm = 1000.0 / selectedSam.maxSpeedMps
                    val pDiff = (trkSecKm - samSecKm).roundToInt()
                    if (abs(pDiff) <= 3) "Exact" else String.format("%+ds/km", pDiff)
                } else "--"

                samRowCadTracker.text = if (selectedTrk.maxCadenceSpm > 0) "${selectedTrk.maxCadenceSpm.roundToInt()} spm" else "--"
                samRowCadSamsung.text = if (selectedSam.maxCadenceSpm > 0) "${selectedSam.maxCadenceSpm.roundToInt()} spm" else "--"
                samRowCadDelta.text = report.maxCadenceDeltaSpm?.let {
                    val cDiff = it.roundToInt()
                    if (abs(cDiff) <= 2) "Exact" else String.format("%+d spm", cDiff)
                } ?: "--"

                samRowStrideTracker.text = report.trackerStrideM?.let { String.format("%.2f m", it) } ?: "--"
                samRowStrideSamsung.text = report.samsungStrideM?.let { String.format("%.2f m", it) } ?: "--"
                samRowStrideDelta.text = String.format("Cal %.2fx", scale)
                samRowStrideDelta.setTextColor(AthleticColors.CYAN_BRIGHT)

                samsungPrimaryBtn.text = "Pull Yesterday & Today"
                samsungPrimaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }

                val needsAlignment = (selectedSam.steps > 0 || selectedSam.distanceMeters >= 20.0) && (
                    abs(selectedSam.steps - selectedTrk.totalSteps) >= 30 ||
                        abs(selectedSam.distanceMeters - selectedTrk.totalDistanceMeters) >= 35.0 ||
                        abs(selectedSam.jogRunSec - selectedTrk.jogRunCombinedSec) >= 20.0
                    )
                samsungSecondaryBtn.visibility = View.VISIBLE
                if (needsAlignment) {
                    val stepDeltaFromSam = selectedSam.steps - selectedTrk.totalSteps
                    samsungSecondaryBtn.text = when (activeComparisonScope) {
                        ComparisonDayScope.YESTERDAY -> when {
                            stepDeltaFromSam > 25 -> "Align Yest (+$stepDeltaFromSam)"
                            stepDeltaFromSam < -25 -> "Clean & Align Yest"
                            else -> "Align Yesterday"
                        }
                        ComparisonDayScope.TWO_DAY_COMBINED -> "Align Both Days"
                        ComparisonDayScope.TODAY -> when {
                            stepDeltaFromSam > 25 -> "Align (+$stepDeltaFromSam)"
                            stepDeltaFromSam < -25 -> "Clean & Align Today"
                            else -> "Align Today"
                        }
                    }
                    samsungSecondaryBtn.setTextColor(AthleticColors.CYAN_BRIGHT)
                    samsungSecondaryBtn.setOnClickListener {
                        when (activeComparisonScope) {
                            ComparisonDayScope.YESTERDAY -> {
                                yesterdaySam?.let { repository.alignDailyActivityWithSamsung(yesterdayIso, it) }
                            }
                            ComparisonDayScope.TODAY -> {
                                todaySam?.let { snap ->
                                    val svc = trackingService
                                    if (svc != null) {
                                        svc.alignTodayWithSamsungHealth(snap)
                                    } else {
                                        repository.alignDailyActivityWithSamsung(todayTrk.dateIso, snap)
                                    }
                                }
                            }
                            ComparisonDayScope.TWO_DAY_COMBINED -> {
                                yesterdaySam?.let { repository.alignDailyActivityWithSamsung(yesterdayIso, it) }
                                todaySam?.let { snap ->
                                    val svc = trackingService
                                    if (svc != null) {
                                        svc.alignTodayWithSamsungHealth(snap)
                                    } else {
                                        repository.alignDailyActivityWithSamsung(todayTrk.dateIso, snap)
                                    }
                                }
                            }
                        }
                        refreshCachedDailyHistory(forceRender = true)
                        renderSamsungComparisonCard(force = true)
                        renderDailyHistoryCards(latestDailySummaryForCompare)
                        Toast.makeText(this, "Aligned ${activeComparisonScope.titlePrefix} with ${selectedSam.sourceLabel}!", Toast.LENGTH_SHORT).show()
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
            if (outcome.yesterdaySnapshot != null) {
                yesterdaySamsungSnapshot = outcome.yesterdaySnapshot
            }
            trackingService?.syncThresholdsFromPrefs()
            refreshCachedDailyHistory(forceRender = true)
            if (outcome.importedRunCount > 0) {
                refreshProgressDashboard()
                Toast.makeText(
                    this@MainActivity,
                    "Pulled Yesterday & Today + imported ${outcome.importedRunCount} Samsung Health run(s)!",
                    Toast.LENGTH_LONG
                ).show()
            } else if (userTriggered) {
                Toast.makeText(this@MainActivity, outcome.statusMessage, Toast.LENGTH_SHORT).show()
            }
            renderSamsungComparisonCard(force = true)
            renderDailyHistoryCards(latestDailySummaryForCompare)

            // Hands-free automatic background telemetry + Samsung 48h upload (no manual button tap needed!)
            if (::tuningTelemetryManager.isInitialized && !isTuningUploadInFlight) {
                trackingService?.flushTuningBuffersNow()
                withContext(Dispatchers.IO) {
                    runCatching {
                        val handledCmd = tuningTelemetryManager.checkAndHandlePendingPullCommands {
                            latestDailySummaryForCompare
                        }
                        if (!handledCmd) {
                            tuningTelemetryManager.maybeAutoSyncIfDue(
                                liveTodayOverride = latestDailySummaryForCompare,
                                reason = "app_open"
                            )
                        }
                    }
                }
                refreshTuningStatusLabels()
            }
        }
    }

    private fun refreshTuningStatusLabels() {
        val lastSummary = repository.lastTuningSyncSummary
        if (::samsungSendTuningBtn.isInitialized) {
            samsungSendTuningBtn.isEnabled = !isTuningUploadInFlight
            samsungSendTuningBtn.text = if (isTuningUploadInFlight) {
                "Pulling 7-Day + Hourly Logs & Uploading..."
            } else {
                "Pull & Send Fine-Tuning Data (7-Day + Hourly Logs)"
            }
            samsungSendTuningBtn.setTextColor(
                if (isTuningUploadInFlight) AthleticColors.TEXT_SECONDARY else AthleticColors.EMERALD_BRIGHT
            )
        }
        if (::samsungTuningStatusText.isInitialized) {
            if (lastSummary.isNotBlank()) {
                samsungTuningStatusText.visibility = View.VISIBLE
                samsungTuningStatusText.text = "Last Fine-Tuning Pull: $lastSummary"
            } else {
                samsungTuningStatusText.visibility = View.GONE
            }
        }
        if (::labSendTuningBtn.isInitialized) {
            labSendTuningBtn.isEnabled = !isTuningUploadInFlight
            labSendTuningBtn.text = if (isTuningUploadInFlight) {
                "Pulling & Uploading Tuning Bundle..."
            } else {
                "Pull & Send Fine-Tuning Bundle"
            }
        }
        if (::labTuningStatusText.isInitialized) {
            labTuningStatusText.text = if (lastSummary.isNotBlank()) {
                "Last Sync: $lastSummary"
            } else {
                "Not sent yet — tap below to pull 7-day Samsung + 48h hourly sensor logs."
            }
        }
    }

    private fun triggerFineTuningUpload() {
        if (isTuningUploadInFlight || !::tuningTelemetryManager.isInitialized) return
        trackingService?.flushTuningBuffersNow()
        isTuningUploadInFlight = true
        lastHealthSyncElapsedMs = SystemClock.elapsedRealtime()
        refreshTuningStatusLabels()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                tuningTelemetryManager.pullBuildAndUploadTuningBundle(latestDailySummaryForCompare)
            }
            isTuningUploadInFlight = false
            healthSyncStatus = result.deepSamsung.syncOutcome.status
            result.deepSamsung.syncOutcome.snapshot?.let { latestSamsungSnapshot = it }
            result.deepSamsung.syncOutcome.yesterdaySnapshot?.let { yesterdaySamsungSnapshot = it }
            trackingService?.syncThresholdsFromPrefs()
            refreshTuningStatusLabels()
            refreshCachedDailyHistory(forceRender = true)
            if (result.deepSamsung.syncOutcome.importedRunCount > 0) {
                refreshProgressDashboard()
            }
            renderSamsungComparisonCard(force = true)
            renderDailyHistoryCards(latestDailySummaryForCompare)
            if (!isFinishing) {
                SessionDialogs.showTuningUploadResultDialog(this@MainActivity, result)
            }
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

        val goalPlan = buildCurrentGoalPlan(sessions = sessions)
        applyGoalPlanToTodayCard(goalPlan)
        renderProgressGoalRoadmap(goalPlan)

        val todayIso = LocalDate.now().toString()
        val todaySessions = sessions.filter { it.dateIso == todayIso }
        val pastSessions = sessions.filter { it.dateIso != todayIso }
        val todaySummary = latestDailySummaryForCompare.takeIf { it.dateIso == todayIso }
            ?: repository.getDailyActivity(todayIso)

        // 1. TOP OF PROGRESS TAB: Today's Activity Logs (Whole-day summary + Today's recorded runs/splits)
        if (::progressTodayHeaderLabel.isInitialized) {
            progressTodayHeaderLabel.text = "TODAY'S ACTIVITY LOGS  •  TAP FOR SPLITS"
            if (todaySessions.isNotEmpty()) {
                progressTodayCountBadge.text =
                    "${todaySessions.size} RUN${if (todaySessions.size == 1) "" else "S"} TODAY"
                progressTodayCountBadge.setTextColor(AthleticColors.EMERALD_BRIGHT)
                progressTodayCountBadge.background = AthleticTheme.solidPill(
                    this,
                    0x2EC6F432,
                    AthleticColors.EMERALD_BRIGHT,
                    10f
                )
            } else {
                progressTodayCountBadge.text = "LIVE TODAY"
                progressTodayCountBadge.setTextColor(AthleticColors.CYAN_BRIGHT)
                progressTodayCountBadge.background = AthleticTheme.solidPill(
                    this,
                    0x269D6BFF,
                    AthleticColors.CYAN_BRIGHT,
                    10f
                )
            }

            progressTodayDailyStripLayout.removeAllViews()
            progressTodayDailyStripLayout.addView(
                buildDailySummaryCardView(
                    day = todaySummary,
                    isToday = true,
                    isYesterday = false,
                    samSnap = latestSamsungSnapshot
                )
            )

            todaySessionsListLayout.removeAllViews()
            if (todaySessions.isEmpty()) {
                todaySessionsListLayout.addView(TextView(this).apply {
                    text = "No timed or auto run logged yet today — start a 1.6K Trial, 400m Intervals, or tap '+ Log Time'."
                    textSize = 11.5f
                    setTextColor(AthleticColors.TEXT_MUTED)
                    setPadding(dp(2), dp(2), dp(2), 0)
                })
            } else {
                for (s in todaySessions) {
                    todaySessionsListLayout.addView(buildSessionLogRow(s, targetSec, highlightToday = true))
                }
            }
        }

        // Also keep Today tab's top activity container synchronized whenever sessions change
        lastDailyHistoryKey = ""
        renderDailyHistoryCards(todaySummary)

        // 2. BOTTOM OF PROGRESS TAB: Past Activity Logs (Previous Days Only)
        if (::pastSessionsHeaderLabel.isInitialized) {
            pastSessionsHeaderLabel.text = if (pastSessions.isNotEmpty()) {
                "PAST ACTIVITY LOGS  •  ${pastSessions.size} RUN${if (pastSessions.size == 1) "" else "S"}  •  TAP FOR SPLITS"
            } else {
                "PAST ACTIVITY LOGS  •  PREVIOUS DAYS"
            }
        }

        sessionsListLayout.removeAllViews()
        if (pastSessions.isEmpty()) {
            sessionsListLayout.addView(TextView(this).apply {
                text = if (sessions.isEmpty()) {
                    "No past runs yet. Arm a 1.6 km PT Trial, run 400m Intervals, sync Samsung Health, or tap 'Sample Data'."
                } else {
                    "No past run logs before today."
                }
                textSize = 12.5f
                setTextColor(AthleticColors.TEXT_MUTED)
            })
            return
        }

        for (s in pastSessions) {
            sessionsListLayout.addView(buildSessionLogRow(s, targetSec, highlightToday = false))
        }
    }

    private fun buildSessionLogRow(
        s: RunSessionRecord,
        targetSec: Double,
        highlightToday: Boolean
    ): LinearLayout {
        val merit = TsPoliceBenchmark.evaluateMerit1600m(s.equiv1600mSec, targetSec)
        val borderColor = when {
            merit.qualified -> AthleticColors.HERO_EMERALD_BORDER
            highlightToday -> AthleticColors.HERO_CYAN_BORDER
            else -> AthleticColors.BORDER_SUBTLE
        }
        val bgColor = if (highlightToday) 0xFF141720.toInt() else AthleticColors.SURFACE_RECESSED

        val rowCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = AthleticTheme.rippleSurface(
                this@MainActivity,
                bgColor,
                bgColor,
                borderColor,
                12f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                SessionDialogs.showSessionDetailDialog(this@MainActivity, s, targetSec) {
                    repository.deleteSession(s.id)
                    refreshCachedDailyHistory(forceRender = true)
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
            val datePrefix = if (highlightToday) "TODAY" else s.dateIso
            text = "$datePrefix  •  $modeBadge$demoTag"
            textSize = 12f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(if (highlightToday) AthleticColors.EMERALD_BRIGHT else AthleticColors.TEXT_PRIMARY)
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
                    refreshCachedDailyHistory(forceRender = true)
                    refreshProgressDashboard()
                }
            }
            addView(
                VectorIconView(this@MainActivity, IconKind.TRASH, AthleticColors.CRIMSON, 15f).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER)
                }
            )
        }
        bottomLine.addView(delHitbox)
        rowCard.addView(bottomLine)
        return rowCard
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
            batteryStatusBadge.background = AthleticTheme.solidPill(this, 0x22C6F432, AthleticColors.HERO_EMERALD_BORDER, 9f)
            batteryStatusBtn.text = "Background Battery Protection Active"
            AthleticTheme.applyButtonStyle(this, batteryStatusBtn, AthleticTheme.ButtonStyle.SECONDARY_SLATE)
        } else {
            batteryStatusBadge.text = "RESTRICTED"
            batteryStatusBadge.setTextColor(AthleticColors.AMBER_BRIGHT)
            batteryStatusBadge.background = AthleticTheme.solidPill(this, 0x22FF9F1C, AthleticColors.HERO_AMBER_BORDER, 9f)
            batteryStatusBtn.text = "Allow Unrestricted Background Tracking"
            AthleticTheme.applyButtonStyle(this, batteryStatusBtn, AthleticTheme.ButtonStyle.PRIMARY_CYAN)
        }
    }

    private fun refreshSettingsHighlights() {
        val curTarget = repository.targetTimeSec
        val deadlineDate = runCatching { LocalDate.parse(repository.goalDeadlineIso) }
            .getOrElse { LocalDate.of(2026, 11, 30) }
        val shortDeadline = "${deadlineDate.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${deadlineDate.dayOfMonth}"
        headerSubtitleText.text = "PET Coach  •  < ${TsPoliceBenchmark.formatDuration(curTarget)} by $shortDeadline"

        if (::goalDeadlineBtn.isInitialized) {
            val daysLeft = (deadlineDate.toEpochDay() - LocalDate.now().toEpochDay()).toInt().coerceAtLeast(0)
            goalDeadlineBtn.text = "$shortDeadline, ${deadlineDate.year} (${daysLeft}d)  ✎"
        }

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
                headerAudioPill.background = AthleticTheme.solidPill(this, 0x22C6F432, AthleticColors.HERO_EMERALD_BORDER, 18f)
            }
            PocketAudioMode.ALL_STATES -> {
                headerAudioIcon.update(IconKind.AUDIO_ALL, AthleticColors.CYAN_BRIGHT)
                headerAudioLabel.text = "All Audio"
                headerAudioLabel.setTextColor(AthleticColors.CYAN_BRIGHT)
                headerAudioPill.background = AthleticTheme.solidPill(this, 0x229D6BFF, AthleticColors.HERO_CYAN_BORDER, 18f)
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
    // GITHUB OTA AUTO-UPDATE FLOW (AMOLED SHEET + NEON PROGRESS BAR)
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

            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    latestDiscoveredRelease = result.release
                    updateBannerTitleText.text = "Update v${result.release.versionName} Available"
                    updateBanner.visibility = View.VISIBLE

                    updateCardBadge.text = "NEW v${result.release.versionName}"
                    updateCardBadge.setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
                    updateCardBadge.background = AthleticTheme.solidPill(
                        this,
                        AthleticColors.CYAN_BRIGHT,
                        AthleticColors.CYAN_BRIGHT,
                        10f,
                        0f
                    )
                    updateStatusText.text = "Update ready: v${result.installedVersionName}  →  v${result.release.versionName}"
                    updateStatusText.setTextColor(AthleticColors.CYAN_BRIGHT)
                    checkUpdateBtn.text = "Review & Install v${result.release.versionName}  →"

                    if (autoInstallWhenFound && !currentlyRecording) {
                        showUpdateSheet(result.installedVersionName, result.release, startImmediately = true)
                    } else if (manualUserTrigger || !currentlyRecording) {
                        showUpdateSheet(result.installedVersionName, result.release, startImmediately = false)
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    latestDiscoveredRelease = null
                    updateBanner.visibility = View.GONE
                    updateCardBadge.text = "UP TO DATE"
                    updateCardBadge.setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
                    updateCardBadge.background = AthleticTheme.solidPill(
                        this,
                        AthleticColors.EMERALD_BRIGHT,
                        AthleticColors.EMERALD_BRIGHT,
                        10f,
                        0f
                    )
                    updateStatusText.text = "Build ${result.installedVersionCode}  •  Latest release installed"
                    updateStatusText.setTextColor(AthleticColors.EMERALD_BRIGHT)
                    checkUpdateBtn.text = "Check for App Updates"
                    if (manualUserTrigger) {
                        Toast.makeText(this, "You're on the latest version (v${result.installedVersionName})!", Toast.LENGTH_SHORT).show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    checkUpdateBtn.text = "Check for App Updates"
                    if (manualUserTrigger) {
                        Toast.makeText(this, "Update check: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(currentVersion: String, release: UpdateReleaseInfo) {
        showUpdateSheet(currentVersion, release, startImmediately = false)
    }

    private fun startUpdateDownloadAndInstall(release: UpdateReleaseInfo) {
        val (_, curName) = appUpdater.getInstalledVersion()
        showUpdateSheet(curName, release, startImmediately = true)
    }

    private fun showUpdateSheet(
        currentVersion: String,
        release: UpdateReleaseInfo,
        startImmediately: Boolean
    ) {
        activeUpdateDialog?.dismiss()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = AthleticTheme.gradientCard(
                this@MainActivity,
                topColor = 0xFF15151B.toInt(),
                bottomColor = 0xFF09090D.toInt(),
                borderColor = AthleticColors.HERO_EMERALD_BORDER,
                radiusDp = 26f,
                strokeDp = 1.2f
            )
            elevation = dp(14).toFloat()
        }

        // 1. Hero Header Row (Sprinting Runner Emblem + Overline + Title)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val emblemFrame = FrameLayout(this).apply {
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0xFF1A2406.toInt(),
                AthleticColors.HERO_EMERALD_BORDER,
                16f
            )
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(13) }
            addView(
                VectorIconView(this@MainActivity, IconKind.TRACK_EMBLEM, AthleticColors.EMERALD_BRIGHT, 38f).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
                }
            )
        }
        headerRow.addView(emblemFrame)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "OVER-THE-AIR UPDATE"
            textSize = 10.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.CYAN_BRIGHT)
            letterSpacing = 0.08f
        })
        titleCol.addView(TextView(this).apply {
            text = "TS Police 1.6K  v${release.versionName}"
            textSize = 19f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(2), 0, 0)
        })
        headerRow.addView(titleCol)
        card.addView(headerRow)

        // 2. Version Transition Pill Row
        val verRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            }
        }
        verRow.addView(TextView(this).apply {
            text = "v$currentVersion"
            textSize = 11.5f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(dp(10), dp(4.5f), dp(10), dp(4.5f))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                AthleticColors.SURFACE_RECESSED,
                AthleticColors.BORDER_SUBTLE,
                10f
            )
        })
        verRow.addView(TextView(this).apply {
            text = "  →  "
            textSize = 13f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_MUTED)
        })
        verRow.addView(TextView(this).apply {
            text = "v${release.versionName}" + if (release.versionCode > 0) "  •  Build ${release.versionCode}" else ""
            textSize = 11.5f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            setPadding(dp(10), dp(4.5f), dp(10), dp(4.5f))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0x24C6F432,
                AthleticColors.HERO_EMERALD_BORDER,
                10f
            )
        })
        verRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        verRow.addView(TextView(this).apply {
            text = "SHA-256"
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.CYAN_BRIGHT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0x249D6BFF,
                AthleticColors.HERO_CYAN_BORDER,
                9f
            )
        })
        card.addView(verRow)

        // 3. Recessed "What's New" Release Notes Box
        val notesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                AthleticColors.SURFACE_RECESSED,
                AthleticColors.BORDER_SUBTLE,
                15f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        notesCard.addView(AthleticTheme.sectionLabel(this, "WHAT'S NEW", AthleticColors.EMERALD_BRIGHT))

        val notesScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = dp(175)
                val cappedSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cappedSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        notesScroll.addView(TextView(this).apply {
            text = release.changelog.ifBlank { "Performance, UI & sensor accuracy improvements." }
            textSize = 13f
            setTextColor(0xFFE4E4E7.toInt())
            setLineSpacing(dp(2.5f).toFloat(), 1f)
        })
        notesCard.addView(notesScroll)
        card.addView(notesCard)

        // 4. Data Preservation Pill
        val safeNote = TextView(this).apply {
            text = "✓  1.6 km history, daily steps & Samsung calibration stay intact"
            textSize = 11.5f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0x14C6F432,
                0x385A7A12,
                11f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        card.addView(safeNote)

        // 5. Seamless Live In-Card Download & Verification Progress Section
        val progressSection = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(12))
            background = AthleticTheme.solidPill(
                this@MainActivity,
                0xFF101016.toInt(),
                AthleticColors.BORDER_STRONG,
                14f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val progressTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val dlStatusTv = TextView(this).apply {
            text = "Connecting to GitHub Releases..."
            textSize = 12f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val dlPercentTv = TextView(this).apply {
            text = ""
            textSize = 13f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(AthleticColors.EMERALD_BRIGHT)
        }
        progressTopRow.addView(dlStatusTv)
        progressTopRow.addView(dlPercentTv)
        progressSection.addView(progressTopRow)

        val neonProgressBar = NeonUpdateProgressBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(12)
            )
        }
        progressSection.addView(neonProgressBar)
        card.addView(progressSection)

        // 6. Primary & Secondary Stadium Pill Buttons
        val primaryBtn = AthleticTheme.createButton(
            this,
            "Download & Install Update  →",
            AthleticTheme.ButtonStyle.PRIMARY_EMERALD,
            50f
        ).apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val secondaryBtn = AthleticTheme.createButton(
            this,
            "Later",
            AthleticTheme.ButtonStyle.SUBTLE_OUTLINE,
            44f
        ).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { dialog.dismiss() }
        }

        fun runDownloadFlow() {
            dialog.setCancelable(false)
            secondaryBtn.visibility = View.GONE
            primaryBtn.isEnabled = false
            primaryBtn.alpha = 0.65f
            primaryBtn.text = "Downloading & Verifying..."
            AthleticTheme.applyButtonStyle(this, primaryBtn, AthleticTheme.ButtonStyle.SECONDARY_SLATE)

            if (progressSection.visibility != View.VISIBLE) {
                progressSection.alpha = 0f
                progressSection.visibility = View.VISIBLE
                progressSection.animate().alpha(1f).setDuration(180L).start()
            }
            neonProgressBar.visibility = View.VISIBLE
            neonProgressBar.isIndeterminateMode = true
            dlStatusTv.setTextColor(AthleticColors.TEXT_PRIMARY)
            dlStatusTv.text = "Connecting to GitHub Releases..."
            dlPercentTv.text = ""

            appUpdater.downloadAndInstall(
                release = release,
                onProgress = { pct, downloaded, total ->
                    if (!dialog.isShowing) return@downloadAndInstall
                    if (pct >= 0 && total > 0L) {
                        neonProgressBar.setProgressPercent(pct, animate = true)
                        dlPercentTv.text = "$pct%"
                        dlStatusTv.text = if (pct >= 100) {
                            "Verifying SHA-256 signature..."
                        } else {
                            String.format(
                                "Downloading APK  •  %.1f / %.1f MB",
                                downloaded / (1024.0 * 1024.0),
                                total / (1024.0 * 1024.0)
                            )
                        }
                    } else {
                        neonProgressBar.isIndeterminateMode = true
                        dlPercentTv.text = ""
                        dlStatusTv.text = String.format("Downloading APK  •  %.1f MB", downloaded / (1024.0 * 1024.0))
                    }
                },
                onNeedsInstallPermission = {
                    if (!dialog.isShowing) return@downloadAndInstall
                    dialog.setCancelable(true)
                    neonProgressBar.setProgressPercent(100, animate = false)
                    dlPercentTv.text = "✓ Verified"
                    dlStatusTv.setTextColor(AthleticColors.EMERALD_BRIGHT)
                    dlStatusTv.text = "APK verified! Allow 'Install unknown apps' on the next screen, then press Back."

                    primaryBtn.isEnabled = true
                    primaryBtn.alpha = 1f
                    primaryBtn.text = "Allow Self-Update in Settings  →"
                    AthleticTheme.applyButtonStyle(this, primaryBtn, AthleticTheme.ButtonStyle.PRIMARY_EMERALD)
                    primaryBtn.setOnClickListener {
                        dialog.dismiss()
                        appUpdater.openUnknownSourcesSettings(this)
                    }
                    secondaryBtn.text = "Cancel"
                    secondaryBtn.visibility = View.VISIBLE
                },
                onError = { msg ->
                    if (!dialog.isShowing) return@downloadAndInstall
                    dialog.setCancelable(true)
                    neonProgressBar.visibility = View.GONE
                    dlPercentTv.text = ""
                    dlStatusTv.setTextColor(AthleticColors.CRIMSON)
                    dlStatusTv.text = "Update failed: $msg"

                    primaryBtn.isEnabled = true
                    primaryBtn.alpha = 1f
                    primaryBtn.text = "Retry Download  →"
                    AthleticTheme.applyButtonStyle(this, primaryBtn, AthleticTheme.ButtonStyle.PRIMARY_EMERALD)
                    primaryBtn.setOnClickListener { runDownloadFlow() }

                    secondaryBtn.text = "Close"
                    secondaryBtn.visibility = View.VISIBLE
                },
                onInstallerLaunched = {
                    if (dialog.isShowing) dialog.dismiss()
                }
            )
        }

        primaryBtn.setOnClickListener { runDownloadFlow() }

        card.addView(primaryBtn)
        card.addView(secondaryBtn)

        dialog.setContentView(card)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setDimAmount(0.80f)
            val widthPx = (resources.displayMetrics.widthPixels * 0.92f).roundToInt()
            win.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        activeUpdateDialog = dialog
        dialog.show()

        card.alpha = 0f
        card.translationY = dp(24).toFloat()
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .start()

        if (startImmediately) {
            runDownloadFlow()
        }
    }

    // =========================================================================
    // NOV 30 GOAL DEADLINE & DYNAMIC DAILY PUSH COACH
    // =========================================================================
    private fun buildCurrentGoalPlan(
        sessions: List<RunSessionRecord>? = null,
        dailyOverride: DailyActivitySummary? = null
    ): DeadlineGoalPlan {
        val todayNow = LocalDate.now()
        val todayIso = todayNow.toString()
        val yesterdayIso = todayNow.minusDays(1).toString()
        val allSessions = sessions ?: repository.getAllSessions()
        val todaySum = dailyOverride
            ?: latestDailySummaryForCompare.takeIf { it.dateIso == todayIso }
            ?: repository.getDailyActivity(todayIso)
        val yesterdaySum = cachedPastDailyLogs.firstOrNull { it.dateIso == yesterdayIso }
            ?: repository.getDailyActivity(yesterdayIso)

        return DeadlineGoalCoach.buildPlan(
            sessions = allSessions,
            todaySummary = todaySum,
            yesterdaySummary = yesterdaySum,
            targetTimeSec = repository.targetTimeSec,
            deadlineIso = repository.goalDeadlineIso,
            todayIso = todayIso
        )
    }

    private fun renderGoalCoachCard(
        dailyOverride: DailyActivitySummary? = null,
        force: Boolean = false
    ) {
        if (!::goalCoachCard.isInitialized) return
        val nowMs = SystemClock.elapsedRealtime()
        val todaySum = dailyOverride ?: latestDailySummaryForCompare
        val key = "${LocalDate.now()}:${repository.targetTimeSec.toInt()}:${repository.goalDeadlineIso}:" +
            "${(todaySum.jogRunCombinedMeters / 100.0).toInt()}"
        if (!force && key == lastGoalCoachKey && (nowMs - lastGoalCoachRenderMs) < 12_000L) {
            return
        }
        lastGoalCoachKey = key
        lastGoalCoachRenderMs = nowMs

        val plan = buildCurrentGoalPlan(dailyOverride = dailyOverride)
        applyGoalPlanToTodayCard(plan)
    }

    private fun applyGoalPlanToTodayCard(plan: DeadlineGoalPlan) {
        if (!::goalCoachCard.isInitialized) return

        val (topGrad, botGrad, borderCol, accentCol, btnStyle) = when (plan.pushAccentKind) {
            PushAccentKind.ORANGE_HARD -> quintuple(
                AthleticColors.HERO_AMBER_TOP,
                AthleticColors.HERO_AMBER_BOTTOM,
                AthleticColors.HERO_AMBER_BORDER,
                AthleticColors.AMBER_BRIGHT,
                AthleticTheme.ButtonStyle.WARNING_AMBER
            )
            PushAccentKind.PURPLE_RECOVERY -> quintuple(
                AthleticColors.HERO_CYAN_TOP,
                AthleticColors.HERO_CYAN_BOTTOM,
                AthleticColors.HERO_CYAN_BORDER,
                AthleticColors.CYAN_BRIGHT,
                AthleticTheme.ButtonStyle.PRIMARY_CYAN
            )
            PushAccentKind.LIME_GO -> quintuple(
                AthleticColors.HERO_EMERALD_TOP,
                AthleticColors.HERO_EMERALD_BOTTOM,
                AthleticColors.HERO_EMERALD_BORDER,
                AthleticColors.EMERALD_BRIGHT,
                AthleticTheme.ButtonStyle.PRIMARY_EMERALD
            )
        }

        goalCoachCard.background = AthleticTheme.gradientCard(this, topGrad, botGrad, borderCol, 18f)

        val daysTxt = if (plan.daysRemaining == 0) {
            "EXAM DAY (${plan.deadlineLabel.uppercase()})"
        } else {
            "${plan.deadlineLabel.uppercase()} GOAL  •  ${plan.daysRemaining}D LEFT  ✎"
        }
        goalHeaderLabel.text = daysTxt
        goalHeaderLabel.setTextColor(accentCol)

        goalPushBadge.text = plan.pushBadgeLabel
        goalPushBadge.setTextColor(accentCol)
        goalPushBadge.background = AthleticTheme.solidPill(
            this,
            (accentCol and 0x00FFFFFF) or 0x26000000,
            accentCol,
            10f
        )

        goalReadinessBar.setProgressPercent(plan.readinessPercent, animate = true)
        goalStatusText.text = plan.statusSummary
        goalDirectiveText.text = plan.todayPushDirective
        goalDirectiveText.background = AthleticTheme.solidPill(
            this,
            0x36000000,
            borderCol,
            12f
        )

        // Compact 4-stage milestone strip inside Today Goal Card
        goalMiniMilestonesLayout.removeAllViews()
        for ((idx, cp) in plan.checkpoints.withIndex()) {
            val isGoalDay = idx == plan.checkpoints.lastIndex
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
                background = AthleticTheme.solidPill(
                    this@MainActivity,
                    if (isGoalDay) 0x22C6F432 else AthleticColors.SURFACE_RECESSED,
                    if (isGoalDay) AthleticColors.HERO_EMERALD_BORDER else AthleticColors.BORDER_SUBTLE,
                    10f
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = dp(4)
                }
            }
            chip.addView(TextView(this).apply {
                text = cp.label.uppercase()
                textSize = 9.5f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(if (isGoalDay) AthleticColors.EMERALD_BRIGHT else AthleticColors.TEXT_SECONDARY)
                maxLines = 1
            })
            chip.addView(TextView(this).apply {
                text = TsPoliceBenchmark.formatDuration(cp.targetTimeSec)
                textSize = 13f
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(if (isGoalDay) AthleticColors.EMERALD_BRIGHT else AthleticColors.TEXT_PRIMARY)
                setPadding(0, dp(2), 0, dp(1))
                maxLines = 1
            })
            chip.addView(TextView(this).apply {
                text = cp.dateLabel
                textSize = 10f
                setTextColor(AthleticColors.TEXT_MUTED)
                maxLines = 1
            })
            goalMiniMilestonesLayout.addView(chip)
        }

        goalActionBtn.text = plan.recommendedActionLabel
        AthleticTheme.applyButtonStyle(this, goalActionBtn, btnStyle)
        goalActionBtn.setOnClickListener {
            when (plan.recommendedAction) {
                RecommendedWorkoutAction.ARM_1600M_TRIAL -> {
                    selectTrackerSubTab(1, animate = true)
                    if (lastRenderedPtPhase == PtTrialPhase.IDLE) {
                        requestNeededPermissionsIfMissing(silentCheckOnly = false)
                        trackingService?.armPtTrial()
                    }
                }
                RecommendedWorkoutAction.START_400M_INTERVALS -> {
                    selectedIntervalReps = 6
                    refreshIntervalRepPresets()
                    selectTrackerSubTab(2, animate = true)
                }
                RecommendedWorkoutAction.DONE_FOR_TODAY -> {
                    selectTab(1, smooth = true)
                }
                RecommendedWorkoutAction.EASY_AEROBIC_JOG -> {
                    requestNeededPermissionsIfMissing(silentCheckOnly = false)
                    Toast.makeText(
                        this,
                        "Push ~4.5/10 easy continuous jog (145–155 spm) — Today auto-records every step!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun renderProgressGoalRoadmap(plan: DeadlineGoalPlan) {
        if (!::progressMilestonesLayout.isInitialized) return

        val targetFmt = TsPoliceBenchmark.formatDuration(plan.targetTimeSec)
        progressGoalHeaderLabel.text = if (plan.daysRemaining == 0) {
            "EXAM DAY (${plan.deadlineLabel.uppercase()})  •  <$targetFmt  ✎"
        } else {
            "${plan.deadlineLabel.uppercase()} ROADMAP  •  ${plan.daysRemaining}D LEFT  ✎"
        }

        val qualified = plan.readinessPercent >= 100
        progressGoalReadinessPill.text = if (qualified) "QUALIFIED ✓" else "${plan.readinessPercent}% READY"
        val rCol = if (qualified) AthleticColors.EMERALD_BRIGHT else AthleticColors.CYAN_BRIGHT
        progressGoalReadinessPill.setTextColor(rCol)
        progressGoalReadinessPill.background = AthleticTheme.solidPill(
            this,
            (rCol and 0x00FFFFFF) or 0x26000000,
            rCol,
            10f
        )

        val cutHint = plan.requiredCutPerWeekSec?.let {
            "Required pace gain: -${String.format("%.0f", it)}s/wk from moving capability. "
        } ?: ""
        progressGoalWeeklyMixText.text = cutHint + plan.weeklyStructureHint

        progressMilestonesLayout.removeAllViews()
        for ((idx, cp) in plan.checkpoints.withIndex()) {
            val isFinal = idx == plan.checkpoints.lastIndex
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(5), dp(9), dp(5), dp(9))
                background = AthleticTheme.solidPill(
                    this@MainActivity,
                    if (isFinal) 0x24C6F432 else AthleticColors.SURFACE_RECESSED,
                    if (isFinal) AthleticColors.HERO_EMERALD_BORDER else AthleticColors.BORDER_SUBTLE,
                    11f
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = dp(5)
                }
            }
            card.addView(TextView(this).apply {
                text = cp.label.uppercase()
                textSize = 9.5f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(if (isFinal) AthleticColors.EMERALD_BRIGHT else AthleticColors.CYAN_BRIGHT)
                maxLines = 1
            })
            card.addView(TextView(this).apply {
                text = cp.dateLabel
                textSize = 10f
                setTextColor(AthleticColors.TEXT_MUTED)
                maxLines = 1
            })
            card.addView(TextView(this).apply {
                text = TsPoliceBenchmark.formatDuration(cp.targetTimeSec)
                textSize = 14f
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(if (isFinal) AthleticColors.EMERALD_BRIGHT else AthleticColors.TEXT_PRIMARY)
                setPadding(0, dp(3), 0, dp(2))
                maxLines = 1
            })
            card.addView(TextView(this).apply {
                text = "${TsPoliceBenchmark.formatDuration(cp.targetLap400mSec)}/lap"
                textSize = 9.5f
                setTextColor(AthleticColors.TEXT_SECONDARY)
                maxLines = 1
            })
            progressMilestonesLayout.addView(card)
        }
    }

    private fun showGoalDeadlinePicker() {
        val current = runCatching { LocalDate.parse(repository.goalDeadlineIso) }
            .getOrElse { LocalDate.of(2026, 11, 30) }
        val picker = DatePickerDialog(
            this,
            { _, year, monthZeroBased, dayOfMonth ->
                val picked = LocalDate.of(year, monthZeroBased + 1, dayOfMonth)
                repository.goalDeadlineIso = picked.toString()
                lastGoalCoachKey = ""
                refreshSettingsHighlights()
                refreshProgressDashboard()
                val daysLeft = (picked.toEpochDay() - LocalDate.now().toEpochDay()).toInt().coerceAtLeast(0)
                Toast.makeText(
                    this,
                    "Goal deadline set to $picked ($daysLeft days remaining)",
                    Toast.LENGTH_SHORT
                ).show()
            },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth
        )
        picker.datePicker.minDate = System.currentTimeMillis() - 86_400_000L
        picker.show()
    }

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )

    private fun <A, B, C, D, E> quintuple(a: A, b: B, c: C, d: D, e: E) = Quintuple(a, b, c, d, e)

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
