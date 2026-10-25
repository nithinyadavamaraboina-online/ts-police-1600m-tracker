package com.tspolice.runtracker.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
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
import com.tspolice.runtracker.domain.LiveTelemetry
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.ProgressionForecaster
import com.tspolice.runtracker.domain.PtTrialPhase
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
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
import java.time.ZoneId
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

    // Tracker sub-tab state: 0 = Always-On All-Day Open Mode (default), 1 = 1.6 km PT Trial
    private var activeTrackerSubTab = 0
    private var lastRenderedPtPhase: PtTrialPhase = PtTrialPhase.IDLE
    private var cachedPastDailyLogs: List<DailyActivitySummary> = emptyList()
    private var lastDailyHistoryRenderMs = -60_000L
    private var lastDailyHistoryKey = ""

    // Top-level tab containers
    private lateinit var stateAudioChip: TextView
    private lateinit var updateBanner: TextView
    private lateinit var tabTrackerContainer: ScrollView
    private lateinit var tabProgressContainer: ScrollView
    private lateinit var tabGuideContainer: ScrollView
    private val navTabViews = mutableListOf<TextView>()

    // Tab 1: Sub-Tab Switcher & Containers
    private lateinit var permissionBanner: LinearLayout
    private lateinit var subTabOpenBtn: TextView
    private lateinit var subTabPtBtn: TextView
    private lateinit var openAllDayContainer: LinearLayout
    private lateinit var ptTrialContainer: LinearLayout

    // Tab 1A: Always-On All-Day Open Mode views
    private lateinit var allDayLiveChip: TextView
    private lateinit var allDayStateBadge: TextView
    private lateinit var allDayStatusSub: TextView
    private lateinit var dailyJogRunTimeVal: TextView
    private lateinit var dailyJogRunDistSub: TextView
    private lateinit var dailyJogRunSplitSub: TextView
    private lateinit var dailyWalkTimeVal: TextView
    private lateinit var dailyWalkDistSub: TextView
    private lateinit var dailyWalkStepsSub: TextView
    private lateinit var dailyMaxPaceVal: TextView
    private lateinit var dailyMaxPaceSub: TextView
    private lateinit var dailyMaxCadenceVal: TextView
    private lateinit var dailyMaxCadenceSub: TextView
    private lateinit var openLivePaceVal: TextView
    private lateinit var openLivePaceSub: TextView
    private lateinit var openLiveCadenceVal: TextView
    private lateinit var openLiveCadenceSub: TextView
    private lateinit var dailyTotalDistVal: TextView
    private lateinit var dailyTotalDistSub: TextView
    private lateinit var openGpsStatusVal: TextView
    private lateinit var openGpsStatusSub: TextView
    private lateinit var dailyGaitBar: GaitBreakdownBarView
    private lateinit var dailyGaitLegendText: TextView
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
    private lateinit var samsungPrimaryBtn: Button
    private lateinit var samsungSecondaryBtn: Button
    private lateinit var launchPtFromOpenBtn: Button
    private lateinit var dailyHistoryListLayout: LinearLayout

    // Tab 1B: 1.6 km PT Trial views (starts ONLY when Jogging or Running)
    private lateinit var paceArcGauge: PaceArcGaugeView
    private lateinit var primaryActionBtn: Button
    private lateinit var ptArmHintText: TextView
    private lateinit var secondaryActionBtn: Button
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

    // Tab 2: Progress & 7:15 ETA views
    private lateinit var etaHeadlineText: TextView
    private lateinit var etaSubText: TextView
    private lateinit var kpiPbVal: TextView
    private lateinit var kpiGapVal: TextView
    private lateinit var kpiRateVal: TextView
    private lateinit var kpiImprovedVal: TextView
    private lateinit var coachPrescriptionText: TextView
    private lateinit var trendChartView: ProgressTrendChartView
    private lateinit var demoToggleBtn: Button
    private lateinit var sessionsListLayout: LinearLayout

    // Tab 3: Benchmark & Sensor Diagnostics views
    private lateinit var allDayAutoSwitch: Switch
    private lateinit var stateAudioSwitch: Switch
    private lateinit var autoCalibrateSamsungSwitch: Switch
    private lateinit var autoImportSamsungRunsSwitch: Switch
    private lateinit var diagGpsText: TextView
    private lateinit var diagStepText: TextView
    private lateinit var diagStrideCalText: TextView
    private lateinit var diagStateText: TextView
    private lateinit var updateStatusText: TextView
    private lateinit var checkUpdateBtn: Button
    private lateinit var targetPresetButtons: List<Pair<Double, TextView>>
    private lateinit var thresholdPresetButtons: List<Pair<Double, TextView>>

    private val healthPermissionLauncher = registerForActivityResult(
        SamsungHealthSyncManager.createPermissionRequestContract()
    ) {
        triggerSamsungHealthSync(userTriggered = true)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionBanner()
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

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
        refreshCachedDailyHistory(forceRender = true)
        renderSamsungComparisonCard(force = true)
        refreshProgressDashboard()
        updatePermissionBanner()

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

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D16.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top App Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setBackgroundColor(0xFF0D1424.toInt())
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "TS POLICE 1.6 KM TRACKER"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
            letterSpacing = 0.04f
        })
        titleCol.addView(TextView(this).apply {
            text = "All-Day Auto Mode • 1.6 km PT Trial (< 7:15)"
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
        })
        stateAudioChip = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(5), dp(9), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                val next = !repository.stateChangeAudioEnabled
                repository.stateChangeAudioEnabled = next
                if (::stateAudioSwitch.isInitialized && stateAudioSwitch.isChecked != next) {
                    stateAudioSwitch.isChecked = next
                }
                refreshStateAudioChip()
                trackingService?.previewStateChangeAudio()
            }
        }
        val goalChip = TextView(this).apply {
            text = "GOAL < 7:15"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF10B981.toInt())
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundedBox(0x1E10B981, 0xFF10B981.toInt(), dp(12))
        }
        header.addView(titleCol)
        header.addView(stateAudioChip)
        header.addView(goalChip)
        root.addView(header)

        // OTA Update Banner (visible whenever a newer release is available on GitHub)
        updateBanner = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF04130D.toInt())
            setBackgroundColor(0xFF38BDF8.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        tabTrackerContainer = buildTrackerTab()
        tabProgressContainer = buildProgressTab()
        tabGuideContainer = buildGuideAndSensorsTab()

        contentFrame.addView(tabTrackerContainer)
        contentFrame.addView(tabProgressContainer)
        contentFrame.addView(tabGuideContainer)
        root.addView(contentFrame)

        // Bottom Navigation Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0F172A.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }
        val tabs = listOf("ALL-DAY & PT", "PROGRESS & ETA", "PET & SENSORS")
        tabs.forEachIndexed { index, label ->
            val tv = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                setOnClickListener { selectTab(index) }
            }
            navTabViews.add(tv)
            bottomBar.addView(tv)
        }
        root.addView(bottomBar)
        return root
    }

    private fun selectTab(index: Int) {
        tabTrackerContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabProgressContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabGuideContainer.visibility = if (index == 2) View.VISIBLE else View.GONE

        navTabViews.forEachIndexed { i, tv ->
            if (i == index) {
                tv.setTextColor(0xFF04130D.toInt())
                tv.background = roundedBox(0xFF10B981.toInt(), 0xFF10B981.toInt(), dp(10))
            } else {
                tv.setTextColor(0xFF94A3B8.toInt())
                tv.background = roundedBox(0x00000000, 0x00000000, dp(10))
            }
        }
        if (index == 0) {
            refreshCachedDailyHistory(forceRender = true)
            if (!currentlyRecording && (SystemClock.elapsedRealtime() - lastHealthSyncElapsedMs) >= 45_000L) {
                triggerSamsungHealthSync(userTriggered = false)
            }
        } else if (index == 1) {
            refreshProgressDashboard()
        }
    }

    private fun selectTrackerSubTab(subIndex: Int) {
        activeTrackerSubTab = subIndex.coerceIn(0, 1)
        val isOpen = activeTrackerSubTab == 0
        openAllDayContainer.visibility = if (isOpen) View.VISIBLE else View.GONE
        ptTrialContainer.visibility = if (!isOpen) View.VISIBLE else View.GONE

        subTabOpenBtn.background = roundedBox(
            if (isOpen) 0xFF10B981.toInt() else 0x00000000,
            if (isOpen) 0xFF10B981.toInt() else 0x00000000,
            dp(9)
        )
        subTabOpenBtn.setTextColor(if (isOpen) 0xFF04130D.toInt() else 0xFF94A3B8.toInt())

        subTabPtBtn.background = roundedBox(
            if (!isOpen) 0xFF38BDF8.toInt() else 0x00000000,
            if (!isOpen) 0xFF38BDF8.toInt() else 0x00000000,
            dp(9)
        )
        subTabPtBtn.setTextColor(if (!isOpen) 0xFF04130D.toInt() else 0xFF94A3B8.toInt())
    }

    // =========================================================================
    // TAB 1: ALWAYS-ON ALL-DAY OPEN MODE + 1.6 KM PT TRIAL
    // =========================================================================
    private fun buildTrackerTab(): ScrollView {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }

        // Permission prompt strip (hidden when permissions granted)
        permissionBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(0x28F59E0B, 0xFFF59E0B.toInt(), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        permissionBanner.addView(TextView(this).apply {
            text = "Enable GPS & Motion Sensors for 24/7 All-Day Walk/Jog/Run tracking"
            textSize = 12f
            setTextColor(0xFFFDE68A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        permissionBanner.addView(Button(this).apply {
            text = "ALLOW"
            textSize = 11.5f
            setOnClickListener { requestNeededPermissionsIfMissing(silentCheckOnly = false) }
        })
        col.addView(permissionBanner)

        // Sub-Tab Switcher: [All-Day Auto (Open)] vs [1.6 km PT Trial]
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBox(0xFF111827.toInt(), 0xFF1E293B.toInt(), dp(12))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        subTabOpenBtn = TextView(this).apply {
            text = "All-Day Auto (Open)"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTrackerSubTab(0) }
        }
        subTabPtBtn = TextView(this).apply {
            text = "1.6 km PT Trial"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTrackerSubTab(1) }
        }
        modeRow.addView(subTabOpenBtn)
        modeRow.addView(subTabPtBtn)
        col.addView(modeRow)

        openAllDayContainer = buildOpenAllDaySubView()
        ptTrialContainer = buildPtTrialSubView()

        col.addView(openAllDayContainer)
        col.addView(ptTrialContainer)

        scroll.addView(col)
        return scroll
    }

    /**
     * Sub-View 0: Always-On Open All-Day Auto Mode (Default — Zero Start Button Needed!).
     * Automatically tracks whole-day Walking time, combined Jogging + Running time (with Jog/Run breakdown),
     * Max Pace, Max Cadence, Live Pace, Live Cadence, Total Distance, and Total Steps.
     */
    private fun buildOpenAllDaySubView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Live Auto-Detection Status Card
        val liveCard = cardContainer().apply {
            background = roundedBox(0xFF0F1D2E.toInt(), 0xFF10B981.toInt(), dp(15))
        }
        val topStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topStatusRow.addView(TextView(this).apply {
            text = "OPEN MODE • ALWAYS-ON WHOLE-DAY AUTO"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF10B981.toInt())
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        allDayLiveChip = TextView(this).apply {
            text = "24/7 ACTIVE"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF04130D.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedBox(0xFF10B981.toInt(), 0xFF10B981.toInt(), dp(10))
        }
        topStatusRow.addView(allDayLiveChip)
        liveCard.addView(topStatusRow)

        allDayStateBadge = TextView(this).apply {
            text = "AUTO-PAUSED • STANDING"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }
        liveCard.addView(allDayStateBadge)

        allDayStatusSub = TextView(this).apply {
            text = "No Start button needed — automatically counts your whole-day Walk & Jog+Run time (ignores sitting & bike)."
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
        }
        liveCard.addView(allDayStatusSub)
        box.addView(liveCard)

        // 2. Two Hero Cards Side-by-Side: [JOG + RUN TODAY (Combined)] & [WALKING TODAY]
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // Left Hero: Combined Jog + Run Today
        val jogRunCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBox(0xFF111827.toInt(), 0xFF10B981.toInt(), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
        }
        jogRunCard.addView(TextView(this).apply {
            text = "JOG + RUN TODAY"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF10B981.toInt())
        })
        dailyJogRunTimeVal = TextView(this).apply {
            text = "0:00"
            textSize = 25f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, dp(4), 0, dp(2))
        }
        dailyJogRunDistSub = TextView(this).apply {
            text = "0 m combined"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF34D399.toInt())
        }
        dailyJogRunSplitSub = TextView(this).apply {
            text = "Jog 0:00 • Run 0:00"
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, dp(3), 0, 0)
        }
        jogRunCard.addView(dailyJogRunTimeVal)
        jogRunCard.addView(dailyJogRunDistSub)
        jogRunCard.addView(dailyJogRunSplitSub)

        // Right Hero: Walking Today
        val walkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBox(0xFF111827.toInt(), 0xFF38BDF8.toInt(), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(5)
            }
        }
        walkCard.addView(TextView(this).apply {
            text = "WALKING TODAY"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF38BDF8.toInt())
        })
        dailyWalkTimeVal = TextView(this).apply {
            text = "0:00"
            textSize = 25f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, dp(4), 0, dp(2))
        }
        dailyWalkDistSub = TextView(this).apply {
            text = "0 m walked"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF7DD3FC.toInt())
        }
        dailyWalkStepsSub = TextView(this).apply {
            text = "0 steps today"
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, dp(3), 0, 0)
        }
        walkCard.addView(dailyWalkTimeVal)
        walkCard.addView(dailyWalkDistSub)
        walkCard.addView(dailyWalkStepsSub)

        heroRow.addView(jogRunCard)
        heroRow.addView(walkCard)
        box.addView(heroRow)

        // 3. 3x2 Peak & Live Metrics Grid (Max Pace, Max Cadence, Live Pace, Live Cadence, Total Distance, GPS/Step)
        val peakRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val maxPaceTile = metricTile("MAX PACE TODAY", "--:-- /km", "Peak 0.0 km/h")
        dailyMaxPaceVal = maxPaceTile.second
        dailyMaxPaceSub = maxPaceTile.third
        val maxCadTile = metricTile("MAX CADENCE", "0 spm", "Peak stride rate")
        dailyMaxCadenceVal = maxCadTile.second
        dailyMaxCadenceSub = maxCadTile.third
        peakRow.addView(maxPaceTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        peakRow.addView(maxCadTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        box.addView(peakRow)

        val liveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val livePaceTile = metricTile("LIVE PACE NOW", "--:-- /km", "0.0 km/h")
        openLivePaceVal = livePaceTile.second
        openLivePaceSub = livePaceTile.third
        val liveCadTile = metricTile("LIVE CADENCE", "0 spm", "0.0 m/s² motion")
        openLiveCadenceVal = liveCadTile.second
        openLiveCadenceSub = liveCadTile.third
        liveRow.addView(livePaceTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        liveRow.addView(liveCadTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        box.addView(liveRow)

        val totalsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val totDistTile = metricTile("TOTAL DISTANCE", "0 m", "0 total steps")
        dailyTotalDistVal = totDistTile.second
        dailyTotalDistSub = totDistTile.third
        val gpsTile = metricTile("GPS & STEP ENGINE", "Step-Stride", "Indoor / Pocket ready")
        openGpsStatusVal = gpsTile.second
        openGpsStatusSub = gpsTile.third
        totalsRow.addView(totDistTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        totalsRow.addView(gpsTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        box.addView(totalsRow)

        // 4. Whole-Day Gait Share Bar
        val dayGaitCard = cardContainer()
        dayGaitCard.addView(sectionHeader("TODAY'S WHOLE-DAY MOVEMENT BREAKDOWN"))
        dailyGaitBar = GaitBreakdownBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(14)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
        dayGaitCard.addView(dailyGaitBar)
        dailyGaitLegendText = TextView(this).apply {
            text = "Jog+Run 0:00 (Run 0:00 • Jog 0:00)  •  Walk 0:00"
            textSize = 12f
            setTextColor(0xFFCBD5E1.toInt())
        }
        dayGaitCard.addView(dailyGaitLegendText)
        box.addView(dayGaitCard)

        // 5. Samsung Health Sync, Side-by-Side Comparison & Personal Stride Auto-Calibration Card
        box.addView(buildSamsungComparisonCard())

        // 6. 1-Tap CTA to Arm & Launch 1.6 km PT Trial
        launchPtFromOpenBtn = Button(this).apply {
            text = "START 1.6 KM PT TRIAL (Starts Only When You Jog/Run) →"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(0xFF04130D.toInt())
            background = roundedBox(0xFF38BDF8.toInt(), 0xFF38BDF8.toInt(), dp(13))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setOnClickListener {
                selectTrackerSubTab(1)
                if (trackingService?.let { lastRenderedPtPhase == PtTrialPhase.IDLE } != false) {
                    requestNeededPermissionsIfMissing(silentCheckOnly = false)
                    trackingService?.armPtTrial()
                }
            }
        }
        box.addView(launchPtFromOpenBtn)

        // 7. Recent Whole-Day Activity Log Card
        val dailyLogCard = cardContainer()
        dailyLogCard.addView(sectionHeader("DAILY WHOLE-DAY ACTIVITY LOG"))
        dailyHistoryListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        dailyLogCard.addView(dailyHistoryListLayout)
        box.addView(dailyLogCard)

        return box
    }

    private fun buildSamsungComparisonCard(): LinearLayout {
        val card = cardContainer().apply {
            background = roundedBox(0xFF0E172A.toInt(), 0xFF38BDF8.toInt(), dp(14))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "SAMSUNG HEALTH SYNC & ACCURACY"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF38BDF8.toInt())
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        samsungSyncBadge = TextView(this).apply {
            text = "CONNECT"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF04130D.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedBox(0xFF38BDF8.toInt(), 0xFF38BDF8.toInt(), dp(10))
        }
        headerRow.addView(samsungSyncBadge)
        card.addView(headerRow)

        samsungHeadlineText = TextView(this).apply {
            text = "Sync Samsung Health for Side-by-Side Calibration"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, dp(6), 0, dp(3))
        }
        samsungDetailText = TextView(this).apply {
            text = "Compares Steps, Distance, Walk & Jog+Run time via Android Health Connect and calibrates your personal stride length."
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(samsungHeadlineText)
        card.addView(samsungDetailText)

        // Side-by-side comparison table (Metric | TS Tracker | Samsung | Delta / Match)
        samsungCompareTable = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBox(0xFF090F1D.toInt(), 0xFF1E293B.toInt(), dp(10))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val tblHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(5))
        }
        fun hdrCell(txt: String, weight: Float, alignEnd: Boolean = false) = TextView(this).apply {
            text = txt
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = if (alignEnd) Gravity.END else Gravity.START
            setTextColor(0xFF64748B.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        tblHeader.addView(hdrCell("METRIC", 1.05f))
        tblHeader.addView(hdrCell("TS APP", 0.95f, alignEnd = true))
        tblHeader.addView(hdrCell("SAMSUNG", 0.95f, alignEnd = true))
        tblHeader.addView(hdrCell("ACCURACY", 1.15f, alignEnd = true))
        samsungCompareTable.addView(tblHeader)

        fun buildCompareRow(label: String): Triple<TextView, TextView, TextView> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val lblTv = TextView(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(0xFFCBD5E1.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f)
            }
            val trkTv = TextView(this).apply {
                text = "--"
                textSize = 12f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                setTextColor(0xFFF8FAFC.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f)
            }
            val samTv = TextView(this).apply {
                text = "--"
                textSize = 12f
                gravity = Gravity.END
                typeface = Typeface.MONOSPACE
                setTextColor(0xFF7DD3FC.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f)
            }
            val dltTv = TextView(this).apply {
                text = "--"
                textSize = 11f
                gravity = Gravity.END
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF10B981.toInt())
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
        samRowStepsTracker = rSteps.first
        samRowStepsSamsung = rSteps.second
        samRowStepsDelta = rSteps.third

        val rDist = buildCompareRow("Distance")
        samRowDistTracker = rDist.first
        samRowDistSamsung = rDist.second
        samRowDistDelta = rDist.third

        val rWalk = buildCompareRow("Walk Time")
        samRowWalkTracker = rWalk.first
        samRowWalkSamsung = rWalk.second
        samRowWalkDelta = rWalk.third

        val rRun = buildCompareRow("Jog + Run")
        samRowRunTracker = rRun.first
        samRowRunSamsung = rRun.second
        samRowRunDelta = rRun.third

        val rStride = buildCompareRow("Stride")
        samRowStrideTracker = rStride.first
        samRowStrideSamsung = rStride.second
        samRowStrideDelta = rStride.third

        card.addView(samsungCompareTable)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        samsungPrimaryBtn = Button(this).apply {
            text = "Connect Samsung Health"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(0xFF04130D.toInt())
            background = roundedBox(0xFF10B981.toInt(), 0xFF10B981.toInt(), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply {
                marginEnd = dp(5)
            }
        }
        samsungSecondaryBtn = Button(this).apply {
            text = "Samsung Settings"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFCBD5E1.toInt())
            background = roundedBox(0xFF111827.toInt(), 0xFF334155.toInt(), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f).apply {
                marginStart = dp(5)
            }
        }
        btnRow.addView(samsungPrimaryBtn)
        btnRow.addView(samsungSecondaryBtn)
        card.addView(btnRow)

        return card
    }

    /**
     * Sub-View 1: Dedicated 1.6 km TS Police PT Trial.
     * Pressing Start arms the trial at 0:00.0 / 0m; standing & walking are ignored;
     * the timer & distance start ONLY when Jogging or Running is detected!
     */
    private fun buildPtTrialSubView(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Gauge Card
        val gaugeCard = cardContainer().apply {
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        paceArcGauge = PaceArcGaugeView(this)
        gaugeCard.addView(paceArcGauge)
        col.addView(gaugeCard)

        // PT Trial Action Controls (placed right under Gauge for instant thumb reach!)
        primaryActionBtn = Button(this).apply {
            text = "START 1.6 KM PT TRIAL (Starts When You Jog/Run)"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(0xFF04130D.toInt())
            background = roundedBox(0xFF10B981.toInt(), 0xFF10B981.toInt(), dp(14))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        ptArmHintText = TextView(this).apply {
            text = "Tap Start, put phone in pocket & walk to the line — timer stays at 0:00 until you start jogging or running."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF94A3B8.toInt())
            setPadding(dp(6), 0, dp(6), dp(10))
        }
        secondaryActionBtn = Button(this).apply {
            text = "← Back to All-Day Open Mode"
            textSize = 12.5f
            isAllCaps = false
            setTextColor(0xFF38BDF8.toInt())
            background = roundedBox(0xFF111827.toInt(), 0xFF334155.toInt(), dp(12))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        col.addView(primaryActionBtn)
        col.addView(ptArmHintText)
        col.addView(secondaryActionBtn)

        // 2x2 Live PT Trial Sensor Metrics Grid
        val gridRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val paceTile = metricTile("LIVE PACE", "--:-- /km", "0.0 km/h")
        metricPaceVal = paceTile.second
        metricPaceSub = paceTile.third
        val cadTile = metricTile("STEP CADENCE", "0 spm", "0 steps today")
        metricCadenceVal = cadTile.second
        metricCadenceSub = cadTile.third
        gridRow1.addView(paceTile.first.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5)
        })
        gridRow1.addView(cadTile.first.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        })
        col.addView(gridRow1)

        val gridRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val projTile = metricTile("PROJ. 1.6K TIME", "--:--", "Target 7:15")
        metricProjVal = projTile.second
        metricProjSub = projTile.third
        val sensTile = metricTile("MOTION / GPS", "0.0 m/s²", "Searching GPS...")
        metricSensorVal = sensTile.second
        metricSensorSub = sensTile.third
        gridRow2.addView(projTile.first.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5)
        })
        gridRow2.addView(sensTile.first.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        })
        col.addView(gridRow2)

        // PT Trial Gait Share Card
        val gaitCard = cardContainer()
        gaitCard.addView(sectionHeader("PT TRIAL GAIT BREAKDOWN"))
        gaitBar = GaitBreakdownBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(14)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
        gaitCard.addView(gaitBar)
        gaitLegendText = TextView(this).apply {
            text = "Run 0:00  •  Jog 0:00  •  Walk 0:00  •  Auto-Pause 0:00"
            textSize = 12f
            setTextColor(0xFFCBD5E1.toInt())
        }
        gaitCard.addView(gaitLegendText)
        col.addView(gaitCard)

        // Live 400m Track Splits Card
        val splitsCard = cardContainer()
        splitsCard.addView(sectionHeader("400M TRACK LAP SPLITS (TARGET 1:48.8 / LAP)"))
        splitsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        splitsCard.addView(splitsListLayout)
        col.addView(splitsCard)

        return col
    }

    // =========================================================================
    // TAB 2: DAILY PROGRESS & DAYS-TO-7:15 FORECAST
    // =========================================================================
    private fun buildProgressTab(): ScrollView {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }

        // Hero Forecast Card
        val heroCard = cardContainer().apply {
            background = roundedBox(0xFF0F1D2E.toInt(), 0xFF10B981.toInt(), dp(16))
        }
        heroCard.addView(TextView(this).apply {
            text = "PRACTICE IMPROVEMENT & TARGET REACH ETA"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF10B981.toInt())
            letterSpacing = 0.05f
        })
        etaHeadlineText = TextView(this).apply {
            text = "-- Days to 7:15"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, dp(6), 0, dp(4))
        }
        etaSubText = TextView(this).apply {
            text = "Loading progression..."
            textSize = 13f
            setTextColor(0xFFCBD5E1.toInt())
        }
        heroCard.addView(etaHeadlineText)
        heroCard.addView(etaSubText)

        // Coach prescription box inside hero
        coachPrescriptionText = TextView(this).apply {
            text = ""
            textSize = 12.5f
            setTextColor(0xFF93C5FD.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBox(0x1E38BDF8, 0x4438BDF8, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        heroCard.addView(coachPrescriptionText)
        col.addView(heroCard)

        // 4 KPI Summary Grid
        val kpiRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val pbTile = metricTile("1.6K PERSONAL BEST", "--:--", "Best 1600m time")
        kpiPbVal = pbTile.second
        val gapTile = metricTile("GAP TO 7:15", "--", "Seconds to shave")
        kpiGapVal = gapTile.second
        kpiRow1.addView(pbTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        kpiRow1.addView(gapTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        col.addView(kpiRow1)

        val kpiRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val rateTile = metricTile("IMPROVEMENT RATE", "--", "Weekly pace gain")
        kpiRateVal = rateTile.second
        val impTile = metricTile("TOTAL SHAVED", "--", "Since Day 1")
        kpiImprovedVal = impTile.second
        kpiRow2.addView(rateTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(5) })
        kpiRow2.addView(impTile.first.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5) })
        col.addView(kpiRow2)

        // Trend & Projection Chart Card
        val chartCard = cardContainer()
        chartCard.addView(sectionHeader("DAILY 1.6 KM CURVE & 7:15 PROJECTION"))
        trendChartView = ProgressTrendChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220)
            ).apply { topMargin = dp(6) }
        }
        chartCard.addView(trendChartView)
        chartCard.addView(TextView(this).apply {
            text = "Blue dots = 1.6km PT Trials  •  Amber = Auto Open Runs  •  Dashed line = Forecast to 7:15"
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        col.addView(chartCard)

        // Action Buttons (+ Manual Track Time & Sample Data Toggle)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val manualBtn = Button(this).apply {
            text = "+ Log Track Time"
            textSize = 12.5f
            isAllCaps = false
            setTextColor(0xFF04130D.toInt())
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBox(0xFF38BDF8.toInt(), 0xFF38BDF8.toInt(), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { showManualRunDialog() }
        }
        demoToggleBtn = Button(this).apply {
            text = "Load Sample Data"
            textSize = 12.5f
            isAllCaps = false
            setTextColor(0xFFCBD5E1.toInt())
            background = roundedBox(0xFF111827.toInt(), 0xFF334155.toInt(), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
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
        actionsRow.addView(manualBtn)
        actionsRow.addView(demoToggleBtn)
        col.addView(actionsRow)

        // Daily Sessions History Card
        val historyCard = cardContainer()
        historyCard.addView(sectionHeader("RECORDED 1.6 KM PT TRIALS & AUTO RUNS"))
        sessionsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        historyCard.addView(sessionsListLayout)
        col.addView(historyCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 3: TS POLICE PET SPLITS & SENSOR CALIBRATION
    // =========================================================================
    private fun buildGuideAndSensorsTab(): ScrollView {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }

        // Benchmark Split Table Card
        val tableCard = cardContainer()
        tableCard.addView(sectionHeader("TS POLICE 1600M (1.6 KM) PACING SPLITS"))
        tableCard.addView(TextView(this).apply {
            text = "Even-split checkpoints on a standard 400m ground to beat 7 min 15 sec (435s):"
            textSize = 12.5f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, dp(4), 0, dp(10))
        })
        val splitRows = listOf(
            Triple("200 m (Half Lap)", "0:54.4", "13.24 km/h"),
            Triple("400 m (Lap 1)", "1:48.8", "4:31.9 /km"),
            Triple("800 m (Lap 2 • Halfway)", "3:37.5", "Stay relaxed"),
            Triple("1,200 m (Lap 3 • Push)", "5:26.3", "Hold cadence >168"),
            Triple("1,600 m (Finish Cutoff)", "7:15.0", "QUALIFYING MARK")
        )
        for ((dist, split, note) in splitRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBox(0xFF0B1120.toInt(), 0xFF1E293B.toInt(), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            row.addView(TextView(this).apply {
                text = dist
                textSize = 12.5f
                setTextColor(0xFFE2E8F0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
            })
            row.addView(TextView(this).apply {
                text = split
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(0xFF10B981.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f)
            })
            row.addView(TextView(this).apply {
                text = note
                textSize = 11.5f
                gravity = Gravity.END
                setTextColor(0xFF94A3B8.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            tableCard.addView(row)
        }
        col.addView(tableCard)

        // Target Time & Tracking Preferences Card
        val settingsCard = cardContainer()
        settingsCard.addView(sectionHeader("TARGET CUTOFF, ALL-DAY AUTO & AUDIO"))
        settingsCard.addView(TextView(this).apply {
            text = "1.6 km Target Cutoff Time:"
            textSize = 12.5f
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(0, dp(8), 0, dp(6))
        })

        val targetPresetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val presets = listOf(
            435.0 to "7:15 (PET)",
            420.0 to "7:00",
            390.0 to "6:30",
            360.0 to "6:00"
        )
        val tBtns = mutableListOf<Pair<Double, TextView>>()
        for ((sec, label) in presets) {
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(9), dp(6), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
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
        settingsCard.addView(targetPresetRow)

        settingsCard.addView(TextView(this).apply {
            text = "Jogging → Running Auto-Classification Threshold:"
            textSize = 12.5f
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(0, dp(4), 0, dp(6))
        })
        val threshRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
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
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(9), dp(6), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
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
        settingsCard.addView(threshRow)

        // Switches for All-Day Auto Mode, State-Change Audio, Voice Coach & Indoor Step Fallback
        allDayAutoSwitch = Switch(this).apply {
            text = "All-Day 24/7 Open Auto Mode (Counts whole-day Walk & Jog+Run)"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13f
            isChecked = repository.allDayTrackingEnabled
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                repository.allDayTrackingEnabled = checked
                trackingService?.syncPocketForegroundAndWakeLock()
            }
        }
        stateAudioSwitch = Switch(this).apply {
            text = "Pocket & Screen-Off State Audio (Speaks Stand/Walk/Jog/Run in pocket)"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13f
            isChecked = repository.stateChangeAudioEnabled
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                repository.stateChangeAudioEnabled = checked
                trackingService?.syncPocketForegroundAndWakeLock()
                refreshStateAudioChip()
            }
        }
        val voiceSwitch = Switch(this).apply {
            text = "Voice Split Coach (400m & 1600m TTS audio)"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13f
            isChecked = repository.voiceCoachEnabled
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> repository.voiceCoachEnabled = checked }
        }
        val stepFallbackSwitch = Switch(this).apply {
            text = "Step-Stride Distance Fallback when GPS is indoors/weak"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13f
            isChecked = repository.indoorStepFallbackEnabled
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> repository.indoorStepFallbackEnabled = checked }
        }
        autoCalibrateSamsungSwitch = Switch(this).apply {
            text = "Auto-Calibrate Personal Stride Length from Samsung Health"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13f
            isChecked = repository.autoCalibrateFromSamsung
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                repository.autoCalibrateFromSamsung = checked
                trackingService?.syncThresholdsFromPrefs()
                renderSamsungComparisonCard(force = true)
            }
        }
        autoImportSamsungRunsSwitch = Switch(this).apply {
            text = "Auto-Import Samsung Health / Watch Runs (>= 600m) to 7:15 ETA"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13f
            isChecked = repository.autoImportSamsungRuns
            setPadding(0, dp(6), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> repository.autoImportSamsungRuns = checked }
        }
        val testStateAudioBtn = Button(this).apply {
            text = "Test State Change Audio Cue"
            textSize = 12.5f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFE2E8F0.toInt())
            background = roundedBox(0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                trackingService?.previewStateChangeAudio(MotionState.TARGET_PACE)
            }
        }
        val resetTodayDailyBtn = Button(this).apply {
            text = "Reset Today's All-Day Counters"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFF94A3B8.toInt())
            background = roundedBox(0xFF111827.toInt(), 0xFF334155.toInt(), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset today's whole-day counters?")
                    .setMessage("This resets today's Walk & Jog+Run counters to 0:00 (saved 1.6 km sessions are untouched).")
                    .setPositiveButton("Reset Today") { _, _ ->
                        trackingService?.resetTodayDailySummary()
                        refreshCachedDailyHistory(forceRender = true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        val resetStrideCalBtn = Button(this).apply {
            text = "Reset Personal Stride Calibration (1.00x)"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFF94A3B8.toInt())
            background = roundedBox(0xFF111827.toInt(), 0xFF334155.toInt(), dp(10))
            setOnClickListener {
                repository.strideCalibrationScale = 1.0
                trackingService?.syncThresholdsFromPrefs()
                renderSamsungComparisonCard(force = true)
                Toast.makeText(this@MainActivity, "Stride calibration reset to 1.00x", Toast.LENGTH_SHORT).show()
            }
        }

        settingsCard.addView(allDayAutoSwitch)
        settingsCard.addView(stateAudioSwitch)
        settingsCard.addView(voiceSwitch)
        settingsCard.addView(stepFallbackSwitch)
        settingsCard.addView(autoCalibrateSamsungSwitch)
        settingsCard.addView(autoImportSamsungRunsSwitch)
        settingsCard.addView(testStateAudioBtn)
        settingsCard.addView(resetTodayDailyBtn)
        settingsCard.addView(resetStrideCalBtn)
        col.addView(settingsCard)

        // Live Sensor Diagnostics Card
        val diagCard = cardContainer()
        diagCard.addView(sectionHeader("LIVE HARDWARE SENSOR FUSION DIAGNOSTICS"))
        diagGpsText = TextView(this).apply {
            text = "GPS Receiver: Checking..."
            textSize = 12.5f
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }
        diagStepText = TextView(this).apply {
            text = "Step / Cadence Engine: Checking..."
            textSize = 12.5f
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(0, dp(2), 0, dp(4))
        }
        diagStrideCalText = TextView(this).apply {
            text = String.format("Samsung Health Stride Scale: %.2fx", repository.strideCalibrationScale)
            textSize = 12.5f
            setTextColor(0xFF38BDF8.toInt())
            setPadding(0, dp(2), 0, dp(4))
        }
        diagStateText = TextView(this).apply {
            text = "Classifier Verdict: STANDING"
            textSize = 12.5f
            setTextColor(0xFF10B981.toInt())
            setPadding(0, dp(2), 0, dp(4))
        }
        diagCard.addView(diagGpsText)
        diagCard.addView(diagStepText)
        diagCard.addView(diagStrideCalText)
        diagCard.addView(diagStateText)
        col.addView(diagCard)

        // OTA Auto-Update Card
        val updateCard = cardContainer()
        updateCard.addView(sectionHeader("APP VERSION & GITHUB OTA AUTO-UPDATE"))
        val (instCode, instName) = appUpdater.getInstalledVersion()
        updateStatusText = TextView(this).apply {
            text = "Installed: v$instName (Build $instCode) • Source: GitHub Releases"
            textSize = 12.5f
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(0, dp(8), 0, dp(6))
        }
        updateCard.addView(updateStatusText)

        val autoUpdateSwitch = Switch(this).apply {
            text = "Background & Launch Auto-Update (Checks every 15 min + Notification)"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13f
            isChecked = appUpdater.autoCheckOnLaunch
            setPadding(0, dp(4), 0, dp(10))
            setOnCheckedChangeListener { _, checked -> appUpdater.autoCheckOnLaunch = checked }
        }
        updateCard.addView(autoUpdateSwitch)

        checkUpdateBtn = Button(this).apply {
            text = "Check for App Updates Now"
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF04130D.toInt())
            background = roundedBox(0xFF38BDF8.toInt(), 0xFF38BDF8.toInt(), dp(10))
            setOnClickListener { performUpdateCheck(manualUserTrigger = true) }
        }
        updateCard.addView(checkUpdateBtn)
        col.addView(updateCard)

        refreshSettingsHighlights()
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // LIVE RENDERING & STATE UPDATES
    // =========================================================================
    private fun formatDistanceShort(meters: Double): String {
        return if (meters >= 1000.0) {
            String.format("%.2f km", meters / 1000.0)
        } else {
            "${meters.toInt()} m"
        }
    }

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

        val rows = mutableListOf(todaySummary)
        rows.addAll(cachedPastDailyLogs)

        dailyHistoryListLayout.removeAllViews()
        for ((idx, day) in rows.withIndex()) {
            val isToday = idx == 0
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(9), dp(11), dp(9))
                background = roundedBox(
                    if (isToday) 0xFF0F1D2E.toInt() else 0xFF0B1120.toInt(),
                    if (isToday) 0xFF1E3A5F.toInt() else 0xFF1E293B.toInt(),
                    dp(10)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7) }
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dateTag = when {
                isToday -> "TODAY (${day.dateIso})"
                day.isDemo -> "${day.dateIso} • DEMO"
                else -> day.dateIso
            }
            top.addView(TextView(this).apply {
                text = dateTag
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isToday) 0xFF38BDF8.toInt() else 0xFFE2E8F0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this).apply {
                text = "${formatDistanceShort(day.totalDistanceMeters)} • ${day.totalSteps} steps"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(0xFFCBD5E1.toInt())
            })
            card.addView(top)

            card.addView(TextView(this).apply {
                text = "Jog+Run ${TsPoliceBenchmark.formatDuration(day.jogRunCombinedSec)}  •  " +
                    "Walk ${TsPoliceBenchmark.formatDuration(day.walkingSec)}  •  " +
                    "Max ${TsPoliceBenchmark.formatPacePerKm(day.maxSpeedMps)} (${day.maxCadenceSpm.roundToInt()} spm)"
                textSize = 11.5f
                setTextColor(0xFF94A3B8.toInt())
                setPadding(0, dp(3), 0, 0)
            })

            dailyHistoryListLayout.addView(card)
        }
    }

    private fun renderLiveTelemetry(snap: LiveTelemetry) {
        currentlyRecording = snap.isRecording
        refreshStateAudioChip()
        val targetSec = repository.targetTimeSec
        val daily = snap.dailySummary

        // Auto-switch to the 1.6 km PT Trial view whenever a PT Trial transitions into ARMED or RUNNING
        if (snap.ptTrialPhase != lastRenderedPtPhase) {
            if (snap.ptTrialPhase != PtTrialPhase.IDLE) {
                selectTrackerSubTab(1)
            }
            lastRenderedPtPhase = snap.ptTrialPhase
        }

        // Update sub-tab badge label if PT Trial is active
        subTabPtBtn.text = when (snap.ptTrialPhase) {
            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> "1.6 km PT (READY)"
            PtTrialPhase.RUNNING_TRIAL -> "1.6 km PT (LIVE)"
            PtTrialPhase.IDLE -> "1.6 km PT Trial"
        }

        // =====================================================================
        // A. RENDER ALWAYS-ON ALL-DAY OPEN MODE (SUB-VIEW 0)
        // =====================================================================
        val allDayOn = repository.allDayTrackingEnabled
        allDayLiveChip.text = if (allDayOn) "24/7 ACTIVE" else "PAUSED"
        allDayLiveChip.background = roundedBox(
            if (allDayOn) 0xFF10B981.toInt() else 0xFF475569.toInt(),
            if (allDayOn) 0xFF10B981.toInt() else 0xFF475569.toInt(),
            dp(10)
        )
        allDayStateBadge.text = snap.motionState.badgeText
        allDayStateBadge.setTextColor(
            if (snap.motionState == MotionState.STANDING) 0xFFCBD5E1.toInt() else snap.motionState.colorArgb
        )
        allDayStatusSub.text = when (snap.ptTrialPhase) {
            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN ->
                "1.6 km PT Trial is ARMED & waiting for Jog/Run • Whole-day counters remain active."
            PtTrialPhase.RUNNING_TRIAL ->
                "1.6 km PT Trial in progress (${snap.distanceMeters.toInt()}m / 1,600m) • Tap '1.6 km PT' above."
            PtTrialPhase.IDLE ->
                "No Start button needed — automatically counts whole-day Walk & Jog+Run time (ignores sitting & bike)."
        }

        // Hero Cards: Combined Jog + Run Today & Walking Today
        dailyJogRunTimeVal.text = TsPoliceBenchmark.formatDuration(daily.jogRunCombinedSec)
        dailyJogRunDistSub.text = "${formatDistanceShort(daily.jogRunCombinedMeters)} jog + run"
        dailyJogRunSplitSub.text =
            "Jog ${TsPoliceBenchmark.formatDuration(daily.joggingSec)} • Run ${TsPoliceBenchmark.formatDuration(daily.runningSec)}"

        dailyWalkTimeVal.text = TsPoliceBenchmark.formatDuration(daily.walkingSec)
        dailyWalkDistSub.text = "${formatDistanceShort(daily.walkingMeters)} walked"
        dailyWalkStepsSub.text = "${daily.totalSteps} steps today"

        // 3x2 Peak & Live Tiles
        dailyMaxPaceVal.text = TsPoliceBenchmark.formatPacePerKm(daily.maxSpeedMps)
        dailyMaxPaceSub.text = String.format("Peak %.1f km/h today", daily.maxSpeedMps * 3.6)

        dailyMaxCadenceVal.text = "${daily.maxCadenceSpm.roundToInt()} spm"
        dailyMaxCadenceSub.text = if (daily.maxCadenceSpm >= 165.0) "Target stride reached!" else "Peak stride rate today"

        openLivePaceVal.text = TsPoliceBenchmark.formatPacePerKm(snap.currentSpeedMps)
        openLivePaceSub.text = String.format("%.1f km/h live speed", snap.currentSpeedMps * 3.6)

        openLiveCadenceVal.text = "${snap.cadenceSpm.roundToInt()} spm"
        openLiveCadenceSub.text = String.format("%.1f m/s² motion", snap.dynamicAccelRms)

        dailyTotalDistVal.text = formatDistanceShort(daily.totalDistanceMeters)
        dailyTotalDistSub.text = "${daily.totalSteps} total steps"

        openGpsStatusVal.text = if (snap.isGpsLocked) "GPS Locked" else "Step-Stride"
        openGpsStatusVal.setTextColor(if (snap.isGpsLocked) 0xFF10B981.toInt() else 0xFF38BDF8.toInt())
        openGpsStatusSub.text = when {
            snap.isGpsLocked && snap.gpsAccuracyMeters != null ->
                String.format("±%.0fm sky lock", snap.gpsAccuracyMeters)
            snap.gpsAccuracyMeters != null ->
                String.format("±%.0fm • Step active", snap.gpsAccuracyMeters)
            else -> "Pocket / Indoor active"
        }

        // Whole-Day Gait Bar
        dailyGaitBar.setDurations(daily.runningSec, daily.joggingSec, daily.walkingSec, 0.0)
        dailyGaitLegendText.text =
            "Combined Jog+Run ${TsPoliceBenchmark.formatDuration(daily.jogRunCombinedSec)} " +
                "(Run ${TsPoliceBenchmark.formatDuration(daily.runningSec)} • Jog ${TsPoliceBenchmark.formatDuration(daily.joggingSec)})  •  " +
                "Walk ${TsPoliceBenchmark.formatDuration(daily.walkingSec)}"

        launchPtFromOpenBtn.text = when (snap.ptTrialPhase) {
            PtTrialPhase.IDLE -> "START 1.6 KM PT TRIAL (Starts Only When You Jog/Run) →"
            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> "1.6 KM PT TRIAL ARMED • VIEW / CANCEL →"
            PtTrialPhase.RUNNING_TRIAL -> "VIEW LIVE 1.6 KM PT TRIAL (${snap.distanceMeters.toInt()}m) →"
        }

        latestDailySummaryForCompare = daily
        renderSamsungComparisonCard(force = false)
        renderDailyHistoryCards(daily)

        // =====================================================================
        // B. RENDER 1.6 KM PT TRIAL (SUB-VIEW 1)
        // =====================================================================
        paceArcGauge.updateState(
            distanceMeters = snap.distanceMeters,
            activeDurationSec = snap.activeDurationSec,
            targetSec = targetSec,
            motionState = snap.motionState,
            recording = snap.ptTrialPhase == PtTrialPhase.RUNNING_TRIAL,
            armed = snap.ptTrialPhase == PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN
        )

        metricPaceVal.text = TsPoliceBenchmark.formatPacePerKm(snap.currentSpeedMps)
        metricPaceSub.text = String.format("%.1f km/h ground speed", snap.currentSpeedMps * 3.6)

        metricCadenceVal.text = String.format("%d spm", snap.cadenceSpm.roundToInt())
        metricCadenceSub.text = "${snap.totalSteps} steps today"

        val projSec = if (snap.ptTrialPhase == PtTrialPhase.RUNNING_TRIAL) {
            TsPoliceBenchmark.projected1600mFinishSec(
                snap.distanceMeters,
                snap.activeDurationSec,
                snap.currentSpeedMps
            )
        } else null
        metricProjVal.text = if (projSec != null) TsPoliceBenchmark.formatDuration(projSec) else "--:--"
        metricProjVal.setTextColor(
            when {
                projSec == null -> 0xFFF8FAFC.toInt()
                projSec <= targetSec -> 0xFF10B981.toInt()
                else -> 0xFFF59E0B.toInt()
            }
        )
        metricProjSub.text = "Goal < ${TsPoliceBenchmark.formatDuration(targetSec)}"

        metricSensorVal.text = String.format("%.1f m/s² RMS", snap.dynamicAccelRms)
        metricSensorSub.text = when {
            snap.isGpsLocked && snap.gpsAccuracyMeters != null ->
                String.format("GPS Locked (±%.0fm)", snap.gpsAccuracyMeters)
            snap.gpsAccuracyMeters != null ->
                String.format("GPS Weak (±%.0fm) • Step Est.", snap.gpsAccuracyMeters)
            else -> "Indoor / Step-Stride Mode"
        }

        // PT Trial Gait breakdown bar
        gaitBar.setDurations(snap.runningSec, snap.joggingSec, snap.walkingSec, snap.pausedDurationSec)
        gaitLegendText.text =
            "Run ${TsPoliceBenchmark.formatDuration(snap.runningSec)}  •  " +
                "Jog ${TsPoliceBenchmark.formatDuration(snap.joggingSec)}  •  " +
                "Walk ${TsPoliceBenchmark.formatDuration(snap.walkingSec)}  •  " +
                "Pause ${TsPoliceBenchmark.formatDuration(snap.pausedDurationSec)}"

        // PT Trial Action Buttons across IDLE -> ARMED_WAITING_FOR_JOG_RUN -> RUNNING_TRIAL
        when (snap.ptTrialPhase) {
            PtTrialPhase.IDLE -> {
                primaryActionBtn.text = "START 1.6 KM PT TRIAL (Starts When You Jog/Run)"
                primaryActionBtn.setTextColor(0xFF04130D.toInt())
                primaryActionBtn.background = roundedBox(0xFF10B981.toInt(), 0xFF10B981.toInt(), dp(14))
                primaryActionBtn.setOnClickListener {
                    requestNeededPermissionsIfMissing(silentCheckOnly = false)
                    trackingService?.armPtTrial()
                }
                ptArmHintText.text =
                    "Tap Start, put phone in pocket & walk to the line — timer stays at 0:00 until you start jogging or running."
                ptArmHintText.setTextColor(0xFF94A3B8.toInt())

                secondaryActionBtn.visibility = View.VISIBLE
                secondaryActionBtn.text = "← Back to All-Day Open Mode"
                secondaryActionBtn.setTextColor(0xFF38BDF8.toInt())
                secondaryActionBtn.background = roundedBox(0xFF111827.toInt(), 0xFF334155.toInt(), dp(12))
                secondaryActionBtn.setOnClickListener { selectTrackerSubTab(0) }
            }

            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> {
                primaryActionBtn.text = "WAITING FOR JOG / RUN... (Tap to Cancel)"
                primaryActionBtn.setTextColor(0xFF04130D.toInt())
                primaryActionBtn.background = roundedBox(0xFFF59E0B.toInt(), 0xFFF59E0B.toInt(), dp(14))
                primaryActionBtn.setOnClickListener {
                    trackingService?.cancelPtTrial()
                }
                ptArmHintText.text =
                    "Detected now: ${snap.motionState.label} • Standing & Walking ignored. Start jogging or running to launch!"
                ptArmHintText.setTextColor(0xFFFDE68A.toInt())

                secondaryActionBtn.visibility = View.GONE
            }

            PtTrialPhase.RUNNING_TRIAL -> {
                primaryActionBtn.text = "FINISH EARLY & SAVE PT TRIAL"
                primaryActionBtn.setTextColor(0xFFF8FAFC.toInt())
                primaryActionBtn.background = roundedBox(0xFFEF4444.toInt(), 0xFFEF4444.toInt(), dp(14))
                primaryActionBtn.setOnClickListener {
                    val id = trackingService?.finishWorkout(forceSave = true)
                    if (id != null) {
                        Toast.makeText(this, "PT Trial saved! Updating your 7:15 ETA forecast.", Toast.LENGTH_LONG).show()
                        refreshProgressDashboard()
                        selectTab(1)
                    } else {
                        Toast.makeText(this, "Trial too short to log (<50m).", Toast.LENGTH_SHORT).show()
                    }
                }
                ptArmHintText.text =
                    "Auto-finishes & saves at 1,600 m • Calls out every 400 m lap split"
                ptArmHintText.setTextColor(0xFF34D399.toInt())

                secondaryActionBtn.visibility = View.VISIBLE
                secondaryActionBtn.text = "Cancel PT Trial (Don't Save)"
                secondaryActionBtn.setTextColor(0xFF94A3B8.toInt())
                secondaryActionBtn.background = roundedBox(0xFF111827.toInt(), 0xFF334155.toInt(), dp(12))
                secondaryActionBtn.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Cancel current 1.6 km PT Trial?")
                        .setMessage("This stops the 1.6 km stopwatch without saving (whole-day open tracking stays intact).")
                        .setPositiveButton("Cancel Trial") { _, _ -> trackingService?.cancelPtTrial() }
                        .setNegativeButton("Keep Running", null)
                        .show()
                }
            }
        }

        // Auto-completion jump to Progress tab when 1.6km PT trial finishes hands-free!
        if (snap.ptTrialPhase == PtTrialPhase.IDLE &&
            snap.justFinishedSessionId != null &&
            snap.justFinishedSessionId != lastHandledFinishId
        ) {
            lastHandledFinishId = snap.justFinishedSessionId
            refreshProgressDashboard()
            Toast.makeText(this, "1.6 km PT Trial Complete! Saved to Progress.", Toast.LENGTH_LONG).show()
            selectTab(1)
        }

        // Render 400m splits
        splitsListLayout.removeAllViews()
        if (snap.lapSplits.isEmpty()) {
            splitsListLayout.addView(TextView(this).apply {
                text = "Splits record automatically every 400m (400m • 800m • 1200m • 1600m)."
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
            })
        } else {
            val targetLap = TsPoliceBenchmark.targetLap400mSec(targetSec)
            for (sp in snap.lapSplits) {
                val diff = sp.lapDurationSec - targetLap
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }
                row.addView(TextView(this).apply {
                    text = "Lap ${sp.lapNumber} (${sp.distanceMeters.toInt()}m)"
                    textSize = 12.5f
                    setTextColor(0xFFE2E8F0.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                })
                row.addView(TextView(this).apply {
                    text = TsPoliceBenchmark.formatDuration(sp.lapDurationSec, true)
                    textSize = 12.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (diff <= 0.0) 0xFF10B981.toInt() else 0xFFF59E0B.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
                })
                row.addView(TextView(this).apply {
                    text = "${sp.dominantState.label.substringBefore(" ")} (${TsPoliceBenchmark.formatDuration(sp.cumulativeSec)})"
                    textSize = 11.5f
                    gravity = Gravity.END
                    setTextColor(0xFF94A3B8.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                })
                splitsListLayout.addView(row)
            }
        }

        // Tab 3 diagnostics
        diagGpsText.text = if (snap.isGpsLocked) {
            "GPS Receiver: LOCKED (±${snap.gpsAccuracyMeters?.toInt() ?: 0}m) • Speed ${String.format("%.2f m/s", snap.currentSpeedMps)}"
        } else {
            "GPS Receiver: Waiting for sky lock (Step-Stride indoor estimation active)"
        }
        diagStepText.text = if (snap.hasHardwareStepSensor) {
            "Step Sensor: Hardware TYPE_STEP_DETECTOR + 3-Axis Accel (${snap.cadenceSpm.roundToInt()} spm)"
        } else {
            "Step Sensor: 3-Axis Accelerometer Peak Detector active (${snap.cadenceSpm.roundToInt()} spm)"
        }
        val activeScale = if (repository.autoCalibrateFromSamsung) repository.strideCalibrationScale else 1.0
        diagStrideCalText.text = String.format(
            "Personal Stride Calibration: %.2fx (%s)",
            activeScale,
            if (latestSamsungSnapshot != null) "Calibrated via ${latestSamsungSnapshot!!.sourceLabel}" else "Default biomechanical curve"
        )
        diagStateText.text = "Live Auto-Detected Gait: ${snap.motionState.badgeText}"
        diagStateText.setTextColor(snap.motionState.colorArgb)
    }

    // =========================================================================
    // SAMSUNG HEALTH SYNC, SIDE-BY-SIDE COMPARISON & STRIDE AUTO-CALIBRATION
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
        val scale = if (repository.autoCalibrateFromSamsung) repository.strideCalibrationScale else 1.0

        val key = "${healthSyncStatus}:${isHealthSyncInFlight}:${daily.totalSteps}:${daily.totalDistanceMeters.toInt()}:" +
            "${daily.walkingSec.toInt()}:${daily.jogRunCombinedSec.toInt()}:${sam?.steps}:${sam?.distanceMeters?.toInt()}:${String.format("%.3f", scale)}"
        if (!force && key == lastSamsungRenderKey) return
        lastSamsungRenderKey = key

        if (isHealthSyncInFlight) {
            samsungSyncBadge.text = "SYNCING..."
            samsungSyncBadge.setTextColor(0xFF04130D.toInt())
            samsungSyncBadge.background = roundedBox(0xFF38BDF8.toInt(), 0xFF38BDF8.toInt(), dp(10))
            samsungPrimaryBtn.isEnabled = false
            samsungPrimaryBtn.text = "Syncing Health Connect..."
            return
        }
        samsungPrimaryBtn.isEnabled = true

        when (healthSyncStatus) {
            HealthSyncStatus.NOT_INSTALLED -> {
                samsungCompareTable.visibility = View.GONE
                samsungSyncBadge.text = "INSTALL HC"
                samsungSyncBadge.setTextColor(0xFF04130D.toInt())
                samsungSyncBadge.background = roundedBox(0xFFF59E0B.toInt(), 0xFFF59E0B.toInt(), dp(10))
                samsungHeadlineText.text = "Android Health Connect Needed"
                samsungDetailText.text = "Install or update Android Health Connect so Samsung Health can share live steps, distance & runs."
                samsungPrimaryBtn.text = "Get Health Connect"
                samsungPrimaryBtn.setOnClickListener { healthSyncManager.openSamsungHealthOrHealthConnectSettings() }
                samsungSecondaryBtn.visibility = View.VISIBLE
                samsungSecondaryBtn.text = "Retry Check"
                samsungSecondaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }
            }

            HealthSyncStatus.PERMISSION_REQUIRED -> {
                samsungCompareTable.visibility = View.GONE
                samsungSyncBadge.text = "CONNECT"
                samsungSyncBadge.setTextColor(0xFF04130D.toInt())
                samsungSyncBadge.background = roundedBox(0xFF38BDF8.toInt(), 0xFF38BDF8.toInt(), dp(10))
                samsungHeadlineText.text = "Connect Samsung Health for Better Accuracy"
                samsungDetailText.text =
                    "Grant Health Connect read access to compare TS Tracker vs Samsung Health side-by-side and auto-calibrate your personal stride."
                samsungPrimaryBtn.text = "Connect Samsung Health"
                samsungPrimaryBtn.setOnClickListener { requestSamsungPermissionsSafely() }
                samsungSecondaryBtn.visibility = View.VISIBLE
                samsungSecondaryBtn.text = "Samsung Settings"
                samsungSecondaryBtn.setOnClickListener {
                    if (!healthSyncManager.openSamsungHealthAppDirectly()) {
                        healthSyncManager.openSamsungHealthOrHealthConnectSettings()
                    }
                }
            }

            HealthSyncStatus.WAITING_FOR_SAMSUNG_SYNC -> {
                samsungCompareTable.visibility = View.GONE
                samsungSyncBadge.text = "ENABLE IN SAMSUNG"
                samsungSyncBadge.setTextColor(0xFF04130D.toInt())
                samsungSyncBadge.background = roundedBox(0xFFF59E0B.toInt(), 0xFFF59E0B.toInt(), dp(10))
                samsungHeadlineText.text = "Turn ON Health Connect inside Samsung Health"
                samsungDetailText.text =
                    "In Samsung Health → ⋮ → Settings → Health Connect → Turn ON Allow all, then return here and tap 'Sync Now'."
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
                    samsungSyncBadge.text = "RETRY"
                    samsungSyncBadge.setTextColor(0xFF04130D.toInt())
                    samsungSyncBadge.background = roundedBox(0xFFF59E0B.toInt(), 0xFFF59E0B.toInt(), dp(10))
                    samsungHeadlineText.text = "Tap Sync Now to Pull Samsung Health Data"
                    samsungDetailText.text = "Make sure Health Connect permissions are enabled for both Samsung Health and TS Police 1.6K."
                    samsungPrimaryBtn.text = "Sync & Calibrate Now"
                    samsungPrimaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }
                    samsungSecondaryBtn.visibility = View.VISIBLE
                    samsungSecondaryBtn.text = "Health Connect"
                    samsungSecondaryBtn.setOnClickListener { healthSyncManager.openSamsungHealthOrHealthConnectSettings() }
                    return
                }

                val report = SamsungHealthComparator.compare(
                    tracker = daily,
                    samsung = sam,
                    currentStrideScale = scale
                )
                samsungCompareTable.visibility = View.VISIBLE

                val matchBadge = report.overallMatchPercent?.let { String.format("%.0f%% MATCH", it) }
                    ?: String.format("STRIDE %.2fx", scale)
                val highMatch = (report.overallMatchPercent ?: 100.0) >= 88.0
                samsungSyncBadge.text = matchBadge
                samsungSyncBadge.setTextColor(0xFF04130D.toInt())
                samsungSyncBadge.background = roundedBox(
                    if (highMatch) 0xFF10B981.toInt() else 0xFFF59E0B.toInt(),
                    if (highMatch) 0xFF10B981.toInt() else 0xFFF59E0B.toInt(),
                    dp(10)
                )

                samsungHeadlineText.text = report.headline
                samsungDetailText.text = report.detailAdvice

                // Populate 5 side-by-side comparison rows
                samRowStepsTracker.text = "${daily.totalSteps}"
                samRowStepsSamsung.text = "${sam.steps}"
                val stSign = if (report.stepsDelta > 0) "+${report.stepsDelta}" else "${report.stepsDelta}"
                samRowStepsDelta.text = if (report.stepsMatchPercent != null) {
                    String.format("%.0f%% (%s)", report.stepsMatchPercent, if (report.stepsDelta == 0) "0" else stSign)
                } else stSign
                samRowStepsDelta.setTextColor(
                    if ((report.stepsMatchPercent ?: 100.0) >= 88.0) 0xFF10B981.toInt() else 0xFFFBBF24.toInt()
                )

                samRowDistTracker.text = formatDistanceShort(daily.totalDistanceMeters)
                samRowDistSamsung.text = formatDistanceShort(sam.distanceMeters)
                val dMeters = report.distanceDeltaMeters.roundToInt()
                val dSign = if (dMeters > 0) "+${dMeters}m" else "${dMeters}m"
                samRowDistDelta.text = if (report.distanceMatchPercent != null) {
                    String.format("%.0f%% (%s)", report.distanceMatchPercent, if (abs(dMeters) <= 3) "0m" else dSign)
                } else dSign
                samRowDistDelta.setTextColor(
                    if ((report.distanceMatchPercent ?: 100.0) >= 88.0) 0xFF10B981.toInt() else 0xFFFBBF24.toInt()
                )

                samRowWalkTracker.text = TsPoliceBenchmark.formatDuration(daily.walkingSec)
                samRowWalkSamsung.text = TsPoliceBenchmark.formatDuration(sam.walkingSec)
                samRowWalkDelta.text = formatSignedSeconds(report.walkSecDelta)
                samRowWalkDelta.setTextColor(0xFFCBD5E1.toInt())

                samRowRunTracker.text = TsPoliceBenchmark.formatDuration(daily.jogRunCombinedSec)
                samRowRunSamsung.text = TsPoliceBenchmark.formatDuration(sam.jogRunSec)
                samRowRunDelta.text = formatSignedSeconds(report.jogRunSecDelta)
                samRowRunDelta.setTextColor(0xFF34D399.toInt())

                samRowStrideTracker.text = report.trackerStrideM?.let { String.format("%.2f m", it) } ?: "--"
                samRowStrideSamsung.text = report.samsungStrideM?.let { String.format("%.2f m", it) } ?: "--"
                samRowStrideDelta.text = String.format("Cal %.2fx", scale)
                samRowStrideDelta.setTextColor(0xFF38BDF8.toInt())

                samsungPrimaryBtn.text = "Sync & Calibrate Now"
                samsungPrimaryBtn.setOnClickListener { triggerSamsungHealthSync(userTriggered = true) }

                val samsungHasHigherTotals = sam.steps > daily.totalSteps + 25 ||
                    sam.distanceMeters > daily.totalDistanceMeters + 25.0
                samsungSecondaryBtn.visibility = View.VISIBLE
                if (samsungHasHigherTotals) {
                    val extraSteps = max(0, sam.steps - daily.totalSteps)
                    samsungSecondaryBtn.text = if (extraSteps > 0) "Align Today (+$extraSteps)" else "Align Today"
                    samsungSecondaryBtn.setTextColor(0xFF38BDF8.toInt())
                    samsungSecondaryBtn.setOnClickListener {
                        trackingService?.alignTodayWithSamsungHealth(sam)
                        refreshCachedDailyHistory(forceRender = true)
                        renderSamsungComparisonCard(force = true)
                        Toast.makeText(
                            this,
                            "Aligned today's floor with ${sam.sourceLabel}!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    samsungSecondaryBtn.text = "Health Connect"
                    samsungSecondaryBtn.setTextColor(0xFFCBD5E1.toInt())
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
                    "Synced ${outcome.importedRunCount} Samsung Health run(s) into 7:15 Progress!",
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
                    "TS Police 1.6 KM Tracker reads Steps, Distance, Exercise Sessions, Speed, and Cadence from Android Health Connect strictly on-device to:\n\n" +
                        "1. Compare your daily steps, distance, walk time, and jog/run time side-by-side with Samsung Health.\n" +
                        "2. Auto-calibrate your personal stride length for higher 1.6 km PET pace & indoor accuracy.\n\n" +
                        "Your health data stays 100% locally on your phone and is never uploaded to any external server."
                )
                .setPositiveButton("Got It", null)
                .show()
        }
    }

    // =========================================================================
    // PROGRESS & TARGET FORECAST REFRESH
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
        when (forecast.status) {
            ForecastStatus.NO_DATA -> {
                etaHeadlineText.text = "Record Day 1 Baseline"
                etaHeadlineText.setTextColor(0xFFF8FAFC.toInt())
            }
            ForecastStatus.SINGLE_DAY_BASELINE -> {
                etaHeadlineText.text = "1 More Run Unlocks ETA"
                etaHeadlineText.setTextColor(0xFF38BDF8.toInt())
            }
            ForecastStatus.QUALIFIED -> {
                etaHeadlineText.text = "QUALIFIED (< $targetFmt!)"
                etaHeadlineText.setTextColor(0xFF10B981.toInt())
            }
            ForecastStatus.ON_TRACK -> {
                etaHeadlineText.text = "~${forecast.estimatedDaysToTarget} Days to $targetFmt"
                etaHeadlineText.setTextColor(0xFF10B981.toInt())
            }
            ForecastStatus.PLATEAU_OR_SLOWER -> {
                etaHeadlineText.text = "${String.format("%.0fs", forecast.gapToTargetSec ?: 0.0)} Above $targetFmt"
                etaHeadlineText.setTextColor(0xFFF59E0B.toInt())
            }
        }

        etaSubText.text = forecast.coachSummary
        coachPrescriptionText.text = "Coach Plan: ${forecast.workoutPrescription}"

        kpiPbVal.text = forecast.personalBestSec?.let { TsPoliceBenchmark.formatDuration(it, true) } ?: "--:--"
        kpiGapVal.text = forecast.gapToTargetSec?.let {
            if (it <= 0.0) "0.0s (Met!)" else String.format("-%.1fs needed", it)
        } ?: "--"
        kpiRateVal.text = forecast.improvementSecPerWeek?.let {
            if (it >= 0.0) String.format("-%.1fs / wk", it) else String.format("+%.1fs / wk", -it)
        } ?: "--"
        kpiImprovedVal.text = forecast.totalImprovementSec?.let {
            if (it >= 0.0) String.format("%.1fs faster", it) else String.format("%.1fs", it)
        } ?: "--"

        trendChartView.setForecast(forecast)

        demoToggleBtn.text = if (repository.hasDemoSessions()) {
            "Clear Demo Data"
        } else {
            "Load Sample Data"
        }

        // Populate sessions list
        sessionsListLayout.removeAllViews()
        if (sessions.isEmpty()) {
            sessionsListLayout.addView(TextView(this).apply {
                text = "No sessions yet. Run a 1.6 km PT Trial, jog >= 600m in Open Mode, tap '+ Log Track Time', or tap 'Load Sample Data'."
                textSize = 12.5f
                setTextColor(0xFF64748B.toInt())
            })
            return
        }

        for (s in sessions) {
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBox(0xFF0B1120.toInt(), 0xFF1E293B.toInt(), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val topLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tagText = if (s.isDemo) "${s.dateIso} • DEMO" else s.dateIso
            topLine.addView(TextView(this).apply {
                text = "$tagText  •  ${String.format("%.2f km", s.distanceMeters / 1000.0)}"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFE2E8F0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            val qualified = s.equiv1600mSec <= targetSec
            topLine.addView(TextView(this).apply {
                val suffix = if (kotlin.math.abs(s.distanceMeters - 1600.0) <= 50.0) "1.6K" else "1.6K eq"
                text = "${TsPoliceBenchmark.formatDuration(s.equiv1600mSec, true)} ($suffix)"
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(if (qualified) 0xFF10B981.toInt() else 0xFF38BDF8.toInt())
            })
            rowCard.addView(topLine)

            val totalMove = (s.runningSec + s.joggingSec + s.walkingSec).coerceAtLeast(1.0)
            val runPct = ((s.runningSec / totalMove) * 100.0).roundToInt()
            val jogPct = ((s.joggingSec / totalMove) * 100.0).roundToInt()

            val bottomLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            bottomLine.addView(TextView(this).apply {
                text = "Time ${TsPoliceBenchmark.formatDuration(s.activeDurationSec)} • Run $runPct% / Jog $jogPct% • ${s.notes}"
                textSize = 11.5f
                setTextColor(0xFF94A3B8.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            bottomLine.addView(TextView(this).apply {
                text = "Delete"
                textSize = 11.5f
                setTextColor(0xFFEF4444.toInt())
                setPadding(dp(8), dp(2), dp(2), dp(2))
                setOnClickListener {
                    repository.deleteSession(s.id)
                    refreshProgressDashboard()
                }
            })
            rowCard.addView(bottomLine)
            sessionsListLayout.addView(rowCard)
        }
    }

    private fun showManualRunDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        val dateInput = EditText(this).apply {
            hint = "Date (YYYY-MM-DD)"
            setText(LocalDate.now().toString())
        }
        val distInput = EditText(this).apply {
            hint = "Distance in meters (e.g. 1600)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("1600")
        }
        val minInput = EditText(this).apply {
            hint = "Minutes (e.g. 7)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("7")
        }
        val secInput = EditText(this).apply {
            hint = "Seconds (e.g. 48.5)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("45")
        }
        form.addView(TextView(this).apply { text = "Date (YYYY-MM-DD):" })
        form.addView(dateInput)
        form.addView(TextView(this).apply { text = "Distance (meters):" })
        form.addView(distInput)
        form.addView(TextView(this).apply { text = "Minutes:" })
        form.addView(minInput)
        form.addView(TextView(this).apply { text = "Seconds:" })
        form.addView(secInput)

        AlertDialog.Builder(this)
            .setTitle("Log Manual Track Stopwatch Time")
            .setView(form)
            .setPositiveButton("Save Run") { _, _ ->
                val dateIso = runCatching { LocalDate.parse(dateInput.text.toString().trim()).toString() }
                    .getOrDefault(LocalDate.now().toString())
                val distM = distInput.text.toString().toDoubleOrNull() ?: 1600.0
                val mins = minInput.text.toString().toIntOrNull() ?: 0
                val secs = secInput.text.toString().toDoubleOrNull() ?: 0.0
                val totalSec = mins * 60.0 + secs
                if (distM >= 200.0 && totalSec >= 30.0) {
                    val epochMs = LocalDate.parse(dateIso)
                        .atTime(6, 30)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    val is1600 = kotlin.math.abs(distM - 1600.0) <= 25.0
                    repository.insertSession(
                        RunSessionRecord(
                            startedAtEpochMs = epochMs,
                            dateIso = dateIso,
                            mode = RunMode.MANUAL_LOG,
                            distanceMeters = distM,
                            activeDurationSec = totalSec,
                            pausedDurationSec = 0.0,
                            first1600mSplitSec = if (is1600) totalSec else null,
                            equiv1600mSec = TsPoliceBenchmark.computeEquiv1600mSec(
                                distM,
                                totalSec,
                                if (is1600) totalSec else null
                            ),
                            avgCadenceSpm = 168.0,
                            maxSpeedMps = distM / totalSec,
                            runningSec = totalSec,
                            joggingSec = 0.0,
                            walkingSec = 0.0,
                            notes = "Manual stopwatch entry",
                            isDemo = false
                        )
                    )
                    refreshProgressDashboard()
                } else {
                    Toast.makeText(this, "Enter valid distance (>=200m) and duration.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshSettingsHighlights() {
        val curTarget = repository.targetTimeSec
        for ((sec, btn) in targetPresetButtons) {
            val active = kotlin.math.abs(curTarget - sec) < 1.0
            btn.background = roundedBox(
                if (active) 0xFF10B981.toInt() else 0xFF0B1120.toInt(),
                if (active) 0xFF10B981.toInt() else 0xFF334155.toInt(),
                dp(8)
            )
            btn.setTextColor(if (active) 0xFF04130D.toInt() else 0xFFCBD5E1.toInt())
        }

        val curThresh = repository.runningThresholdMps
        for ((mps, btn) in thresholdPresetButtons) {
            val active = kotlin.math.abs(curThresh - mps) < 0.05
            btn.background = roundedBox(
                if (active) 0xFF38BDF8.toInt() else 0xFF0B1120.toInt(),
                if (active) 0xFF38BDF8.toInt() else 0xFF334155.toInt(),
                dp(8)
            )
            btn.setTextColor(if (active) 0xFF04130D.toInt() else 0xFFCBD5E1.toInt())
        }
        refreshStateAudioChip()
    }

    private fun refreshStateAudioChip() {
        if (!::stateAudioChip.isInitialized) return
        val on = repository.stateChangeAudioEnabled
        if (::stateAudioSwitch.isInitialized && stateAudioSwitch.isChecked != on) {
            stateAudioSwitch.isChecked = on
        }
        stateAudioChip.text = if (on) "POCKET AUDIO ON" else "MUTED"
        stateAudioChip.setTextColor(if (on) 0xFF38BDF8.toInt() else 0xFF64748B.toInt())
        stateAudioChip.background = roundedBox(
            if (on) 0x1E38BDF8 else 0xFF111827.toInt(),
            if (on) 0xFF38BDF8.toInt() else 0xFF334155.toInt(),
            dp(12)
        )
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

    private fun updatePermissionBanner() {
        permissionBanner.visibility = if (hasCorePermissions()) View.GONE else View.VISIBLE
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
    // GITHUB OTA AUTO-UPDATE UI FLOW
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
                    updateBanner.text =
                        "UPDATE v${result.release.versionName} AVAILABLE  •  TAP TO INSTALL"
                    updateBanner.visibility = View.VISIBLE
                    updateStatusText.text =
                        "Update ready: v${result.installedVersionName} → v${result.release.versionName}"
                    updateStatusText.setTextColor(0xFF38BDF8.toInt())

                    if (autoInstallWhenFound && !currentlyRecording) {
                        startUpdateDownloadAndInstall(result.release)
                    } else if (manualUserTrigger || !currentlyRecording) {
                        showUpdateAvailableDialog(result.installedVersionName, result.release)
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    updateBanner.visibility = View.GONE
                    updateStatusText.text =
                        "Up to date: v${result.installedVersionName} (Build ${result.installedVersionCode})"
                    updateStatusText.setTextColor(0xFF10B981.toInt())
                    if (manualUserTrigger) {
                        Toast.makeText(
                            this,
                            "You're on the latest version (v${result.installedVersionName})!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (manualUserTrigger) {
                        Toast.makeText(
                            this,
                            "Update check: ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()
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
                    "Your daily 1.6 km run history and progress data will stay intact."
            )
            .setPositiveButton("Download & Install") { _, _ ->
                startUpdateDownloadAndInstall(release)
            }
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
                    val dlMb = downloaded / (1024.0 * 1024.0)
                    val totMb = total / (1024.0 * 1024.0)
                    statusTv.text = String.format("Downloading APK... %d%% (%.1f / %.1f MB)", pct, dlMb, totMb)
                } else {
                    val dlMb = downloaded / (1024.0 * 1024.0)
                    statusTv.text = String.format("Downloading APK... %.1f MB", dlMb)
                }
            },
            onNeedsInstallPermission = {
                progressDialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Allow App Self-Update")
                    .setMessage(
                        "Update v${release.versionName} is downloaded and SHA-256 verified!\n\n" +
                            "On the next screen, toggle 'Allow from this source' and press Back — the update will install automatically."
                    )
                    .setPositiveButton("Open Settings") { _, _ ->
                        appUpdater.openUnknownSourcesSettings(this)
                    }
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
            onInstallerLaunched = {
                progressDialog.dismiss()
            }
        )
    }

    // =========================================================================
    // UI STYLING HELPERS
    // =========================================================================
    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = roundedBox(0xFF111827.toInt(), 0xFF1E293B.toInt(), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF94A3B8.toInt())
        letterSpacing = 0.05f
    }

    private fun metricTile(label: String, initialVal: String, initialSub: String): Triple<LinearLayout, TextView, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = roundedBox(0xFF111827.toInt(), 0xFF1E293B.toInt(), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val lbl = TextView(this).apply {
            text = label
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF94A3B8.toInt())
        }
        val valueTv = TextView(this).apply {
            text = initialVal
            textSize = 19f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, dp(3), 0, dp(2))
        }
        val subTv = TextView(this).apply {
            text = initialSub
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
        }
        box.addView(lbl)
        box.addView(valueTv)
        box.addView(subTv)
        return Triple(box, valueTv, subTv)
    }

    private fun roundedBox(fillColor: Int, strokeColor: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
}
