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
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.domain.ForecastStatus
import com.tspolice.runtracker.domain.LiveTelemetry
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.ProgressionForecaster
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import com.tspolice.runtracker.service.RunTrackingService
import com.tspolice.runtracker.update.AppUpdater
import com.tspolice.runtracker.update.UpdateCheckResult
import com.tspolice.runtracker.update.UpdateReleaseInfo
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var repository: RunRepository
    private lateinit var appUpdater: AppUpdater
    private var trackingService: RunTrackingService? = null
    private var serviceBound = false
    private var lastHandledFinishId: Long? = null
    private var latestDiscoveredRelease: UpdateReleaseInfo? = null
    private var currentlyRecording = false
    private var activityVisible = false
    private var lastAutoUpdateCheckElapsedMs = -10_000_000L
    private var activeUpdateDialog: AlertDialog? = null

    // Top-level tab containers
    private lateinit var stateAudioChip: TextView
    private lateinit var updateBanner: TextView
    private lateinit var tabTrackerContainer: ScrollView
    private lateinit var tabProgressContainer: ScrollView
    private lateinit var tabGuideContainer: ScrollView
    private val navTabViews = mutableListOf<TextView>()

    // Tab 1: Live Tracker views
    private lateinit var permissionBanner: LinearLayout
    private lateinit var mode1600Btn: TextView
    private lateinit var modeOpenBtn: TextView
    private lateinit var paceArcGauge: PaceArcGaugeView
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
    private lateinit var primaryActionBtn: Button
    private lateinit var secondaryActionBtn: Button
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
    private lateinit var stateAudioSwitch: Switch
    private lateinit var diagGpsText: TextView
    private lateinit var diagStepText: TextView
    private lateinit var diagStateText: TextView
    private lateinit var updateStatusText: TextView
    private lateinit var checkUpdateBtn: Button
    private lateinit var targetPresetButtons: List<Pair<Double, TextView>>
    private lateinit var thresholdPresetButtons: List<Pair<Double, TextView>>

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionBanner()
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

        setContentView(buildRootLayout())
        selectTab(0)
        refreshProgressDashboard()
        updatePermissionBanner()

        val svcIntent = Intent(this, RunTrackingService::class.java)
        startService(svcIntent)
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        requestNeededPermissionsIfMissing(silentCheckOnly = false)
        appUpdater.syncBackgroundUpdateSchedule()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeInstallFromNotificationIntent(intent)
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
            text = "Auto Run/Jog Detection • 7:15 PET Target & ETA"
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
        val tabs = listOf("LIVE 1.6K RUN", "PROGRESS & ETA", "PET & SENSORS")
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
        if (index == 1) {
            refreshProgressDashboard()
        }
    }

    // =========================================================================
    // TAB 1: LIVE RUN TRACKER & AUTO-GAIT CLASSIFIER
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
            text = "Enable GPS & Motion Sensors for live Run/Jog auto-detection"
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

        // Mode switcher (1.6km Trial vs Open Practice)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBox(0xFF111827.toInt(), 0xFF1E293B.toInt(), dp(12))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        mode1600Btn = TextView(this).apply {
            text = "1.6 km PET Trial"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(9), dp(8), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { trackingService?.setRunMode(RunMode.TIMED_1600M) }
        }
        modeOpenBtn = TextView(this).apply {
            text = "Open Practice Run"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(9), dp(8), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { trackingService?.setRunMode(RunMode.OPEN_PRACTICE) }
        }
        modeRow.addView(mode1600Btn)
        modeRow.addView(modeOpenBtn)
        col.addView(modeRow)

        // Gauge Card
        val gaugeCard = cardContainer().apply {
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        paceArcGauge = PaceArcGaugeView(this)
        gaugeCard.addView(paceArcGauge)
        col.addView(gaugeCard)

        // 2x2 Live Sensor Metrics Grid
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
        val cadTile = metricTile("STEP CADENCE", "0 spm", "0 steps")
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

        // Auto-Detected Gait Share Card
        val gaitCard = cardContainer()
        gaitCard.addView(sectionHeader("AUTO-DETECTED GAIT BREAKDOWN (NO SPORT PICKER)"))
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

        // Start / Auto-Arm / Finish Controls
        primaryActionBtn = Button(this).apply {
            text = "START 1.6 KM TIMED RUN"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(0xFF04130D.toInt())
            background = roundedBox(0xFF10B981.toInt(), 0xFF10B981.toInt(), dp(14))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        secondaryActionBtn = Button(this).apply {
            text = "Arm Hands-Free Auto-Start (Begins when you Jog/Run)"
            textSize = 12.5f
            isAllCaps = false
            setTextColor(0xFF38BDF8.toInt())
            background = roundedBox(0xFF111827.toInt(), 0xFF38BDF8.toInt(), dp(12))
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        col.addView(primaryActionBtn)
        col.addView(secondaryActionBtn)

        // Live 400m Track Splits Card
        val splitsCard = cardContainer()
        splitsCard.addView(sectionHeader("400M TRACK LAP SPLITS (TARGET 1:48.8 / LAP)"))
        splitsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        splitsCard.addView(splitsListLayout)
        col.addView(splitsCard)

        scroll.addView(col)
        return scroll
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
            text = "Blue dots = 1.6km Trials  •  Amber = Open Run Equiv  •  Dashed line = Forecast to 7:15"
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
                refreshProgressDashboard()
            }
        }
        actionsRow.addView(manualBtn)
        actionsRow.addView(demoToggleBtn)
        col.addView(actionsRow)

        // Daily Sessions History Card
        val historyCard = cardContainer()
        historyCard.addView(sectionHeader("RECORDED PRACTICE SESSIONS"))
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

        // Target Time Selector Card
        val settingsCard = cardContainer()
        settingsCard.addView(sectionHeader("TARGET CUTOFF & GAIT THRESHOLD"))
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

        // Switches for State-Change Audio, Voice Coach & Indoor Step Fallback
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
            setPadding(0, dp(6), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> repository.indoorStepFallbackEnabled = checked }
        }
        val testStateAudioBtn = Button(this).apply {
            text = "Test State Change Audio Cue"
            textSize = 12.5f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFE2E8F0.toInt())
            background = roundedBox(0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), dp(10))
            setOnClickListener {
                trackingService?.previewStateChangeAudio(MotionState.TARGET_PACE)
            }
        }
        settingsCard.addView(stateAudioSwitch)
        settingsCard.addView(voiceSwitch)
        settingsCard.addView(stepFallbackSwitch)
        settingsCard.addView(testStateAudioBtn)
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
        diagStateText = TextView(this).apply {
            text = "Classifier Verdict: STANDING"
            textSize = 12.5f
            setTextColor(0xFF10B981.toInt())
            setPadding(0, dp(2), 0, dp(4))
        }
        diagCard.addView(diagGpsText)
        diagCard.addView(diagStepText)
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
    private fun renderLiveTelemetry(snap: LiveTelemetry) {
        currentlyRecording = snap.isRecording
        refreshStateAudioChip()
        val targetSec = repository.targetTimeSec

        // Mode highlight
        val is1600 = snap.mode == RunMode.TIMED_1600M
        mode1600Btn.background = roundedBox(
            if (is1600) 0xFF1E293B.toInt() else 0x00000000,
            if (is1600) 0xFF38BDF8.toInt() else 0x00000000,
            dp(9)
        )
        mode1600Btn.setTextColor(if (is1600) 0xFFF8FAFC.toInt() else 0xFF94A3B8.toInt())
        modeOpenBtn.background = roundedBox(
            if (!is1600) 0xFF1E293B.toInt() else 0x00000000,
            if (!is1600) 0xFF38BDF8.toInt() else 0x00000000,
            dp(9)
        )
        modeOpenBtn.setTextColor(if (!is1600) 0xFFF8FAFC.toInt() else 0xFF94A3B8.toInt())

        // Gauge
        paceArcGauge.updateState(
            distanceMeters = snap.distanceMeters,
            activeDurationSec = snap.activeDurationSec,
            targetSec = targetSec,
            motionState = snap.motionState,
            recording = snap.isRecording,
            armed = snap.isAutoStartArmed
        )

        // 4 Live Sensor Tiles
        metricPaceVal.text = TsPoliceBenchmark.formatPacePerKm(snap.currentSpeedMps)
        metricPaceSub.text = String.format("%.1f km/h ground speed", snap.currentSpeedMps * 3.6)

        metricCadenceVal.text = String.format("%d spm", snap.cadenceSpm.roundToInt())
        metricCadenceSub.text = "${snap.totalSteps} steps detected"

        val projSec = TsPoliceBenchmark.projected1600mFinishSec(
            snap.distanceMeters,
            snap.activeDurationSec,
            snap.currentSpeedMps
        )
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

        // Gait breakdown bar
        gaitBar.setDurations(snap.runningSec, snap.joggingSec, snap.walkingSec, snap.pausedDurationSec)
        gaitLegendText.text =
            "Run ${TsPoliceBenchmark.formatDuration(snap.runningSec)}  •  " +
                "Jog ${TsPoliceBenchmark.formatDuration(snap.joggingSec)}  •  " +
                "Walk ${TsPoliceBenchmark.formatDuration(snap.walkingSec)}  •  " +
                "Pause ${TsPoliceBenchmark.formatDuration(snap.pausedDurationSec)}"

        // Action buttons
        if (snap.isRecording) {
            primaryActionBtn.text = "FINISH & SAVE RUN"
            primaryActionBtn.setTextColor(0xFFF8FAFC.toInt())
            primaryActionBtn.background = roundedBox(0xFFEF4444.toInt(), 0xFFEF4444.toInt(), dp(14))
            primaryActionBtn.setOnClickListener {
                val id = trackingService?.finishWorkout(forceSave = true)
                if (id != null) {
                    Toast.makeText(this, "Run saved! Updating your 7:15 ETA forecast.", Toast.LENGTH_LONG).show()
                    refreshProgressDashboard()
                    selectTab(1)
                } else {
                    Toast.makeText(this, "Run too short to log (<30m).", Toast.LENGTH_SHORT).show()
                }
            }

            secondaryActionBtn.text = "Discard Current Run"
            secondaryActionBtn.setTextColor(0xFF94A3B8.toInt())
            secondaryActionBtn.background = roundedBox(0xFF111827.toInt(), 0xFF334155.toInt(), dp(12))
            secondaryActionBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Discard current run?")
                    .setMessage("This will stop the timer without saving.")
                    .setPositiveButton("Discard") { _, _ -> trackingService?.discardWorkout() }
                    .setNegativeButton("Keep Running", null)
                    .show()
            }
        } else {
            primaryActionBtn.text = if (is1600) "START 1.6 KM TIMED RUN" else "START OPEN PRACTICE RUN"
            primaryActionBtn.setTextColor(0xFF04130D.toInt())
            primaryActionBtn.background = roundedBox(0xFF10B981.toInt(), 0xFF10B981.toInt(), dp(14))
            primaryActionBtn.setOnClickListener {
                requestNeededPermissionsIfMissing(silentCheckOnly = false)
                trackingService?.startWorkout(snap.mode)
            }

            if (snap.isAutoStartArmed) {
                secondaryActionBtn.text = "Auto-Start ARMED (Tap to Disarm) • Start Jogging!"
                secondaryActionBtn.setTextColor(0xFFFBBF24.toInt())
                secondaryActionBtn.background = roundedBox(0x22F59E0B, 0xFFF59E0B.toInt(), dp(12))
                secondaryActionBtn.setOnClickListener { trackingService?.setAutoStartArmed(false) }
            } else {
                secondaryActionBtn.text = "Arm Hands-Free Auto-Start (Begins when you Jog/Run)"
                secondaryActionBtn.setTextColor(0xFF38BDF8.toInt())
                secondaryActionBtn.background = roundedBox(0xFF111827.toInt(), 0xFF38BDF8.toInt(), dp(12))
                secondaryActionBtn.setOnClickListener {
                    requestNeededPermissionsIfMissing(silentCheckOnly = false)
                    trackingService?.setAutoStartArmed(true)
                }
            }
        }

        // Auto-completion jump to Progress tab when 1.6km trial finishes hands-free!
        if (!snap.isRecording && snap.justFinishedSessionId != null && snap.justFinishedSessionId != lastHandledFinishId) {
            lastHandledFinishId = snap.justFinishedSessionId
            refreshProgressDashboard()
            Toast.makeText(this, "1.6 km Trial Complete! Saved to Progress.", Toast.LENGTH_LONG).show()
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
        diagStateText.text = "Live Auto-Detected Gait: ${snap.motionState.badgeText}"
        diagStateText.setTextColor(snap.motionState.colorArgb)
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
                text = "No sessions yet. Start a live 1.6 km run, tap '+ Log Track Time', or tap 'Load Sample Data' to preview."
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
