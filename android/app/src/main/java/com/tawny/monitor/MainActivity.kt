package com.tawny.monitor

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class TawnyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the saved theme choice before any Activity is themed.
        val m = getSharedPreferences("tawny", MODE_PRIVATE).getString("theme", "system")
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (m) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}

/**
 * On-device flight recorder for the signaling path.
 *
 * The failures worth chasing happen with the Handheld on cellular — off the
 * Wi-Fi, so the phone is not reachable by adb and logcat is out of reach. Both
 * the native shell and the web page (through `Bridge.post` "diag") append here,
 * the log survives a restart, and it can be read on the phone itself by
 * long-pressing the version stamp, or pulled with:
 *
 *   adb shell run-as com.tawny.monitor.debug cat files/diag.log
 *
 * Never write the channel key or a raw admission ticket in here — only whether
 * one was present. The room id is a hash and is logged on purpose: it is what
 * lets the Monitor's log and the Handheld's log be lined up.
 */
object Diag {
    private const val MAX_BYTES = 96 * 1024
    private const val KEEP_LINES = 400
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "diag.log")
        log("app", "── launched v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
            (if (BuildConfig.BUILD_TYPE == "release") "" else " [${BuildConfig.BUILD_TYPE}]") + ", " +
            "android ${android.os.Build.VERSION.SDK_INT} on ${android.os.Build.MODEL}")
    }

    /**
     * Any IPv4 address in a line, replaced by what kind of address it was. The
     * privacy policy promises this log never holds an IP address, and lines
     * arrive from all over the shell and the page (`lan=…`, `dial lan …`), so
     * the promise is kept here, once, rather than at every call site. Whether
     * an address was on the home network or not is all a bug report needs.
     */
    private val IPV4 = Regex("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])")

    private fun redact(s: String): String = IPV4.replace(s) { m ->
        when {
            m.groupValues.drop(1).any { (it.toIntOrNull() ?: 256) > 255 } -> m.value
            m.value == "127.0.0.1" -> m.value      // this phone's own loopback
            PairLink.isPrivateHostPort("${m.value}:0") -> "<lan-ip>"
            else -> "<ip>"
        }
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${stamp.format(java.util.Date())}  $tag  ${redact(msg)}"
        Log.d("TawnyDiag", line)
        val f = file ?: return
        try {
            f.appendText(line + "\n")
            if (f.length() > MAX_BYTES) {
                f.writeText(f.readLines().takeLast(KEEP_LINES).joinToString("\n") + "\n")
            }
        } catch (e: Exception) { /* diagnostics must never break the app */ }
    }

    /** Redacted again on the way out, for lines written by a build that did not. */
    @Synchronized fun dump(): String =
        try { redact(file?.takeIf { it.exists() }?.readText().orEmpty()) } catch (e: Exception) { "" }

    @Synchronized fun clear() {
        try { file?.writeText("") } catch (e: Exception) {}
    }
}

/**
 * The type scale.
 *
 * There were seventeen distinct text sizes in this file and five different ones
 * for what is semantically the same thing — a screen title. Sizes are sp; the
 * matching line-height multipliers live beside them so multi-line copy stops
 * being tight on some screens and airy on others.
 */
object Type {
    const val WORDMARK = 46f     // the welcome screen's "Tawny"
    const val WORDMARK_SM = 28f  // the same mark on a secondary screen
    const val TITLE = 27f        // screen title
    const val PILL = 17f         // button label
    const val CARD_TITLE = 20f   // title inside a card
    const val DIALOG = 22f       // title inside a dialog
    const val BODY = 16f
    const val SUB = 15f          // supporting line under a title; also links
    const val LABEL = 13f        // tracked-out small caps
    const val CAPTION = 11f
    const val MICRO = 10f        // the build stamp
    const val LEAD_BODY = 1.45f  // line-height multiplier for running text
    const val LEAD_TIGHT = 1.2f  // for headings
}

/**
 * One radius language, three tiers.
 *
 * This used to be 4dp cards on 3dp controls — near-square, which read as
 * "equipment panel" in the abstract and as "unfinished" on a real screen full
 * of soft illustrated animals. The whole app is warm parchment, a brush
 * wordmark and plush critters; boxes with 3dp corners were the one hard-edged
 * thing in it.
 *
 * The tiers exist so the softening stays a system rather than a pile of local
 * tweaks: a surface that *holds* things is rounder than a control you press,
 * which is rounder than a chip floating over video. Keep that order if these
 * ever move again.
 */
object Radius {
    const val CARD = 16     // dp — surfaces that hold content: cards, panels, dialogs
    const val CONTROL = 11  // dp — things you press or type into: pills, inputs
    const val CHIP = 7      // dp — small overlays sitting on top of the camera
}

/**
 * Palette, resolved from res/values(-night)/colors.xml so it tracks light/dark.
 * Light = "strawberry cheesecake"; dark = "brushed leather" with a warm-bronze
 * accent. Mirrors public/style.css. Call [load] before building any UI.
 * (Field names are roles, not literal hues — BERRY is bronze in dark mode.)
 */
object Hue {
    var BG = 0; var PANEL = 0; var RAISE = 0; var LINE = 0
    var TEXT = 0; var DIM = 0; var BERRY = 0; var SKY = 0
    var LIVE = 0; var STAGE = 0; var ON_ACCENT = 0

    fun load(c: Context) {
        BG = c.getColor(R.color.bg)
        PANEL = c.getColor(R.color.panel)
        RAISE = c.getColor(R.color.raise)
        LINE = c.getColor(R.color.line)
        TEXT = c.getColor(R.color.text)
        DIM = c.getColor(R.color.dim)
        BERRY = c.getColor(R.color.berry)
        SKY = c.getColor(R.color.sky)
        LIVE = c.getColor(R.color.live)
        STAGE = c.getColor(R.color.stage)
        ON_ACCENT = c.getColor(R.color.on_accent)   // text/glyph on a BERRY fill
    }
}

/**
 * Native onboarding for Tawny — no address to type, Wi-Fi only.
 *
 *   welcome (animated) → "this device" role choice
 *     ├─ The Watcher  → runs the web app + a signaling relay in this APK,
 *     │                 shows a QR with its LAN address + channel key
 *     └─ The Handheld → scans that QR, connects straight to the Watcher
 *
 * The web app ([public/app.js], bundled in assets/web/) runs in a WebView and
 * owns the WebRTC stack. It is served from a local server on 127.0.0.1 — a
 * secure-context origin, so getUserMedia works with no external URL. The page
 * is loaded with `#native` and [tawnyStart] is called with the role, key,
 * and signaling address to jump straight into the session.
 *
 * The wrapper still does what a WebView cannot: grant the WebView's own capture
 * request, hold the screen on during a call, pull the backlight down and slow
 * the panel while the Monitor dozes, route audio through the hardware echo
 * canceller, and catch snapshot downloads as base64.
 */

/**
 * Dim mode's backlight level. Deliberately *not* 0f: several OEM builds —
 * Samsung's among them — read an exact zero as "no override, follow the system
 * value", which hands the backlight straight back to auto-brightness. In a lit
 * room that is the opposite of what dim mode is for. A hair above zero is
 * unambiguous to every implementation, and is visually black on both an AMOLED
 * A50 and an LCD tablet.
 */
private const val DIM_BRIGHTNESS = 0.004f

/**
 * The floor for the refresh-rate drop. Panels advertise seamless low-rate modes
 * well below this; going down there makes the UI feel broken on wake and buys
 * very little over 30, since the expensive part is the scan-out, not the last
 * few hertz.
 */
private const val MIN_REFRESH_HZ = 30f

/**
 * Phones that may watch one Monitor at once. Hardcoded, and hardcoded in four
 * other places that each independently refuse a fourth: public/app.js
 * (MAX_VIEWERS), LocalWeb.kt and the two rendezvous ports (MAX_PER_ROOM = this
 * + 1). Nothing here reads it from prefs, an intent extra or the page — the
 * page reports the ceiling back and the shell only uses it for wording.
 */
private const val MAX_VIEWERS = 3

/**
 * The prefs key behind the Animations row. Named here rather than typed out in
 * four places because the drawn scenes read it directly — they are file-scope
 * views with no handle on the Activity, and a typo in one of them would fail
 * silently as "this one screen ignores the setting".
 */
private const val STILL_MODE = "stillMode"

/** The channel's role, set aside by the welcome screen; see resumeSession(). */
private const val PARKED_ROLE = "parkedRole"

/** Roles ("viewer" / "station") whose first-call walkthrough has been shown. */
private const val COACH_SEEN = "coachSeen"

/** The native walkthrough was finished or skipped once. */
private const val TOUR_SEEN = "tourSeen"

/** The last VERSION_CODE whose update board was shown (or skipped as a fresh
 *  install). See MainActivity.maybeShowWhatsNew. */
private const val WHATSNEW_SEEN = "whatsNewSeen"

/** The rating prompt's bookkeeping: sessions used for real, times asked, and
 *  when last. See MainActivity.maybeAskForReview. */
private const val GOOD_SESSIONS = "goodSessions"
private const val REVIEW_ASKS = "reviewAsks"
private const val REVIEW_LAST = "reviewLast"

/** The Play listing the rating row and the review fallback open. Not
 *  packageName: a debug build carries a ".debug" suffix that has no listing. */
private const val PLAY_PACKAGE = "com.tawny.monitor"

/**
 * Whether the user has asked this app to hold still, read straight from the
 * prefs file for the benefit of the drawn scenes.
 *
 * Only ever called when a view is attached or detached, never per frame — the
 * scenes latch it once and keep it, because a SharedPreferences lookup inside
 * onDraw would cost more than the animation it is trying to save.
 */
private fun stillModeOn(ctx: Context) =
    ctx.getSharedPreferences("tawny", Context.MODE_PRIVATE).getBoolean(STILL_MODE, false)

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var pairOverlay: View? = null
    private var pairChip: View? = null      // way back to the code, over the live view
    /** The words behind the [+], revealed while a finger is on the chip. */
    private var pairChipLabel: TextView? = null
    private var pairChipHide: Runnable? = null
    /** The pairing sheet's own status line, updated as phones come and go. */
    private var pairStatus: TextView? = null
    /**
     * How many Handhelds are watching, and the fixed ceiling. The page is the
     * only writer (Bridge "watching"/"waiting"); the shell never invents a
     * count and has no way to raise the ceiling.
     */
    private var viewersNow = 0
    private var viewersMax = MAX_VIEWERS
    private var isLive = false
    /** When the current live session began, for [countSession]. */
    private var liveSince = 0L
    /** Live only while this phone is the Monitor: forwards its battery to the
     *  page, which mirrors it to every Handheld. */
    private var batteryRx: BroadcastReceiver? = null
    /** Chime playback for the Monitor, on the call's own audio stream (see
     *  startChimeAudio). */
    private var chimePool: SoundPool? = null
    private val chimeIds = HashMap<String, Int>()
    private val chimeFds = mutableListOf<android.content.res.AssetFileDescriptor>()
    private var pendingChime: String? = null
    private var pendingChimeAt = 0L
    /** Dim mode is showing, so the backlight is pinned near-black. */
    private var isDimmed = false

    /** Held for the length of a session; see acquireSessionLocks(). */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var scene: PetSceneView? = null
    private var playScene: PlayfulSceneView? = null   // the animated critters on sessions home
    private var versionView: View? = null             // build stamp / diagnostics hatch

    // Which native screen is up. Flipping the theme recreates the Activity, so
    // this rides along in the instance state and the user comes back to the
    // screen they were on instead of being dropped at the front door.
    private var screen: String = "welcome"
    private var scannerStop: (() -> Unit)? = null
    private var scanHandled = false
    private var pendingScan = false

    private var assetServer: AssetHttpServer? = null
    private var signalServer: SignalServer? = null

    // Horizontal-swipe navigation. Swipe RIGHT = back (the platform convention),
    // swipe LEFT = forward. `null` = no gesture on that edge.
    private var onSwipeBack: (() -> Unit)? = null
    private var onSwipeForward: (() -> Unit)? = null
    private val swipes by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < dp(64) || abs(dx) < abs(dy) * 2 || abs(vx) < 500f) return false
                val act = if (dx > 0) onSwipeBack else onSwipeForward
                act?.let { haptic(); it() }
                return act != null
            }
        })
    }

    private val prefs by lazy { getSharedPreferences("tawny", Context.MODE_PRIVATE) }

    // Bundled fonts (assets/fonts): brush calligraphy for titles, Mukta for UI text.
    private val titleFont by lazy { Typeface.createFromAsset(assets, "fonts/MaShanZheng.ttf") }
    private val uiFont by lazy { Typeface.createFromAsset(assets, "fonts/Mukta-Regular.ttf") }
    private val uiFontSemi by lazy { Typeface.createFromAsset(assets, "fonts/Mukta-SemiBold.ttf") }

    /** Text stops being comfortable to read much past this. */
    private val CONTENT_MAX_DP = 440

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    /** Background work that must not sit on the UI thread. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tawny-io").apply { isDaemon = true }
    }

    private var pendingConsent: (() -> Unit)? = null

    /**
     * What the pending permission request was *for*, as a name that survives a
     * Bundle. The lambda above cannot: if the Activity is recreated while the
     * OS permission dialog is up ("Don't keep activities", memory pressure, a
     * locale or font-scale change), it comes back null and the user lands on a
     * dead screen having just granted camera and microphone.
     */
    private var consentTag: String? = null

    /** Rebuild the post-permission action from its tag. */
    private fun consentAction(tag: String?): (() -> Unit)? = when (tag) {
        "monitor" -> ({ onWatcher() })
        "handheld" -> ({ onHandheld() })
        "live-viewer" -> ({ goLive("viewer") })
        else -> null
    }

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val consent = pendingConsent ?: consentAction(consentTag)
        pendingConsent = null
        consentTag = null
        if (granted.values.all { it }) {
            consent?.invoke()
        } else {
            onPermissionRefused()
        }
        if (pendingScan) {
            pendingScan = false
            if (has(android.Manifest.permission.CAMERA)) showScanner()
        }
    }

    /**
     * A denial used to be a toast and nothing else — and once the user has
     * checked "Don't ask again" the system dialog never appears again, so the
     * app became permanently unusable with no way out. Offer the only route
     * that still works.
     */
    private fun onPermissionRefused() {
        val permanent = !shouldShowRequestPermissionRationale(
            android.Manifest.permission.RECORD_AUDIO
        ) && !shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)
        if (!permanent) {
            toast(getString(R.string.perm_needs_toast))
            return
        }
        themedDialog(
            title = getString(R.string.perm_needed_title),
            body = getString(R.string.perm_needed_body),
            primaryLabel = getString(R.string.perm_open_settings),
            onPrimary = {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                } catch (e: Exception) { toast(getString(R.string.perm_open_settings_failed)) }
            },
            secondaryLabel = getString(R.string.common_not_now)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Edge-to-edge on every API level, not just 35+. Fully transparent
        // scrims: native screens are inset by the listener below, and the live
        // view draws its own gradient behind the bars. auto() still picks the
        // icon contrast from day/night, which refreshSystemBars() then owns.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        Diag.init(applicationContext)
        Hue.load(this)                       // resolves light vs. dark palette
        root = FrameLayout(this).apply { setBackgroundColor(Hue.BG) }
        setContentView(root)

        // Keep content clear of the status and navigation bars.
        // Native screens sit inside the system bars. The live view must not:
        // padding root while the video WebView is mounted framed every call in
        // cream — a bar of page colour above the picture and another below it —
        // instead of letting the video run to the edges under the rail's own
        // gradient scrim, which is what that gradient is for.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (videoIsBehindBars()) v.setPadding(0, 0, 0, 0)
            else v.setPadding(0, b.top, 0, b.bottom)
            lastInsets = b.top to b.bottom
            pushSafeInsets()
            insets
        }

        onBackPressedDispatcher.addCallback(this, backHandler)
        seedWhatsNew()

        // Land on a real screen first, so declining a pairing link below leaves
        // the user somewhere sensible instead of on a blank activity.
        val role = prefs.getString("role", null)
        val key = prefs.getString("channelKey", null)
        val signal = prefs.getString("signalUrl", null)
        val canViewerResume = !key.isNullOrBlank() && (!signal.isNullOrBlank() || hasRendezvous)
        // A recreate (theme flip, system light/dark change) carries the screen
        // in the instance state — honour it instead of re-running the cold-start
        // routing, which would send the user back to the front door.
        savedInstanceState?.let {
            tourStep = it.getInt("tourStep", 0)
            tourFrom = it.getString("tourFrom") ?: "welcome"
            helpFrom = it.getString("helpFrom") ?: "home"
        }
        val resumed = savedInstanceState?.getString("screen")?.let { restoreScreen(it) } == true
        // A permission request that was in flight when the Activity was
        // recreated: keep what it was for, so the grant still leads somewhere.
        consentTag = savedInstanceState?.getString("consentTag")
        pendingScan = savedInstanceState?.getBoolean("pendingScan", false) == true
        when {
            resumed -> Unit
            role == "station" && !key.isNullOrBlank() -> startStationLive()
            role == "viewer" && canViewerResume -> showHandheldHome()
            loadRecentSessions().isNotEmpty() -> showSessionsHome()
            // Onboarding is a one-time thing: it shows on the very first launch
            // and never auto-appears again. A returning user who never finished
            // setting up a session lands straight on the role screen instead.
            prefs.getBoolean("seenWelcome", false) -> showRole()
            else -> showWelcome()
        }

        // Only on a real launch. A recreate (theme or language flip) hands the
        // same launch intent back, and with it the "Connect to …?" dialog for a
        // link that was already answered.
        if (savedInstanceState == null) handlePairLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only the native screens are worth restoring; a live call is rebuilt by
        // the normal resume path instead of being re-entered blind.
        if (!isLive) outState.putString("screen", screen)
        outState.putInt("tourStep", tourStep)
        outState.putString("tourFrom", tourFrom)
        outState.putString("helpFrom", helpFrom)
        consentTag?.let { outState.putString("consentTag", it) }
        if (pendingScan) outState.putBoolean("pendingScan", true)
    }

    /** Re-mount a screen by name after a recreate. False = not restorable. */
    private fun restoreScreen(name: String): Boolean {
        when (name) {
            "welcome" -> showWelcome()
            "role" -> showRole()
            "handheld" -> showHandheldHome()
            "offline" -> showMonitorOffline()
            "diag" -> showDiagnostics()
            "servers" -> showServers()
            "about" -> showAbout()
            "lntip" -> showLightningTip()
            "tour" -> showTour(tourStep)
            "help" -> showHelp()
            // Only if there is still something to list, else fall through to the
            // normal routing rather than showing an empty home.
            "sessions" -> if (loadRecentSessions().isEmpty()) return false else showSessionsHome()
            else -> return false
        }
        return true
    }

    /**
     * The system back button follows the same path as the on-screen arrow. In a
     * live call it asks first — back used to drop straight out of the app and
     * silently kill an unattended Watcher.
     */
    private val backHandler = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val back = onSwipeBack
            when {
                isLive -> confirmEndCall()
                back != null -> { haptic(); back() }
                else -> finish()
            }
        }
    }

    private fun confirmEndCall() {
        val watching = prefs.getString("role", null) == "station"
        themedDialog(
            title = if (watching) getString(R.string.endcall_stop_title) else getString(R.string.endcall_end_title),
            body = if (watching)
                getString(R.string.endcall_stop_body)
            else
                getString(R.string.endcall_end_body),
            primaryLabel = if (watching) getString(R.string.endcall_stop) else getString(R.string.endcall_end),
            onPrimary = {
                saveRecentSession()
                endLive()
                if (watching) stopServers()
                afterSession()
            },
            secondaryLabel = getString(R.string.endcall_keep_going)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairLink(intent)
    }

    // Watch every touch for a horizontal fling without stealing it from the
    // views underneath (taps, vertical scroll, the WebView all still work).
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipes.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Register this screen's swipe-right (back) and swipe-left (forward) actions. */
    private fun swipeNav(back: (() -> Unit)?, forward: (() -> Unit)?) {
        onSwipeBack = back
        onSwipeForward = forward
    }

    private fun haptic() {
        // Nobody is holding the Monitor during a session, and the vibrator
        // motor is the most expensive thing on the phone per millisecond.
        if (isLive) return
        root.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    /**
     * Jump to the session this device already has: the Handheld's "call" home
     * if it's paired, the Watcher's code screen if it's a Watcher. Returns
     * false when nothing is set up yet.
     */
    private fun resumeSession(): Boolean {
        val key = prefs.getString("channelKey", null)
        if (key.isNullOrBlank()) return false
        // A Handheld has a saved role of "viewer", or a LAN signalUrl; anything
        // else with a channel key is a Watcher (it owns the channel it made).
        // The welcome screen parks the role rather than forgetting it: an
        // internet-only Handheld has no signalUrl, so without the parked role it
        // was read as a Watcher and started a second Monitor on its own channel.
        val role = prefs.getString("role", null) ?: prefs.getString(PARKED_ROLE, null)
        val isViewer = role == "viewer" ||
            !prefs.getString("signalUrl", null).isNullOrBlank()
        if (isViewer) {
            prefs.edit().putString("role", "viewer").apply()
            showHandheldHome()
        } else {
            prefs.edit().putString("role", "station").apply()
            startStationLive()
        }
        return true
    }

    /**
     * A `tawny://pair?...` link opened from outside the app (a camera app, a
     * browser, another app). The intent filter is exported and BROWSABLE, so any
     * page or app on the device can fire one of these — accepting it silently
     * would let a hostile link repoint this Handheld at someone else's Watcher
     * and throw away the pairing the user already had. Always ask first; the
     * in-app scanner path ([onScanned]) is the one that needs no confirmation,
     * because there the user deliberately aimed the camera at a code.
     */
    private fun handlePairLink(intent: Intent?): Boolean {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return false
        val p = parsePairing(data.toString()) ?: run {
            themedDialog(
                title = getString(R.string.pair_link_bad_title),
                body = getString(R.string.pair_link_bad_body),
                primaryLabel = getString(R.string.common_ok),
                onPrimary = {}
            )
            return false
        }
        confirmPairing(p)
        return true
    }

    /** "Connect to this monitor?" — the gate on every externally supplied link. */
    private fun confirmPairing(p: Pairing) {
        // Say "expired" before "connect to this?" — asking someone to approve a
        // link that cannot work is a worse dialog than the one that explains.
        if (p.expired) { pairingExpired(); return }
        val paired = prefs.getString("channelKey", null)
        val replacing = !paired.isNullOrBlank() && paired != p.key
        // Deliberately not the host:port. The address is meaningless to the
        // person holding the phone and reads like an error code; what they can
        // actually act on is which network the connection goes over.
        val where = if (p.signal != null) getString(R.string.pair_where_lan)
            else getString(R.string.pair_where_internet)
        themedDialog(
            title = getString(R.string.pair_confirm_title, p.name),
            body = getString(R.string.pair_confirm_body, where,
                if (replacing)
                    getString(R.string.pair_confirm_replacing)
                else
                    getString(R.string.pair_confirm_own)
            ),
            primaryLabel = getString(R.string.common_connect),
            onPrimary = { joinAsHandheld(p) },
            secondaryLabel = getString(R.string.common_not_now)
        )
    }

    /**
     * The single funnel every pairing goes through — the in-app scanner, a
     * pasted link and an external `tawny://pair` intent alike. The expiry check
     * lives here rather than in each caller so a new way in cannot skip it.
     */
    private fun joinAsHandheld(p: Pairing) {
        stopScanner()
        if (p.expired) { pairingExpired(); return }
        // A web Monitor's link carries its own rendezvous, and adopting it is
        // what lets a plain scan of the /setup QR work with nothing pasted into
        // Servers. Two conditions, and both matter:
        //   - the user has not set a relay by hand. Scanning a code must not
        //     silently rewrite a server they deliberately chose.
        //   - the relay is one PairLink can vouch for. A link naming its own
        //     host is not evidence of anything — believing it would let any QR
        //     code from anywhere aim this phone's signalling at a server of the
        //     sender's choosing. See PairLink.relayAdoptable().
        // A relay that fails the second test is still parsed, still shown, and
        // still logged; it just does not get to write itself into settings.
        val adoptRelay = p.relay?.takeIf {
            p.relayTrusted && !strictPrivacy() && customRendezvous().isBlank() && RELAY_URL_RE.matches(it)
        }
        val relayNote = when {
            p.relay == null -> ""
            adoptRelay != null -> " (adopted)"
            strictPrivacy() -> " (not adopted: tighter privacy)"
            !p.relayTrusted -> " (NOT adopted: unrecognised host)"
            else -> " (not adopted: own relay set)"
        }
        Diag.log("shell", "pair accepted name=\"${p.name}\" lan=${p.signal ?: "-"} " +
            "relay=${p.relay ?: "-"}$relayNote " +
            "ticket=${if (p.token.isNullOrBlank()) "MISSING" else "yes"} " +
            "code=${if (p.code == null) "none" else "yes"}")
        prefs.edit()
            .apply { if (p.signal != null) putString("signalUrl", p.signal) else remove("signalUrl") }
            .apply { if (p.token != null) putString("pairToken", p.token) else remove("pairToken") }
            .apply { if (p.code != null) putString("pairCode", p.code) else remove("pairCode") }
            .apply { if (adoptRelay != null) putString(PREF_RENDEZVOUS, adoptRelay) }
            .putString("channelKey", p.key)
            .putString("channelName", p.name)
            .putString("role", "viewer")
            .apply()
        goLive("viewer")
    }

    /**
     * A code that ran out, said the same way wherever it was noticed — this
     * phone's own check on the scanned link, or the Monitor turning the offer
     * away over LAN or the relay (Bridge "error", reason `expired`).
     */
    private fun pairingExpired() {
        Diag.log("shell", "pairing refused — code expired")
        stopScanner()
        themedDialog(
            title = getString(R.string.pair_expired_title),
            body = getString(R.string.pair_expired_body),
            primaryLabel = getString(R.string.pair_scan_again),
            onPrimary = { onHandheld() },
            secondaryLabel = getString(R.string.common_not_now),
            onSecondary = { showRole() }
        )
    }

    /** Manual fallback when the camera can't get a clean read. */
    private fun promptPairLink() {
        val input = EditText(this).apply {
            hint = "tawny://pair?h=\u2026  or  https://\u2026/#k=\u2026"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(Hue.TEXT)
            setHintTextColor(Hue.DIM)
            typeface = uiFont
            textSize = Type.BODY
            background = roundRect(Hue.BG, Hue.LINE)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            minHeight = dp(48)
            layoutParams = lp(topMargin = 18)
            // Prefill from the clipboard for either dialect. A web Monitor's
            // link is `https://<host>/#k=…`, and only offering to prefill the
            // `tawny://` one is how someone who has just copied the web link
            // ends up typing it out by hand.
            clipboardText()?.let {
                val t = it.trim()
                if (t.startsWith("tawny://pair") ||
                    ((t.startsWith("https://") || t.startsWith("http://")) && t.contains("#") && t.contains("k="))
                ) setText(t)
            }
        }
        themedDialog(
            title = getString(R.string.pair_paste_title),
            body = getString(R.string.pair_paste_body),
            primaryLabel = getString(R.string.common_connect),
            onPrimary = {
                val p = parsePairing(input.text.toString())
                if (p == null) {
                    themedDialog(
                        title = getString(R.string.pair_link_bad_title),
                        body = getString(R.string.pair_link_paste_bad_body),
                        primaryLabel = getString(R.string.common_try_again),
                        onPrimary = { promptPairLink() },
                        secondaryLabel = getString(R.string.common_cancel)
                    )
                } else joinAsHandheld(p)
            },
            secondaryLabel = getString(R.string.common_cancel),
            content = input,
        )
    }

    /**
     * The scanner could not get the camera. It used to leave a black rectangle
     * on screen with a toast, and two different strings for the same failure —
     * so the user was stuck looking at nothing. Offer the way in that works.
     */
    private fun cameraUnavailable() {
        Diag.log("shell", "scanner: camera unavailable")
        stopScanner()
        themedDialog(
            title = getString(R.string.cam_busy_title),
            body = getString(R.string.cam_busy_body),
            primaryLabel = getString(R.string.common_paste_a_link),
            onPrimary = { promptPairLink() },
            secondaryLabel = getString(R.string.common_back),
            onSecondary = { showRole() }
        )
    }

    private fun clipboardText(): String? = try {
        getSystemService(android.content.ClipboardManager::class.java)
            ?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    } catch (e: Exception) { null }

    /**
     * Prominent disclosure before the OS permission prompt (Play policy): say
     * plainly what camera + microphone are for and where the media goes, then
     * ask. Runs [then] once the needed permissions are granted (immediately if
     * they already are).
     */
    private fun disclose(needCamera: Boolean, tag: String, then: () -> Unit) {
        val needed = buildList {
            if (needCamera) add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.RECORD_AUDIO)
        }.filter { !has(it) }
        if (needed.isEmpty()) { then(); return }

        val body = if (needCamera)
            getString(R.string.perm_disclose_cam_body)
        else
            getString(R.string.perm_disclose_mic_body)

        themedDialog(
            title = if (needCamera) getString(R.string.perm_disclose_cam_title) else getString(R.string.perm_disclose_mic_title),
            body = body,
            primaryLabel = getString(R.string.common_continue),
            onPrimary = {
                pendingConsent = then
                consentTag = tag
                askPermissions.launch(needed.toTypedArray())
            },
            secondaryLabel = getString(R.string.common_not_now)
        )
    }

    // -------------------------------------------------------- screen frame

    /**
     * The user's own "hold still" switch, from the Animations row on the home
     * and About screens.
     *
     * Android already has a system-wide one, and the app has always honoured it
     * — but reaching it means Developer options or Accessibility, it is worded
     * for the whole phone rather than for this app, and plenty of the phones
     * this matters on are somebody's spare handset that they would rather not
     * go rummaging in. A monitor left running on a cheap phone spends its whole
     * day redrawing pets nobody is looking at; this is the switch that says
     * don't.
     */
    private fun stillMode() = prefs.getBoolean(STILL_MODE, false)

    /**
     * How long an animation should run, as a multiple of its natural duration:
     * the system's animator scale, or a hard 0 when the user has asked this app
     * to hold still. Every animate() in this file is already multiplied by it,
     * so returning 0 here is what actually stops them — see [metaPress],
     * [metaPanel] and the screen cross-fade.
     */
    private val animScale: Float
        get() = if (stillMode()) 0f else try {
            Settings.Global.getFloat(
                contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            )
        } catch (e: Exception) { 1f }

    private fun clearScreen() {
        scene?.stop(); scene = null
        playScene?.stop(); playScene = null
        scannerStop?.invoke(); scannerStop = null
        swipeNav(null, null)
        stopPairCountdown()
        stopOrientationWatch()   // only the live WebView has anything to tell
        (pairOverlay?.parent as? ViewGroup)?.removeView(pairOverlay)
        pairOverlay = null
        pairStatus = null
        pairCountdown = null
        pairQrView = null
        pairLinkView = null
        pairSheetReset = null          // a closure over a sheet that is now gone
        removePairChip()
        viewersNow = 0                 // a new screen knows about nobody
        viewersMax = MAX_VIEWERS
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.loadUrl("about:blank")
            it.destroy()
        }
        web = null
        root.removeAllViews()
        // Mounted first so it sits behind whatever the caller adds next, then
        // lifted to the front once that screen is up — a screen's full-bleed
        // ScrollView would otherwise swallow the long-press. Screens that own
        // the whole surface (live WebView, QR overlay, scanner) hide it.
        versionView = versionTag().also { root.addView(it) }
        root.post { versionView?.let { if (it.parent === root) root.bringChildToFront(it) } }
        // Gentle cross-fade into whatever the caller mounts next.
        root.animate().cancel()
        if (animScale > 0f) {
            root.alpha = 0.35f
            root.animate().alpha(1f).setDuration((150 * animScale).toLong()).start()
        } else {
            root.alpha = 1f
        }
    }

    /**
     * The standard content column, capped at a comfortable reading width.
     *
     * Without the cap the layout is MATCH_PARENT everywhere, which is fine on a
     * phone in portrait and poor everywhere else: in landscape, and on the
     * tablets this app is offered to (every `uses-feature` is optional), body
     * copy ran the full width of the screen and a primary button stretched to
     * 2400px. `body(maxW = 300)` could not help — a TextView's maxWidth loses to
     * a MATCH_PARENT parent.
     */
    private fun column(scroll: Boolean): LinearLayout {
        val pad = dp(24)
        val w = minOf(resources.displayMetrics.widthPixels, dp(CONTENT_MAX_DP))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            if (!scroll) gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(w, if (scroll) WC else MP).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }

    /**
     * Mount a centred column so it still works when it does not fit.
     *
     * The manifest allows rotation (`screenOrientation="fullUser"`), and the
     * welcome, handheld-home and monitor-offline screens each put a large
     * illustration plus a wordmark, body copy and two buttons in a bare
     * LinearLayout. In landscape on a normal phone the buttons went off the
     * bottom of the screen with no way to reach them. `fillViewport` keeps the
     * content optically centred when there is room, and lets it scroll when
     * there is not.
     */
    private fun mountCentered(col: LinearLayout) {
        val w = minOf(resources.displayMetrics.widthPixels, dp(CONTENT_MAX_DP))
        col.layoutParams = FrameLayout.LayoutParams(w, WC).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            addView(col)
        }
        root.addView(scroll)
    }

    private fun gap(h: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(MP, dp(h))
    }

    private fun lp(topMargin: Int = 0, centerH: Boolean = false) =
        LinearLayout.LayoutParams(if (centerH) WC else MP, WC).also {
            it.topMargin = dp(topMargin)
            if (centerH) it.gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun roundRect(fill: Int, stroke: Int, radius: Int = Radius.CONTROL) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(if (fill == 0) Color.TRANSPARENT else fill)
            setStroke(dp(1), stroke)
        }

    /**
     * Wrap a background so the surface actually reacts to a finger.
     *
     * There was not one StateListDrawable or ripple in this file: every button,
     * card and link was a static shape, so a tap produced no feedback at all
     * until the next screen appeared. Hue.RAISE exists for exactly this tier and
     * was unused.
     */
    private fun pressable(
        base: android.graphics.drawable.Drawable,
        radius: Int = Radius.CONTROL,
        tint: Int = Hue.RAISE,
    ): android.graphics.drawable.Drawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(Color.WHITE)
        }
        return android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(rippleColor(tint)), base, mask
        )
    }

    /** A ripple needs alpha; the palette colours are opaque. */
    private fun rippleColor(c: Int) = (c and 0x00FFFFFF) or 0x55000000

    /** Every primary action should also confirm itself in the hand. */
    private fun View.tapFeedback(onClick: () -> Unit) {
        isClickable = true
        isFocusable = true
        setOnClickListener { haptic(); onClick() }
    }

    /**
     * The one dialog in the app, on the same parchment card the rest of the app
     * uses rather than the stock Material AlertDialog.
     *
     * There used to be four: this card, two stock Material `AlertDialog`s (one
     * of them built on the *framework* class rather than the AppCompat one used
     * everywhere else), and a hand-copied variant in promptRoomName that existed
     * only because this had nowhere to put an input. `content` is that slot.
     *
     * @param content an optional view (a text field, say) placed between the
     *   body and the buttons.
     */
    private fun themedDialog(
        title: String,
        body: String,
        primaryLabel: String,
        onPrimary: () -> Unit,
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null,
        cancelable: Boolean = true,
        content: View? = null,
        onShow: ((AlertDialog) -> Unit)? = null,
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(Hue.PANEL, Hue.LINE, Radius.CARD)
            val p = dp(22); setPadding(p, p, p, p)
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(Hue.TEXT)
            textSize = Type.DIALOG
            typeface = uiFontSemi
            setLineSpacing(0f, Type.LEAD_TIGHT)
        })
        if (body.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = body
                setTextColor(Hue.DIM)
                textSize = Type.SUB
                typeface = uiFont
                setLineSpacing(0f, Type.LEAD_BODY)
                setPadding(0, dp(12), 0, 0)
            })
        }
        if (content != null) card.addView(content)

        // A long body on a small screen used to run off the bottom of the card.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(card)
        }
        val wrap = FrameLayout(this).apply { val m = dp(16); setPadding(m, m, m, m); addView(scroll) }
        val dialog = AlertDialog.Builder(this).setView(wrap).setCancelable(cancelable).create()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.82f)
        }
        card.addView(primary(primaryLabel) { dialog.dismiss(); onPrimary() })
        if (secondaryLabel != null) {
            card.addView(link(secondaryLabel) { dialog.dismiss(); onSecondary?.invoke() })
        }
        dialog.show()
        onShow?.invoke(dialog)
    }

    // -------------------------------------------------------- widgets

    private fun wordmark(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = lp(topMargin = 4)
        addView(TextView(this@MainActivity).apply {
            text = "Tawny"
            setTextColor(Hue.TEXT)
            textSize = 46f
            letterSpacing = 0.03f
            typeface = titleFont
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.pet_monitor)
            setTextColor(Hue.DIM)
            textSize = 15f
            letterSpacing = 0.04f
            typeface = uiFont
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun body(s: String, maxW: Int = 0) = TextView(this).apply {
        text = s
        setTextColor(Hue.DIM)
        textSize = 16f
        typeface = uiFont
        gravity = Gravity.CENTER
        if (maxW > 0) maxWidth = dp(maxW)
        setLineSpacing(0f, 1.45f)
        layoutParams = lp(topMargin = 16)
    }

    private fun heading(title: String, sub: String, center: Boolean = false) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp()
            val g = if (center) Gravity.CENTER_HORIZONTAL else Gravity.START
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Hue.TEXT)
                textSize = Type.TITLE
                letterSpacing = 0f
                typeface = uiFontSemi
                gravity = g
                setLineSpacing(0f, 1.2f)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub
                setTextColor(Hue.DIM)
                textSize = Type.SUB
                typeface = uiFont
                gravity = g
                setLineSpacing(0f, 1.4f)
                setPadding(0, dp(5), 0, 0)
            })
        }

    private fun pill(label: String, fg: Int, fill: Int, stroke: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            letterSpacing = 0.04f
            textSize = Type.PILL
            typeface = uiFontSemi
            setTextColor(fg)
            gravity = Gravity.CENTER
            // A filled pill ripples light, an outlined one ripples in the accent.
            background = pressable(
                roundRect(fill, stroke),
                tint = if (fill == 0) stroke else Hue.ON_ACCENT
            )
            setPadding(dp(18), dp(15), dp(18), dp(15))
            layoutParams = lp(topMargin = 12)
            tapFeedback(onClick)
        }

    /** The outlined counterpart to primary(). Was hand-inlined at each use. */
    private fun ghost(label: String, onClick: () -> Unit) =
        pill(label, Hue.BERRY, 0, Hue.BERRY, onClick)

    private fun primary(label: String, onClick: () -> Unit) =
        pill(label, Hue.ON_ACCENT, Hue.BERRY, Hue.BERRY, onClick)

    private fun link(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        letterSpacing = 0.02f
        textSize = Type.SUB
        typeface = uiFont
        setTextColor(Hue.DIM)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(48)                       // Android's minimum touch target
        background = pressable(roundRect(0, Color.TRANSPARENT))
        layoutParams = lp(topMargin = 14, centerH = true)
        tapFeedback(onClick)
    }

    /**
     * Press feedback for a meta row.
     *
     * The two rows used to share one treatment — ripple plus a flat 97% squeeze
     * — so "Support Tawny" and "About Tawny" read as the same control printed
     * twice. Three things now differ, and they are deliberately the *only*
     * three: these sit below the fold under a live pet list and an animated
     * scene, and must not compete with either.
     *
     *  1. the row still dips, but only to 98.5% — the icon and the glyph now
     *     carry the motion, so the slab moving as well would be noise;
     *  2. the icon reacts in character: a double-thump for the heart, a single
     *     hard zap for the lightning bolt ([IconView.strike]), one soft swell
     *     for anything else ([IconView.beat]);
     *  3. the trailing glyph nudges the way it points — "↗" up and out of the
     *     row because it leaves the app, "›" straight along because it does not.
     *
     * Non-consuming, so the click still fires and the ripple still draws.
     * Everything is scaled by [animScale], so "remove animations" stops it dead.
     */
    private fun metaPress(
        row: View, icon: IconView, trail: View, kind: String, glyph: String,
        wipe: WipeLabel? = null,
    ) {
        val leaves = glyph == "↗"
        val nx = dp(if (leaves) 3 else 4).toFloat()
        val ny = if (leaves) -dp(3).toFloat() else 0f
        row.setOnTouchListener { v, e ->
            val a = animScale
            if (a > 0f) when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.985f).scaleY(0.985f)
                        .setDuration((90 * a).toLong()).start()
                    trail.animate().translationX(nx).translationY(ny)
                        .setDuration((140 * a).toLong())
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                    if (kind == "bolt") icon.strike(a) else icon.beat(kind == "heart", a)
                    wipe?.sweep(1f, a)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration((260 * a).toLong())
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
                        .start()
                    trail.animate().translationX(0f).translationY(0f)
                        .setDuration((340 * a).toLong())
                        .setInterpolator(android.view.animation.OvershootInterpolator(3.0f))
                        .start()
                    // Let the reveal land for a beat before it wipes back — on
                    // ACTION_UP the click also fires and the browser opens, so
                    // this mostly plays as the app leaves.
                    wipe?.let { w -> w.postDelayed({ w.sweep(0f, a) }, (130 * a).toLong()) }
                }
            }
            false
        }
    }

    /**
     * A secondary "meta" row — icon, label, trailing glyph — on the app's own
     * card surface. Used for the Support / About / contact links, which were
     * bare grey text before and read as an afterthought next to the styled
     * cards around them.
     */
    private fun metaRow(
        kind: String, label: String, fg: Int, trail: String,
        sub: String? = null, reveal: String? = null, onClick: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(roundRect(0, Color.TRANSPARENT, 0), 0, Hue.BERRY)
        val ph = dp(16); val pv = dp(15)
        setPadding(ph, pv, ph, pv)
        minimumHeight = dp(54)
        // Held rather than added inline: metaPress animates both of them, and
        // the beat needs the IconView's own type, not a bare View.
        //
        // Only the rows that are *asking* for something carry an accent.
        // "About" and "Email us" are neutral errands, and painting their glyphs
        // the same berry as the rows that do ask flattened them all into a
        // stack of equally-loud buttons — the glyph shouted while the label
        // beside it sat in plain body colour. Tinting them DIM lines each glyph
        // up with its own label and trailing chevron.
        //
        // The two asks then get one accent each, because they are two different
        // offers and not one repeated: the Ko-fi heart is BERRY, the Lightning
        // bolt is SKY. Same panel, same shape, unmistakably not the same thing.
        val icon = IconView(
            this@MainActivity, kind, behind = Hue.PANEL,
            tint = when (kind) {
                "heart" -> Hue.BERRY
                "bolt" -> Hue.SKY
                else -> Hue.DIM
            },
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(19), dp(19))
        }
        addView(icon)
        val wipe = reveal?.let {
            WipeLabel(
                this@MainActivity, label, it, uiFontSemi,
                android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, Type.SUB, resources.displayMetrics
                ),
                fg,
            )
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).also { it.leftMargin = dp(13) }
            addView(wipe ?: TextView(this@MainActivity).apply {
                text = label
                setTextColor(fg)
                textSize = Type.SUB
                typeface = uiFontSemi
                letterSpacing = 0.01f
                maxLines = 1
            })
            if (sub != null) addView(TextView(this@MainActivity).apply {
                text = sub
                setTextColor(Hue.DIM)
                textSize = 12.5f
                typeface = uiFont
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(2), 0, 0)
            })
        })
        val glyph = TextView(this@MainActivity).apply {
            text = trail
            setTextColor(Hue.DIM)
            textSize = 18f
            typeface = uiFontSemi
        }
        addView(glyph)
        isClickable = true; isFocusable = true
        metaPress(this, icon, glyph, kind, trail, wipe)
        setOnClickListener { haptic(); onClick() }
    }

    /**
     * The Animations row, for the two panels that carry it.
     *
     * A row rather than a switch widget because everything else in these panels
     * is a row, and because the state belongs in the trailing slot where the
     * eye is already going for the chevron: "Animations … On". Tapping it flips
     * the pref and rebuilds the screen it is on — [rebuild] — which is both how
     * the row repaints itself and how the scene above it stops, since the drawn
     * views latch the setting when they are attached.
     */
    private fun motionRow(rebuild: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(roundRect(0, Color.TRANSPARENT, 0), 0, Hue.BERRY)
        val ph = dp(16); val pv = dp(15)
        setPadding(ph, pv, ph, pv)
        minimumHeight = dp(54)
        addView(IconView(this@MainActivity, "motion", behind = Hue.PANEL, tint = Hue.DIM).apply {
            layoutParams = LinearLayout.LayoutParams(dp(19), dp(19))
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).also { it.leftMargin = dp(13) }
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.motion_title)
                setTextColor(Hue.TEXT)
                textSize = Type.SUB
                typeface = uiFontSemi
                letterSpacing = 0.01f
                maxLines = 1
            })
            addView(TextView(this@MainActivity).apply {
                // The state is the switch's job, so this says what the setting
                // *does* rather than repeating "on" in a second voice.
                text = if (stillMode()) getString(R.string.motion_still)
                else getString(R.string.motion_on)
                setTextColor(Hue.DIM)
                textSize = 12.5f
                typeface = uiFont
                setPadding(0, dp(2), 0, 0)
            })
        })
        val knob = SwitchMark(this@MainActivity, !stillMode()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(28))
                .also { it.leftMargin = dp(12) }
        }
        addView(knob)
        isClickable = true; isFocusable = true
        contentDescription = getString(R.string.a11y_animations)
        // Flip the knob under the finger, then rebuild. Without the first half
        // the switch would appear to lag a whole screen rebuild behind the tap.
        setOnClickListener {
            haptic()
            knob.on = stillMode()          // about to become the new value
            prefs.edit().putBoolean(STILL_MODE, !stillMode()).apply()
            rebuild()
        }
    }

    /**
     * One rounded panel grouping the meta rows, hairline-divided. Sits well
     * clear of whatever is above it — it is a change of subject, not another
     * item in the same list.
     *
     * The rows rise and fade in on a stagger. It is a small thing, but it is
     * what stops the panel reading as a dead slab of two identical lines: the
     * eye gets told there are two of them, in order, and then it is over. Kept
     * to ~9dp and a third of a second — this is below the fold, under a list of
     * live sessions and an animated scene, and it must not pull rank on either.
     * Skipped entirely when the system animator scale is 0 ("remove
     * animations"), which also leaves alpha at 1 so nothing can strand
     * invisible.
     */
    private fun metaPanel(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(Hue.PANEL, Hue.LINE, Radius.CARD)
        clipToOutline = true
        layoutParams = lp(topMargin = 34)
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MP, dp(1))
                setBackgroundColor(Hue.LINE)
            })
            addView(r)
        }
        val a = animScale
        if (a > 0f) rows.forEachIndexed { i, r ->
            r.alpha = 0f
            r.translationY = dp(9).toFloat()
            r.animate().alpha(1f).translationY(0f)
                .setStartDelay(((70 * i + 90) * a).toLong())
                .setDuration((320 * a).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f))
                .start()
        }
    }

    /**
     * Tiny build stamp in the bottom-left of every native screen. It exists so
     * you can glance at each phone and see they are all running the same
     * deploy — `tools/tawny-bump` moves it, `tools/tawny-deploy` ships it.
     */
    private fun versionTag(): View = TextView(this).apply {
        text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}"
        textSize = 10f
        typeface = uiFont
        setTextColor(Hue.DIM)
        alpha = 0.5f
        letterSpacing = 0.06f
        contentDescription = getString(R.string.a11y_app_version)
        layoutParams = FrameLayout.LayoutParams(WC, WC).also {
            it.gravity = Gravity.START or Gravity.BOTTOM
            it.leftMargin = dp(12); it.bottomMargin = dp(8)
        }
        // Deliberately hidden behind a long-press: a support hatch, not a feature.
        isLongClickable = true
        setOnLongClickListener { haptic(); diagFromHelp = false; showDiagnostics(); true }
    }

    /**
     * The bug reporter. Shows the flight recorder ([Diag]) and hands it off —
     * "Send report" opens the share sheet so the log can be mailed or messaged
     * out from a phone that is on cellular and unreachable by adb.
     */
    private fun showDiagnostics() {
        clearScreen()
        screen = "diag"
        val diagBack = { if (diagFromHelp) showHelp() else afterSession() }
        swipeNav(back = diagBack, forward = null)
        val report = Diag.dump().ifBlank { getString(R.string.diag_none) }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16); setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(MP, MP)
        }
        outer.addView(backLink { diagBack() })
        outer.addView(heading(getString(R.string.diag_title), getString(R.string.diag_subtitle)))

        // Vertical scroller wrapping a horizontal one: the lines are long and
        // must not wrap, so the log pans in both directions.
        val vScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, 0, 1f).also { it.topMargin = dp(12) }
            background = roundRect(Hue.PANEL, Hue.LINE, Radius.CARD)
            val p = dp(12); setPadding(p, p, p, p)
        }
        vScroll.addView(HorizontalScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, WC)
            addView(TextView(this@MainActivity).apply {
                text = report
                setTextColor(Hue.TEXT)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setHorizontallyScrolling(true)
                layoutParams = FrameLayout.LayoutParams(WC, WC)
            })
        })
        outer.addView(vScroll)

        val shareOut = {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_share_subject, BuildConfig.VERSION_NAME))
                        putExtra(Intent.EXTRA_TEXT, report)
                    },
                    getString(R.string.diag_share_chooser)
                )
            )
        }
        // Under tighter privacy the log goes nowhere this app picks: the share
        // sheet only, to wherever the user sends it.
        if (builtInRendezvous().isNotBlank()) {
            outer.addView(primary(getString(R.string.diag_send_to_tawny)) { sendReport(report, shareOut) })
            outer.addView(TextView(this).apply {
                text = getString(R.string.diag_send_body)
                setTextColor(Hue.DIM)
                textSize = 12.5f
                typeface = uiFont
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.35f)
                layoutParams = lp(topMargin = 8, centerH = true).also { it.leftMargin = dp(12); it.rightMargin = dp(12) }
            })
            outer.addView(link(getString(R.string.diag_send_another_way)) { shareOut() })
        } else {
            outer.addView(primary(getString(R.string.diag_send_report)) { shareOut() })
        }
        outer.addView(link(getString(R.string.diag_copy)) {
            copyToClipboard("Tawny diagnostics", report)
            toast(getString(R.string.diag_copied))
        })
        outer.addView(link(getString(R.string.diag_clear)) {
            themedDialog(
                title = getString(R.string.diag_clear_title),
                body = getString(R.string.diag_clear_body),
                primaryLabel = getString(R.string.common_clear),
                onPrimary = { Diag.clear(); Diag.init(applicationContext); showDiagnostics() },
                secondaryLabel = getString(R.string.diag_keep)
            )
        })
        // The other thing behind this hatch. Same reasoning as the hatch
        // itself: a support surface, not a feature.
        outer.addView(link(
            if (strictPrivacy()) getString(R.string.servers_meta_strict)
            else if (customRendezvous().isNotBlank()) getString(R.string.servers_meta_using_own)
            else getString(R.string.servers_meta_default)
        ) { showServers() })
        root.addView(outer)
    }

    /**
     * Point Tawny at your own rendezvous / TURN, at runtime.
     *
     * `tawny.rendezvousUrl` in `local.properties` has always existed, but it is
     * a build-time property — so the only person who could use their own relay
     * was whoever compiled the APK. Everyone who installs the app had no way at
     * all, which for a project whose whole pitch is "your video does not go
     * through anybody's cloud" is the wrong way round.
     *
     * Normally nothing here can take the remote path down. A custom relay is
     * *preferred*, never substituted: if it does not answer, the page falls
     * back to the built-in tunnel and says so (see openSignal/fetchIce in
     * public/app.js). Blank fields mean "use the defaults", which is also the
     * reset.
     *
     * "For tighter privacy" turns every one of those safety nets off, on
     * purpose — see [PREF_STRICT]. The switch is held on this screen until Save,
     * like the fields, so nothing half-applies; turning it on asks first, and
     * Save repeats back exactly what this phone will and will not contact.
     */
    private fun showServers() {
        clearScreen()
        screen = "servers"
        swipeNav(back = { showDiagnostics() }, forward = null)

        // The screen's own copy of the tighter-privacy settings. Written to prefs
        // on Save only.
        var strict = strictPrivacy()
        var turnMode = strictTurnMode()
        var turnFetch = prefs.getBoolean(PREF_TURN_FETCH, true)
        var lanPath = prefs.getBoolean(PREF_LAN_PATH, true)

        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP) }
        val col = column(scroll = true)
        col.addView(backLink { showDiagnostics() })
        col.addView(heading(getString(R.string.servers_title), getString(R.string.servers_subtitle)))
        val intro = aboutBody(getString(R.string.servers_body))
        col.addView(intro)
        fun tipCard(title: String, body: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(Hue.BG, Hue.LINE, Radius.CARD)
            val p = dp(14); setPadding(p, p, p, p)
            layoutParams = lp(topMargin = 18)
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Hue.TEXT)
                textSize = Type.LABEL
                letterSpacing = 0.04f
                typeface = uiFontSemi
            })
            addView(TextView(this@MainActivity).apply {
                text = body
                setTextColor(Hue.DIM)
                textSize = 13f
                typeface = uiFont
                setLineSpacing(0f, 1.4f)
                layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.topMargin = dp(6) }
            })
        }
        val tailscaleTip = tipCard(
            getString(R.string.servers_tailscale_title),
            getString(R.string.servers_tailscale_body)
        )
        col.addView(tailscaleTip)

        /** A title, a line saying what it does, and a drawn switch. */
        fun switchRow(title: String, sub: String, on: Boolean, onFlip: (SwitchMark) -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pressable(roundRect(0, Color.TRANSPARENT, 0), 0, Hue.BERRY)
                setPadding(0, dp(12), 0, dp(12))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        setTextColor(Hue.TEXT)
                        textSize = Type.SUB
                        typeface = uiFontSemi
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = sub
                        setTextColor(Hue.DIM)
                        textSize = 12.5f
                        typeface = uiFont
                        setLineSpacing(0f, 1.3f)
                        setPadding(0, dp(2), 0, 0)
                    })
                })
                val knob = SwitchMark(this@MainActivity, on).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(46), dp(28))
                        .also { it.leftMargin = dp(12) }
                }
                addView(knob)
                isClickable = true; isFocusable = true
                contentDescription = title
                setOnClickListener { haptic(); onFlip(knob) }
            }

        // ---- the tighter-privacy switch, above everything it changes ----------
        val privacyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14); setPadding(p, dp(4), p, p)
            layoutParams = lp(topMargin = 18)
        }
        val privacyState = TextView(this).apply {
            setTextColor(Hue.LIVE)
            textSize = 12.5f
            typeface = uiFontSemi
            setLineSpacing(0f, 1.35f)
        }
        col.addView(privacyCard)

        // Built before the fields so the switch can show and hide them.
        val strictBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(topMargin = 8)
        }

        // input -> the small red line under it. Populated by field(), read by
        // markInvalid()/clearInvalid() so Save can point at exactly the field
        // that's wrong instead of one dialog with no way to tell which line it
        // meant.
        val errorFor = HashMap<EditText, TextView>()

        fun clearInvalid(input: EditText) {
            input.background = roundRect(Hue.BG, Hue.LINE)
            errorFor[input]?.visibility = View.GONE
        }

        fun markInvalid(input: EditText, message: String) {
            input.background = roundRect(Hue.BG, Hue.LIVE)
            errorFor[input]?.apply { text = message; visibility = View.VISIBLE }
        }

        fun field(label: String, hint: String, key: String, password: Boolean = false): EditText {
            col.addView(TextView(this).apply {
                text = label
                setTextColor(Hue.DIM)
                textSize = Type.LABEL
                letterSpacing = 0.1f
                typeface = uiFontSemi
                layoutParams = lp(topMargin = 20)
            })
            val input = EditText(this).apply {
                this.hint = hint
                inputType = if (password)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else
                    InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setTextColor(Hue.TEXT)
                setHintTextColor(Hue.DIM)
                typeface = uiFont
                textSize = Type.BODY
                background = roundRect(Hue.BG, Hue.LINE)
                setPadding(dp(14), dp(13), dp(14), dp(13))
                minHeight = dp(48)
                layoutParams = lp(topMargin = 6)
                setText(prefs.getString(key, ""))
            }
            col.addView(input)
            val error = TextView(this).apply {
                setTextColor(Hue.LIVE)
                textSize = 12.5f
                typeface = uiFont
                setLineSpacing(0f, 1.3f)
                visibility = View.GONE
                layoutParams = lp(topMargin = 4)
            }
            col.addView(error)
            errorFor[input] = error
            // The red is a "fix this", not a permanent verdict — it goes away
            // the moment the user acts on the field, before they even try Save
            // again.
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = clearInvalid(input)
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            return input
        }

        val rvIn = field("Rendezvous", "wss://relay.example.net", PREF_RENDEZVOUS)

        col.addView(TextView(this).apply {
            text = getString(R.string.servers_stunturn_title)
            setTextColor(Hue.TEXT)
            textSize = Type.SUB
            typeface = uiFontSemi
            layoutParams = lp(topMargin = 28)
        })
        val stunTurnBody = aboutBody(
            getString(R.string.servers_stunturn_body)
        ).apply { layoutParams = lp(topMargin = 6) }
        col.addView(stunTurnBody)

        val stunIn = field(getString(R.string.servers_stun_label), "stun:stun.example.net:3478", PREF_STUN)
        val turnIn = field(getString(R.string.servers_turn_label), "turns:turn.example.net:5349", PREF_TURN)
        // No asterisk: these are not unconditionally required, only alongside a
        // TURN address (enforced below) — a static "*" next to both would have
        // read as "fill this in regardless," which is exactly the ambiguity
        // that sent Tailscale users looking for TURN credentials they never
        // needed.
        val userIn = field(getString(R.string.servers_turn_user_label), getString(R.string.servers_turn_blank_hint), PREF_TURN_USER)
        val passIn = field(getString(R.string.servers_turn_pass_label), getString(R.string.servers_turn_blank_hint), PREF_TURN_PASS, password = true)

        // ---- the controls only tighter privacy has ----------------------------
        strictBox.addView(TextView(this).apply {
            text = getString(R.string.privacy_controls_title)
            setTextColor(Hue.TEXT)
            textSize = Type.SUB
            typeface = uiFontSemi
            layoutParams = lp(topMargin = 20)
        })
        strictBox.addView(aboutBody(getString(R.string.privacy_controls_body))
            .apply { layoutParams = lp(topMargin = 6) })

        strictBox.addView(TextView(this).apply {
            text = getString(R.string.privacy_turn_mode_label)
            setTextColor(Hue.DIM)
            textSize = Type.LABEL
            letterSpacing = 0.1f
            typeface = uiFontSemi
            layoutParams = lp(topMargin = 20)
        })
        val modeNote = TextView(this).apply {
            setTextColor(Hue.DIM)
            textSize = 12.5f
            typeface = uiFont
            setLineSpacing(0f, 1.35f)
            layoutParams = lp(topMargin = 8)
        }
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(topMargin = 8)
        }
        val modeLabels = mapOf(
            "auto" to getString(R.string.privacy_turn_auto),
            "always" to getString(R.string.privacy_turn_always),
            "never" to getString(R.string.privacy_turn_never)
        )
        val modeNotes = mapOf(
            "auto" to getString(R.string.privacy_turn_auto_note),
            "always" to getString(R.string.privacy_turn_always_note),
            "never" to getString(R.string.privacy_turn_never_note)
        )
        val pills = HashMap<String, TextView>()
        fun paintModes() {
            for ((m, v) in pills) {
                val sel = m == turnMode
                v.background = roundRect(if (sel) Hue.BERRY else Hue.BG, if (sel) Hue.BERRY else Hue.LINE, Radius.CARD)
                v.setTextColor(if (sel) Hue.ON_ACCENT else Hue.TEXT)
            }
            modeNote.text = modeNotes[turnMode]
        }
        for (m in TURN_MODES) {
            val pill = TextView(this).apply {
                text = modeLabels[m]
                gravity = Gravity.CENTER
                textSize = 13.5f
                typeface = uiFontSemi
                setPadding(dp(6), dp(11), dp(6), dp(11))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f).also {
                    if (m != TURN_MODES.first()) it.leftMargin = dp(8)
                }
                isClickable = true; isFocusable = true
                setOnClickListener { haptic(); turnMode = m; paintModes() }
            }
            pills[m] = pill
            modeRow.addView(pill)
        }
        strictBox.addView(modeRow)
        strictBox.addView(modeNote)
        paintModes()

        strictBox.addView(switchRow(
            getString(R.string.privacy_turn_fetch_title),
            getString(R.string.privacy_turn_fetch_sub),
            turnFetch
        ) { knob -> turnFetch = !turnFetch; knob.on = turnFetch }.apply { layoutParams = lp(topMargin = 12) })
        strictBox.addView(switchRow(
            getString(R.string.privacy_lan_title),
            getString(R.string.privacy_lan_sub),
            lanPath
        ) { knob -> lanPath = !lanPath; knob.on = lanPath })
        col.addView(strictBox)

        val note = TextView(this).apply {
            setTextColor(Hue.DIM)
            textSize = 12.5f
            typeface = uiFont
            setLineSpacing(0f, 1.35f)
            layoutParams = lp(topMargin = 14)
        }
        col.addView(note)

        /** Everything that reads differently once tighter privacy is on. */
        fun paintStrict() {
            strictBox.visibility = if (strict) View.VISIBLE else View.GONE
            tailscaleTip.visibility = if (strict) View.GONE else View.VISIBLE
            intro.text = getString(if (strict) R.string.privacy_servers_body else R.string.servers_body)
            stunTurnBody.text = getString(if (strict) R.string.privacy_stunturn_body else R.string.servers_stunturn_body)
            privacyCard.background = roundRect(Hue.BG, if (strict) Hue.LIVE else Hue.LINE, Radius.CARD)
            privacyState.text = getString(if (strict) R.string.privacy_state_on else R.string.privacy_state_off)
            rvIn.hint = if (strict) getString(R.string.privacy_rv_hint) else "wss://relay.example.net"
            stunIn.hint = if (strict) getString(R.string.privacy_stun_hint) else "stun:stun.example.net:3478"
            note.text = when {
                strict -> getString(R.string.privacy_note_on)
                customRendezvous().isNotBlank() -> getString(R.string.servers_using_yours_toast)
                else -> getString(R.string.servers_using_tawny_toast) +
                    (if (BuildConfig.RENDEZVOUS_URL.isBlank()) getString(R.string.servers_none_lan_only) else ".")
            }
        }

        privacyCard.addView(switchRow(
            getString(R.string.privacy_toggle_title),
            getString(R.string.privacy_toggle_sub),
            strict
        ) { knob ->
            if (strict) {
                // Off needs no ceremony: it only puts safety nets back.
                strict = false; knob.on = false; paintStrict()
            } else {
                themedDialog(
                    title = getString(R.string.privacy_confirm_title),
                    body = getString(R.string.privacy_confirm_body),
                    primaryLabel = getString(R.string.privacy_confirm_yes),
                    onPrimary = { strict = true; knob.on = true; paintStrict() },
                    secondaryLabel = getString(R.string.privacy_confirm_no)
                )
            }
        })
        privacyCard.addView(privacyState)
        paintStrict()

        col.addView(primary(getString(R.string.common_save)) {
            val rv = rvIn.text.toString().trim()
            val stunList = stunIn.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val turnList = turnIn.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val userVal = userIn.text.toString().trim()
            val passVal = passIn.text.toString()

            listOf(rvIn, stunIn, turnIn, userIn, passIn).forEach { clearInvalid(it) }
            var firstBad: EditText? = null
            val issues = StringBuilder()
            fun flag(input: EditText, message: String) {
                markInvalid(input, message)
                issues.append("• ").append(message).append('\n')
                if (firstBad == null) firstBad = input
            }

            if (rv.isNotEmpty() && !RELAY_URL_RE.matches(rv))
                flag(rvIn, getString(R.string.servers_err_rendezvous))
            val stunBad = stunList.filterNot { STUN_URL_RE.matches(it) }
            if (stunBad.isNotEmpty())
                flag(stunIn, getString(R.string.servers_err_stun, stunBad.first()))
            val turnBad = turnList.filterNot { TURN_URL_RE.matches(it) }
            if (turnBad.isNotEmpty())
                flag(turnIn, getString(R.string.servers_err_turn, turnBad.first()))
            // A TURN server with no credentials will not authenticate a real
            // caller, and half a credential pair is never valid either way — so
            // these three fields are mandatory together, or not at all.
            if (turnList.isNotEmpty() && userVal.isEmpty())
                flag(userIn, getString(R.string.servers_err_turn_user))
            if (turnList.isNotEmpty() && passVal.isEmpty())
                flag(passIn, getString(R.string.servers_err_turn_pass))
            if (turnList.isEmpty() && (userVal.isNotEmpty() || passVal.isNotEmpty()))
                flag(turnIn, getString(R.string.servers_err_turn_orphan))
            // Under tighter privacy the only checks left are the ones where the
            // combination cannot work at all — everything else is the user's
            // call, including a setup that only works on their own Wi-Fi.
            if (strict && rv.isEmpty() && !lanPath)
                flag(rvIn, getString(R.string.privacy_err_no_path))
            if (strict && turnMode == "always" && turnList.isEmpty() && (!turnFetch || rv.isEmpty()))
                flag(turnIn, getString(R.string.privacy_err_always_no_turn))

            if (issues.isNotEmpty()) {
                themedDialog(
                    title = getString(R.string.servers_check_fields_title),
                    body = issues.toString().trim(),
                    primaryLabel = getString(R.string.common_back), onPrimary = { firstBad?.requestFocus() }
                )
                return@primary
            }
            prefs.edit()
                .putString(PREF_RENDEZVOUS, rv)
                .putString(PREF_STUN, stunIn.text.toString().trim())
                .putString(PREF_TURN, turnIn.text.toString().trim())
                .putString(PREF_TURN_USER, userVal)
                .putString(PREF_TURN_PASS, passVal)
                .putBoolean(PREF_STRICT, strict)
                .putString(PREF_TURN_MODE, turnMode)
                .putBoolean(PREF_TURN_FETCH, turnFetch)
                .putBoolean(PREF_LAN_PATH, lanPath)
                .apply()
            Diag.log("shell", "servers saved rv=${rv.ifBlank { "default" }}" +
                if (strict) " strict turn=$turnMode fetch=$turnFetch lan=$lanPath stun=${stunList.size}" else "")
            // The asset server bakes the allowed relay hosts into its CSP, and
            // the live page has already read the old settings — so both are
            // rebuilt on the next session rather than patched underneath one.
            stopServers()
            if (strict) {
                themedDialog(
                    title = getString(R.string.privacy_saved_title),
                    body = strictSummary(rv, stunList, turnList, turnMode, turnFetch, lanPath),
                    primaryLabel = getString(R.string.common_ok), onPrimary = { showDiagnostics() }
                )
                return@primary
            }
            val usingOwn = rv.isNotBlank() || turnList.isNotEmpty() || stunIn.text.toString().isNotBlank()
            themedDialog(
                title = if (rv.isNotBlank() && rv.startsWith("ws://")) getString(R.string.servers_saved_warning_title) else getString(R.string.servers_saved),
                body = if (rv.isNotBlank() && rv.startsWith("ws://"))
                    getString(R.string.servers_saved_ws_body)
                else if (usingOwn)
                    getString(R.string.servers_saved_yours_body)
                else getString(R.string.servers_saved_tawny_body),
                primaryLabel = getString(R.string.common_ok), onPrimary = { showDiagnostics() }
            )
        })
        col.addView(link(getString(R.string.servers_use_tawny)) {
            themedDialog(
                title = getString(R.string.servers_back_title),
                body = getString(
                    if (strictPrivacy()) R.string.privacy_back_body else R.string.servers_back_body
                ),
                primaryLabel = getString(R.string.common_clear),
                onPrimary = {
                    prefs.edit()
                        .remove(PREF_RENDEZVOUS).remove(PREF_STUN).remove(PREF_TURN)
                        .remove(PREF_TURN_USER).remove(PREF_TURN_PASS)
                        .remove(PREF_STRICT).remove(PREF_TURN_MODE)
                        .remove(PREF_TURN_FETCH).remove(PREF_LAN_PATH)
                        .apply()
                    stopServers()
                    toast(getString(R.string.servers_back_toast))
                    showServers()
                },
                secondaryLabel = getString(R.string.servers_keep_them)
            )
        })
        col.addView(gap(16))
        scroll.addView(col)
        root.addView(scroll)
    }

    /**
     * What this phone will contact, and what it no longer will, in plain words.
     * Shown on Save so the last thing the user reads before leaving is the
     * whole consequence of the switch, not a generic "Saved".
     */
    private fun strictSummary(
        rv: String, stun: List<String>, turn: List<String>,
        turnMode: String, turnFetch: Boolean, lanPath: Boolean
    ): String {
        val none = getString(R.string.privacy_sum_none)
        val sb = StringBuilder(getString(R.string.privacy_sum_intro)).append("\n\n")
        sb.append("• ").append(getString(R.string.privacy_sum_rv, rv.ifBlank { getString(R.string.privacy_sum_rv_none) })).append('\n')
        sb.append("• ").append(getString(R.string.privacy_sum_stun, if (stun.isEmpty()) none else stun.joinToString(", "))).append('\n')
        val turnDesc = when {
            turnMode == "never" -> getString(R.string.privacy_sum_turn_never)
            else -> (turn + (if (turnFetch && rv.isNotBlank()) listOf(getString(R.string.privacy_sum_turn_from_rv)) else emptyList()))
                .ifEmpty { listOf(none) }.joinToString(", ") +
                (if (turnMode == "always") getString(R.string.privacy_sum_turn_always) else "")
        }
        sb.append("• ").append(getString(R.string.privacy_sum_turn, turnDesc)).append('\n')
        sb.append("• ").append(getString(if (lanPath) R.string.privacy_sum_lan_on else R.string.privacy_sum_lan_off)).append("\n\n")
        if (rv.startsWith("ws://")) sb.append(getString(R.string.privacy_sum_ws)).append("\n\n")
        sb.append(getString(R.string.privacy_sum_outro))
        return sb.toString()
    }

    /** True on a Firebase Test Lab phone (what Play's pre-launch report runs on). */
    private fun onTestLab(): Boolean = try {
        android.provider.Settings.System.getString(contentResolver, "firebase.test.lab") == "true"
    } catch (e: Exception) { false }

    /**
     * POST the diagnostics log to the rendezvous Worker's /report endpoint,
     * where it is stacked in KV for the developer to pull. Runs off the UI
     * thread; on any failure it hands back to [onFail] (the share sheet) so a
     * report is never simply lost.
     */
    private fun sendReport(log: String, onFail: () -> Unit) {
        val base = BuildConfig.RENDEZVOUS_URL
            .replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http")
            .trimEnd('/')
        if (base.isBlank()) { onFail(); return }
        toast(getString(R.string.diag_sending))
        io.execute {
            val ok = try {
                val payload = org.json.JSONObject().apply {
                    put("v", BuildConfig.VERSION_NAME)
                    put("c", BuildConfig.VERSION_CODE.toString())
                    put("model", android.os.Build.MODEL ?: "")
                    put("android", android.os.Build.VERSION.RELEASE ?: "")
                    put("id", randToken(6))
                    put("log", log)
                    // Google Play's pre-launch robot taps "Send to Tawny" too.
                    // Mark its reports so they can be told from real users'.
                    // Only ever set on Google's test phones: a user's report
                    // holds nothing new.
                    if (onTestLab()) put("lab", true)
                }.toString().toByteArray(Charsets.UTF_8)
                val conn = (java.net.URL("$base/report").openConnection()
                        as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            } catch (e: Exception) {
                Diag.log("shell", "report POST failed: ${e.javaClass.simpleName} ${e.message}")
                false
            }
            runOnUiThread {
                if (ok) {
                    toast(getString(R.string.diag_sent_thanks))
                } else {
                    toast(getString(R.string.diag_send_failed))
                    onFail()
                }
            }
        }
    }

    /** Small round theme toggle, pinned top-right of the screen: cycles
     *  Follow system → Light → Dark. */
    private fun themeToggleView(): View {
        return TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Hue.DIM)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Hue.PANEL)
                setStroke(dp(1), Hue.LINE)
            }
            val s = dp(38)
            layoutParams = FrameLayout.LayoutParams(s, s).also {
                it.gravity = Gravity.END or Gravity.TOP
                it.topMargin = dp(6); it.rightMargin = dp(6)
            }
            isClickable = true; isFocusable = true
            val mode = themeMode()
            contentDescription = getString(R.string.a11y_theme_toggle, themeLabel(mode))
            text = when (mode) { "dark" -> "☾"; "light" -> "☀\uFE0E"; else -> "◐" }
            // The half-disc sets small in the UI font next to the sun and moon.
            if (mode == "system") textSize = 22f
            setOnClickListener {
                haptic()
                val next = when (mode) { "system" -> "light"; "light" -> "dark"; else -> "system" }
                setThemeMode(next) { rebuildScreen() }
            }
        }
    }

    // ---------------------------------------------------------- language

    /** The languages Tawny ships, in menu order: BCP-47 tag -> its own endonym.
     *  Add a row here and a res/values-<tag>/strings.xml to add a language. */
    private val languages = linkedMapOf(
        "en" to "English",
        "es" to "Español",
    )

    /** Tag of the language on screen now: the app override if one is set and
     *  supported, else the best system match, else English. */
    private fun currentLang(): String {
        val picked = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        for (i in 0 until picked.size()) {
            picked[i]?.language?.let { if (languages.containsKey(it)) return it }
        }
        val sys = resources.configuration.locales[0].language
        return if (languages.containsKey(sys)) sys else "en"
    }

    private fun setLang(tag: String) {
        if (tag == currentLang()) return
        // Persists (see AppLocalesMetadataHolderService in the manifest) and
        // recreates the activity in the new locale.
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** Small round language pill, bottom-right of the landing screens. */
    private fun languageToggleView(): View = TextView(this).apply {
        textSize = 12f
        typeface = uiFontSemi
        gravity = Gravity.CENTER
        setTextColor(Hue.DIM)
        letterSpacing = 0.08f
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Hue.PANEL)
            setStroke(dp(1), Hue.LINE)
        }
        val s = dp(38)
        layoutParams = FrameLayout.LayoutParams(s, s).also {
            it.gravity = Gravity.END or Gravity.BOTTOM
            it.rightMargin = dp(6); it.bottomMargin = dp(8)
        }
        isClickable = true; isFocusable = true
        text = currentLang().uppercase()
        contentDescription = getString(R.string.a11y_language)
        setOnClickListener { haptic(); showLanguageMenu() }
    }

    private fun showLanguageMenu() {
        val now = currentLang()
        val holder = arrayOfNulls<AlertDialog>(1)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            for ((tag, name) in languages) {
                addView(TextView(this@MainActivity).apply {
                    text = if (tag == now) "$name  ✓" else name
                    setTextColor(if (tag == now) Hue.BERRY else Hue.TEXT)
                    textSize = Type.BODY
                    typeface = if (tag == now) uiFontSemi else uiFont
                    setPadding(dp(4), dp(14), dp(4), dp(14))
                    isClickable = true; isFocusable = true
                    background = pressable(roundRect(0, Color.TRANSPARENT))
                    setOnClickListener { holder[0]?.dismiss(); setLang(tag) }
                })
            }
        }
        themedDialog(
            title = getString(R.string.language_title),
            body = "",
            primaryLabel = getString(R.string.close),
            onPrimary = {},
            content = list,
            onShow = { holder[0] = it },
        )
    }

    private fun applyNightMode(mode: String) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * @param overCamera draws the glow that keeps the arrow legible on top of a
     *   live camera preview. It used to be unconditional, which put a 40%-white
     *   halo around dark text on cream on every ordinary screen — it read as a
     *   printing defect.
     */
    private fun backLink(overCamera: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = "←"
        textSize = 32f
        typeface = uiFontSemi
        setTextColor(if (overCamera) Color.WHITE else Hue.TEXT)
        if (overCamera) setShadowLayer(6f, 0f, 0f, 0x66000000)
        gravity = Gravity.CENTER
        // Was ~38dp wide and flush to the column edge; 48dp is the minimum.
        setPadding(dp(6), dp(2), dp(14), dp(10))
        minWidth = dp(48)
        minHeight = dp(48)
        background = pressable(roundRect(0, Color.TRANSPARENT))
        contentDescription = getString(R.string.common_back)
        layoutParams = LinearLayout.LayoutParams(WC, WC).also {
            it.gravity = Gravity.START
            it.leftMargin = -dp(6)               // keep the glyph optically aligned
            it.bottomMargin = dp(2)
        }
        tapFeedback(onClick)
    }

    private fun roleCard(
        tag: String, title: String, blurb: String, kind: String, critter: String, onClick: () -> Unit
    ): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(IconView(this@MainActivity, kind).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).also { it.bottomMargin = dp(8) }
            })
            addView(TextView(this@MainActivity).apply {
                text = tag.uppercase()
                setTextColor(Hue.BERRY)
                textSize = Type.LABEL
                letterSpacing = 0.16f
                typeface = uiFontSemi
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Hue.TEXT)
                textSize = Type.CARD_TITLE
                typeface = uiFontSemi
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = blurb
                setTextColor(Hue.DIM)
                textSize = Type.SUB
                typeface = uiFont
                setLineSpacing(0f, Type.LEAD_BODY)
            })
        }
        return FrameLayout(this).apply {
            background = pressable(roundRect(Hue.PANEL, Hue.LINE, Radius.CARD), Radius.CARD, Hue.BERRY)
            layoutParams = lp(topMargin = 12)
            tapFeedback(onClick)
            // Tucked in the corner the header row leaves empty (icon is top-
            // left, title/blurb start below) — it can never end up under text
            // because that space never holds any.
            addView(CritterSilhouetteView(this@MainActivity, critter).apply {
                // Loud enough to be an illustration, quiet enough that the eye
                // still lands on the title first. The warm fur tones do most
                // of the recessing on their own against the panel; alpha only
                // has to take the outline and the eyes down with them.
                alpha = if (paletteIsDark()) 0.54f else 0.58f
                layoutParams = FrameLayout.LayoutParams(dp(64), dp(64)).also {
                    it.gravity = Gravity.TOP or Gravity.END
                    it.topMargin = dp(10); it.marginEnd = dp(12)
                }
            })
            addView(content)
        }
    }

    /**
     * A plain "working on it" screen. `goLive` used to wipe the display and
     * mount a WebView whose background is just the page colour, so the user
     * stared at a flat cream rectangle until the page finished loading.
     */
    private fun showBusy(label: String) {
        clearScreen()
        screen = "busy"
        val col = column(scroll = false)
        col.addView(wordmark())
        col.addView(waitingRow(label))
        mountCentered(col)
    }

    /** True when the video itself is what is under the system bars — which,
     *  with the pairing sheet now a transparent-top overlay rather than a
     *  full-screen cream one, is simply whenever a call is live. */
    private fun videoIsBehindBars() = isLive

    private fun showPairOverlay() {
        removePairChip()
        pairStatus?.text = pairSheetStatus().uppercase()
        pairSheetReset?.invoke()
        pairOverlay?.visibility = View.VISIBLE
        // Coming back to the sheet after a while: whatever is drawn on it may
        // have lapsed while nobody was looking. The tick below notices on its
        // first run and mints a fresh one.
        startPairCountdown()
        refreshSystemBars()
    }

    private fun removePairChip() {
        pairChipHide?.let { root.removeCallbacks(it) }
        pairChipHide = null
        pairChipLabel = null
        pairChip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pairChip = null
    }

    /** What the [+] says once you touch it. The count is the sheet's job. */
    private fun pairChipCopy() =
        if (viewersNow == 0) getString(R.string.pairchip_show_code) else getString(R.string.pairchip_add_phone)

    /**
     * The one affordance for adding phones two and three.
     *
     * First it was a chip the shell threw away the instant a Handheld
     * connected, which left no route back to the code at all and made the
     * Monitor look like a one-phone device. The fix for that overcorrected: a
     * wide berry pill reading "+ Add another phone (2 left)", parked in the
     * middle of the top of the picture. On a monitor you leave running in a
     * room, the loudest thing on screen should not be an administrative button.
     *
     * So: a 44dp [+] in smoked glass, tucked into the top-right corner below
     * the page's own two rails, borrowing the same glass-and-accent language as
     * the chips already in that rail. It says nothing at rest. Put a finger on
     * it and the words unroll to its left — the glyph itself stays pinned to
     * the corner — and the sheet follows a beat later, so the label is read
     * rather than merely flashed. TalkBack reads the same words from the
     * chip's content description.
     *
     * Lifecycle is unchanged: offered only while there is room and the sheet is
     * down, retired when the third phone is on, back when a slot frees.
     */
    private fun syncPairChip() {
        // Full, or the sheet itself is up: nothing to offer.
        if (viewersNow >= viewersMax || pairOverlay?.visibility == View.VISIBLE) {
            removePairChip(); return
        }
        if (pairChip != null) {
            // Only the wording moves (the first pairing is not "another"), and
            // it is hidden at rest, so nothing on screen changes here.
            pairChipLabel?.text = pairChipCopy()
            pairChip?.contentDescription = pairChipCopy()
            return
        }

        val label = TextView(this).apply {
            text = pairChipCopy()
            textSize = Type.LABEL
            typeface = uiFontSemi
            letterSpacing = 0.06f
            setTextColor(Hue.BERRY)
            maxLines = 1
            includeFontPadding = false
            visibility = View.GONE
            setPadding(dp(4), 0, dp(8), 0)
        }
        val glyph = IconView(this, "plus", tint = Hue.BERRY).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Smoked glass with an accent hairline: the same construction as
            // the status and phone-count chips the page draws in this rail, so
            // it reads as one more thing floating on the video rather than a
            // slab of UI dropped on top of it.
            background = pressable(
                roundRect(
                    Color.argb(150, 12, 9, 6),
                    (Hue.BERRY and 0x00FFFFFF) or 0x8A000000.toInt(),
                    Radius.CONTROL
                ),
                Radius.CONTROL, Hue.BERRY
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumWidth = dp(44)
            minimumHeight = dp(44)
            // Label first so the [+] stays welded to the right-hand corner and
            // the words unroll leftward into open picture.
            addView(label)
            addView(glyph)
            // No tooltipText: Android would float a second copy of the same
            // words above the chip on a long press, and the label below is
            // already showing them. contentDescription still carries them to
            // TalkBack, which is the reader that has no glyph to go on.
            contentDescription = pairChipCopy()
            if (animScale > 0f) layoutTransition = LayoutTransition()

            // The reveal is on touch-*down*, not on click: by the time a tap is
            // released the sheet is on its way, and a label nobody can read is
            // not an explanation.
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> revealPairChipLabel()
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> hidePairChipLabel(1200)
                }
                false        // never swallow the click
            }
            tapFeedback {
                // A beat, so the words are legible before the sheet covers them.
                postDelayed({ if (pairChip != null) showPairOverlay() }, 200)
            }
        }
        val lp = FrameLayout.LayoutParams(WC, WC).also {
            it.gravity = Gravity.TOP or Gravity.END
            // Clear of *both* of the page's own top rails — the "on air"
            // status line and, under it, the phone-count and pet-name tags.
            // dp(64) cleared only the first, so the chip sat straight on top of
            // "1 phone · your pet monitor".
            it.topMargin = (lastInsets.first) + dp(96)
            it.marginEnd = dp(12)
        }
        pairChip = chip
        pairChipLabel = label
        root.addView(chip, lp)
    }

    private fun revealPairChipLabel() {
        pairChipHide?.let { root.removeCallbacks(it) }
        pairChipHide = null
        val l = pairChipLabel ?: return
        if (l.visibility == View.VISIBLE) return
        l.alpha = 0f
        l.visibility = View.VISIBLE
        if (animScale > 0f) l.animate().alpha(1f).setDuration((140 * animScale).toLong()).start()
        else l.alpha = 1f
    }

    /** Roll the words back up, so the resting state is a bare [+] again. */
    private fun hidePairChipLabel(delayMs: Long) {
        pairChipHide?.let { root.removeCallbacks(it) }
        val r = Runnable {
            pairChipLabel?.let { it.alpha = 1f; it.visibility = View.GONE }
            pairChipHide = null
        }
        pairChipHide = r
        root.postDelayed(r, delayMs)
    }

    /** What the pairing sheet says under the QR, given who is already watching. */
    private fun pairSheetStatus() = when {
        viewersNow == 0 -> getString(R.string.pairsheet_status_waiting)
        viewersNow == 1 -> getString(R.string.pairsheet_status_one, viewersMax - 1)
        viewersNow < viewersMax ->
            getString(R.string.pairsheet_status_some, viewersNow, viewersMax - viewersNow)
        else -> getString(R.string.pairsheet_status_max, viewersNow)
    }

    private var lastInsets: Pair<Int, Int> = 0 to 0

    /**
     * Hand the page the real window insets.
     *
     * The live view runs edge-to-edge under the system bars, but a WebView
     * inside an app never reports `env(safe-area-inset-*)` — so the control
     * rail was drawn at y=0, straight through the clock and the status icons.
     * The shell knows the numbers; the page just needs to be told.
     */
    private fun pushSafeInsets() {
        val w = web ?: return
        val (top, bottom) = lastInsets
        val t = if (videoIsBehindBars()) (top / d).toInt() else 0
        val b = if (videoIsBehindBars()) (bottom / d).toInt() else 0
        w.evaluateJavascript(
            "document.documentElement.style.setProperty('--safe-t','${t}px');" +
                "document.documentElement.style.setProperty('--safe-b','${b}px');",
            null
        )
    }

    /**
     * Go edge-to-edge for a call and back to the framed layout afterwards. The
     * bars stay visible (people need the clock and the battery on a monitor
     * that is left running) but they float over the video instead of cutting
     * it, and their icons flip to light because the video behind them is dark.
     */
    private fun refreshSystemBars() {
        val over = videoIsBehindBars()
        val light = !over && !isNightMode()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        pushSafeInsets()
        ViewCompat.requestApplyInsets(root)
    }

    private fun isNightMode() =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** The LAN relay would not bind. Offer a retry that re-enters as a Monitor. */
    private fun relayFailed() {
        Diag.log("shell", "signal server failed to bind, no rendezvous to fall back on")
        themedDialog(
            title = getString(R.string.relay_failed_title),
            body = getString(R.string.relay_failed_body),
            primaryLabel = getString(R.string.common_try_again),
            onPrimary = { onWatcher() },
            secondaryLabel = getString(R.string.common_back),
            onSecondary = { backToSessionsOrWelcome() }
        )
    }

    /**
     * Tighter privacy left this phone with no road to the other one: the Wi-Fi
     * path is off (or there is no LAN hint) and no rendezvous is set. There is
     * deliberately nothing to fall back to, so say so and point at the fix.
     */
    private fun strictNothingToDial() {
        Diag.log("shell", "tighter privacy: no rendezvous and no Wi-Fi path — nothing to dial")
        themedDialog(
            title = getString(R.string.privacy_nothing_title),
            body = getString(R.string.privacy_nothing_body),
            primaryLabel = getString(R.string.privacy_open_servers),
            onPrimary = { showServers() },
            secondaryLabel = getString(R.string.common_back),
            onSecondary = { backToSessionsOrWelcome() }
        )
    }

    /**
     * Bring the LAN relay up without blocking the UI thread.
     *
     * Starting it inline meant a `CountDownLatch.await(3, SECONDS)` plus up to
     * fifty blocking socket binds ran on the main thread from a tap — well
     * inside ANR territory on a cold device. The server is usually already up
     * by the time this is called a second time, so the fast path stays sync.
     */
    private fun withSignalServer(onReady: (Int) -> Unit) {
        // Tighter privacy with the Wi-Fi path off: nothing listens on this phone.
        // 0 is "no relay" to every caller (the pairing code then has no `h=`).
        if (!lanPathOn()) {
            try { signalServer?.stop(800) } catch (e: Exception) {}
            signalServer = null
            onReady(0); return
        }
        signalServer?.let { onReady(it.boundPort); return }
        showBusy(getString(R.string.busy_starting_monitor))
        io.execute {
            var port = ensureSignalServer()
            // The Wi-Fi path is a shortcut, not the only road: with a rendezvous
            // set, go on without it (0 = no relay, and no `h=` in the code)
            // instead of stopping the Monitor at an error.
            if (port < 0 && hasRendezvous) {
                Diag.log("shell", "signal server unavailable, rendezvous only")
                port = 0
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) onReady(port) }
        }
    }

    private fun waitingRow(label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = lp(topMargin = 8)
        // An indeterminate ProgressBar spins off its own drawable animation
        // rather than off the animator scale, so it is the one thing on the
        // pairing sheet that would keep turning after the user asked the app to
        // hold still — on the very screen a Monitor sits on for hours. Dropped
        // entirely in that case: the label beside it already says what it is
        // waiting for, and a frozen spinner reads as a hang.
        if (!stillMode()) addView(ProgressBar(this@MainActivity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Hue.BERRY)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.rightMargin = dp(10) }
        })
        addView(TextView(this@MainActivity).apply {
            text = label.uppercase()
            setTextColor(Hue.DIM)
            textSize = 13f
            letterSpacing = 0.14f
            typeface = uiFontSemi
        })
    }

    // ------------------------------------------------- recent sessions

    private fun saveRecentSession() {
        val key = prefs.getString("channelKey", null) ?: return
        val role = prefs.getString("role", null) ?: return
        val petName = prefs.getString("channelName", getString(R.string.default_pet_name)) ?: getString(R.string.default_pet_name)
        val signalUrl = prefs.getString("signalUrl", null)
        val token = if (role == "station") prefs.getString("myToken", null)
                    else prefs.getString("pairToken", null)
        val entry = org.json.JSONObject().apply {
            put("role", role); put("petName", petName); put("channelKey", key)
            if (!signalUrl.isNullOrBlank()) put("signalUrl", signalUrl)
            if (!token.isNullOrBlank()) put("token", token)
            put("timestamp", System.currentTimeMillis())
        }
        val existing = loadRecentSessions()
            .filter { !(it.optString("channelKey") == key && it.optString("role") == role) }
            .take(2)
        val arr = org.json.JSONArray()
        arr.put(entry)
        existing.forEach { arr.put(it) }
        prefs.edit().putString("recentSessions", arr.toString()).apply()
    }

    private fun loadRecentSessions(): List<org.json.JSONObject> {
        val raw = prefs.getString("recentSessions", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun updateRecentSessionName(channelKey: String, newName: String) {
        val updated = loadRecentSessions().map { s ->
            if (s.optString("channelKey") == channelKey) s.apply { put("petName", newName) } else s
        }
        val arr = org.json.JSONArray()
        updated.forEach { arr.put(it) }
        prefs.edit().putString("recentSessions", arr.toString()).apply()
    }

    private fun deleteRecentSession(channelKey: String, role: String) {
        val remaining = loadRecentSessions()
            .filter { !(it.optString("channelKey") == channelKey && it.optString("role") == role) }
        val arr = org.json.JSONArray()
        remaining.forEach { arr.put(it) }
        prefs.edit().putString("recentSessions", arr.toString()).apply()
    }

    private fun restoreSession(session: org.json.JSONObject) {
        val key = session.optString("channelKey")
        val role = session.optString("role")
        val petName = session.optString("petName", getString(R.string.default_pet_name))
        val signalUrl = session.optString("signalUrl").takeIf { it.isNotBlank() }
        val token = session.optString("token").takeIf { it.isNotBlank() }
        prefs.edit().apply {
            putString("channelKey", key)
            putString("channelName", petName)
            if (role == "station") {
                if (token != null) putString("myToken", token) else remove("myToken")
                remove("pairToken"); remove("signalUrl")
            } else {
                if (token != null) putString("pairToken", token) else remove("pairToken")
                remove("myToken")
                if (signalUrl != null) putString("signalUrl", signalUrl) else remove("signalUrl")
            }
        }.apply()
        if (role == "station") onWatcher()
        else disclose(needCamera = false, tag = "live-viewer") { goLive("viewer") }
    }

    private fun relativeTime(ts: Long): String {
        if (ts == 0L) return ""
        val d = System.currentTimeMillis() - ts
        return when {
            d < 60_000L -> getString(R.string.time_just_now)
            d < 3_600_000L -> getString(R.string.time_m_ago, (d / 60_000L).toInt())
            d < 86_400_000L -> getString(R.string.time_h_ago, (d / 3_600_000L).toInt())
            d < 172_800_000L -> getString(R.string.time_yesterday)
            else -> getString(R.string.time_d_ago, (d / 86_400_000L).toInt())
        }
    }

    // ------------------------------------------------- sessions home

    /** After a session ends: go to sessions home if there are any, else role select. */
    private fun afterSession() {
        if (loadRecentSessions().isNotEmpty()) showSessionsHome() else showRole()
    }

    /**
     * Where "back" (or "give up") should land when leaving a setup/role screen
     * with no live session of its own: sessions home if the user has other
     * sessions to return to, welcome only for a genuinely fresh install.
     * Centralizes the check so no screen hardcodes a session-blind jump to
     * onboarding — see the bug this fixes: creating a session nobody joined,
     * then backing out, used to land on Welcome even when other sessions (or
     * the one just abandoned) still existed to return to.
     */
    private fun backToSessionsOrWelcome() {
        if (loadRecentSessions().isNotEmpty()) showSessionsHome() else showWelcome()
    }

    /**
     * The landing screen when saved sessions exist. Sessions ARE the home —
     * no welcome, no role choice needed. A secondary link lets the user start fresh.
     */
    private fun showSessionsHome() {
        clearScreen()
        screen = "sessions"
        swipeNav(back = null, forward = null)
        val sessions = loadRecentSessions()
        if (sessions.isEmpty()) { showRole(); return }

        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP) }
        val col = column(scroll = true)

        col.addView(TextView(this).apply {
            text = "Tawny"
            setTextColor(Hue.TEXT)
            textSize = Type.WORDMARK_SM
            letterSpacing = 0.03f
            typeface = titleFont
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = lp(topMargin = 4, centerH = true)
        })
        // A bit of life over an otherwise plain list: a kitten swatting a ball,
        // Tawny hopping, a dog with its bone. Idles quietly; respects "remove
        // animations".
        playScene = PlayfulSceneView(this).also {
            it.layoutParams = LinearLayout.LayoutParams(MP, dp(128)).also { p ->
                p.topMargin = dp(2)
            }
        }
        col.addView(playScene)
        col.addView(TextView(this).apply {
            text = getString(R.string.sessions_subtitle)
            setTextColor(Hue.DIM)
            textSize = Type.SUB
            typeface = uiFont
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = lp(topMargin = 2, centerH = true)
        })
        col.addView(gap(10))

        sessions.forEach { session ->
            val key = session.optString("channelKey")
            val role = session.optString("role")
            val petName = session.optString("petName", getString(R.string.default_pet_name))
            val roleLabel = if (role == "station") getString(R.string.sessions_role_monitor) else getString(R.string.sessions_role_viewer)
            val timeStr = relativeTime(session.optLong("timestamp", 0L))

            // Container: delete strip behind + card in front for swipe-left
            val container = FrameLayout(this).apply { layoutParams = lp(topMargin = 10) }

            val deleteStrip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                // Was Material Red 700 — a stock Google red that appears nowhere
                // else in a warm strawberry/bronze palette. Hue.LIVE exists for
                // exactly this and was unused.
                background = roundRect(Hue.LIVE, Hue.LIVE, Radius.CARD)
                layoutParams = FrameLayout.LayoutParams(MP, MP)
                setPadding(0, 0, dp(20), 0)
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.sessions_delete)
                    setTextColor(Hue.ON_ACCENT)
                    textSize = Type.SUB
                    typeface = uiFontSemi
                })
            }
            container.addView(deleteStrip)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pressable(
                    roundRect(Hue.PANEL, Hue.LINE, Radius.CARD), Radius.CARD, Hue.BERRY
                )
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = FrameLayout.LayoutParams(MP, WC)
                isClickable = true; isFocusable = true; isLongClickable = true
            }

            // Swipe-left to reveal delete; tap to resume; long-press to rename.
            //
            // This listener used to return true from ACTION_DOWN. Android skips
            // View.onTouchEvent() entirely when a touch listener consumes the
            // event, and long-press detection lives in onTouchEvent — so the
            // rename dialog below could never open, and the card's pressed state
            // never drew either. Return false until an actual horizontal drag
            // starts, and only then take the gesture over.
            var downX = 0f; var downY = 0f
            var swipeRevealed = false
            var dragging = false
            val swipeThreshold = dp(60).toFloat()
            val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
            card.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY; dragging = false
                        false        // let the view handle press state + long-press
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                            dragging = true
                            // Now it is unambiguously a horizontal swipe: cancel
                            // the pending click/long-press and take the gesture.
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            v.isPressed = false
                            v.cancelLongPress()
                        }
                        if (!dragging) return@setOnTouchListener false
                        if (dx < 0) v.translationX = maxOf(dx, -dp(88).toFloat())
                        else v.translationX = minOf(0f, (if (swipeRevealed) -dp(88).toFloat() else 0f) + dx)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!dragging) {
                            // A tap on a revealed card just puts it back.
                            if (swipeRevealed && event.actionMasked == MotionEvent.ACTION_UP) {
                                v.animate().translationX(0f).setDuration(150).start()
                                swipeRevealed = false
                                return@setOnTouchListener true
                            }
                            return@setOnTouchListener false
                        }
                        val dx = event.rawX - downX
                        if (dx < -swipeThreshold) {
                            v.animate().translationX(-dp(88).toFloat()).setDuration(150).start()
                            swipeRevealed = true
                            haptic()
                        } else {
                            v.animate().translationX(0f).setDuration(150).start()
                            swipeRevealed = false
                        }
                        dragging = false
                        true
                    }
                    else -> false
                }
            }
            card.setOnClickListener { haptic(); restoreSession(session) }
            deleteStrip.setOnClickListener {
                haptic()
                themedDialog(
                    title = getString(R.string.sessions_remove_title),
                    body = getString(R.string.sessions_remove_body, petName),
                    primaryLabel = getString(R.string.common_remove),
                    onPrimary = { deleteRecentSession(key, role); showSessionsHome() },
                    secondaryLabel = getString(R.string.common_cancel)
                )
            }
            card.setOnLongClickListener {
                haptic()
                themedDialog(
                    title = petName,
                    body = getString(R.string.sessions_menu_body),
                    primaryLabel = getString(R.string.sessions_edit_name),
                    onPrimary = {
                        askName(
                            title = getString(R.string.sessions_rename_title),
                            body = getString(R.string.sessions_rename_body),
                            initial = petName,
                            primaryLabel = getString(R.string.common_save),
                        ) { n ->
                            updateRecentSessionName(key, n)
                            showSessionsHome()
                        }
                    },
                    secondaryLabel = getString(R.string.sessions_delete_session),
                    onSecondary = {
                        themedDialog(
                            title = getString(R.string.sessions_remove_title),
                            body = getString(R.string.sessions_remove_body, petName),
                            primaryLabel = getString(R.string.common_remove),
                            onPrimary = { deleteRecentSession(key, role); showSessionsHome() },
                            secondaryLabel = getString(R.string.common_cancel)
                        )
                    }
                )
                true
            }

            card.addView(IconView(this, if (role == "station") "camera" else "phone").apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also { it.rightMargin = dp(14) }
            })

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = petName
                setTextColor(Hue.TEXT)
                textSize = 18f
                typeface = uiFontSemi
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textCol.addView(TextView(this).apply {
                text = "$roleLabel · $timeStr"
                setTextColor(Hue.DIM)
                textSize = 13f
                typeface = uiFont
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(textCol)

            card.addView(TextView(this).apply {
                text = "›"
                setTextColor(Hue.BERRY)
                textSize = 30f
                typeface = uiFontSemi
                layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.leftMargin = dp(12) }
            })

            container.addView(card)
            col.addView(container)
        }

        col.addView(gap(12))
        // Was pill() reimplemented by hand, at a different size and padding.
        col.addView(ghost(getString(R.string.sessions_new)) { showRole() })

        // The only two places money is ever mentioned, both below the fold of
        // the thing the user came here to do. Deliberately not on welcome, role
        // or pairing: an ask inside the setup funnel reads as a paywall, which
        // is the exact complaint the whole category earns (docs/DIRECTION.md,
        // part 2). Nothing here unlocks anything — see [showAbout].
        col.addView(metaPanel(
            metaRow("heart", getString(R.string.meta_support), Hue.BERRY, "↗", reveal = "ko-fi.com") {
                openExternal(SUPPORT_URL)
            },
            metaRow("bolt", getString(R.string.meta_tip_bitcoin), Hue.SKY, "›") {
                lnFromAbout = false; showLightningTip()
            },
            metaRow("star", getString(R.string.meta_rate), Hue.TEXT, "↗") { openStoreListing() },
        ))
        // Settings and help, apart from the asks above: a change of subject.
        col.addView(metaPanel(
            themeRow { showSessionsHome() },
            motionRow { showSessionsHome() },
            metaRow("help", getString(R.string.meta_help), Hue.TEXT, "›") { showHelp(from = "home") },
            metaRow("info", getString(R.string.meta_about), Hue.TEXT, "›") { showAbout() },
        ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) })
        col.addView(gap(24))

        scroll.addView(col)
        root.addView(scroll)
        root.addView(themeToggleView())
        root.addView(languageToggleView())
        if (!maybeShowWhatsNew()) maybeAskForReview()
    }

    // ---------------------------------------------------------- about

    /**
     * Where "Support Tawny" points.
     *
     * An external link, opened in the *system browser* — not Play Billing, not a
     * Custom Tab, and never the app's own WebView (which holds the
     * [Bridge]). Play's Payments policy carves out a peer-to-peer contribution
     * that grants "no digital content, service or benefit of any kind": the
     * moment a supporter gets so much as a badge this has to become an in-app
     * purchase. So supporters get nothing, on purpose, and the About copy says
     * so out loud.
     */
    private val SUPPORT_URL = "https://ko-fi.com/tawnyone"

    /**
     * The same contribution, over Bitcoin's Lightning network.
     *
     * [LN_ADDRESS] is a Lightning Address: a wallet resolves it over LNURL-pay
     * at `strike.me/.well-known/lnurlp/loustrikes` and negotiates the amount
     * itself. Tawny never presets a figure and never handles a key, an invoice
     * or a satoshi.
     *
     * [LN_URI] is what actually gets opened — the `lightning:` scheme every
     * current wallet registers for. [LN_WEB_URL] is only the fallback for a
     * phone with no wallet at all; on its own it draws a QR code, which is no
     * use to the person most likely to tap this: someone holding one phone.
     */
    private val LN_ADDRESS = "loustrikes@strike.me"
    private val LN_URI = "lightning:$LN_ADDRESS"
    private val LN_WEB_URL = "https://strike.me/@loustrikes"
    private val SUPPORT_EMAIL = "tawnysupport@pm.me"

    /** Hand a URL to whatever the user browses with. Never loaded in-app. */
    private fun openExternal(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // A phone with no browser at all, or one where the intent is
            // blocked. Leave the user something they can act on.
            copyToClipboard("Tawny link", url)
            toast(getString(R.string.no_browser_copied, url))
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    /** body() centres its text, which is wrong for a screen of running prose. */
    private fun aboutBody(s: String) = body(s).apply {
        gravity = Gravity.START
        layoutParams = lp(topMargin = 14)
    }

    /**
     * What the app is, in plain words.
     *
     * There was no About screen at all — the only thing behind a long-press was
     * the diagnostics hatch — so the app never said what it does with a user's
     * video anywhere except the Play listing, and a cautious person tapping
     * "Support Tawny" had no way to check the destination was really ours. This
     * is that page, and it is where the support link is explained rather than
     * just offered.
     */
    private fun showAbout() {
        clearScreen()
        screen = "about"
        swipeNav(back = { afterSession() }, forward = null)

        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP) }
        val col = column(scroll = true)

        col.addView(backLink { afterSession() })
        col.addView(
            heading(
                getString(R.string.meta_about),
                getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString())
            )
        )

        // A quiet tracked-out heading to set one part of the page off from the
        // next. Before this the screen was one unbroken column of grey prose.
        fun eyebrow(s: String) = TextView(this).apply {
            text = s.uppercase()
            setTextColor(Hue.DIM)
            textSize = Type.LABEL
            letterSpacing = 0.16f
            typeface = uiFontSemi
            layoutParams = lp(topMargin = 30)
        }

        col.addView(aboutBody(
            getString(R.string.about_intro)
        ))

        col.addView(eyebrow(getString(R.string.about_eyebrow_home)))
        col.addView(aboutBody(
            getString(R.string.about_home_body)
        ))

        // Repeated from the home screen on purpose. Home is where someone who
        // already has a session will look, but a phone that struggles with the
        // drawn pets is exactly the phone whose owner goes hunting through
        // About for something to turn off.
        col.addView(eyebrow(getString(R.string.about_eyebrow_thisphone)))
        col.addView(metaPanel(
            themeRow { showAbout() },
            motionRow { showAbout() },
            metaRow("help", getString(R.string.meta_help), Hue.TEXT, "›") { showHelp(from = "about") },
            metaRow("star", getString(R.string.meta_rate), Hue.TEXT, "↗") { openStoreListing() },
        ))

        col.addView(eyebrow(getString(R.string.about_eyebrow_keeping)))
        col.addView(aboutBody(
            getString(R.string.about_keeping_body)
        ))
        col.addView(metaPanel(
            metaRow("heart", getString(R.string.meta_support), Hue.BERRY, "↗", sub = "ko-fi.com/tawnyone") {
                openExternal(SUPPORT_URL)
            },
            metaRow("bolt", getString(R.string.meta_tip_bitcoin), Hue.SKY, "›", sub = LN_ADDRESS) {
                lnFromAbout = true; showLightningTip()
            },
            metaRow("mail", getString(R.string.meta_email_us), Hue.TEXT, "↗", sub = SUPPORT_EMAIL) {
                try {
                    startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
                            .putExtra(
                                Intent.EXTRA_SUBJECT,
                                "Tawny ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    copyToClipboard(getString(R.string.about_email_chooser), SUPPORT_EMAIL)
                    toast(getString(R.string.about_no_email))
                }
            },
        ))
        col.addView(aboutBody(
            getString(R.string.about_contact_body)
        ))

        // No "Sound credits" section: every bundled chime — including
        // "psp psp psp" as of 2026-09-04, an internal recording replacing
        // the earlier CC BY 4.0 freesound clip — ships under a licence or
        // origin that needs no in-app attribution. See
        // public/sounds/README.md for the source of each clip.
        col.addView(gap(16))

        scroll.addView(col)
        root.addView(scroll)
    }

    // ---------------------------------------------------- lightning tip

    /** Which screen the tip flow was opened from, so back returns there. */
    private var lnFromAbout = false

    /**
     * Bitcoin over Lightning — the Ko-fi contribution, in the other currency.
     *
     * This deliberately does NOT open `strike.me/@loustrikes`. That page draws
     * a QR code, and a QR code is useless to the person most likely to tap this
     * row: someone holding the single phone they would also be paying from.
     * So the row lands here, and "Open in wallet" hands the payment off with a
     * `lightning:` URI — the scheme every current wallet registers for (Phoenix,
     * Zeus, Wallet of Satoshi, Breez, Blink, Muun, Strike…). Android opens the
     * one that is installed, or offers a chooser. `startActivity` is not
     * subject to package-visibility filtering, so this needs no `<queries>`
     * entry and no guessing about which wallet is there.
     *
     * The amount is agreed inside the wallet. Tawny never presets one, and
     * never touches a key, an invoice or a satoshi.
     *
     * TODO: BOLT12 offer if Lou runs a node that supports it — static,
     * reusable, no LNURL server in the middle, and better for privacy. Strike
     * does not issue one today, so a Lightning Address it is.
     *
     * Policy-wise this is the Ko-fi link again: an external hand-off that
     * unlocks nothing, grants nothing, and involves no Play Billing.
     *
     * @param noWallet the second state, entered when the intent found nothing
     *   to open. Never a dead end: the address stays, a QR appears for a phone
     *   that does have a wallet, and the web page is one tap away.
     */
    private fun showLightningTip(noWallet: Boolean = false) {
        clearScreen()
        screen = "lntip"
        val back = { if (lnFromAbout) showAbout() else afterSession() }
        swipeNav(back = back, forward = null)

        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP) }
        val col = column(scroll = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(backLink { back() })

        // The same glyph as the row that got you here, at size — and it takes
        // the same zap when you press the button.
        val bolt = IconView(this, "bolt", behind = Hue.BG, tint = Hue.SKY).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also {
                it.topMargin = dp(10)
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        col.addView(bolt)
        col.addView(heading(
            getString(R.string.tip_title),
            getString(R.string.tip_wallet_body),
            center = true,
        ))

        if (!noWallet) {
            col.addView(pill(getString(R.string.tip_open_wallet), Hue.ON_ACCENT, Hue.SKY, Hue.SKY) {
                bolt.strike(animScale)
                openLightning()
            })
        } else {
            col.addView(body(
                getString(R.string.tip_no_wallet_body),
                maxW = 320,
            ))
        }

        col.addView(TextView(this).apply {
            text = LN_ADDRESS
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Hue.TEXT)
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
            setTextIsSelectable(true)
            background = roundRect(Hue.PANEL, Hue.LINE, Radius.CARD)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = lp(topMargin = 18)
        })
        col.addView(link(getString(R.string.tip_copy_address)) {
            copyToClipboard("Tawny Lightning address", LN_ADDRESS)
            toast(getString(R.string.tip_copied, LN_ADDRESS))
        })

        if (noWallet) {
            val qr = ImageView(this).apply {
                val s = dp(190)
                layoutParams = LinearLayout.LayoutParams(s, s).also {
                    it.topMargin = dp(6)
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            col.addView(qr)
            // Encoding is not free and this is a plain navigation, so keep it
            // off the UI thread like the pairing QR does.
            io.execute {
                val bmp = try { qrBitmap(LN_URI, 480) } catch (e: Exception) { null }
                runOnUiThread { if (bmp != null && qr.isAttachedToWindow) qr.setImageBitmap(bmp) }
            }
            col.addView(link(getString(R.string.tip_open_strike)) { openExternal(LN_WEB_URL) })
        }

        col.addView(body(
            getString(R.string.tip_body),
            maxW = 320,
        ))
        col.addView(gap(16))

        scroll.addView(col)
        root.addView(scroll)
    }

    /** Hand the tip to a wallet app, or fall back to the no-wallet screen. */
    private fun openLightning() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(LN_URI))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Diag.log("shell", "lightning: nothing registered for $LN_URI")
            showLightningTip(noWallet = true)
        } catch (e: Exception) {
            copyToClipboard("Tawny Lightning address", LN_ADDRESS)
            toast(getString(R.string.tip_no_wallet_open_failed))
        }
    }

    // ---------------------------------------------------- walkthrough

    /** Which walkthrough page is up, and where it was opened from — both ride
     *  along in the instance state, so a theme flip mid-tour stays put. */
    private var tourStep = 0
    private var tourFrom = "welcome"      // "welcome" | "role" | "help"

    private class TourPage(val icons: List<String>, val title: Int, val body: Int)

    private val tourPages = listOf(
        TourPage(listOf("camera", "phone"), R.string.tour_1_title, R.string.tour_1_body),
        TourPage(listOf("qr"), R.string.tour_2_title, R.string.tour_2_body),
        TourPage(listOf("chat", "heart"), R.string.tour_3_title, R.string.tour_3_body),
        TourPage(listOf("moon"), R.string.tour_4_title, R.string.tour_4_body),
        TourPage(listOf("help"), R.string.tour_5_title, R.string.tour_5_body),
    )

    /**
     * The new-user walkthrough: what Tawny is, one idea per page, before the
     * role picker asks a question that only makes sense once you know it.
     *
     * First run passes through it on the way from "Get started"; after that
     * it is one tap away from the role picker and from Help. Skippable from
     * every page, swipeable both ways, and it never repeats on its own. The
     * in-call half — a spotlight on the real keys — lives in the page, see
     * runCoach() in public/app.js.
     */
    private fun showTour(step: Int, from: String? = null) {
        if (from != null) tourFrom = from
        val i = step.coerceIn(0, tourPages.lastIndex)
        val forward = i >= tourStep
        tourStep = i
        clearScreen()
        screen = "tour"
        val last = i == tourPages.lastIndex
        val back = { if (i > 0) showTour(i - 1) else leaveTour(backOut = true) }
        val next = { if (last) leaveTour(backOut = false) else showTour(i + 1) }
        swipeNav(back = back, forward = next)
        val page = tourPages[i]

        val col = column(scroll = false)

        // The illustration: the app's own glyphs on a soft disc, rather than
        // stock art that would look like any other app.
        val art = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Hue.PANEL)
                setStroke(dp(1), Hue.LINE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(168)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.topMargin = dp(8)
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(MP, MP)
                val size = if (page.icons.size > 1) 58 else 80
                page.icons.forEachIndexed { n, k ->
                    addView(IconView(
                        this@MainActivity, k, behind = Hue.PANEL,
                        tint = if (n == 0) Hue.BERRY else Hue.SKY,
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).also {
                            if (n > 0) it.leftMargin = dp(10)
                        }
                    })
                }
            })
        }
        col.addView(art)

        val title = TextView(this).apply {
            text = getString(page.title)
            setTextColor(Hue.TEXT)
            textSize = Type.TITLE
            typeface = uiFontSemi
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            layoutParams = lp(topMargin = 26)
        }
        col.addView(title)
        val text = body(getString(page.body), maxW = 340).apply {
            layoutParams = lp(topMargin = 12)
        }
        col.addView(text)

        // Progress dots: where you are, and how little is left.
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = lp(topMargin = 26, centerH = true)
            contentDescription = getString(R.string.tour_progress, i + 1, tourPages.size)
            tourPages.indices.forEach { n ->
                addView(View(this@MainActivity).apply {
                    background = roundRect(if (n == i) Hue.BERRY else Hue.LINE, 0, 4)
                    layoutParams = LinearLayout.LayoutParams(dp(if (n == i) 22 else 8), dp(8)).also {
                        it.leftMargin = dp(4); it.rightMargin = dp(4)
                    }
                })
            }
        })

        col.addView(gap(10))
        col.addView(primary(getString(
            if (!last) R.string.common_next
            else if (tourFrom == "help") R.string.common_done
            else R.string.tour_finish
        )) { next() })
        if (!last) col.addView(link(getString(R.string.tour_skip)) { leaveTour(backOut = false) })
        mountCentered(col)
        // Pinned to the top corner rather than riding in the centred column,
        // where it floated halfway down the screen.
        root.addView(backLink { back() }.apply {
            layoutParams = FrameLayout.LayoutParams(WC, WC).also {
                it.gravity = Gravity.START or Gravity.TOP
                it.leftMargin = dp(18); it.topMargin = dp(18)
            }
        })

        // The page slides in from the side it came from.
        val a = animScale
        if (a > 0f) listOf(art, title, text).forEachIndexed { n, v ->
            v.alpha = 0f
            v.translationX = dp(if (forward) 28 else -28).toFloat()
            v.animate().alpha(1f).translationX(0f)
                .setStartDelay((40L * n * a).toLong())
                .setDuration((260 * a).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /**
     * @param backOut the back arrow on the first page: return to where the
     *   tour was opened. Otherwise (finished or skipped) move on to setting
     *   up — or back to Help, which is where a replay starts and ends.
     */
    private fun leaveTour(backOut: Boolean) {
        prefs.edit().putBoolean(TOUR_SEEN, true).apply()
        tourStep = 0
        when {
            tourFrom == "help" -> showHelp()
            backOut && tourFrom == "welcome" -> showWelcome()
            else -> showRole()
        }
    }

    // ------------------------------------------------ help & feedback

    private var helpFrom = "home"         // "home" | "about" | "handheld"

    private fun helpBack() = when (helpFrom) {
        "about" -> showAbout()
        "handheld" -> showHandheldHome()
        else -> afterSession()
    }

    /**
     * Help & feedback: the common questions answered on the phone itself, and
     * every way of telling us something — a note, a diagnostics log, a rating.
     *
     * The answers are what the listing, the battery tip and the support inbox
     * already say, gathered in one place; keep them in step with those when
     * behaviour changes (docs/store-listing.md).
     */
    private fun showHelp(from: String? = null) {
        if (from != null) helpFrom = from
        clearScreen()
        screen = "help"
        swipeNav(back = { helpBack() }, forward = null)

        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP) }
        val col = column(scroll = true)
        col.addView(backLink { helpBack() })
        col.addView(heading(getString(R.string.help_title), getString(R.string.help_subtitle)))

        fun eyebrow(s: String) = TextView(this).apply {
            text = s.uppercase()
            setTextColor(Hue.DIM)
            textSize = Type.LABEL
            letterSpacing = 0.16f
            typeface = uiFontSemi
            layoutParams = lp(topMargin = 28)
        }

        col.addView(metaPanel(
            metaRow("info", getString(R.string.help_replay_tour), Hue.TEXT, "›",
                sub = getString(R.string.help_replay_tour_sub)) { showTour(0, from = "help") },
        ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(22) })

        col.addView(eyebrow(getString(R.string.help_eyebrow_faq)))
        val faqs = listOf(
            R.string.faq_two_phones_q to R.string.faq_two_phones_a,
            R.string.faq_pair_q to R.string.faq_pair_a,
            R.string.faq_away_q to R.string.faq_away_a,
            R.string.faq_screen_q to R.string.faq_screen_a,
            R.string.faq_battery_q to R.string.faq_battery_a,
            R.string.faq_private_q to R.string.faq_private_a,
            R.string.faq_viewers_q to R.string.faq_viewers_a,
            R.string.faq_light_q to R.string.faq_light_a,
            R.string.faq_connect_q to R.string.faq_connect_a,
            R.string.faq_cost_q to R.string.faq_cost_a,
        )
        col.addView(metaPanel(*faqs.map { (q, a) -> faqRow(getString(q), getString(a)) }.toTypedArray())
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })

        col.addView(eyebrow(getString(R.string.help_eyebrow_tell_us)))
        col.addView(metaPanel(
            metaRow("chat", getString(R.string.help_feedback), Hue.TEXT, "›",
                sub = getString(R.string.help_feedback_sub)) { showFeedback() },
            metaRow("star", getString(R.string.meta_rate), Hue.TEXT, "↗",
                sub = getString(R.string.help_rate_sub)) { openStoreListing() },
            metaRow("info", getString(R.string.help_diagnostics), Hue.TEXT, "›",
                sub = getString(R.string.help_diagnostics_sub)) { diagFromHelp = true; showDiagnostics() },
        ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
        col.addView(gap(24))

        scroll.addView(col)
        root.addView(scroll)
    }

    /** One question in the FAQ panel; tap to open or close its answer. */
    private fun faqRow(question: String, answer: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable(roundRect(0, Color.TRANSPARENT, 0), 0, Hue.BERRY)
        val ph = dp(16); val pv = dp(14)
        setPadding(ph, pv, ph, pv)
        val sign = TextView(this@MainActivity).apply {
            text = "+"
            setTextColor(Hue.BERRY)
            textSize = 20f
            typeface = uiFontSemi
            layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.leftMargin = dp(12) }
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = question
                setTextColor(Hue.TEXT)
                textSize = Type.SUB
                typeface = uiFontSemi
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            })
            addView(sign)
        })
        val ans = TextView(this@MainActivity).apply {
            text = answer
            setTextColor(Hue.DIM)
            textSize = 14.5f
            typeface = uiFont
            setLineSpacing(0f, Type.LEAD_BODY)
            setPadding(0, dp(8), dp(8), dp(2))
            visibility = View.GONE
        }
        addView(ans)
        isClickable = true; isFocusable = true
        setOnClickListener {
            haptic()
            val open = ans.visibility != View.VISIBLE
            ans.visibility = if (open) View.VISIBLE else View.GONE
            sign.text = if (open) "−" else "+"
            val a = animScale
            if (open && a > 0f) {
                ans.alpha = 0f
                ans.animate().alpha(1f).setDuration((220 * a).toLong()).start()
            }
        }
    }

    /** Where diagnostics' back arrow goes: Help, when it was opened from there. */
    private var diagFromHelp = false

    /**
     * "Send feedback": pick what kind of note it is, write it, and it goes to
     * the support inbox through the user's own mail app — no account, no
     * server of ours in the way, and the user sees exactly what is sent before
     * it goes. The diagnostics log rides along only if they switch it on.
     */
    private fun showFeedback(kind: Int = 0, draft: String = "", withLog: Boolean = false) {
        val kinds = listOf(R.string.feedback_kind_bug, R.string.feedback_kind_idea, R.string.feedback_kind_other)
        var picked = kind
        var attachLog = withLog

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(topMargin = 16)
        }
        fun paintChips() {
            for (n in 0 until chips.childCount) {
                val c = chips.getChildAt(n) as TextView
                val on = n == picked
                c.setTextColor(if (on) Hue.ON_ACCENT else Hue.TEXT)
                c.background = pressable(
                    roundRect(if (on) Hue.BERRY else 0, if (on) Hue.BERRY else Hue.LINE),
                    tint = if (on) Hue.ON_ACCENT else Hue.BERRY
                )
            }
        }
        kinds.forEachIndexed { n, res ->
            chips.addView(TextView(this).apply {
                text = getString(res)
                textSize = 14f
                typeface = uiFontSemi
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f).also {
                    if (n > 0) it.leftMargin = dp(8)
                }
                isClickable = true; isFocusable = true
                setOnClickListener { haptic(); picked = n; paintChips() }
            })
        }
        paintChips()
        content.addView(chips)

        val input = EditText(this).apply {
            hint = getString(R.string.feedback_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setText(draft)
            setSelection(text.length)
            typeface = uiFont
            textSize = Type.BODY
            setTextColor(Hue.TEXT)
            setHintTextColor(Hue.DIM)
            background = roundRect(Hue.BG, Hue.LINE)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = lp(topMargin = 14)
        }
        content.addView(input)

        val knob = SwitchMark(this, attachLog).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(28)).also { it.leftMargin = dp(12) }
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(topMargin = 12)
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.feedback_attach_log)
                setTextColor(Hue.DIM)
                textSize = 14f
                typeface = uiFont
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            })
            addView(knob)
            isClickable = true; isFocusable = true
            setOnClickListener { haptic(); attachLog = !attachLog; knob.on = attachLog }
        })

        themedDialog(
            title = getString(R.string.help_feedback),
            body = getString(R.string.feedback_body),
            primaryLabel = getString(R.string.feedback_send),
            onPrimary = {
                val note = input.text.toString().trim()
                if (note.isEmpty()) {
                    toast(getString(R.string.feedback_empty))
                    showFeedback(picked, "", attachLog)
                } else {
                    sendFeedback(getString(kinds[picked]), note, attachLog)
                }
            },
            secondaryLabel = getString(R.string.common_cancel),
            content = content,
        )
    }

    private fun sendFeedback(kind: String, note: String, attachLog: Boolean) {
        val about = "Tawny ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
            currentLang()
        val text = buildString {
            append(note).append("\n\n—\n").append(about)
            if (attachLog) append("\n\n--- diagnostics ---\n").append(Diag.dump().ifBlank { "(empty)" })
        }
        val subject = getString(R.string.feedback_subject, kind, BuildConfig.VERSION_NAME)
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(mail)
        } catch (e: Exception) {
            // No mail app: any app that takes text, and failing that the
            // clipboard, so the note is never simply lost.
            try {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    getString(R.string.help_feedback)
                ))
            } catch (e2: Exception) {
                copyToClipboard(subject, "$SUPPORT_EMAIL\n\n$text")
                toast(getString(R.string.feedback_copied, SUPPORT_EMAIL))
            }
        }
    }

    // ---------------------------------------------------------- rating

    /** The Play listing, in the Play Store app if there is one. */
    private fun openStoreListing() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PLAY_PACKAGE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openExternal("https://play.google.com/store/apps/details?id=$PLAY_PACKAGE")
        }
    }

    /**
     * A session counts toward the rating prompt once it has actually been used
     * — a minute or more live — not every time a screen was opened and closed.
     */
    private fun countSession(ms: Long) {
        if (ms < 60_000L) return
        prefs.edit().putInt(GOOD_SESSIONS, prefs.getInt(GOOD_SESSIONS, 0) + 1).apply()
    }

    /**
     * The in-app rating prompt, through Google Play's own review sheet.
     *
     * Asked of everyone the same way, whatever their experience: no "Do you
     * like Tawny?" gate in front of it, which Play's policy forbids and which
     * would skew the ratings anyway. It waits until Tawny has been used for
     * real — three sessions of a minute or more — and lands on a home screen
     * between calls, never during one or in the middle of setting up. Play
     * itself decides whether the sheet actually appears (it has its own quota),
     * so this asks at most three times, a month apart, and moves on.
     */
    private fun maybeAskForReview() {
        val sessions = prefs.getInt(GOOD_SESSIONS, 0)
        val asks = prefs.getInt(REVIEW_ASKS, 0)
        val last = prefs.getLong(REVIEW_LAST, 0L)
        if (sessions < 3 || asks >= 3) return
        if (System.currentTimeMillis() - last < 30L * 24 * 3600 * 1000) return
        if (sessions < 3 * (asks + 1)) return
        val shownOn = screen
        root.postDelayed({
            if (isFinishing || isDestroyed || screen != shownOn || isLive) return@postDelayed
            prefs.edit()
                .putInt(REVIEW_ASKS, asks + 1)
                .putLong(REVIEW_LAST, System.currentTimeMillis())
                .apply()
            val manager = com.google.android.play.core.review.ReviewManagerFactory.create(this)
            manager.requestReviewFlow().addOnCompleteListener { req ->
                if (!req.isSuccessful || isFinishing || isDestroyed) {
                    Diag.log("review", "flow unavailable: ${req.exception?.javaClass?.simpleName}")
                    return@addOnCompleteListener
                }
                manager.launchReviewFlow(this, req.result)
                    .addOnCompleteListener { Diag.log("review", "flow finished") }
            }
        }, 1500L)
    }

    // ------------------------------------------------------- update board

    /** Where the update board's changelog link points. */
    private val CHANGELOG_URL = "https://github.com/PressF4me/Tawny-APK/blob/master/docs/changelog/android.md"

    /**
     * A fresh install has nothing to be "new" against, so it is marked as
     * having seen this build's board before any screen is up. Anyone with a
     * trace of an earlier build — a finished tour, a role, a saved session —
     * is left unmarked and gets the board on their next home screen.
     */
    private fun seedWhatsNew() {
        if (prefs.contains(WHATSNEW_SEEN)) return
        val upgraded = prefs.getBoolean(TOUR_SEEN, false) ||
            prefs.contains("role") || loadRecentSessions().isNotEmpty()
        if (!upgraded) prefs.edit().putInt(WHATSNEW_SEEN, BuildConfig.VERSION_CODE).apply()
    }

    /**
     * The update board, once per build, on a home screen between calls — the
     * same quiet spot [maybeAskForReview] waits for, and instead of it, so the
     * two never stack. Returns whether it is going up.
     */
    private fun maybeShowWhatsNew(): Boolean {
        if (prefs.getInt(WHATSNEW_SEEN, 0) >= BuildConfig.VERSION_CODE) return false
        val shownOn = screen
        root.postDelayed({
            if (isFinishing || isDestroyed || screen != shownOn || isLive) return@postDelayed
            prefs.edit().putInt(WHATSNEW_SEEN, BuildConfig.VERSION_CODE).apply()
            showWhatsNew()
        }, 450L)
        return true
    }

    /**
     * "Tawny just got better": what changed in a line or three, where the
     * whole changelog lives, a thank-you, and the same two ways to chip in as
     * About. Nothing on it unlocks anything — see [SUPPORT_URL].
     *
     * Its own full-window dialog rather than [themedDialog]: the confetti has
     * to fall across the whole screen, not inside the card, so the scrim is
     * drawn here instead of by the window's dim.
     */
    private fun showWhatsNew() {
        Diag.log("app", "update board shown for ${BuildConfig.VERSION_CODE}")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundRect(Hue.PANEL, Hue.LINE, Radius.CARD)
            val p = dp(22); setPadding(p, dp(26), p, dp(14))
            isClickable = true                     // taps on the card stay on it
        }
        card.addView(IconView(this, "star", behind = Hue.PANEL, tint = Hue.BERRY).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.whatsnew_eyebrow, BuildConfig.VERSION_NAME).uppercase()
            setTextColor(Hue.DIM)
            textSize = Type.LABEL
            letterSpacing = 0.16f
            typeface = uiFontSemi
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.whatsnew_title)
            setTextColor(Hue.TEXT)
            textSize = Type.WORDMARK_SM
            typeface = titleFont
            gravity = Gravity.CENTER
            setLineSpacing(0f, Type.LEAD_TIGHT)
            setPadding(0, dp(4), 0, 0)
        })

        val notes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(Hue.BG, Hue.LINE, Radius.CONTROL)
            val p = dp(14); setPadding(p, dp(10), p, dp(10))
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(16) }
        }
        resources.getStringArray(R.array.whatsnew_notes).forEach { note ->
            notes.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
                addView(TextView(this@MainActivity).apply {
                    text = "✦"
                    setTextColor(Hue.BERRY)
                    textSize = Type.SUB
                    layoutParams = LinearLayout.LayoutParams(dp(22), WC)
                })
                addView(TextView(this@MainActivity).apply {
                    text = note
                    setTextColor(Hue.TEXT)
                    textSize = Type.SUB
                    typeface = uiFont
                    setLineSpacing(0f, 0.95f)
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                })
            })
        }
        card.addView(notes)
        card.addView(link(getString(R.string.whatsnew_changelog) + "  ↗") {
            openExternal(CHANGELOG_URL)
        }.apply {
            setTextColor(Hue.BERRY)
            layoutParams = lp(topMargin = 4, centerH = true)
        })

        card.addView(TextView(this).apply {
            text = getString(R.string.whatsnew_thanks)
            setTextColor(Hue.DIM)
            textSize = Type.SUB
            typeface = uiFont
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.05f)
            setPadding(dp(4), dp(6), dp(4), 0)
        })
        card.addView(metaPanel(
            metaRow("heart", getString(R.string.meta_support), Hue.BERRY, "↗", sub = "ko-fi.com/tawnyone") {
                openExternal(SUPPORT_URL)
            },
            metaRow("bolt", getString(R.string.meta_tip_bitcoin), Hue.SKY, "›", sub = LN_ADDRESS) {
                whatsNewDialog?.dismiss(); lnFromAbout = false; showLightningTip()
            },
        ).apply {
            background = roundRect(Hue.BG, Hue.LINE, Radius.CARD)
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(14) }
        })
        card.addView(primary(getString(R.string.whatsnew_done)) { whatsNewDialog?.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(MP, WC).also { it.topMargin = dp(18) }
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(this@MainActivity).apply {
                val m = dp(20); setPadding(m, m, m, m)
                addView(card, FrameLayout.LayoutParams(MP, WC, Gravity.CENTER).also {
                    // The same reading-width cap as column(), for tablets.
                    it.width = minOf(resources.displayMetrics.widthPixels - 2 * m, dp(420))
                })
            })
        }
        val wrap = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(208, 0, 0, 0))
            addView(scroll, FrameLayout.LayoutParams(MP, MP))
            setOnClickListener { whatsNewDialog?.dismiss() }
            (scroll.getChildAt(0)).setOnClickListener { whatsNewDialog?.dismiss() }
            if (animScale > 0f) addView(
                ConfettiView(this@MainActivity, animScale),
                FrameLayout.LayoutParams(MP, MP)
            )
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
            .setView(wrap).setCancelable(true).create()
        whatsNewDialog = dialog
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(MP, MP)
            setDimAmount(0f)
        }
        dialog.setOnDismissListener { whatsNewDialog = null }
        dialog.show()
        dialog.window?.setLayout(MP, MP)
    }
    private var whatsNewDialog: AlertDialog? = null

    // ----------------------------------------------------------- theme row

    /**
     * Theme, as a row: Follow system, Light or Dark. The round toggle on the
     * landing screens cycles the same three; this is where the choice is
     * spelled out, for anyone who never guessed what "◐" meant.
     */
    private fun themeRow(rebuild: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(roundRect(0, Color.TRANSPARENT, 0), 0, Hue.BERRY)
        val ph = dp(16); val pv = dp(15)
        setPadding(ph, pv, ph, pv)
        minimumHeight = dp(54)
        addView(IconView(this@MainActivity, "theme", behind = Hue.PANEL, tint = Hue.DIM).apply {
            layoutParams = LinearLayout.LayoutParams(dp(19), dp(19))
        })
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.theme_title)
            setTextColor(Hue.TEXT)
            textSize = Type.SUB
            typeface = uiFontSemi
            letterSpacing = 0.01f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).also { it.leftMargin = dp(13) }
        })
        // A three-way segmented control in the trailing slot: the state is
        // visible without opening anything, and one tap changes it.
        val seg = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(Hue.BG, Hue.LINE, 999)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val now = themeMode()
        listOf("system" to "◐", "light" to "☀\uFE0E", "dark" to "☾").forEach { (mode, glyph) ->
            val on = mode == now
            seg.addView(TextView(this@MainActivity).apply {
                text = glyph
                // U+FE0E asks for the text form of the sun; the half-disc sets
                // small in the UI font, so it gets a size up to match.
                textSize = if (mode == "system") 19f else 15f
                gravity = Gravity.CENTER
                setTextColor(if (on) Hue.ON_ACCENT else Hue.DIM)
                background = if (on) roundRect(Hue.BERRY, Hue.BERRY, 999) else null
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(30))
                contentDescription = themeLabel(mode)
                isSelected = on
                isClickable = true; isFocusable = true
                setOnClickListener { haptic(); if (!on) setThemeMode(mode, rebuild) }
            })
        }
        addView(seg)
    }

    /** Re-mount whatever native screen is up — after a setting that changes
     *  how it draws but not which screen it is. */
    private fun rebuildScreen() {
        if (!isLive && !restoreScreen(screen)) afterSession()
    }

    private fun coachSeen(): Set<String> = prefs.getStringSet(COACH_SEEN, emptySet()) ?: emptySet()

    // -------------------------------------------------------- welcome

    private fun showWelcome() {
        clearScreen()
        screen = "welcome"
        // Not committed to a role here — don't let onCreate auto-resume into one.
        // The role is parked, not dropped, so resumeSession() can still tell a
        // Handheld from a Watcher. Also mark onboarding as seen so a cold start
        // never lands here again.
        prefs.edit()
            .apply { prefs.getString("role", null)?.let { putString(PARKED_ROLE, it) } }
            .remove("role").putBoolean("seenWelcome", true).apply()
        swipeNav(back = null, forward = { if (!resumeSession()) showRole() })
        val col = column(scroll = false)
        scene = PetSceneView(this).also {
            it.layoutParams = LinearLayout.LayoutParams(dp(300), dp(200))
        }
        col.addView(scene)
        col.addView(wordmark())
        col.addView(
            body(
                // Say the two-phone shape before the role screen asks which one
                // this is: "one stays, one comes with you" is the whole mental
                // model, and without it the next screen is a quiz.
                getString(R.string.welcome_blurb),
                maxW = 300
            )
        )
        col.addView(gap(4))
        // First run goes through the walkthrough on its way to the role
        // picker; anyone who has seen it (or skipped it) goes straight there.
        col.addView(primary(getString(R.string.welcome_get_started)) {
            if (prefs.getBoolean(TOUR_SEEN, false)) showRole() else showTour(0, from = "welcome")
        })
        // Not "I want to watch a monitor": that asked the user to know which of
        // two roles they were before the app had explained either, and it
        // competed with "Get started" for the same first-time tap. Having a
        // code is a fact you can check by looking at the other phone, so the
        // two routes no longer overlap.
        col.addView(link(getString(R.string.welcome_have_code)) { onHandheld() })
        mountCentered(col)
        root.addView(themeToggleView())
        root.addView(languageToggleView())
    }

    // -------------------------------------------------------- handheld home

    /**
     * A paired Handheld between calls. Rather than dropping the user back on the
     * welcome screen — as if nothing had ever been set up — show one obvious
     * action: call the Watcher again. Re-pairing is still one tap away.
     */
    private fun showHandheldHome() {
        clearScreen()
        screen = "handheld"
        swipeNav(back = { showWelcome() }, forward = { goLive("viewer") })
        val pawView = PawTrailView(this)
        root.addView(pawView, FrameLayout.LayoutParams(MP, MP))
        val name = prefs.getString("channelName", getString(R.string.default_pet_name)) ?: getString(R.string.default_pet_name)
        val col = column(scroll = false)
        col.addView(IconView(this, "phone", behind = Hue.BG).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).also {
                it.bottomMargin = dp(6)
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        col.addView(wordmark())
        col.addView(
            body(
                getString(R.string.handheld_connected, name),
                maxW = 300
            )
        )
        col.addView(gap(6))
        col.addView(primary(getString(R.string.handheld_watch_now, name)) { goLive("viewer") })
        col.addView(link(getString(R.string.handheld_connect_different)) { onHandheld() })
        col.addView(link(getString(R.string.meta_help)) { showHelp(from = "handheld") })
        mountCentered(col)
        if (!maybeShowWhatsNew()) maybeAskForReview()

        // Same reasoning as showRole(): keep the trail off the actual content,
        // recomputed on layout/scroll since mountCentered's ScrollView can
        // move independently of the paw view underneath it.
        val scroll = col.parent as? ScrollView
        val updateAvoid = {
            val a = IntArray(2); pawView.getLocationOnScreen(a)
            val b = IntArray(2); col.getLocationOnScreen(b)
            val x = (b[0] - a[0]).toFloat(); val y = (b[1] - a[1]).toFloat()
            pawView.avoid = listOf(RectF(x, y, x + col.width, y + col.height))
        }
        scroll?.viewTreeObserver?.addOnGlobalLayoutListener { updateAvoid() }
        scroll?.setOnScrollChangeListener { _, _, _, _, _ -> updateAvoid() }
    }

    // -------------------------------------------------------- role choice

    private fun showRole() {
        clearScreen()
        screen = "role"
        // Only strip a stale "role" when there is nothing to resume. Wiping it
        // unconditionally used to race the very next line's resumeSession()
        // check: a returning user's role vanished before the forward-swipe
        // handler could read it, so it was silently treated as a fresh setup —
        // exactly the "creates a new session it shouldn't" bug.
        if (prefs.getString("channelKey", null).isNullOrBlank()) {
            prefs.edit().remove("role").apply()
        }
        swipeNav(back = { backToSessionsOrWelcome() }, forward = { if (!resumeSession()) onWatcher() })
        val pawView = PawTrailView(this)
        root.addView(pawView, FrameLayout.LayoutParams(MP, MP))
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP) }
        val col = column(scroll = true)
        col.addView(backLink { backToSessionsOrWelcome() })
        val head = heading(getString(R.string.role_title), getString(R.string.role_subtitle))
        col.addView(head)
        val monitorCard = roleCard(
            getString(R.string.role_monitor_tag), getString(R.string.role_monitor_line),
            getString(R.string.role_monitor_blurb),
            "camera", "cat"
        ) { onWatcher() }
        col.addView(monitorCard)
        val viewerCard = roleCard(
            getString(R.string.role_viewer_tag), getString(R.string.role_viewer_line),
            getString(R.string.role_viewer_blurb),
            "phone", "dog"
        ) { onHandheld() }
        col.addView(viewerCard)
        // For whoever reaches the quiz without the explanation: a returning
        // user who skipped the walkthrough, or one who has forgotten it.
        col.addView(link(getString(R.string.role_how_it_works)) { showTour(0, from = "role") })
        scroll.addView(col)
        root.addView(scroll)

        // The trail has to steer around the cards *and* the header, in the paw
        // view's own coordinates — recomputed on every layout pass and every
        // scroll, since they move (scroll) independently of it. The header was
        // missing from this list at first, and prints duly walked straight
        // over the back arrow and the word "Tawny".
        fun cardRect(v: View): RectF {
            val a = IntArray(2); pawView.getLocationOnScreen(a)
            val b = IntArray(2); v.getLocationOnScreen(b)
            val x = (b[0] - a[0]).toFloat(); val y = (b[1] - a[1]).toFloat()
            return RectF(x, y, x + v.width, y + v.height)
        }
        // The header goes in as one full-width slab from the very top of the
        // view down to the bottom of the subtitle, not as the two text
        // rectangles it is made of. The back arrow is barely 50dp wide, so
        // the honest rectangles left a 40dp-tall slot open beside it — and a
        // walker that seeded into that slot could only shuffle from side to
        // side in it forever, since the full-width heading below sealed it
        // off. Blocking the whole strip leaves one generous area, under the
        // cards, and the trail actually roams.
        val updateAvoid = {
            val h = cardRect(head)
            pawView.avoid = listOf(
                RectF(0f, 0f, pawView.width.toFloat(), h.bottom),
                cardRect(monitorCard), cardRect(viewerCard)
            )
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener { updateAvoid() }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateAvoid() }
    }

    // -------------------------------------------------------- the watcher

    private fun onWatcher() {
        val ip = lanIp()
        if (ip == null && !hasRendezvous) {
            themedDialog(
                title = getString(R.string.wifi_title),
                body = getString(R.string.wifi_body),
                primaryLabel = getString(R.string.common_ok), onPrimary = {}
            )
            return
        }
        disclose(needCamera = true, tag = "monitor") {
            monitorBatteryTip {
                if (prefs.getString("channelKey", null).isNullOrBlank()) {
                    promptRoomName { name ->
                        prefs.edit()
                            .putString("channelKey", newKey())
                            .putString("channelName", name)
                            .remove("myToken")        // fresh channel → fresh admission ticket
                            .apply()
                        pairCode = null               // …and a fresh pairing code with it
                        startWatcher(ip)
                    }
                } else {
                    startWatcher(ip)
                }
            }
        }
    }

    /**
     * Every way a user can put this phone on duty as a Monitor: a cold start
     * that restores the saved role, a swipe-forward resume, and the pairing
     * flow in [onWatcher]. They all go through here so the battery tip has one
     * place to live rather than three. [goLive] itself is left alone — it is
     * also called to rebuild the live view mid-session, which is not a moment
     * to put a dialog in front of anyone.
     */
    private fun startStationLive() = monitorBatteryTip { goLive("station") }

    /**
     * Shown once, the first time this phone is set up as a Monitor.
     *
     * Only three things, and only the ones the owner has to do themselves: the
     * screen is far and away the biggest draw on a phone that is being held
     * awake for hours, and the two settings that govern it are system settings
     * an app cannot touch. Everything Tawny can do for itself, it now does
     * without asking (see the power section).
     *
     * Not cancelable, because dismissing it would strand the caller — [then] is
     * the rest of the start-the-monitor flow.
     */
    private fun monitorBatteryTip(then: () -> Unit) {
        if (prefs.getBoolean("monitorTipSeen", false)) { then(); return }
        prefs.edit().putBoolean("monitorTipSeen", true).apply()
        themedDialog(
            title = getString(R.string.battery_tip_title),
            body = getString(R.string.battery_tip_body),
            primaryLabel = getString(R.string.common_got_it),
            onPrimary = then,
            cancelable = false
        )
    }

    /** A single-line text field styled for [themedDialog]'s content slot. */
    private fun dialogInput(hint: String, initial: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        setSingleLine()
        setText(initial)
        setSelection(text.length)
        typeface = uiFont
        textSize = Type.BODY
        letterSpacing = 0.02f
        setTextColor(Hue.TEXT)
        setHintTextColor(Hue.DIM)
        background = roundRect(Hue.BG, Hue.LINE)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        minHeight = dp(48)
        layoutParams = lp(topMargin = 18)
    }

    /** Ask for a name. Was a hand-copied second implementation of themedDialog. */
    private fun askName(
        title: String,
        body: String,
        initial: String,
        primaryLabel: String,
        onName: (String) -> Unit,
    ) {
        val input = dialogInput("Mochi, Bella, Luna\u2026", initial)
        var submit: () -> Unit = {}
        themedDialog(
            title = title,
            body = body,
            primaryLabel = primaryLabel,
            onPrimary = { onName(input.text.toString().trim().ifBlank { getString(R.string.default_pet_name) }) },
            secondaryLabel = getString(R.string.common_cancel),
            content = input,
            onShow = { dialog ->
                submit = {
                    dialog.dismiss()
                    onName(input.text.toString().trim().ifBlank { getString(R.string.default_pet_name) })
                }
                input.setOnEditorActionListener { _, _, _ -> submit(); true }
                // Open with the keyboard up and the cursor waiting.
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                )
                input.requestFocus()
            },
        )
    }

    /** Name the spot the Watcher is aimed at; the Handheld shows "<name> monitor". */
    private fun promptRoomName(onName: (String) -> Unit) {
        val current = prefs.getString("channelName", "")
            ?.takeUnless { it == "Pet camera" || it == getString(R.string.default_pet_name) }.orEmpty()
        askName(
            title = getString(R.string.name_ask_title),
            body = "",
            initial = current,
            primaryLabel = getString(R.string.common_continue),
            onName = onName,
        )
    }

    private fun startWatcher(ip: String?) = withSignalServer { sigPort ->
        if (sigPort < 0) { relayFailed(); return@withSignalServer }
        // Persist the role now so an unattended Watcher that gets killed
        // (Samsung battery, low memory) comes back as the Watcher, not the setup
        // screen. Clear any leftover Handheld state so resumeSession() can't
        // misread this device as a paired Handheld.
        prefs.edit()
            .putString("role", "station")
            .remove("signalUrl")
            .remove("pairToken")
            .apply()
        // Go straight into the live station view — camera on, joined to the
        // relay room — with the pairing QR as an overlay until a Handheld
        // connects. (No manual "start watching" tap.)
        goLive("station")
    }

    /**
     * A short admission ticket for the rendezvous. Minted once per channel and
     * kept in prefs so a Watcher restart re-registers the same one and earlier
     * Handhelds still connect. No secret — the rendezvous only stores sha256(t).
     */
    private fun watcherToken(): String? {
        if (!hasRendezvous) return null
        prefs.getString("myToken", null)?.let { return it }
        return randToken(16).also { prefs.edit().putString("myToken", it).apply() }
    }

    // -------------------------------------------------------- the handheld

    private fun onHandheld() {
        disclose(needCamera = false, tag = "handheld") {
            if (has(android.Manifest.permission.CAMERA)) {
                showScanner()
                return@disclose
            }
            themedDialog(
                title = getString(R.string.scan_title),
                body = getString(R.string.scan_body),
                primaryLabel = getString(R.string.scan_use_camera),
                onPrimary = {
                    pendingScan = true
                    askPermissions.launch(arrayOf(android.Manifest.permission.CAMERA))
                },
                secondaryLabel = getString(R.string.common_paste_a_link),
                onSecondary = { promptPairLink() }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun showScanner() {
        clearScreen()
        scanHandled = false
        swipeNav(back = { stopScanner(); showRole() }, forward = null)

        val preview = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(preview)
        versionView?.visibility = View.GONE      // camera preview owns the surface

        val overlay = column(scroll = false).apply { gravity = Gravity.TOP }
        overlay.addView(backLink(overCamera = true) { stopScanner(); showRole() })
        overlay.addView(TextView(this).apply {
            text = getString(R.string.scan_overlay_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 21f
            letterSpacing = 0f
            typeface = uiFontSemi
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            layoutParams = lp(topMargin = 4)
        })
        overlay.addView(TextView(this).apply {
            text = getString(R.string.scan_overlay_hint)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            typeface = uiFont
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            layoutParams = lp(topMargin = 6)
        })
        overlay.addView(TextView(this).apply {
            text = getString(R.string.scan_paste_instead)
            letterSpacing = 0.12f
            textSize = 14f
            typeface = uiFontSemi
            setTextColor(Hue.BERRY)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setPadding(0, dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = lp(topMargin = 10)
            setOnClickListener { promptPairLink() }
        })
        root.addView(overlay)

        val exec = Executors.newSingleThreadExecutor()
        scannerStop = { exec.shutdown() }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // The user left the scanner before the camera provider was ready:
            // clearScreen() already ran scannerStop, which shut exec down. Binding
            // now would leave the camera running behind whatever screen is up.
            if (exec.isShutdown) return@addListener
            val provider = try { future.get() } catch (e: Exception) {
                exec.shutdown()
                cameraUnavailable()
                return@addListener
            }
            val prev = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE))
                )
            }
            analysis.setAnalyzer(exec) { proxy ->
                val text = try { decodeQr(proxy, reader) } catch (e: Exception) { null }
                proxy.close()
                if (text != null) runOnUiThread { onScanned(text) }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis
                )
            } catch (e: Exception) {
                cameraUnavailable()
            }
            scannerStop = {
                try { provider.unbindAll() } catch (e: Exception) {}
                exec.shutdown()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopScanner() {
        scannerStop?.invoke()
        scannerStop = null
    }

    private fun decodeQr(proxy: ImageProxy, reader: MultiFormatReader): String? {
        val plane = proxy.planes[0]
        val buf = plane.buffer
        val data = ByteArray(buf.remaining())
        buf.get(data)
        val rowStride = plane.rowStride
        val width = min(proxy.width, rowStride)
        val source = PlanarYUVLuminanceSource(
            data, rowStride, proxy.height, 0, 0, width, proxy.height, false
        )
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: NotFoundException) {
            reader.reset()
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } catch (e2: NotFoundException) {
                reader.reset()
                null
            }
        }
    }

    private fun onScanned(raw: String) {
        if (scanHandled) return
        val p = parsePairing(raw) ?: run {
            themedDialog(
                title = getString(R.string.scan_not_tawny_title),
                body = getString(R.string.scan_not_tawny_body),
                primaryLabel = getString(R.string.scan_keep_scanning),
                onPrimary = {}
            )
            return
        }
        scanHandled = true
        joinAsHandheld(p)
    }

    // -------------------------------------------------------- keys / codes

    private fun randToken(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun newKey() = randToken(16)

    // ------------------------------------------------- servers (advanced)
    //
    // `tawny.rendezvousUrl` in local.properties is a *build-time* setting, so
    // the only person who could ever point Tawny at their own relay was
    // whoever compiled the APK. Someone who installs from Play — which is
    // everyone — had no way at all. These five prefs are the runtime version.
    //
    // Deliberately behind the diagnostics hatch rather than in About: it is a
    // support surface, not a feature, and a normal user has no business being
    // shown a WebSocket URL field.
    //
    // The built-in relay is never *replaced*, only preferred against: a custom
    // rendezvous that cannot be reached hands the session back to the default
    // tunnel rather than taking the remote path down with it (the fallback
    // lives in openSignal()/fetchIce() in public/app.js, because that is where
    // the failure is visible). A bad URL typed in here costs a few seconds, not
    // a working app.

    private val PREF_RENDEZVOUS = "srvRendezvous"
    private val PREF_STUN = "srvStun"
    private val PREF_TURN = "srvTurn"
    private val PREF_TURN_USER = "srvTurnUser"
    private val PREF_TURN_PASS = "srvTurnPass"

    // ---- tighter privacy -----------------------------------------------------
    //
    // Everything above is built to be forgiving: a relay that does not answer
    // hands the session to Tawny's, and STUN falls back to the build's public
    // list. That is the right default and the wrong one for somebody whose
    // whole reason for being on this screen is that nothing of theirs goes
    // near a server they did not choose. With this on, the app contacts
    // exactly what is written here and nothing else — no built-in rendezvous,
    // no public STUN, no relay adopted from a scanned code, no "Send to
    // Tawny" — and a wrong address fails instead of being papered over. The
    // screen says that in as many words before it lets anyone turn it on.
    private val PREF_STRICT = "srvStrict"
    /** `auto` (TURN only if nothing direct works), `always` (relay only), `never`. */
    private val PREF_TURN_MODE = "srvTurnMode"
    /** Ask the rendezvous's own /turn for credentials. Default true. */
    private val PREF_TURN_FETCH = "srvTurnFetch"
    /** The Monitor's own Wi-Fi relay and the `h=` in its codes. Default true. */
    private val PREF_LAN_PATH = "srvLanPath"
    private val TURN_MODES = listOf("auto", "always", "never")

    private fun strictPrivacy(): Boolean = prefs.getBoolean(PREF_STRICT, false)
    private fun strictTurnMode(): String =
        prefs.getString(PREF_TURN_MODE, "auto").takeIf { it in TURN_MODES } ?: "auto"
    /** The direct Wi-Fi path is only ever switched off by tighter privacy. */
    private fun lanPathOn(): Boolean = !strictPrivacy() || prefs.getBoolean(PREF_LAN_PATH, true)

    /** The rendezvous this build ships with — none at all under tighter privacy. */
    private fun builtInRendezvous(): String =
        if (strictPrivacy()) "" else BuildConfig.RENDEZVOUS_URL

    /** `wss://host[:port][/path]` — or `ws://` for a relay on your own LAN. */
    private val RELAY_URL_RE = Regex("^wss?://[A-Za-z0-9._~%\\-]+(:\\d{1,5})?(/[^\\s?#]*)?$")
    private val STUN_URL_RE = Regex("^stuns?:[^\\s,]+$")
    private val TURN_URL_RE = Regex("^turns?:[^\\s,]+$")

    private fun customRendezvous(): String =
        prefs.getString(PREF_RENDEZVOUS, "")?.trim().orEmpty()
            .takeIf { RELAY_URL_RE.matches(it) }.orEmpty()

    /** What the page should dial first: the user's relay if they set one. */
    private fun preferredRendezvous(): String =
        customRendezvous().ifBlank { builtInRendezvous() }

    /** Whether this install can reach a Handheld off the LAN, by any route. */
    private val hasRendezvous get() = preferredRendezvous().isNotBlank()

    private fun csvPref(key: String, scheme: Regex): List<String> =
        prefs.getString(key, "").orEmpty().split(',')
            .map { it.trim() }.filter { it.isNotEmpty() && scheme.matches(it) }

    /**
     * The server settings, as the page reads them (`opts.servers`).
     *
     * `fallback` is the built-in tunnel, and it is only sent when a custom
     * rendezvous is in play — it is what the page drops back to when the user's
     * relay does not answer. Both ends of a call apply the same rule, so a
     * relay that is genuinely down sends the Monitor and the Handheld to the
     * same place and they still meet.
     *
     * Custom STUN *replaces* the built-in list (a self-hoster who names their
     * own STUN usually means "only mine"); custom TURN is added *ahead of*
     * whatever `/turn` issues, so a working built-in relay is still there
     * underneath a TURN server that turns out to be wrong.
     */
    private fun serversJson(): String {
        val custom = customRendezvous()
        val strict = strictPrivacy()
        val turn = if (strict && strictTurnMode() == "never") emptyList()
                   else csvPref(PREF_TURN, TURN_URL_RE)
        val o = org.json.JSONObject()
        if (strict) {
            // No `fallback` key at all, so fallBackToDefault() has nowhere to
            // go. STUN is sent even when empty — empty means "none", not "the
            // build's list" — and the page is told not to trust any default.
            o.put("strict", true)
            o.put("stun", org.json.JSONArray(csvPref(PREF_STUN, STUN_URL_RE)))
            o.put("turnMode", strictTurnMode())
            o.put("turnFetch", strictTurnMode() != "never" && prefs.getBoolean(PREF_TURN_FETCH, true))
        } else {
            if (custom.isNotBlank() && BuildConfig.RENDEZVOUS_URL.isNotBlank()) {
                o.put("fallback", BuildConfig.RENDEZVOUS_URL)
            }
            csvPref(PREF_STUN, STUN_URL_RE).takeIf { it.isNotEmpty() }
                ?.let { o.put("stun", org.json.JSONArray(it)) }
        }
        if (turn.isNotEmpty()) {
            o.put("turn", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("urls", org.json.JSONArray(turn))
                prefs.getString(PREF_TURN_USER, "")?.takeIf { it.isNotBlank() }
                    ?.let { put("username", it) }
                prefs.getString(PREF_TURN_PASS, "")?.takeIf { it.isNotBlank() }
                    ?.let { put("credential", it) }
            }))
        }
        return o.toString()
    }

    // ---- pairing codes expire after ten minutes ----------------------------
    //
    // See the long note above `pairLink()` in public/app.js for why the deadline
    // has to be the *Monitor's*: the channel key rides inside the code, so no
    // check the bearer performs on itself, and none the relay performs, can
    // hold. What this side owns is the code on screen — `pairCode`, rotated
    // every [PAIR_TTL_MS] while the pairing sheet is up — and handing it to the
    // page, which is the thing that actually answers a Handheld's offer.

    private val PAIR_TTL_MS = 10 * 60 * 1000L

    /** The code on screen right now, and the moment it stops admitting phones. */
    private var pairCode: String? = null
    private var pairCodeExp = 0L
    private var pairTick: Runnable? = null
    private var pairCountdown: TextView? = null
    /** Rebuilds the `tawny://pair` payload around whatever code is current. */
    private var rebuildPairPayload: (() -> String)? = null

    private fun pairCodeLeftMs() = if (pairCode == null) 0L else pairCodeExp - System.currentTimeMillis()

    /** Mint the next pairing code. 8 bytes: short enough to keep the QR light. */
    private fun rotatePairCode(): String {
        val c = randToken(8)
        pairCode = c
        pairCodeExp = System.currentTimeMillis() + PAIR_TTL_MS
        return c
    }

    private fun currentPairCode(): String = pairCode?.takeIf { pairCodeLeftMs() > 0 } ?: rotatePairCode()

    /**
     * `tawny://pair?k=&n=&h=<lan ip:port>&t=<token>&c=<code>&e=<unix seconds>`.
     *
     * `h` is dropped when Wi-Fi is down or the Watcher is relay-only; `t` (the
     * long-lived rendezvous admission ticket) is added only when this build can
     * reach a relay. `c` is the pairing code and `e` is when it lapses — `e` is
     * a courtesy so a scanning phone can say "expired" without dialling, and `c`
     * is what the Monitor actually checks.
     */
    private fun pairingPayload(ip: String?, sigPort: Int, key: String, name: String, token: String?) =
        buildString {
            append("tawny://pair?k=${Uri.encode(key)}&n=${Uri.encode(name)}")
            if (ip != null && sigPort > 0) append("&h=$ip:$sigPort")
            if (token != null) append("&t=${Uri.encode(token)}")
            append("&c=${Uri.encode(currentPairCode())}")
            append("&e=${pairCodeExp / 1000}")
        }

    // Pairing and the rules for reading a link now live in PairLink.kt, with no
    // Android in them, so they can be unit-tested — an Activity cannot be stood
    // up in a JVM test, and every decision a stranger's QR code is allowed to
    // make about this phone is taken in that file.

    /**
     * Split the link with the platform's URI parser, then hand the pieces to
     * PairLink, which holds the rules and the anchors this install trusts.
     */
    private fun parsePairing(raw: String): Pairing? {
        val uri = try { Uri.parse(raw.trim()) } catch (e: Exception) { return null }
        return PairLink.parse(
            scheme = uri.scheme,
            host = uri.host,
            port = uri.port,
            encodedQuery = uri.encodedQuery,
            encodedFragment = uri.encodedFragment,
            preferredRelayHost = relayHost(preferredRendezvous()),
            buildRelayHost = relayHost(builtInRendezvous()),
            hasRendezvous = hasRendezvous
        )
    }

    private fun qrBitmap(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
        )
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                // QR must stay dark-on-light regardless of theme.
                pixels[row + x] = if (matrix.get(x, y)) 0xFF1B1B24.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // -------------------------------------------------------- local servers

    /** Serves the bundled web app on 127.0.0.1. Both roles need it. */
    private fun ensureAssetServer(): Int {
        // The page's CSP names the relay hosts it may reach, and an advanced
        // user can change theirs between sessions — so a cached server whose
        // header no longer lists the right host has to go, or the new relay is
        // blocked before it gets a socket and the failure is invisible.
        val custom = customRendezvous()
        val hosts = listOfNotNull(
            relayHost(builtInRendezvous()),
            relayHost(custom)?.let { if (custom.startsWith("ws://", ignoreCase = true)) "ws://$it" else it }
        )
        assetServer?.let { if (it.relayHosts == hosts) return it.port else { it.stop(); assetServer = null } }
        return AssetHttpServer(applicationContext, 8809, hosts).also { assetServer = it }.port
    }

    /**
     * The Watcher's signaling relay, reachable on the LAN. The port is kept
     * stable across restarts so a Handheld paired earlier can still reconnect
     * (as long as the Watcher keeps the same Wi-Fi address). Returns the bound
     * port, or -1 if the relay could not start.
     */
    private fun ensureSignalServer(): Int {
        signalServer?.let { return it.boundPort }
        // The saved port first, so earlier Handhelds find it again; then any port
        // the system will give, since a different port beats no Wi-Fi path.
        for (want in listOf(prefs.getInt("sigPort", 8820), 0)) {
            // The relay proves each LAN peer holds the channel key before letting it
            // into the room; read the key fresh so a re-pair takes effect at once.
            val s = try {
                SignalServer(want) { prefs.getString("channelKey", null) }.apply {
                    isReuseAddr = true
                    start()
                }
            } catch (e: Exception) {
                Diag.log("shell", "signal server port=$want: ${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            val signalled = s.ready.await(3, TimeUnit.SECONDS)
            val err = s.startError
            if (signalled && err == null) {
                signalServer = s
                prefs.edit().putInt("sigPort", s.boundPort).apply()
                return s.boundPort
            }
            Diag.log("shell", "signal server port=$want: " +
                if (err != null) "${err.javaClass.simpleName}: ${err.message}" else "no start after 3s")
            try { s.stop(200) } catch (e: Exception) {}
        }
        return -1
    }

    private fun stopServers() {
        assetServer?.stop(); assetServer = null
        try { signalServer?.stop(800) } catch (e: Exception) {}
        signalServer = null
    }

    /** This phone's private Wi-Fi address, preferring the wlan interface. */
    private fun lanIp(): String? {
        var fallback: String? = null
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.startsWith("wlan")) return ip
                        if (fallback == null) fallback = ip
                    }
                }
            }
        } catch (e: Exception) { /* fall through */ }
        return fallback
    }

    // -------------------------------------------------------- live (webview)

    private fun goLive(role: String) {
        if (role == "station" && !lanPathOn()) {
            // Tighter privacy with the Wi-Fi path off: no local relay is
            // started, nothing listens on this phone, and the code carries no
            // `h=`. The user's rendezvous is the only way in — and without one
            // there is no way in at all, which is said rather than hidden.
            if (!hasRendezvous) { strictNothingToDial(); return }
            goLiveWith(role, null)
            return
        }
        if (role == "station") {
            withSignalServer { port ->
                if (port < 0) { relayFailed(); return@withSignalServer }
                goLiveWith(role, if (port > 0) "ws://127.0.0.1:$port" else null)
            }
            return
        }
        val lan = prefs.getString("signalUrl", null)?.takeIf { lanPathOn() }
        if (lan == null && !hasRendezvous) {
            if (strictPrivacy()) { strictNothingToDial(); return }
            return showWelcome()
        }
        goLiveWith(role, lan)   // may be null — app.js then uses the rendezvous only
    }

    private fun goLiveWith(role: String, signal: String?) {
        val key = prefs.getString("channelKey", null) ?: return showWelcome()
        val name = prefs.getString("channelName", getString(R.string.default_pet_name)) ?: getString(R.string.default_pet_name)
        val httpPort = ensureAssetServer()
        // Name this session's relay in the page's connect-src instead of opening
        // the whole `ws:` scheme. Set before the WebView is pointed at the asset
        // server, because the policy is baked into the response that carries the
        // page. A malformed value is dropped by the setter, which costs the LAN
        // leg rather than widening the policy.
        assetServer?.lanRelay = signal
        // Mint the Monitor's admission ticket BEFORE reading it. The rendezvous
        // admits a Monitor only if its first frame carries sha256(ticket), and
        // the QR has to advertise that very same ticket. Reading `myToken`
        // straight from prefs here used to hand the page a null on the first
        // run of a fresh channel (onWatcher() clears it) — the Monitor was then
        // refused by the relay ("no pairing ticket") and every Handheld off the
        // LAN saw "Monitor isn't on yet". The LAN relay needs no ticket, which
        // is why this only ever broke the over-the-internet path.
        val token = if (role == "station") watcherToken()
                    else prefs.getString("pairToken", null)
        prefs.edit().putString("role", role).apply()
        // No LAN address in the code when tighter privacy has the Wi-Fi path off.
        val ip = if (lanPathOn()) lanIp() else null   // was enumerated three times in a row, on the UI thread
        // A Monitor going live mints (or keeps) the code its QR advertises; a
        // Handheld carries the code it scanned, which the Monitor checks once.
        val sigPort = signalServer?.boundPort ?: 0
        rebuildPairPayload =
            if (role == "station") ({ pairingPayload(ip, sigPort, key, name, token) }) else null
        val pairPayload = rebuildPairPayload?.invoke()
        val code = if (role == "station") pairCode else prefs.getString("pairCode", null)
        val codeExp = if (role == "station") pairCodeExp else 0L
        val rv = preferredRendezvous()
        Diag.log("shell", "goLive role=$role lan=${ip ?: "-"} signal=${signal ?: "-"} " +
            "rv=${rv.ifBlank { "NONE" }}${if (customRendezvous().isNotBlank()) " (custom)" else ""} " +
            "ticket=${if (token.isNullOrBlank()) "MISSING" else "yes"} " +
            "paircode=${if (code.isNullOrBlank()) "none" else "yes"}")
        showWeb("http://127.0.0.1:$httpPort/#native", role, key, name, signal,
            rv, token, pairPayload, code, codeExp)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(
        url: String, role: String, key: String, name: String,
        signal: String?, rendezvous: String, token: String?, pairPayload: String? = null,
        pairCodeArg: String? = null, pairCodeExpArg: Long = 0L
    ) {
        clearScreen()
        val serverHost = Uri.parse(url).host

        val view = WebView(this)
        web = view
        var kicked = false

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // channel keys live here
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Tighter privacy: the WebView's Safe Browsing checks go to Google
            // from this app's own uid (measured on the emulator). The page is
            // bundled and served from loopback, so they protect nothing here.
            safeBrowsingEnabled = !strictPrivacy()
        }
        view.setBackgroundColor(Hue.BG)
        // GUARD: this binding is NOT origin-scoped. Android has no per-origin
        // form of addJavascriptInterface — every frame the WebView loads gets
        // `TawnyNative`, including any third-party iframe. It is safe here only
        // because of two invariants that must hold together:
        //
        //   1. the page is bundled in the APK and served from this process's own
        //      loopback server (AssetHttpServer), never from the network, and
        //   2. `public/index.html` embeds no iframe, and its meta CSP is
        //      `default-src 'none'` with no `frame-src`, so one cannot be
        //      loaded even if a script tried.
        //
        // Break either one — an <iframe>, an external help page, a remote CDN
        // for a font — and a foreign origin can call snapshot(), keepAwake() and
        // the rest of Bridge directly. If that day comes, move the bridge behind
        // WebViewCompat.addWebMessageListener(..., allowedOriginRules), which
        // *is* origin-scoped. See SECURITY.md, "JavaScript bridge".
        view.addJavascriptInterface(Bridge(), "TawnyNative")

        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.origin.host != serverHost) {
                    request.deny(); return
                }
                val allowed = request.resources.filter { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            has(android.Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            has(android.Manifest.permission.RECORD_AUDIO)
                        else -> false
                    }
                }
                if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
            }

            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                Log.d("Tawny", "${m.message()} @${m.lineNumber()}")
                return true
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                v: WebView, req: WebResourceRequest
            ): Boolean {
                val target = req.url
                if (target.host == serverHost) return false
                // Hand off only ordinary web links. Passing every scheme to
                // ACTION_VIEW would let the page launch intent://, file:// and
                // friends at other apps on the device.
                if (target.scheme !in setOf("http", "https")) return true
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, target))
                } catch (e: Exception) {
                    toast(getString(R.string.err_no_app_for_link))
                }
                return true
            }

            override fun onPageFinished(v: WebView, u: String) {
                if (kicked) return
                kicked = true
                pushSafeInsets()      // before the page paints its control rail
                v.evaluateJavascript(
                    "window.tawnyStart && window.tawnyStart(" +
                        "${jsStr(role)},${jsStr(key)},${jsStr(name)}," +
                        "${signal?.let { jsStr(it) } ?: "null"},${jsStr(rendezvous)}," +
                        "${token?.let { jsStr(it) } ?: "null"},{theme:${jsStr(themeMode())}," +
                        // "Follow system" is resolved here: the WebView's own
                        // prefers-color-scheme tracks the app's forced mode.
                        "systemTheme:${jsStr(systemTheme())}," +
                        "coachSeen:${org.json.JSONArray(coachSeen().toList())}," +
                        // The page cannot see the app's own Animations switch —
                        // a WebView reads prefers-reduced-motion off the system
                        // setting, which is the one thing this switch exists to
                        // be independent of — so it has to be told.
                        "motion:${jsStr(if (stillMode()) "off" else "on")}," +
                        "lang:${jsStr(currentLang())}," +
                        "pairCode:${pairCodeArg?.let { jsStr(it) } ?: "null"}," +
                        "pairExp:$pairCodeExpArg,servers:${serversJson()}})",
                    null
                )
                // Only the live screen has a WebView to tell, so this is where
                // the accelerometer goes on. clearScreen() turns it back off.
                startOrientationWatch()
            }

            override fun onReceivedError(
                v: WebView, req: WebResourceRequest, err: WebResourceError
            ) {
                if (!req.isForMainFrame) return
                endLive()
                showError(err.description?.toString() ?: getString(R.string.err_load_app))
            }
        }

        // Snapshots come across the bridge as base64 (see Bridge.saveImage), so
        // this only fires for stray links. Same scheme rule as navigation.
        view.setDownloadListener { dlUrl, _, _, _, _ ->
            val u = Uri.parse(dlUrl)
            if (u.scheme !in setOf("http", "https")) return@setDownloadListener
            try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
        }

        root.addView(view, FrameLayout.LayoutParams(MP, MP))
        versionView?.visibility = View.GONE      // the live view owns the surface

        if (pairPayload != null) {
            pairOverlay = buildPairOverlay(name, pairPayload)
            root.addView(pairOverlay, FrameLayout.LayoutParams(MP, MP))
            refreshSystemBars()   // the pairing sheet is now up over the video
        }
        // Station live view: swipe / back ends the session.
        if (role == "station") swipeNav(back = { confirmEndCall() }, forward = null)

        view.loadUrl(url)
    }

    /**
     * The pairing QR, shown over the Watcher's live view until a Handheld
     * connects (Bridge "watching"/"waiting" toggle its visibility).
     *
     * A bottom sheet over a transparent top half, not a full-screen cream
     * sheet: the live camera the Monitor is already showing plays right
     * through the gap, so "is the pet actually in frame" is answered by
     * looking, not by a separate "see what the camera sees" tap-away that
     * used to be the only route back to a code you might still need. The
     * pet's name is the rename trigger (no separate "Rename this monitor"
     * row), and the link copies on a single tap (no separate "show as link"
     * screen) \u2014 three formerly-separate actions folded into the controls
     * that were already on screen for a different reason.
     *
     * That gap is negotiable too: the sheet has a handle and drags between
     * three stops — a bare sliver with the camera filling the screen, the
     * resting height built here, and full screen with the camera hidden
     * altogether — because "how much camera versus how much code" has a
     * different answer while you are aiming the phone at a cat bed than it
     * does while you are reading the link out to someone. The mechanism is at
     * the bottom of this function, next to the panel it moves.
     */
    private fun buildPairOverlay(name: String, payload: String): View {
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            // No background: this sits over the Watcher's own live camera
            // preview, and stays that way everywhere but the sheet below.
        }

        // A soft scrim, not a solid bar: the page's own status rail (dot,
        // "Connecting", battery) is drawn by the WebView underneath and used
        // to be hidden by the old full-screen cream sheet along with
        // everything else. It would otherwise show through right where the
        // back arrow sits; this fades it without hiding the camera itself,
        // which stays fully visible everywhere below it.
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x8A000000.toInt(), 0x00000000)
            )
            layoutParams = FrameLayout.LayoutParams(MP, dp(110))
        })

        root.addView(backLink(overCamera = true) { confirmEndCall() }.apply {
            layoutParams = FrameLayout.LayoutParams(WC, WC)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                (v.layoutParams as FrameLayout.LayoutParams).topMargin = b.top
                insets
            }
        })

        // The sheet's own two insets, kept where the drag code below can read
        // them: the bottom one because the resting sheet has to clear the nav
        // bar, the top one because a sheet dragged all the way up ends level
        // with the status bar and would otherwise run its pet name under the
        // clock.
        var barTop = 0
        var barBottom = 0

        // The sheet's drag, reachable from the rows built below it.
        //
        // The gesture itself is defined at the bottom of this function, where
        // the views it moves exist; the two tappable rows in the middle of the
        // sheet — the pet's name and the link — are built long before that and
        // still have to offer it, because they sit exactly where a hand reaches
        // to pull the sheet and a sheet that refuses to move under half of
        // itself feels broken rather than careful. Hence a hook rather than a
        // local function: assigned once, at the end, and null only in the
        // window before then, when nothing is on screen to touch.
        var dragTouch: ((View, MotionEvent) -> Boolean)? = null

        // The content of the sheet, no longer the sheet itself: the cream, the
        // rounded top corners and the lift now belong to the full-height panel
        // built at the bottom of this function, and this is the column of stuff
        // that rides inside it. Nothing here knows how tall the panel is.
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(20))
            layoutParams = FrameLayout.LayoutParams(MP, WC)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                barTop = b.top; barBottom = b.bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dp(20) + b.bottom)
                insets
            }
        }

        // The pet's name IS the rename control \u2014 tap it rather than hunt for a
        // separate row further down. A quiet pencil sits beside it at rest so
        // the affordance is discoverable at all (with nothing there, a first
        // Monitor has no way to learn the name is tappable); the word "Rename"
        // still only appears under a finger, the same reveal-on-touch trick the
        // [+] chip uses, so the sheet does not read as "a button" at rest.
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(roundRect(0, Color.TRANSPARENT), tint = Hue.BERRY)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = lp(centerH = true)
        }
        nameRow.addView(TextView(this).apply {
            text = name
            setTextColor(Hue.TEXT)
            textSize = Type.TITLE
            typeface = uiFontSemi
            setLineSpacing(0f, Type.LEAD_TIGHT)
        })
        val pencil = PencilMark(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).also {
                it.leftMargin = dp(9)
                it.topMargin = dp(3)   // optical centre against a 27sp cap height
            }
        }
        nameRow.addView(pencil)
        val renameHint = TextView(this).apply {
            text = "  " + getString(R.string.pairsheet_rename)
            setTextColor(Hue.BERRY)
            textSize = Type.SUB
            typeface = uiFont
            visibility = View.GONE
        }
        nameRow.addView(renameHint)
        nameRow.setOnTouchListener { v, ev ->
            // The name is the first thing under the drag handle, so it is also
            // where a hand lands to pull the sheet about. The drag hook sees the
            // event first and only claims it once the finger is past the touch
            // slop, which leaves a tap a tap; when it does claim one, the
            // touch-reveal is wound straight back, because a row still
            // advertising "Rename" under a finger that is now dragging the sheet
            // is promising something that is no longer going to happen.
            val grabbed = dragTouch?.invoke(v, ev) ?: false
            when {
                grabbed -> {
                    renameHint.visibility = View.GONE
                    pencil.tint = Hue.DIM
                }
                ev.actionMasked == MotionEvent.ACTION_DOWN -> {
                    renameHint.visibility = View.VISIBLE
                    pencil.tint = Hue.BERRY
                }
                ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL -> {
                    renameHint.visibility = View.GONE
                    pencil.tint = Hue.DIM
                }
            }
            grabbed
        }
        nameRow.tapFeedback {
            promptRoomName { newName ->
                prefs.edit().putString("channelName", newName).apply()
                goLive("station")   // rebuild the live view + a fresh QR
            }
        }
        sheet.addView(nameRow)

        // One axis. The name and this line used to be MATCH_PARENT and so read
        // left-aligned (and 8dp further in than each other) under a centred QR
        // \u2014 three different left edges on a sheet with one idea on it.
        sheet.addView(TextView(this).apply {
            text = getString(R.string.pairsheet_scan_or_link)
            setTextColor(Hue.DIM)
            textSize = Type.SUB
            typeface = uiFont
            gravity = Gravity.CENTER
            layoutParams = lp(topMargin = 2)
        })

        val qr = ImageView(this).apply {
            val s = dp(170)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        // The bitmap carries a one-module quiet zone, which is a quarter of what
        // the spec asks for and left the code running right up to a hard white
        // edge \u2014 a raw asset pasted onto the sheet, and on the dark palette a
        // glaring square brick. The padding here is the rest of that quiet zone
        // and the rounded card is what turns it into a deliberate surface; the
        // hairline is what keeps the card's edge visible on the light palette,
        // where Hue.PANEL is itself pure white.
        sheet.addView(FrameLayout(this).apply {
            background = roundRect(Color.WHITE, Hue.LINE, Radius.CARD)
            val p = dp(13); setPadding(p, p, p, p)
            layoutParams = lp(topMargin = 18, centerH = true)
            addView(qr)
        })
        // A 640x640 ZXing encode is not free, and this runs on the way into a
        // live session where the UI thread is already busy.
        pairQrView = qr
        pairPayloadNow = payload
        drawPairQr(payload)

        // One tap, straight to the clipboard \u2014 the old "Show as link" screen
        // was a whole extra dialog to get to the same string. The glyph carries
        // the accent and the URL stays dim: the row is an action, and the forty
        // characters of base64 in it are not something anyone reads.
        val linkRow = TextView(this).apply {
            setTextColor(Hue.DIM)
            textSize = Type.LABEL
            typeface = uiFont
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = pressable(roundRect(Hue.RAISE, Color.TRANSPARENT, Radius.CONTROL))
            layoutParams = lp(topMargin = 16, centerH = true)
            tapFeedback {
                // Deliberately the *current* payload, not the one this sheet
                // was built with: the code behind it rotates, and copying a
                // stale link is the exact failure the countdown exists to
                // prevent.
                val live = pairPayloadNow ?: payload
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Tawny pairing", live))
                toast(getString(R.string.pairsheet_link_copied))
            }
        }
        // Draggable for the same reason the name is: it is a wide row across the
        // sheet, and the only thing that tells a finger it is a button rather
        // than somewhere to grab is that it copies when tapped — which it still
        // does, because the hook declines everything short of a real drag.
        linkRow.setOnTouchListener { v, ev -> dragTouch?.invoke(v, ev) ?: false }
        pairLinkView = linkRow
        showPairLink(payload)
        sheet.addView(linkRow)

        // The code has ten minutes in it, and a Monitor is a phone left sitting
        // on this screen \u2014 so a silent deadline would mean a dead code on
        // display with nothing to say why the far phone was refused. It belongs
        // with the code it describes, above the rule, not stacked under the
        // status line as a second grey sentence of the same weight.
        pairCountdown = TextView(this).apply {
            setTextColor(Hue.DIM)
            textSize = Type.CAPTION
            typeface = uiFont
            gravity = Gravity.CENTER
            alpha = 0.85f
            layoutParams = lp(topMargin = 12, centerH = true)
        }
        sheet.addView(pairCountdown)

        // A rule under the two ways in, so the live status below it reads as a
        // footer rather than as a fourth line of small print.
        sheet.addView(View(this).apply {
            setBackgroundColor(Hue.LINE)
            layoutParams = LinearLayout.LayoutParams(MP, dp(1)).also { it.topMargin = dp(17) }
        })

        val statusRow = waitingRow(pairSheetStatus())
        pairStatus = statusRow.getChildAt(1) as? TextView
        statusRow.layoutParams = lp(topMargin = 15)
        sheet.addView(statusRow)

        // The sheet used to meet the camera on a bare edge. Over a bright scene
        // \u2014 and on the light palette Hue.PANEL is pure white \u2014 that edge simply
        // vanished, so the sheet had no bottom to it. This seats it: a short
        // fade into the footage, the same device as the scrim behind the back
        // arrow. It used to be stacked directly above the sheet in a column; now
        // that the sheet's top edge moves under a finger, it is a free-floating
        // strip that the drag code below keeps parked on the seam by translating
        // it along with the panel.
        val seam = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x4D000000)
            )
            layoutParams = FrameLayout.LayoutParams(MP, dp(46)).also { it.gravity = Gravity.BOTTOM }
        }
        root.addView(seam)

        /*
         * The sheet is a full-screen panel that mostly hangs off the bottom of
         * the screen.
         *
         * The obvious way to build a draggable sheet is to set its height as the
         * finger moves. That means a measure-and-layout pass of the whole window
         * on every touch frame, and the thing directly underneath this overlay is
         * a WebView rendering a live camera at 30fps \u2014 the one place in this app
         * where stealing the UI thread is most visible. So the panel is instead
         * MATCH_PARENT tall for its whole life and never re-measured: what moves
         * is translationY, which the render thread can apply without a layout at
         * all. "Height" everywhere below means "how much of the panel is on
         * screen", i.e. root.height - translationY, and the content column is
         * laid out against the panel's *top* edge — the edge the user is
         * actually holding — with its own translation for the rest.
         *
         * A consequence worth knowing: the panel's touch bounds are its
         * translated bounds, so at rest it claims only the strip it visibly
         * covers and the back arrow above it still gets its taps. Dragged to full
         * screen it does swallow that arrow \u2014 correctly, because at that point
         * the arrow is behind an opaque panel and nobody can see what they would
         * be aiming at.
         */
        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Hue.PANEL)
                val r = dp(Radius.CARD).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            elevation = dp(8).toFloat()
            // Nothing is bound to this click; it exists so View.onTouchEvent
            // keeps returning true after the listener below declines ACTION_DOWN,
            // which is the only way a gesture that starts as a possible tap can
            // still turn into a drag (the same reason the sessions-list card is
            // clickable). It also stops taps on the sheet's blank areas falling
            // through to the page underneath, which they quietly used to.
            isClickable = true
            // Positioned by the first applyHeight() below, once there is a
            // measured column to position it against. Until then it would
            // otherwise flash across the whole screen for a frame.
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(MP, MP)
        }
        panel.addView(sheet)

        // The grab handle: a 36x4dp pill, but a full-width 30dp strip of touch
        // target around it, because the pill is also the only thing left on
        // screen when the sheet is collapsed and a 4dp target is not a target.
        // It is added after the content column so it wins the hit test in the
        // band they share, and it carries Hue.LINE \u2014 the hairline colour \u2014 so it
        // reads as an edge treatment rather than as a control with a job.
        val handle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, dp(30))
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    setColor(Hue.LINE)
                    cornerRadius = dp(2).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(dp(36), dp(4)).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                    it.topMargin = dp(11)
                }
            })
        }
        panel.addView(handle)
        root.addView(panel)

        // Three stops, and the sheet is always resting on one of them.
        val PEEK = 0; val NATURAL = 1; val FULL = 2
        var stop = NATURAL
        var dragging = false

        // Just the handle plus a sliver of the rounded corner, clear of the
        // bottom inset so the collapsed grip never lands on the gesture bar and
        // gets read as a swipe-up-to-home instead.
        fun peekHeight() = dp(32) + barBottom
        // The resting height is whatever the content measures to \u2014 asked of the
        // laid-out column rather than cached, so a longer pet name, a rotation or
        // a late inset all move the resting stop without anyone recalculating it.
        fun naturalHeight() = sheet.height
        fun heightOf(s: Int) = when (s) {
            PEEK -> peekHeight()
            FULL -> root.height
            else -> naturalHeight()
        }

        /**
         * Put the panel at [h] pixels of on-screen height. Everything the drag
         * does goes through here, dragging and snapping alike, so there is one
         * description of what any given height looks like.
         *
         * Three things move with the height rather than being switched at a
         * threshold. Below the resting stop the content column dips towards
         * invisible, so collapsing does not end with the top of the pet's name
         * poking out of a sliver that is meant to read as a bare edge (and it
         * goes properly INVISIBLE at the bottom, or the name would still be
         * catching taps it no longer looks like it deserves). Above the resting
         * stop the column slides down half of the extra height, which keeps it
         * optically centred in the panel all the way to full screen — a QR left
         * clinging to the top edge with a third of a phone of empty cream under
         * it looks like a layout that ran out, not like a sheet that opened.
         * And the handle — which does stay welded to the top edge, because that
         * is the edge you are holding — carries the status-bar inset in the same
         * proportion, so at full screen it sits below the clock rather than
         * behind it, while the resting stop is untouched because there the
         * proportion is zero.
         */
        fun applyHeight(h: Int, animated: Boolean) {
            val screen = root.height
            val nat = naturalHeight()
            if (screen == 0 || nat == 0) return          // not laid out yet
            val peek = peekHeight()
            val ty = (screen - h).toFloat()
            val fade = ((h - peek).toFloat() / (nat - peek).coerceAtLeast(1)).coerceIn(0f, 1f)
            val open = ((h - nat).toFloat() / (screen - nat).coerceAtLeast(1)).coerceIn(0f, 1f)
            val lift = barTop * open
            val centre = maxOf(lift, (h - nat).coerceAtLeast(0) / 2f)
            panel.visibility = View.VISIBLE
            if (fade > 0.02f) sheet.visibility = View.VISIBLE
            if (animated) {
                panel.animate().translationY(ty).setDuration(150).start()
                seam.animate().translationY(-h.toFloat()).setDuration(150).start()
                sheet.animate().alpha(fade).translationY(centre).setDuration(150)
                    .withEndAction { if (fade <= 0.02f) sheet.visibility = View.INVISIBLE }
                    .start()
                handle.animate().translationY(lift).setDuration(150).start()
            } else {
                panel.animate().cancel(); seam.animate().cancel()
                sheet.animate().cancel(); handle.animate().cancel()
                panel.translationY = ty
                seam.translationY = -h.toFloat()
                sheet.alpha = fade
                sheet.translationY = centre
                handle.translationY = lift
                sheet.visibility = if (fade <= 0.02f) View.INVISIBLE else View.VISIBLE
            }
        }

        // The panel is never re-measured, so the only news about how tall the
        // resting stop should be arrives here: first layout, the window insets
        // landing, a rename making the title wrap, a rotation. Re-seating the
        // current stop on each of those is also what puts the panel on screen in
        // the first place.
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!dragging) applyHeight(heightOf(stop), false)
        }

        /*
         * Drag the sheet up over the camera, or down to a sliver of itself.
         *
         * Same shape as the swipe-to-delete card on the sessions list: decline
         * ACTION_DOWN so the view underneath keeps its press state and its
         * click, and only take the gesture over once the finger has travelled
         * past the touch slop. That deferral is the whole safety property here,
         * because this sheet is made of tap targets \u2014 the name opens the rename
         * dialog, the link copies itself \u2014 and a listener that grabbed the
         * gesture on contact would eat both. It is also what lets those two rows
         * hand their own events to this same code (see the hook at the top of
         * the function) instead of being dead zones in the middle of a sheet
         * that otherwise drags: a touch that turns into a drag is one they never
         * finish, and a touch that stays put is one this never wanted.
         *
         * On release it snaps to whichever of the three stops is nearest, with
         * the same 150ms and the same haptic-on-arrival as the card \u2014 but only
         * when the stop actually changed, so a nudge that settles back where it
         * started stays silent.
         */
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var startHeight = 0
        var startStop = NATURAL
        dragTouch = fun(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    dragging = false
                    startStop = stop
                    startHeight = heightOf(stop)
                    return false     // let the press state and any click still happen
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (!dragging && abs(dy) > slop) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = false
                        v.cancelLongPress()
                    }
                    if (!dragging) return false
                    applyHeight((startHeight - dy).toInt().coerceIn(peekHeight(), root.height), false)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    val h = (startHeight - (event.rawY - downY)).toInt()
                        .coerceIn(peekHeight(), root.height)
                    val peek = peekHeight(); val nat = naturalHeight(); val full = root.height
                    stop = when {
                        h <= (peek + nat) / 2 -> PEEK
                        h <= (nat + full) / 2 -> NATURAL
                        else -> FULL
                    }
                    applyHeight(heightOf(stop), true)
                    if (stop != startStop) haptic()
                    return true
                }
                else -> return false
            }
        }
        // Everywhere on the sheet that is not one of those two rows: the QR, the
        // countdown, the status line, the handle, and all the space between them.
        panel.setOnTouchListener { v, event -> dragTouch?.invoke(v, event) ?: false }

        // Where the sheet was left is a property of one look at it, not of the
        // monitor: the next time this overlay is put back up \u2014 a Handheld
        // dropping off, the [+] chip \u2014 it is being shown *because* someone needs
        // the code, and handing them the sliver they collapsed it to an hour ago
        // would be handing them nothing.
        pairSheetReset = {
            stop = NATURAL
            dragging = false
            applyHeight(heightOf(NATURAL), false)
        }

        startPairCountdown()
        return root
    }

    /** Puts the pairing sheet back on its resting stop \u2014 see the drag code at
     *  the end of [buildPairOverlay] for why it does not remember the last one.
     *  Null until a sheet has been built, i.e. on any screen but a Monitor's. */
    private var pairSheetReset: (() -> Unit)? = null

    /** The truncated link on the pairing sheet. It has to be re-rendered when
     *  the code rotates, or the string on screen quietly stops matching both
     *  the QR above it and what a tap copies. */
    private var pairLinkView: TextView? = null

    private fun showPairLink(payload: String) {
        val v = pairLinkView ?: return
        val s = android.text.SpannableString("\u29c9  $payload")   // U+29C9, a link glyph
        s.setSpan(
            android.text.style.ForegroundColorSpan(Hue.BERRY), 0, 1,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        v.text = s
    }

    /**
     * The switch on the Animations row.
     *
     * The row used to end in the word "On" or "Off", set in the same weight as
     * the chevrons beside it — which made it read as a third kind of trailing
     * glyph rather than as a control, and left the state to be *read* on a
     * panel where everything else is recognised at a glance. A track and a knob
     * are what a setting looks like; the label under the title carries the
     * meaning, and this carries the state.
     *
     * Drawn rather than a Material `SwitchCompat` for the same reason as
     * everything else in this file: the stock widget brings its own accent,
     * its own ripple and its own metrics, none of which are the palette's, and
     * it would be the only imported control on any screen.
     */
    private class SwitchMark(ctx: Context, on: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        var on: Boolean = on
            set(v) { field = v; invalidate() }

        override fun onDraw(c: Canvas) {
            if (width <= 0 || height <= 0) return
            // Authored in a 46x28 box and scaled, so the knob keeps its inset
            // and the track its radius whatever density this lands on.
            val s = min(width / 46f, height / 28f)
            c.save()
            c.translate((width - 46f * s) / 2f, (height - 28f * s) / 2f)
            c.scale(s, s)
            p.style = Paint.Style.FILL
            // Off is a grey track under a pale knob — the usual way round, and
            // the reason it is Hue.DIM held down rather than Hue.LINE is that
            // Hue.LINE is a hairline colour: against Hue.PANEL, which is pure
            // white on the light palette, it left the whole control a rumour.
            p.color = if (on) Hue.BERRY else Hue.DIM
            if (!on) p.alpha = 78
            c.drawRoundRect(RectF(0f, 0f, 46f, 28f), 14f, 14f, p)
            p.alpha = 255
            p.color = if (on) Hue.ON_ACCENT else Hue.PANEL
            c.drawCircle(if (on) 32f else 14f, 14f, 10f, p)
            // The knob gets its own edge when off, so it reads as a disc
            // sitting in the track rather than as a hole punched through it.
            if (!on) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.1f
                p.color = Hue.DIM
                p.alpha = 70
                c.drawCircle(14f, 14f, 9.5f, p)
                p.alpha = 255
            }
            c.restore()
        }
    }

    /**
     * The little pencil beside the pet's name, drawn rather than typed.
     *
     * The obvious character for it, ✎ (U+270E), is not in Mukta, so it fell
     * through to whichever symbol font the device happens to carry: thin
     * outline clip-art on a Pixel, and on any phone whose fallback for that
     * block is the colour emoji font, a blue-and-yellow emoji that ignores
     * setTextColor outright. Twelve lines of Canvas is the same mark on every
     * device, in the palette's own colour, and this file draws everything else
     * that way already.
     */
    private class PencilMark(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tip = Path()

        /** Hue.DIM at rest, Hue.BERRY under a finger. */
        var tint: Int = Hue.DIM
            set(v) { field = v; invalidate() }

        override fun onDraw(c: Canvas) {
            if (width <= 0) return
            p.color = tint
            c.save()
            // Nib up and to the right, the angle a pencil is held at; the body
            // is drawn flat and the whole 24-unit box is turned instead.
            c.rotate(-45f, width / 2f, height / 2f)
            c.scale(width / 24f, width / 24f)
            c.drawRoundRect(RectF(2.5f, 9.2f, 14.2f, 14.8f), 1.6f, 1.6f, p)
            tip.reset()
            tip.moveTo(15.2f, 9.2f); tip.lineTo(15.2f, 14.8f); tip.lineTo(21.5f, 12f)
            tip.close()
            c.drawPath(tip, p)      // the gap left of it is the wood shoulder
            c.restore()
        }
    }

    // ---- the pairing code's ten minutes, on screen -------------------------

    /** The QR image on the pairing sheet, and the payload currently drawn in it. */
    private var pairQrView: ImageView? = null
    private var pairPayloadNow: String? = null

    /** Encode off the UI thread — a 640x640 ZXing encode is not free. */
    private fun drawPairQr(payload: String) {
        val target = pairQrView ?: return
        io.execute {
            val bmp = try { qrBitmap(payload, 640) } catch (e: Exception) { null }
            runOnUiThread { if (bmp != null && target.isAttachedToWindow) target.setImageBitmap(bmp) }
        }
    }

    /**
     * Rebuild the pairing payload around a freshly minted code, redraw the QR,
     * and tell the page — which is the side that actually refuses a Handheld —
     * which code now counts. Without that last step the sheet would show a new
     * code the Monitor did not accept.
     */
    private fun refreshPairCode() {
        val build = rebuildPairPayload ?: return
        rotatePairCode()
        val payload = build()
        pairPayloadNow = payload
        drawPairQr(payload)
        showPairLink(payload)
        web?.evaluateJavascript(
            "window.tawnyPairCode && window.tawnyPairCode(" +
                "${jsStr(pairCode ?: "")},$pairCodeExp)", null
        )
        Diag.log("shell", "pairing code rotated — ${PAIR_TTL_MS / 1000}s")
    }

    private fun startPairCountdown() {
        stopPairCountdown()
        if (pairCountdown == null) return
        val tick = object : Runnable {
            override fun run() {
                val left = pairCodeLeftMs()
                if (left <= 0) refreshPairCode()
                val secs = ((if (left <= 0) PAIR_TTL_MS else left) / 1000).toInt()
                pairCountdown?.apply {
                    text = getString(R.string.pair_code_refreshes, secs / 60, secs % 60)
                    // Fine print for nine of its ten minutes, and the one line
                    // on the sheet that matters in the last one — a viewer part
                    // way through typing the link in wants to know it is about
                    // to be handed a different one.
                    val close = secs < 60
                    setTextColor(if (close) Hue.BERRY else Hue.DIM)
                    alpha = if (close) 1f else 0.85f
                }
                pairTick = this
                root.postDelayed(this, 1000)
            }
        }
        pairTick = tick
        root.post(tick)
    }

    private fun stopPairCountdown() {
        pairTick?.let { root.removeCallbacks(it) }
        pairTick = null
    }

    /** The user's choice: "system", "light" or "dark". */
    private fun themeMode(): String =
        prefs.getString("theme", null).takeIf { it == "light" || it == "dark" } ?: "system"

    /**
     * What the *phone* is set to, "light" or "dark". Read off the system
     * resources rather than this Activity's, which carry the app's forced mode
     * — so "Follow system" can still be resolved while the user has pinned one.
     */
    private fun systemTheme(): String {
        val night = android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    /** The palette on screen right now — "light" or "dark". */
    private fun currentTheme(): String = themeMode().takeIf { it != "system" } ?: systemTheme()

    private fun themeLabel(mode: String) = getString(
        when (mode) {
            "light" -> R.string.theme_light
            "dark" -> R.string.theme_dark
            else -> R.string.theme_system
        }
    )

    /**
     * Take a new theme choice: persist it, say what it is now, and re-theme.
     * The toast is the feedback the switch itself cannot give — "Follow system"
     * looks exactly like whichever of light or dark the phone is in, and when
     * the palette doesn't change at all there would otherwise be no sign the
     * tap registered. Toasted on the application context so it outlives the
     * recreate that follows.
     */
    private fun setThemeMode(mode: String, rebuild: (() -> Unit)? = null) {
        val before = currentTheme()
        prefs.edit().putString("theme", mode).apply()
        Toast.makeText(
            applicationContext, getString(R.string.theme_now, themeLabel(mode)), Toast.LENGTH_SHORT
        ).show()
        applyNightMode(mode)   // recreates the activity if the palette changes
        if (currentTheme() == before) rebuild?.invoke()
    }

    private fun jsStr(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun showError(message: String) {
        val role = prefs.getString("role", "viewer") ?: "viewer"
        themedDialog(
            title = getString(R.string.err_title),
            body = getString(R.string.err_body, message),
            primaryLabel = getString(R.string.common_retry),
            onPrimary = { goLive(role) },
            secondaryLabel = getString(R.string.err_start_over),
            onSecondary = {
                // Drop only this session's transient state, not the whole
                // prefs store — a wholesale clear() used to wipe recentSessions
                // too, so a load error on one session cost the user every
                // other saved session as well as forcing them through
                // onboarding again.
                prefs.edit()
                    .remove("channelKey").remove("channelName")
                    .remove("role").remove(PARKED_ROLE).remove("myToken").remove("pairToken")
                    .remove("signalUrl")
                    .apply()
                stopServers()
                backToSessionsOrWelcome()
            },
            cancelable = false
        )
    }

    /**
     * Viewer tried to connect but the monitor phone isn't running Tawny yet.
     * Show a warm, non-technical screen instead of an error dialog.
     */
    private fun showMonitorOffline() {
        screen = "offline"
        if (isFinishing) return
        Diag.log("shell", "showMonitorOffline — handheld gave up reaching the monitor")
        val petName = prefs.getString("channelName", null)
            ?.takeUnless { it.isBlank() } ?: getString(R.string.default_pet_name)
        clearScreen()
        swipeNav(back = { showHandheldHome() }, forward = null)
        val col = column(scroll = false)
        col.addView(IconView(this, "phone", behind = Hue.BG).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).also {
                it.bottomMargin = dp(6)
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.offline_title)
            setTextColor(Hue.TEXT)
            textSize = 24f
            typeface = uiFontSemi
            gravity = Gravity.CENTER
            layoutParams = lp(topMargin = 12)
        })
        col.addView(
            body(
                getString(R.string.offline_body, petName),
                maxW = 300
            )
        )
        col.addView(gap(8))
        col.addView(primary(getString(R.string.common_retry)) { goLive("viewer") })
        col.addView(link(getString(R.string.common_go_back)) { showHandheldHome() })
        mountCentered(col)
        root.addView(themeToggleView())
        root.addView(languageToggleView())
    }

    /**
     * Turned away because the monitor already has its three phones.
     *
     * This screen exists because the refusal used to land on "Monitor isn't on
     * yet" — the shell threw the page's message away and guessed from the role.
     * The monitor was on, and watching; the guess sent the user to check a
     * phone that was working perfectly.
     */
    private fun showMonitorFull(message: String) {
        screen = "full"
        if (isFinishing) return
        Diag.log("shell", "showMonitorFull — refused, monitor already has $MAX_VIEWERS phones")
        clearScreen()
        swipeNav(back = { showHandheldHome() }, forward = null)
        val col = column(scroll = false)
        col.addView(IconView(this, "phone", behind = Hue.BG).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).also {
                it.bottomMargin = dp(6)
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.full_title)
            setTextColor(Hue.TEXT)
            textSize = 24f
            typeface = uiFontSemi
            gravity = Gravity.CENTER
            layoutParams = lp(topMargin = 12)
        })
        col.addView(body(message, maxW = 300))
        col.addView(gap(8))
        col.addView(primary(getString(R.string.common_try_again)) { goLive("viewer") })
        col.addView(link(getString(R.string.common_go_back)) { showHandheldHome() })
        mountCentered(col)
        root.addView(themeToggleView())
        root.addView(languageToggleView())
    }

    // ----------------------------------------------------------- bridge

    inner class Bridge {
        /** Called by public/app.js as the session changes state. */
        @JavascriptInterface
        fun post(json: String) {
            val obj = try { org.json.JSONObject(json) } catch (e: Exception) { return }
            val event = obj.optString("event")
            val message = obj.optString("message").ifBlank { null }
            // Log off the UI thread: "diag" is chatty and carries no UI work.
            if (event == "diag") { Diag.log("web ", obj.optString("line")); return }
            Diag.log("web ", "event=$event" + (message?.let { " — $it" } ?: ""))
            runOnUiThread {
                when (event) {
                    "live" -> beginLive()
                    "idle" -> endLive()
                    "ended" -> {
                        saveRecentSession()
                        endLive()
                        if (prefs.getString("role", null) == "station") stopServers()
                        afterSession()
                    }
                    // Monitor: the number of Handhelds watching changed. The
                    // page sends this on every change of count — not once, on
                    // the first Viewer, which is what used to strand the user
                    // with no route back to the pairing code and therefore no
                    // way to add phones two and three.
                    "watching" -> {
                        viewersNow = obj.optInt("n", 1)
                        viewersMax = obj.optInt("max", MAX_VIEWERS).coerceIn(1, MAX_VIEWERS)
                        // A phone just joined, so drop the sheet and show the
                        // camera — but keep offering the code while there is
                        // still room, because that is the whole "add another
                        // phone" affordance.
                        pairOverlay?.visibility = View.GONE
                        syncPairChip()
                        refreshSystemBars()
                    }
                    "waiting" -> {
                        viewersNow = 0
                        viewersMax = obj.optInt("max", MAX_VIEWERS).coerceIn(1, MAX_VIEWERS)
                        removePairChip()
                        pairStatus?.text = pairSheetStatus().uppercase()
                        pairSheetReset?.invoke()
                        pairOverlay?.visibility = View.VISIBLE
                        refreshSystemBars()
                    }
                    // The OS took the camera back (screen off / backgrounded).
                    // The page handles the UX; record it so the diagnostics log
                    // can explain a "it froze" report after the fact.
                    "paused" -> Diag.log("shell", "capture paused — monitor left the foreground")
                    "resumed" -> Diag.log("shell", "capture resumed")
                    // A Viewer pressed a chime. Play it on the call's audio
                    // stream, where the page's own WebAudio cannot reach.
                    "chime" -> playChimeNative(obj.optString("slug"))
                    // Theme changed from the in-session web toggle.
                    "theme" -> {
                        val mode = obj.optString("mode")
                        if (mode == "light" || mode == "dark" || mode == "system") {
                            prefs.edit().putString("theme", mode).apply()
                            // Don't recreate mid-call; the next native screen
                            // picks the choice up from TawnyApp on restart, or
                            // from applyNightMode when it is next set.
                            if (!isLive) applyNightMode(mode)
                        }
                    }
                    // The first-call walkthrough was shown for this role.
                    "coachDone" -> {
                        val r = obj.optString("role")
                        if (r == "viewer" || r == "station") {
                            prefs.edit().putStringSet(COACH_SEEN, coachSeen() + r).apply()
                        }
                    }
                    "petname" -> {
                        val name = obj.optString("name").ifBlank { null } ?: return@runOnUiThread
                        prefs.edit().putString("channelName", name).apply()
                        updateRecentSessionName(prefs.getString("channelKey", null) ?: return@runOnUiThread, name)
                    }
                    "unreachable" -> showMonitorOffline()
                    "error" -> {
                        endLive()
                        // The page says *why* when it knows. Only fall back to
                        // guessing from the role when it doesn't.
                        when {
                            obj.optString("reason") == "full" ->
                                showMonitorFull(
                                    message ?: getString(R.string.full_body, MAX_VIEWERS)
                                )
                            // The Monitor (or the relay) refused the code this
                            // phone arrived with. Same screen as the scanner's
                            // own pre-flight refusal, so the ten minutes reads
                            // as one rule wherever it is noticed.
                            obj.optString("reason") == "expired" -> pairingExpired()
                            // A code-only link at a relay that only admits by
                            // ticket. Not expired, not offline: the page's own
                            // sentence says what is actually wrong.
                            obj.optString("reason") == "noticket" ->
                                showError(message ?: getString(R.string.err_could_not_start_session))
                            prefs.getString("role", null) == "viewer" -> showMonitorOffline()
                            else -> showError(message ?: getString(R.string.err_could_not_start_session))
                        }
                    }
                }
            }
        }

        /**
         * Dim mode, reaching the one thing the page cannot touch itself: the
         * backlight. Called by public/app.js as the black overlay opens and
         * closes. Gated on a live session so a stray call can never strand the
         * user on a black screen.
         */
        @JavascriptInterface
        fun setDimmed(on: Boolean) {
            runOnUiThread { applyDim(on && isLive) }
        }

        /**
         * WebView drops `<a download>` on blob: URLs, so snapshots would
         * disappear without this. The page hands us base64 instead.
         */
        @JavascriptInterface
        fun saveImage(dataUrl: String, filename: String) {
            // Decoding and writing a multi-megabyte PNG used to happen inside
            // runOnUiThread. It also went to getExternalFilesDir(), which is
            // app-private from API 29 on — so "Saved" was true but the picture
            // never appeared in the user's gallery. MediaStore, off the UI thread.
            val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .replace(Regex("^\\.+"), "_")        // no "." / ".." names
                .ifBlank { "snapshot.png" }
            io.execute {
                try {
                    val b64 = dataUrl.substringAfter("base64,")
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/Tawny"
                        )
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: throw java.io.IOException("no MediaStore row")
                    resolver.openOutputStream(uri).use { out ->
                        (out ?: throw java.io.IOException("no stream")).write(bytes)
                    }
                    runOnUiThread { toast(getString(R.string.snap_saved_photo)) }
                } catch (e: Exception) {
                    Log.w("Tawny", "snapshot failed", e)
                    runOnUiThread { toast(getString(R.string.snap_save_photo_failed)) }
                }
            }
        }

        /**
         * Same reasoning as saveImage: a blob: URL's <a download> is a no-op in
         * this WebView, so a recorded clip (capped at 20s in app.js) crosses the
         * bridge as base64 too. Videos go to MediaStore.Video, not .Images, or
         * they render as a broken thumbnail in the gallery despite saving fine.
         */
        @JavascriptInterface
        fun saveVideo(dataUrl: String, filename: String) {
            val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .replace(Regex("^\\.+"), "_")
                .ifBlank { "video.webm" }
            val mime = if (safe.endsWith(".mp4")) "video/mp4" else "video/webm"
            io.execute {
                try {
                    val b64 = dataUrl.substringAfter("base64,")
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/Tawny"
                        )
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: throw java.io.IOException("no MediaStore row")
                    resolver.openOutputStream(uri).use { out ->
                        (out ?: throw java.io.IOException("no stream")).write(bytes)
                    }
                    runOnUiThread { toast(getString(R.string.snap_saved_video)) }
                } catch (e: Exception) {
                    Log.w("Tawny", "video save failed", e)
                    runOnUiThread { toast(getString(R.string.snap_save_video_failed)) }
                }
            }
        }
    }

    // ------------------------------------------------------------- power
    //
    // The Monitor sits on a charger for hours, usually on an old handset whose
    // battery is already tired. Three levers live here, none of which needs a
    // new permission or a service:
    //
    //  * the backlight. Dim mode was a black <div> in the WebView, which on an
    //    LCD leaves the backlight burning at whatever the user set and on an
    //    AMOLED still drives the panel. Only the window can pull the actual
    //    light down, so the page asks us to.
    //  * the refresh rate. A monitor's own screen shows a still camera frame.
    //    Scanning it at 90 or 120Hz is heat and nothing else.
    //  * haptics. The vibrator is the most expensive actuator on the phone per
    //    millisecond of use, and during a session nobody is holding it.

    /**
     * Keep the CPU and the Wi-Fi radio awake for the length of a session.
     *
     * FLAG_KEEP_SCREEN_ON is not enough. On an LCD tablet, dim mode pins the
     * backlight to almost nothing, the panel goes genuinely dark, and the
     * platform starts treating the device as idle anyway: nine seconds after
     * dim was tapped on the T10Pro the relay socket died 1006, every reconnect
     * failed the same way, and a minute later DNS itself could not resolve a
     * host. The radio had been parked underneath a session that was still, as
     * far as the user was concerned, running.
     *
     *  * WifiLock — FULL_LOW_LATENCY where it exists, high-performance below —
     *    stops Wi-Fi power-save from parking the link. Needs no permission.
     *  * A partial WakeLock keeps the CPU scheduled so the WebRTC and signalling
     *    threads still run when the screen is dark. This is what WAKE_LOCK is
     *    for, and why it went back into the manifest.
     *
     * Both are Activity-scoped: acquired when a session starts, released the
     * moment it ends or the Activity is destroyed, so nothing survives the app
     * being closed and no foreground service is implied. Surviving the screen
     * being *actually* off is a different problem and still belongs to the
     * foreground-service / libwebrtc work in docs/DIRECTION.md §1A — this only
     * stops a session the user can see from having its radio taken away.
     */
    private fun acquireSessionLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = runCatching {
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tawny:session")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = runCatching {
                wm?.createWifiLock(mode, "tawny:session")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
        Diag.log("power", "session locks: wake=${wakeLock != null} wifi=${wifiLock != null}")
    }

    private fun releaseSessionLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        val had = wakeLock != null || wifiLock != null
        wakeLock = null
        wifiLock = null
        if (had) Diag.log("power", "session locks released")
    }

    /** Pin the backlight near-black for dim mode, or hand it back to the system. */
    private fun applyDim(on: Boolean) {
        if (isDimmed == on) return
        isDimmed = on
        val lp = window.attributes
        lp.screenBrightness =
            if (on) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        hideShellChrome(on)
        // Deliberately NOT hiding the system bars here, though they are the
        // brightest pixels left once the page goes black. Doing so makes the OS
        // throw up its full-screen education panel — a large white sheet with a
        // "Got it" button, right at the moment the user has put the phone down
        // and walked away. Verified on the emulator; it costs far more light,
        // and a tap, than the handful of status icons it would have saved.
        Diag.log("power", if (on) "backlight pinned to $DIM_BRIGHTNESS" else "backlight released")
    }

    /** Shell views hidden for the duration of dim mode, to be put back exactly. */
    private val dimHidden = mutableListOf<View>()

    /**
     * Dim mode's black sheet is drawn inside the WebView, but the shell floats
     * its own views on top of it — the "show pairing code" chip, and the build
     * stamp. Left alone they stay lit on an otherwise black screen: wrong to
     * look at, and on an AMOLED very nearly the only pixels still drawing
     * power. Hide every sibling of the WebView, and restore exactly those.
     */
    private fun hideShellChrome(on: Boolean) {
        if (on) {
            dimHidden.clear()
            for (i in 0 until root.childCount) {
                val v = root.getChildAt(i)
                if (v !== web && v.visibility == View.VISIBLE) {
                    v.visibility = View.INVISIBLE   // not GONE: no relayout on wake
                    dimHidden.add(v)
                }
            }
        } else {
            for (v in dimHidden) v.visibility = View.VISIBLE
            dimHidden.clear()
        }
    }

    /**
     * Ask the display for the slowest mode it offers *at the resolution it is
     * already in* — a mode switch that also changed the resolution would resize
     * the WebView mid-session. Returns the rate asked for, or null when the
     * panel has nothing slower than it is already running.
     */
    private fun applyLowRefreshRate(): Float? {
        val display = (if (android.os.Build.VERSION.SDK_INT >= 30) display
            else @Suppress("DEPRECATION") windowManager.defaultDisplay) ?: return null
        val current = display.mode ?: return null
        val slowest = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight &&
                    it.refreshRate >= MIN_REFRESH_HZ
            }
            .minByOrNull { it.refreshRate } ?: return null
        if (slowest.refreshRate >= current.refreshRate - 1f) return null
        val lp = window.attributes
        lp.preferredRefreshRate = slowest.refreshRate
        lp.preferredDisplayModeId = slowest.modeId
        window.attributes = lp
        return slowest.refreshRate
    }

    private fun restoreRefreshRate() {
        val lp = window.attributes
        if (lp.preferredRefreshRate == 0f && lp.preferredDisplayModeId == 0) return
        lp.preferredRefreshRate = 0f
        lp.preferredDisplayModeId = 0
        window.attributes = lp
    }

    private fun beginLive() {
        isLive = true
        liveSince = System.currentTimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireSessionLocks()
        if (prefs.getString("role", null) == "station") {
            startBatteryMirror()
            startChimeAudio()
        }
        val hz = applyLowRefreshRate()
        Diag.log("power", "live — refresh " + (hz?.let { "→ ${it}Hz" } ?: "already lowest"))
        refreshSystemBars()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        // Force the loudspeaker. On API 31+ `isSpeakerphoneOn` is deprecated and
        // often a no-op, which left the far end's talk-back routed to a silent
        // earpiece — so pin the built-in speaker as the communication device.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val speaker = am.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) runCatching { am.setCommunicationDevice(speaker) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    private fun endLive() {
        if (isLive) countSession(System.currentTimeMillis() - liveSince)
        isLive = false
        stopBatteryMirror()
        stopChimeAudio()
        // ...and never left holding a phone with its camera light still on.
        // The page turns it off on its own hang-up path, but the shell ends
        // sessions by routes of its own too (the "End the call?" dialog, a load
        // error, onDestroy). Tearing the WebView down releases the camera and
        // the LED with it; this is the explicit off, ahead of that.
        web?.evaluateJavascript("window.tawnyTorchOff && window.tawnyTorchOff()", null)
        // Whatever else happens, the user must never be left holding a phone
        // whose screen is pinned black with no live screen to tap.
        applyDim(false)
        restoreRefreshRate()
        releaseSessionLocks()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshSystemBars()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    /**
     * Mirror this phone's battery to the Handhelds for the length of a Monitor
     * session. `ACTION_BATTERY_CHANGED` is a sticky broadcast: the first
     * `registerReceiver` returns the current reading straight away, then it
     * fires again on every 1% step and every plug / unplug. All this does is
     * hand each reading to public/app.js, which forwards it over the signalling
     * channel to whoever is watching. No permission is needed to read it.
     */
    private fun startBatteryMirror() {
        if (batteryRx != null) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return
                val pct = Math.round(level * 100f / scale).coerceIn(0, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val charging = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING,
                    BatteryManager.BATTERY_STATUS_FULL -> true
                    BatteryManager.BATTERY_STATUS_DISCHARGING,
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                    else -> plugged != 0            // status unknown — fall back to "on a lead"
                }
                Diag.log("shell", "battery $pct% charging=$charging (status=$status plugged=$plugged)")
                web?.evaluateJavascript(
                    "window.tawnyBattery && window.tawnyBattery($pct, $charging)", null
                )
            }
        }
        batteryRx = rx
        Diag.log("shell", "battery mirror on")
        // ACTION_BATTERY_CHANGED is system-only, so the export flag is moot, but
        // targetSdk 34+ wants one stated. NOT_EXPORTED is the honest answer.
        ContextCompat.registerReceiver(
            this, rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun stopBatteryMirror() {
        batteryRx?.let { runCatching { unregisterReceiver(it) } }
        batteryRx = null
    }

    /**
     * Chime playback for a Monitor session. The page hands the shell a slug; the
     * shell plays the bundled clip through a SoundPool tagged
     * USAGE_VOICE_COMMUNICATION, so it rides the *call* audio stream. Played from
     * WebAudio in the page it lands on STREAM_MUSIC instead — which Android keeps
     * muted underneath a call, and which the volume keys will not touch while one
     * is running. The clips live in assets/web/sounds/ (kept in sync from
     * public/sounds/ by syncWebAssets); .ogg is not compressed in the APK, so
     * openFd() works.
     */
    private val chimeSlugs = listOf("bark", "pspsps", "meow", "goodboy", "bell")

    private fun startChimeAudio() {
        if (chimePool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        pool.setOnLoadCompleteListener { sp, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            val slug = chimeIds.entries.firstOrNull { it.value == sampleId }?.key
            if (slug != null && slug == pendingChime &&
                SystemClock.elapsedRealtime() - pendingChimeAt < 4000L) {
                pendingChime = null
                sp.play(sampleId, 0.95f, 0.95f, 1, 0, 1f)
            }
        }
        for (slug in chimeSlugs) {
            try {
                val fd = assets.openFd("web/sounds/$slug.ogg")
                chimeFds.add(fd)
                chimeIds[slug] = pool.load(fd, 1)
            } catch (e: Exception) {
                Diag.log("shell", "chime load failed for $slug: ${e.message}")
            }
        }
        chimePool = pool
    }

    private fun playChimeNative(slugRaw: String) {
        val slug = if (slugRaw in chimeSlugs) slugRaw else "bell"
        val pool = chimePool ?: run { startChimeAudio(); chimePool } ?: return
        val id = chimeIds[slug] ?: return
        val stream = pool.play(id, 0.95f, 0.95f, 1, 0, 1f)
        if (stream == 0) {                     // sample still decoding — play on load
            pendingChime = slug
            pendingChimeAt = SystemClock.elapsedRealtime()
        }
        Diag.log("shell", "chime $slug" + if (stream == 0) " (queued — loading)" else "")
    }

    private fun stopChimeAudio() {
        chimePool?.release()
        chimePool = null
        chimeIds.clear()
        for (fd in chimeFds) runCatching { fd.close() }
        chimeFds.clear()
        pendingChime = null
    }

    // ------------------------------------------------------- orientation
    //
    // The picture has to match how the phone is *held*, and the window is not
    // a reliable witness to that. With auto-rotate off — a rotation lock, or
    // simply the default on a handset that has been propped on a shelf for
    // hours — the Activity stays portrait however the phone is lying, so the
    // window never turns, the page's `screen.orientation` never changes, and
    // Chromium keeps handing getUserMedia frames turned to a portrait window.
    // The Monitor then streams a room lying on its side and nothing at either
    // end can tell, because nothing at either end can see past the window.
    //
    // So read the accelerometer directly and report both angles to the page,
    // which works out the difference and turns the picture (its own preview,
    // and every Handheld's, over the `meta` message it already sends).
    //
    // Both angles are **degrees clockwise from the phone's natural
    // orientation** — the units OrientationEventListener already reports in
    // ("90 = the device's left side is at the top", which is a quarter turn
    // clockwise). Display.getRotation() is documented as "the rotation of the
    // drawn graphics on the screen, which is the opposite direction of the
    // physical rotation of the device", so it is inverted into the same units
    // below. If a device ever disagrees, the `orientation device=… window=…`
    // line in the diagnostics log is what to read, and [windowRotationCW] is
    // the one table to change.

    private var orientWatch: OrientationEventListener? = null
    private var lastDeviceCW = -1
    private var lastWindowCW = -1

    private fun windowRotationCW(): Int {
        val r = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        } catch (e: Exception) { Surface.ROTATION_0 }
        return when (r) {
            Surface.ROTATION_90 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 90
            else -> 0
        }
    }

    /** Push the current pair of angles at the page, if either has moved. */
    private fun pushOrientation(deviceCW: Int = lastDeviceCW.coerceAtLeast(0)) {
        val w = windowRotationCW()
        if (deviceCW == lastDeviceCW && w == lastWindowCW) return
        lastDeviceCW = deviceCW
        lastWindowCW = w
        Diag.log("shell", "orientation device=$deviceCW window=$w")
        web?.evaluateJavascript(
            "window.tawnyOrientation && window.tawnyOrientation($deviceCW,$w)", null
        )
    }

    private fun startOrientationWatch() {
        if (orientWatch != null || web == null) return
        val w = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(deg: Int) {
                // Flat on a table, or being carried: no usable reading. Keep
                // the last one rather than snapping the picture to north.
                if (deg == ORIENTATION_UNKNOWN) return
                val q = ((deg + 45) / 90 * 90) % 360
                // A phone held near a 45° boundary would otherwise flip back
                // and forth on every degree of hand-shake. Only take a new
                // quarter once the reading is clearly inside it.
                if (q != lastDeviceCW) {
                    val off = ((deg - q + 540) % 360) - 180
                    if (abs(off) > 30) return
                }
                pushOrientation(q)
            }
        }
        if (!w.canDetectOrientation()) {
            // No accelerometer (a tablet in a dock, an emulator). The window is
            // then the only witness there is, which is the old behaviour.
            Diag.log("shell", "orientation: no sensor — following the window only")
            pushOrientation(windowRotationCW())
            return
        }
        w.enable()
        orientWatch = w
        pushOrientation(windowRotationCW())   // a first reading before the sensor speaks
    }

    private fun stopOrientationWatch() {
        orientWatch?.disable()
        orientWatch = null
        lastDeviceCW = -1
        lastWindowCW = -1
    }

    // -------------------------------------------------------- lifecycle

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The window turned. The phone may not have (a fold, a resize), so
        // re-read both angles rather than assuming they moved together.
        pushOrientation()
        // System light/dark flip (uiMode is in configChanges so we aren't
        // recreated). Reload the palette and refresh what's on screen.
        Hue.load(this)
        root.setBackgroundColor(Hue.BG)
        val w = web
        if (w != null) {
            w.evaluateJavascript(
                // Repaint only. This used to call tawnySetTheme with the
                // *resolved* palette, which posted it straight back as the
                // user's choice — so a phone going dark at sunset mid-call
                // silently pinned the app to dark for good.
                "window.tawnySystemTheme && window.tawnySystemTheme(${jsStr(systemTheme())})", null
            )
        } else if (scannerStop == null) {
            recreate()   // rebuild the current native screen with the new palette
        }
    }

    override fun onPause() {
        super.onPause()
        // Nobody is turning a phone whose app is not on screen, and the sensor
        // is not free.
        stopOrientationWatch()
        web?.evaluateJavascript(
            "window.dispatchEvent(new Event('tawny:background'))", null
        )
    }

    override fun onResume() {
        super.onResume()
        startOrientationWatch()
        if (isLive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Window attributes only bind while the window is showing, so both
            // of these have to be re-asserted after any trip through the
            // background — including the user pressing power off and on again.
            applyLowRefreshRate()
            if (isDimmed) { isDimmed = false; applyDim(true) }
        }
        web?.evaluateJavascript(
            "window.dispatchEvent(new Event('tawny:foreground'))", null
        )
    }

    override fun onDestroy() {
        endLive()
        releaseSessionLocks()   // belt and braces: nothing may outlive the Activity
        stopOrientationWatch()
        stopScanner()
        stopServers()
        scene?.stop()
        playScene?.stop()
        web?.destroy()
        web = null
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

// ============================================================ custom views

/**
 * A one-line label that, on press, sweeps an accent fill left-to-right and
 * reveals a second string underneath it — then wipes back. Used only on the
 * sessions-home "Support Tawny" row: base text says what it is, the wipe shows
 * where it goes. Everything on-palette — [Hue.BERRY] fill, [Hue.ON_ACCENT] for
 * the revealed text — and it measures to the wider of the two strings so
 * nothing reflows mid-animation.
 */
private class WipeLabel(
    ctx: Context,
    private val base: String,
    private val alt: String,
    tf: Typeface,
    textPx: Float,
    baseColor: Int,
) : View(ctx) {
    private val baseP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; textSize = textPx; color = baseColor; letterSpacing = 0.01f
    }
    private val altP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; textSize = textPx; color = Hue.ON_ACCENT; letterSpacing = 0.01f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hue.BERRY }
    private val lead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Hue.ON_ACCENT; alpha = 70
    }
    private val textW = maxOf(baseP.measureText(base), altP.measureText(alt))
    private var wipe = 0f
    private var anim: ValueAnimator? = null

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val fm = baseP.fontMetrics
        setMeasuredDimension(
            resolveSize((textW + 1f).toInt(), wSpec),
            resolveSize((fm.descent - fm.ascent + 1f).toInt(), hSpec),
        )
    }

    override fun onDraw(c: Canvas) {
        val by = -baseP.fontMetrics.ascent
        c.drawText(base, 0f, by, baseP)
        if (wipe <= 0f) return
        // Sweep only across the text, plus a rounded cap — not the whole laid-out
        // width, which in a weighted row would flash the entire slab.
        val end = textW + height * 0.55f
        val x = wipe * end
        c.save()
        c.clipRect(0f, 0f, x, height.toFloat())
        val pad = height * 0.12f
        val r = (height - 2f * pad) / 2f
        c.drawRoundRect(-r, pad, x, height - pad, r, r, fill)
        c.drawRect(x - 2f, 0f, x, height.toFloat(), lead)
        c.drawText(alt, 0f, by, altP)
        c.restore()
    }

    /** Sweep to [target] (0 hidden, 1 fully revealed). Honours the animator
     *  scale — 0 snaps with no motion. */
    fun sweep(target: Float, scale: Float) {
        anim?.cancel()
        if (scale <= 0f) { wipe = target; invalidate(); return }
        val opening = target > wipe
        anim = ValueAnimator.ofFloat(wipe, target).apply {
            duration = ((if (opening) 300 else 200) * scale).toLong()
            interpolator = if (opening)
                android.view.animation.DecelerateInterpolator(1.7f)
            else android.view.animation.AccelerateInterpolator(1.3f)
            addUpdateListener { wipe = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        super.onDetachedFromWindow()
    }
}

/**
 * Flat, rounded camera / phone glyph for the role cards — same soft filled
 * language as the owlet mascot. Body in the accent colour, details punched in
 * the card colour behind it, one tiny accent highlight.
 *
 * @param behind the colour actually behind this icon. The cut-out details are
 *   painted in it, so they read as holes. It used to be hard-coded to
 *   Hue.PANEL, which is right on a card but wrong on the two screens that put
 *   the icon straight onto Hue.BG — in dark mode the phone's "screen" and
 *   "home bar" rendered as visibly lighter brown rectangles and the icon just
 *   looked broken.
 */
/**
 * One burst of confetti for the update board: a few seconds of paper falling
 * across the whole window, then nothing — it stops drawing for good and never
 * takes a touch. Only mounted when animations are on; [speed] is the system
 * animator scale, so a slowed-down system gets slowed-down paper.
 */
private class ConfettiView(ctx: Context, private val speed: Float) : View(ctx) {
    private class Bit(
        var x: Float, var y: Float, val vx: Float, var vy: Float,
        var rot: Float, val spin: Float, val w: Float, val h: Float,
        val color: Int, val round: Boolean, val phase: Float,
    )
    private val bits = ArrayList<Bit>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random()
    private val d = ctx.resources.displayMetrics.density
    private var last = 0L
    private var age = 0f
    private val life = 3.6f

    init { isClickable = false; isFocusable = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (bits.isNotEmpty() || w == 0) return
        val palette = intArrayOf(Hue.BERRY, Hue.SKY, Hue.LIVE, 0xFFE8B04B.toInt(), 0xFFF29BB0.toInt())
        repeat(110) {
            bits += Bit(
                x = rnd.nextFloat() * w,
                y = -rnd.nextFloat() * h * 0.55f - 10 * d,
                vx = (rnd.nextFloat() - 0.5f) * 60 * d,
                vy = (120 + rnd.nextFloat() * 160) * d,
                rot = rnd.nextFloat() * 360f,
                spin = (rnd.nextFloat() - 0.5f) * 540f,
                w = (5 + rnd.nextFloat() * 6) * d,
                h = (3 + rnd.nextFloat() * 4) * d,
                color = palette[rnd.nextInt(palette.size)],
                round = rnd.nextInt(4) == 0,
                phase = rnd.nextFloat() * 6.28f,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f) / speed.coerceAtLeast(0.1f)
        last = now
        age += dt
        val fade = ((life - age) / 0.8f).coerceIn(0f, 1f)
        for (b in bits) {
            b.vy += 90 * d * dt
            b.x += (b.vx + sin(age * 3f + b.phase) * 40 * d) * dt
            b.y += b.vy * dt
            b.rot += b.spin * dt
            paint.color = b.color
            paint.alpha = (255 * fade).toInt()
            canvas.save()
            canvas.translate(b.x, b.y)
            canvas.rotate(b.rot)
            // Paper turning over: the width breathes, so it reads as flat stock.
            val sx = 0.35f + 0.65f * kotlin.math.abs(cos(age * 5f + b.phase))
            canvas.scale(sx, 1f)
            if (b.round) canvas.drawCircle(0f, 0f, b.h * 0.7f, paint)
            else canvas.drawRect(-b.w / 2, -b.h / 2, b.w / 2, b.h / 2, paint)
            canvas.restore()
        }
        if (age < life) postInvalidateOnAnimation()
    }
}

private class IconView(
    ctx: Context,
    private val kind: String,
    behind: Int = Hue.PANEL,
    tint: Int = Hue.BERRY,
) : View(ctx) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = behind }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    /** The strike's bloom — an expanding ring of the glyph's own colour. */
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    /** ...and the glyph washing toward the surface behind it, so the flash
     *  reads the same on white card stock as it does on brushed leather. */
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = behind }

    /** Glyph scale about its own centre; 1f at rest. Driven by [beat]. */
    private var pulse = 1f
    /** 0..1 strike intensity; 1f at the instant of the zap. Driven by [strike]. */
    private var flash = 0f
    /** Sideways jitter in 48-box units, also [strike]'s. */
    private var jolt = 0f
    private var pressAnim: ValueAnimator? = null

    /**
     * React to a press.
     *
     * @param strong a real double-thump — the heart on "Support Tawny", which
     *   is the one row where a bit of warmth is the whole point. Everything
     *   else gets a single soft swell, so the rows do not read as one control
     *   repeated twice.
     * @param scale the system animator scale; 0 means "remove animations" is on
     *   and nothing should move.
     */
    fun beat(strong: Boolean, scale: Float) {
        if (scale <= 0f) return
        pressAnim?.cancel()
        flash = 0f; jolt = 0f
        // Interpolated evenly through the keyframes: swell, relax, smaller
        // swell, settle — the shape of a heartbeat rather than a bounce.
        val frames = if (strong) floatArrayOf(1f, 1.28f, 1.03f, 1.15f, 1f)
                     else floatArrayOf(1f, 1.11f, 1f)
        pressAnim = ValueAnimator.ofFloat(*frames).apply {
            duration = ((if (strong) 520 else 260) * scale).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /**
     * The lightning row's press — one zap, and it is over.
     *
     * Deliberately nothing like [beat] or the label's wipe. Where the heart
     * swells (a *slow* thing getting bigger) this is fast and it does not
     * change size at all: the glyph blooms and shakes in place, then is still.
     * Three parts on one 380ms timeline:
     *
     *  - ATTACK. `flash` goes 0→1 in the first 9% — about one frame — because a
     *    strike that ramps in is a glow, not a strike. The decay is a squared
     *    falloff over the rest, which is the shape light actually leaves at.
     *  - BLOOM. A ring of the glyph's own colour, drawn *behind* the bolt,
     *    starting tight and expanding as it fades. At the same time the bolt is
     *    over-painted with the surface colour behind it, so it blinks out
     *    toward the card rather than toward white — the same read in the light
     *    palette and the dark one, which a white core flash would not give.
     *  - JOLT. Two damped sideways oscillations of under 2 units, done by 40%
     *    through. Enough to feel struck; not enough to look broken.
     *
     * These rows sit below the fold under a live session list and an animated
     * scene, so the whole thing is small, single-shot, and over in a third of a
     * second. Scaled by the animator setting like everything else; 0 does
     * nothing at all.
     */
    fun strike(scale: Float) {
        if (scale <= 0f) return
        pressAnim?.cancel()
        pulse = 1f
        pressAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (380 * scale).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                flash = if (p < 0.09f) p / 0.09f else {
                    val q = (1f - p) / 0.91f
                    q * q
                }
                jolt = if (p >= 0.42f) 0f
                       else sin(p * 15.0f) * 1.7f * (1f - p / 0.42f)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pressAnim?.cancel()
        pressAnim = null
        super.onDetachedFromWindow()
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, p: Paint) =
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 48f
        canvas.save()
        canvas.scale(s, s)
        // The glyph is authored in a 48x48 box, so the beat pivots on its middle.
        if (pulse != 1f) canvas.scale(pulse, pulse, 24f, 24f)
        when (kind) {
            "camera" -> {
                rr(canvas, 12f, 9f, 25f, 16f, 3f, body)          // viewfinder hump
                rr(canvas, 5f, 14f, 43f, 39f, 6f, body)          // body
                canvas.drawCircle(24f, 26.5f, 8f, cut)           // lens well
                canvas.drawCircle(24f, 26.5f, 4.2f, dot)         // lens
                canvas.drawCircle(21.6f, 24.1f, 1.5f, cut)       // glint
                canvas.drawCircle(37f, 19.5f, 1.8f, cut)         // flash
            }
            "heart" -> {
                val h = Path().apply {
                    moveTo(24f, 41f)
                    cubicTo(6f, 27f, 5f, 14f, 15f, 12f)
                    cubicTo(21f, 11f, 24f, 15f, 24f, 18.5f)
                    cubicTo(24f, 15f, 27f, 11f, 33f, 12f)
                    cubicTo(43f, 14f, 42f, 27f, 24f, 41f)
                    close()
                }
                canvas.drawPath(h, body)
                canvas.drawCircle(17.5f, 17f, 2.4f, cut)         // shine
            }
            "bolt" -> {
                // Lightning — the Bitcoin/Lightning tip row. Same construction
                // as the heart: one filled path in `body`, one `cut` detail.
                if (jolt != 0f) canvas.translate(jolt, 0f)
                val b = Path().apply {
                    moveTo(30f, 3.5f)      // apex
                    lineTo(11.5f, 26.5f)   // down the leading edge
                    lineTo(22f, 26.5f)     // inner notch
                    lineTo(18f, 44.5f)     // bottom tip
                    lineTo(36.5f, 21.5f)
                    lineTo(26f, 21.5f)
                    close()
                }
                if (flash > 0f) {
                    // Tight at the peak, expanding as it fades.
                    halo.alpha = (100f * flash).toInt()
                    canvas.drawCircle(23f, 24f, 8f + 17f * (1f - flash), halo)
                }
                canvas.drawPath(b, body)
                canvas.drawPath(Path().apply {                   // rim highlight
                    moveTo(28.4f, 9f); lineTo(24.8f, 19f)
                    lineTo(22.6f, 19f); lineTo(26.6f, 9f); close()
                }, cut)
                if (flash > 0f) {
                    wash.alpha = (150f * flash).toInt()
                    canvas.drawPath(b, wash)
                }
            }
            "info" -> {
                canvas.drawCircle(24f, 24f, 20f, body)
                canvas.drawCircle(24f, 15.5f, 2.7f, cut)         // dot
                rr(canvas, 21.4f, 20.5f, 26.6f, 34f, 2.6f, cut)  // stem
            }
            "motion" -> {
                // Speed lines: the mark for movement itself, which is what the
                // Animations row switches. A ball with a trail would have been
                // more literal and also unreadable at 19dp — three bars ranged
                // right, each shorter than the one above, say "moving" at any
                // size, and the round caps keep them in the same family as the
                // plus and the info stem.
                rr(canvas, 8f, 15.5f, 40f, 20.5f, 2.5f, body)
                rr(canvas, 16f, 24.5f, 40f, 29.5f, 2.5f, body)
                rr(canvas, 24f, 33.5f, 40f, 38.5f, 2.5f, body)
            }
            "plus" -> {
                // Drawn rather than typed: a "+" set in the UI font sits a
                // couple of units high in its line box and is a hair too light
                // at 20dp over video. Two rounded bars on the 48-box's centre
                // are optically true at any size.
                rr(canvas, 21.5f, 10f, 26.5f, 38f, 2.5f, body)   // upright
                rr(canvas, 10f, 21.5f, 38f, 26.5f, 2.5f, body)   // crossbar
            }
            "mail" -> {
                rr(canvas, 6f, 11f, 42f, 37f, 5f, body)          // envelope
                val flap = Path().apply {
                    moveTo(8f, 13.5f); lineTo(24f, 26f); lineTo(40f, 13.5f)
                    lineTo(38f, 12f); lineTo(24f, 22.5f); lineTo(10f, 12f); close()
                }
                canvas.drawPath(flap, cut)
            }
            "star" -> {
                // The rating row. Five points on the 48-box's centre, a hair
                // low so it sits optically level with the round glyphs.
                val st = Path()
                for (n in 0 until 10) {
                    val r = if (n % 2 == 0) 21f else 9f
                    val ang = -PI / 2 + n * PI / 5
                    val x = 24f + (r * cos(ang)).toFloat()
                    val y = 25.5f + (r * sin(ang)).toFloat()
                    if (n == 0) st.moveTo(x, y) else st.lineTo(x, y)
                }
                st.close()
                canvas.drawPath(st, body)
                canvas.drawCircle(19.5f, 21.5f, 2.2f, cut)       // shine
            }
            "help" -> {
                canvas.drawCircle(24f, 24f, 20f, body)
                val hook = Paint(cut).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                    strokeCap = Paint.Cap.ROUND
                }
                val q = Path().apply {
                    moveTo(17.5f, 18.5f)
                    cubicTo(17.5f, 10.5f, 30.5f, 10.5f, 30.5f, 18f)
                    cubicTo(30.5f, 23f, 24f, 23f, 24f, 28.5f)
                }
                canvas.drawPath(q, hook)
                canvas.drawCircle(24f, 35.5f, 2.9f, cut)         // dot
            }
            "theme" -> {
                // A disc, half lit: follow-the-system, light and dark at once.
                canvas.drawCircle(24f, 24f, 20f, body)
                canvas.drawCircle(24f, 24f, 15.5f, cut)
                canvas.drawArc(RectF(8.5f, 8.5f, 39.5f, 39.5f), 90f, 180f, true, body)
            }
            "chat" -> {
                rr(canvas, 5f, 7f, 43f, 34f, 8f, body)           // bubble
                canvas.drawPath(Path().apply {                   // tail
                    moveTo(12f, 31f); lineTo(10f, 43f); lineTo(23f, 33f); close()
                }, body)
                canvas.drawCircle(15.5f, 20.5f, 2.6f, cut)
                canvas.drawCircle(24f, 20.5f, 2.6f, cut)
                canvas.drawCircle(32.5f, 20.5f, 2.6f, cut)
            }
            "qr" -> {
                // Three finder squares and a scatter of modules: reads as "a
                // code to scan" at any size, without pretending to be one.
                fun finder(x: Float, y: Float) {
                    rr(canvas, x, y, x + 16f, y + 16f, 3.5f, body)
                    rr(canvas, x + 3.5f, y + 3.5f, x + 12.5f, y + 12.5f, 1.5f, cut)
                    rr(canvas, x + 5.5f, y + 5.5f, x + 10.5f, y + 10.5f, 1f, dot)
                }
                finder(5f, 5f); finder(27f, 5f); finder(5f, 27f)
                rr(canvas, 27f, 27f, 33f, 33f, 1.5f, body)
                rr(canvas, 36f, 31f, 43f, 37f, 1.5f, body)
                rr(canvas, 29f, 37f, 35f, 43f, 1.5f, body)
                rr(canvas, 38f, 40f, 43f, 43f, 1f, body)
            }
            "moon" -> {
                canvas.drawCircle(22f, 25f, 18f, body)
                canvas.drawCircle(31f, 17f, 15f, cut)            // the bite
                canvas.drawCircle(38.5f, 34f, 2f, body)          // a star
                canvas.drawCircle(33f, 42f, 1.3f, body)
            }
            else -> {                                            // "phone"
                rr(canvas, 13f, 4f, 35f, 44f, 6f, body)          // handset
                rr(canvas, 16.5f, 9.5f, 31.5f, 35.5f, 3f, cut)   // screen
                rr(canvas, 20.5f, 39.2f, 27.5f, 41.2f, 1f, cut)  // home bar
                canvas.drawCircle(24f, 6.6f, 1f, cut)            // earpiece
            }
        }
        canvas.restore()
    }
}

/**
 * Shared look for the little animated scenes: soft flat fills in warm cream,
 * biscuit and dove-grey, dark bean eyes with a catch-light, a contact shadow
 * under each critter, and springy secondary motion (ears, tails, a ball that
 * squashes when it lands). Deliberately plush rather than a line drawing.
 * Honours the system "remove animations" setting — motion off draws the pose.
 */
private abstract class CritterScene(ctx: Context) : View(ctx) {

    protected abstract val vw: Float
    protected abstract val vh: Float
    protected open val loopMs = 3800L

    protected fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = c
    }

    /** Dark "brushed leather" UI vs. light "strawberry cheesecake" — the pets
     *  are tuned separately for each so they read on both grounds. */
    protected val onDark = (
        0.299f * Color.red(Hue.BG) + 0.587f * Color.green(Hue.BG) + 0.114f * Color.blue(Hue.BG)
    ) < 128f

    protected val cream = paint(if (onDark) 0xFFE7DBC1.toInt() else 0xFFF1E7D3.toInt())
    protected val creamHi = paint(if (onDark) 0xFFF4EAD5.toInt() else 0xFFFDF8EE.toInt())
    protected val creamLo = paint(if (onDark) 0xFFC9B790.toInt() else 0xFFDDCCAD.toInt())
    protected val biscuit = paint(if (onDark) 0xFFCC9A63.toInt() else 0xFFD59E6B.toInt())   // the dog
    protected val biscuitLo = paint(if (onDark) 0xFFA9784A.toInt() else 0xFFBB8453.toInt())
    protected val dove = paint(if (onDark) 0xFFB6AD99.toInt() else 0xFFC2B8A5.toInt())      // the cat
    protected val ink = paint(if (onDark) 0xFF2E2116.toInt() else 0xFF3C2A1E.toInt())       // eyes / muzzle dot
    protected val berry = paint(Hue.BERRY)                   // noses, beak, inner ear
    // A tongue is pink on any theme. Hue.BERRY is not: it's the app's brand
    // accent, and turns "aged brass / tan" in dark mode by design (see
    // values-night/colors.xml) — which used to make the dog's tongue read as
    // brown whenever the phone was in dark mode. Fixed and separate from BERRY
    // on purpose, so a future accent change can't re-tint it by accident.
    protected val tongue = paint(0xFFE8728F.toInt())
    protected val sky = paint(Hue.SKY)                       // the ball, owl eyes
    protected val hi = paint(if (onDark) 0x22FFFFFF else 0x2BFFFFFF)   // volume highlight
    protected val lo = paint(if (onDark) 0x26000000 else 0x1F000000)   // volume shade
    protected val cast = paint(Color.BLACK)                  // ground shadow (alpha set per call)
    private val castMul = if (onDark) 1.9f else 1f

    /** The pet outline. A warm dark line, a touch chunky by request. */
    protected val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = if (onDark) 0xFF3A2A1B.toInt() else 0xFF48331E.toInt()
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    protected val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = if (onDark) 0xFF5B4634.toInt() else Hue.DIM
        strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND; alpha = if (onDark) 200 else 150
    }

    private var phase = 0f
    protected val t get() = phase
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private val reduceMotion: Boolean
        get() = stillModeOn(context) || try {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (e: Exception) { false }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.duration = loopMs
        // Read the setting once, here, and keep it: the free-running clock is
        // sampled every frame and must not be a ContentResolver round-trip.
        motionOff = reduceMotion
        if (!motionOff && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    fun stop() {
        animator.cancel()
        removeCallbacks(ticker)
        reactWho = 0
    }

    // ---------------------------------------------------------------- tap to react
    //
    // Tap a pet and it does its thing: 1 = cat, 2 = dog, 3 = the owlet (she takes
    // off and flies a loop). The reaction runs on wall-clock time so it is
    // independent of the idle loop; a self-posting ticker keeps frames coming
    // even when the ambient animator is stopped for reduce-motion.

    protected var reactWho = 0
        private set
    private var reactStart = 0L
    private val reactDurMs = intArrayOf(0, 1300, 1500, 2700)   // idx = who

    /** 0..1 progress while [who] is the one reacting, else 0. */
    protected fun reactP(who: Int): Float {
        if (reactWho != who) return 0f
        val e = SystemClock.uptimeMillis() - reactStart
        return (e.toFloat() / reactDurMs[who]).coerceIn(0f, 1f)
    }

    /** Seconds since the current reaction began — for driving sin() wiggles. */
    protected fun reactSecs(): Float = (SystemClock.uptimeMillis() - reactStart) / 1000f

    private fun reactExpired(): Boolean {
        if (reactWho == 0) return true
        if (SystemClock.uptimeMillis() - reactStart >= reactDurMs[reactWho]) {
            reactWho = 0
            return true
        }
        return false
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!reactExpired()) {
                invalidate()
                postOnAnimation(this)
            }
        }
    }

    /** scene-space (vw x vh) hit test → 1/2/3, or 0 for a miss. */
    protected abstract fun critterAt(sx: Float, sy: Float): Int

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val s = min(width / vw, height / vh)
            val sx = (ev.x - (width - vw * s) / 2f) / s
            val sy = (ev.y - (height - vh * s) / 2f) / s
            val who = critterAt(sx, sy)
            if (who != 0) {
                reactWho = who
                reactStart = SystemClock.uptimeMillis()
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                performClick()
                removeCallbacks(ticker)
                post(ticker)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // smoothstep-ish ramp, clamped
    protected fun ramp(x: Float): Float {
        val u = x.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    // a 0→1→0 hump over [0,1]
    protected fun hump(x: Float): Float =
        if (x <= 0f || x >= 1f) 0f else sin(x * PI).toFloat()

    protected fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u

    // ------------------------------------------------------------ organic motion
    //
    // Everything below exists to get the animals off the metronome. A raw
    // sin() on the loop clock is honest about what it is: one rate, one
    // amplitude, forever, and every limb in lockstep because every limb is
    // reading the same tick. Live animals don't do that. They accent one
    // direction of a stroke and drift back from it; they work in bursts and
    // then go quiet; their light parts arrive late to whatever their heavy
    // parts did; and every so often they simply break the idle with an event
    // — an ear flick, a head cock, a sniff — that belongs to no cycle at all.
    //
    // The tools are deliberately arithmetic. No Random, no allocation, no
    // state to keep in step with the frame loop: feed them the clock and the
    // same instant always comes out the same way, which is what lets an
    // "unpredictable" idle survive being redrawn sixty times a second behind
    // live UI.

    private var motionOff = false
    private val born = SystemClock.uptimeMillis()

    /** Free-running seconds since this view was built. Deliberately *not* the
     *  loop phase [t]: anything whose period is the loop is the very thing
     *  that reads as clockwork, because the eye learns the loop in two passes.
     *  Frozen at zero when the system asks for no animation, so the
     *  reduce-motion still frame is the neutral pose rather than a random one. */
    protected val clock: Float
        get() = if (motionOff) 0f else (SystemClock.uptimeMillis() - born) / 1000f

    protected val twoPi = 6.2831855f

    /** Deterministic 0..1 from an integer — the pseudo-randomness the idle
     *  events schedule themselves with, without an allocated Random anywhere
     *  near onDraw. */
    protected fun hash01(n: Int): Float {
        var h = n * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0x07FFFFFF) / 134217727f
    }

    /**
     * True when the app has been asked to hold still.
     *
     * Everything below rests at its neutral value in that case, which is the
     * difference between a still life and a pause. Stopping the clock wherever
     * it happened to be leaves an animal mid-gesture — one ear up, the head
     * cocked at nothing, a tail flung out to the side, eyes caught halfway
     * through a blink — and a drawing frozen mid-blink is exactly the thing
     * that reads as unsettling rather than asleep. So a stopped scene is not
     * this scene paused; it is a pose of its own, and these are the values that
     * make it one.
     */
    protected val still: Boolean get() = motionOff

    /** A smooth wander in −1..1: three sines whose rates share no common
     *  multiple, so the sum has no beat an eye can anticipate. Flat when still,
     *  so ears sit level and tails hang straight. */
    protected fun drift(u: Float): Float =
        if (still) 0f
        else (sin(u) + 0.62f * sin(u * 1.73f + 1.3f) + 0.41f * sin(u * 2.91f + 2.6f)) * 0.49f

    /** The shape nearly every deliberate movement has: out fast, back slow.
     *  [u] runs 0..1 across the whole gesture and [out] is the share of it
     *  spent going out. A sine spends the same time on both halves, which is
     *  the single biggest reason a sine reads as machinery. */
    protected fun snap(u: Float, out: Float = 0.22f): Float =
        if (u <= 0f || u >= 1f) 0f
        else if (u < out) ramp(u / out) else 1f - ramp((u - out) / (1f - out))

    /** Breathing at time [at] in seconds, 0..1: a quicker draw in than sigh
     *  out. Sampling it at a couple of offsets is all "follow-through" is —
     *  the head rides the breath the ribcage had a moment ago. */
    protected fun breath(at: Float, period: Float = 2.9f): Float {
        // Mid-breath when still: every caller reads this as (breath - 0.5), so
        // a half is the ribcage at rest rather than at the top or bottom of a
        // held breath.
        if (still) return 0.5f
        val u = at / period
        return snap(u - floor(u), 0.42f)
    }

    /** Idle punctuation. Once inside each [every]-second window — but only
     *  with probability [chance], and at a moment inside the window picked by
     *  the hash rather than by a cycle — this returns 0..1 progress through a
     *  [dur]-second event, and 0 the rest of the time. Give every event its
     *  own [seed] and no two of them ever queue up in the same order twice in
     *  one sitting, which is what stops a loop from being a loop. */
    protected fun beat(seed: Int, every: Float, dur: Float, chance: Float = 1f): Float {
        val ck = clock
        if (ck <= 0f) return 0f
        val w = ck / every + hash01(seed * 977 + 13)
        val i = floor(w).toInt()
        if (chance < 1f && hash01(i * 8191 + seed) > chance) return 0f
        val at = hash01(i * 131 + seed * 7) * (1f - dur / every).coerceAtLeast(0f)
        val u = (w - i - at) * every / dur
        return if (u <= 0f || u >= 1f) 0f else u
    }

    /** Blinking on the wall clock, at an irregular interval and sometimes in
     *  pairs. [blink] fires once a loop, which means the eyes shut on the same
     *  tick the tail returns on — one give-away tells the eye the whole animal
     *  is one clockwork. 1 = open. */
    protected fun blinkAt(seed: Int): Float {
        val shut = maxOf(
            snap(beat(seed, 3.6f, 0.30f), 0.38f),
            snap(beat(seed + 101, 5.3f, 0.26f, 0.45f), 0.38f)   // clusters into doubles
        )
        return 1f - 0.94f * shut
    }

    /** Flattens the peaks of a −1..1 swing so the stroke hurries through the
     *  middle and hangs at the ends — what a tail (or a bounce at the top of
     *  its arc) does, and what a sine conspicuously doesn't. */
    protected fun hangs(s: Float, k: Float = 0.55f): Float = (1f + k) * s / (1f + k * abs(s))

    // --------------------------------------------------------------- looking
    //
    // Where an animal's eyes point, in −1..1, to be scaled into pixels by the
    // caller.
    //
    // Left to itself every pet in this app aims its eyes dead ahead, which
    // puts three faces on the welcome screen staring out of the glass at
    // whoever is holding the phone, indefinitely, and never at each other or at
    // anything in the scene they are supposedly in. Two eyes locked on the
    // viewer and never moving is the oldest trick in the uncanny book, and it
    // is the one thing these drawings were doing by default.
    //
    // So each animal has a `home` — the direction of whatever it is actually
    // interested in, usually the pet next to it — and it mostly looks there.
    // It wanders around that, now and then looks somewhere else entirely, and
    // only rarely and briefly out at the viewer, which is what makes that a
    // moment rather than a stare. When the scene is stopped, `home` is what is
    // left: the still frame is animals regarding one another.

    /**
     * How much this animal is looking straight at whoever is holding the
     * phone, 0..1.
     *
     * A pet that never once meets your eye is as odd as one that never looks
     * away — it reads as a drawing that happens to be facing you rather than
     * as something aware you are there. So every so often each of them turns
     * and looks, and because both axes are steered from this one number the
     * look lands dead centre rather than merely somewhere near it.
     *
     * The shape is a plateau, not a peak: it turns to the lens over about a
     * third of the gesture, *holds* there, and turns away again. Eye contact
     * that only touches centre for a single frame on its way past is not eye
     * contact, it is a flinch. Roughly one look every quarter of a minute per
     * animal, each on its own schedule, so they never all do it at once.
     */
    protected fun contact(seed: Int): Float {
        val u = beat(seed * 31 + 8, 8.5f, 2.2f, 0.55f)
        if (u <= 0f) return 0f
        return (ramp(u / 0.3f) * (1f - ramp((u - 0.62f) / 0.38f))).coerceIn(0f, 1f)
    }

    /** Sideways gaze. [home] is where this animal's attention lives (−1 left,
     *  +1 right); the rest is it thinking about other things, or about you. */
    protected fun gazeX(seed: Int, home: Float): Float {
        if (still) return home
        val wander = drift(clock * 0.29f + seed * 1.7f) * 0.3f
        // Off to something else on the other side.
        val away = snap(beat(seed * 31 + 7, 9.5f, 2.0f, 0.45f), 0.25f)
        val base = (home + wander) * (1f - away) - home * away * 0.9f
        return base * (1f - contact(seed))
    }

    /** The same for up and down — mostly level, with the odd glance upward,
     *  which is where a real animal looks when it hears something. */
    protected fun gazeY(seed: Int, home: Float = 0f): Float {
        if (still) return home
        val up = snap(beat(seed * 31 + 9, 12f, 1.6f, 0.45f), 0.28f) * -0.85f
        return (home + up) * (1f - contact(seed))
    }

    protected data class Flight(val fx: Float, val fy: Float, val bank: Float, val look: Float)

    /** The owlet's flight path for tap-progress [op] 0..1: lift off the perch,
     *  circle the scene 1.5 times on an ellipse, settle back. */
    protected fun owlFlight(
        op: Float, perchX: Float, perchY: Float,
        cxA: Float, cyA: Float, rx: Float, ry: Float
    ): Flight {
        val a0 = -PI.toFloat() / 2f
        fun at(u: Float): Triple<Float, Float, Float> {
            val a = a0 + u.coerceIn(0f, 1f) * (2f * PI.toFloat()) * 1.5f
            return Triple(cxA + rx * cos(a), cyA + ry * sin(a), a)
        }
        return when {
            op < 0.16f -> {
                val u = ramp(op / 0.16f)
                val (lx, ly, _) = at(0f)
                Flight(lerp(perchX, lx, u), lerp(perchY, ly, u), u * -8f, 0f)
            }
            op < 0.82f -> {
                val u = (op - 0.16f) / 0.66f
                val (lx, ly, a) = at(u)
                Flight(lx, ly, -cos(a) * 15f, -sin(a) * 1.8f)
            }
            else -> {
                val u = ramp((op - 0.82f) / 0.18f)
                val (lx, ly, _) = at(1f)
                Flight(lerp(lx, perchX, u), lerp(ly, perchY, u), lerp(-8f, 0f, u), 0f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        reactExpired()
        val s = min(width / vw, height / vh)
        canvas.save()
        canvas.translate((width - vw * s) / 2f, (height - vh * s) / 2f)
        canvas.scale(s, s)
        drawScene(canvas)
        canvas.restore()
    }

    protected abstract fun drawScene(c: Canvas)

    // ---------------------------------------------------------------- helpers

    /** 1 = eyes open; briefly dips toward 0 once per loop. */
    protected fun blink(offset: Float = 0f): Float {
        val d = abs(((t + offset) % 1f) - 0.5f)
        return if (d < 0.032f) (d / 0.032f).coerceIn(0.06f, 1f) else 1f
    }

    protected fun capsule(
        c: Canvas, cx: Float, cy: Float, w: Float, h: Float, p: Paint, e: Paint? = null
    ) {
        val r = min(w, h) / 2f
        val l = cx - w / 2f; val top = cy - h / 2f; val ri = cx + w / 2f; val b = cy + h / 2f
        c.drawRoundRect(l, top, ri, b, r, r, p)
        if (e != null) c.drawRoundRect(l, top, ri, b, r, r, e)
    }

    /** A filled body mass with a soft inset highlight, underside shade, and the
     *  pet outline. */
    protected fun mass(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, base: Paint) {
        val r = min(w, h) / 2f
        val rc = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        c.drawRoundRect(rc, r, r, base)
        c.drawOval(RectF(cx - w * 0.36f, cy - h * 0.44f, cx + w * 0.06f, cy - h * 0.02f), hi)
        c.drawOval(RectF(cx - w * 0.40f, cy + h * 0.04f, cx + w * 0.40f, cy + h * 0.46f), lo)
        c.drawRoundRect(rc, r, r, edge)
    }

    protected fun castShadow(c: Canvas, cx: Float, cy: Float, w: Float, alpha: Int = 34) {
        cast.alpha = (alpha * castMul).toInt().coerceIn(0, 255)
        c.drawOval(RectF(cx - w / 2f, cy - w * 0.11f, cx + w / 2f, cy + w * 0.11f), cast)
    }

    /** Dark bean eye with a catch-light; [open] 1..0 squashes it shut. [look]
     *  aims the eye sideways, [lookY] up (negative) or down. The catch light
     *  stays put while the eye moves under it, which is what stops an aimed
     *  eye reading as the whole head having turned. */
    protected fun eye(
        c: Canvas, cx: Float, cy: Float, r: Float, open: Float,
        look: Float = 0f, lookY: Float = 0f,
    ) {
        val o = open.coerceIn(0f, 1f)
        val y = cy + lookY * o          // a shut eye has nowhere to look
        c.drawOval(RectF(cx - r + look, y - r * o, cx + r + look, y + r * o), ink)
        if (o > 0.55f) c.drawCircle(cx - r * 0.34f + look, cy - r * 0.44f, r * 0.36f, creamHi)
    }

    /** Big round owl eye: sky ring, dark pupil that can aim ([look]/[lookY]),
     *  catch-light. */
    protected fun owlEye(
        c: Canvas, cx: Float, cy: Float, r: Float, open: Float, look: Float, lookY: Float = 0f
    ) {
        val o = open.coerceIn(0.08f, 1f)
        c.save()
        c.scale(1f, o, cx, cy)
        c.drawCircle(cx, cy, r, sky)
        val ew = edge.strokeWidth
        edge.strokeWidth = ew * 0.68f
        c.drawCircle(cx, cy, r, edge)
        edge.strokeWidth = ew
        val px = cx + look
        val py = cy + r * 0.12f + lookY * r * 0.32f
        c.drawCircle(px, py, r * 0.52f, ink)
        c.drawCircle(px - r * 0.18f, py - r * 0.36f, r * 0.2f, creamHi)
        c.restore()
    }

    /** A soft rounded triangle (ears). Corners a, b, c smoothed with quads. */
    protected fun softTri(
        c: Canvas, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
        p: Paint, e: Paint? = null
    ) {
        val path = Path().apply {
            moveTo((ax + bx) / 2f, (ay + by) / 2f)
            quadTo(bx, by, (bx + cx) / 2f, (by + cy) / 2f)
            quadTo(cx, cy, (cx + ax) / 2f, (cy + ay) / 2f)
            quadTo(ax, ay, (ax + bx) / 2f, (ay + by) / 2f)
            close()
        }
        c.drawPath(path, p)
        if (e != null) c.drawPath(path, e)
    }

    /** A tapered tail / limb from a base point to a tip, filled, rounded end. */
    protected fun taper(
        c: Canvas, bx: Float, by: Float, tipX: Float, tipY: Float, thick: Float,
        p: Paint, e: Paint? = null
    ) {
        val dx = tipX - bx; val dy = tipY - by
        val len = hypot(dx, dy).toFloat().coerceAtLeast(0.001f)
        val ux = -dy / len * thick; val uy = dx / len * thick
        val path = Path().apply {
            moveTo(bx + ux, by + uy)
            quadTo(bx + dx * 0.55f + ux * 0.5f, by + dy * 0.55f + uy * 0.5f, tipX, tipY)
            quadTo(bx + dx * 0.55f - ux * 0.5f, by + dy * 0.55f - uy * 0.5f, bx - ux, by - uy)
            close()
        }
        c.drawPath(path, p)
        c.drawCircle(tipX, tipY, thick * 0.55f, p)
        if (e != null) c.drawPath(path, e)
    }

    protected fun bone(c: Canvas, cx: Float, cy: Float, half: Float) {
        val k = half * 0.34f
        val sh = half * 0.28f
        c.drawRoundRect(cx - half, cy - sh, cx + half, cy + sh, sh, sh, creamLo)
        for (e in listOf(cx - half, cx + half)) {
            c.drawCircle(e, cy - k * 0.85f, k, creamLo)
            c.drawCircle(e, cy + k * 0.85f, k, creamLo)
        }
        c.drawRoundRect(cx - half + 0.6f, cy - sh * 0.55f, cx + half - 0.6f, cy + sh * 0.55f, 1.2f, 1.2f, creamHi)
        for (e in listOf(cx - half, cx + half)) {
            c.drawCircle(e, cy - k * 0.85f, k - 1.1f, creamHi)
            c.drawCircle(e, cy + k * 0.85f, k - 1.1f, creamHi)
        }
    }

    /**
     * A floppy ear hanging off the side of a head: narrow where it joins at
     * ([ax],[ay]), bulging out and rounding off at the bottom, swung by
     * [angleDeg]. Anchor it on the head's edge — the join is drawn tucked
     * slightly under, so the lobe reads as hanging beside the face, not lying
     * across it.
     */
    protected fun floppyEar(
        c: Canvas, ax: Float, ay: Float, len: Float, wid: Float, angleDeg: Float, p: Paint
    ) {
        c.save()
        c.rotate(angleDeg, ax, ay)
        val path = Path().apply {
            moveTo(ax - wid * 0.28f, ay)
            cubicTo(
                ax - wid * 0.60f, ay + len * 0.38f,
                ax - wid * 0.52f, ay + len * 0.88f,
                ax, ay + len
            )
            cubicTo(
                ax + wid * 0.52f, ay + len * 0.88f,
                ax + wid * 0.60f, ay + len * 0.38f,
                ax + wid * 0.28f, ay
            )
            quadTo(ax, ay - wid * 0.30f, ax - wid * 0.28f, ay)
            close()
        }
        c.drawPath(path, p)
        c.drawPath(path, edge)
        c.restore()
    }

    /** Tawny the owlet — shared between both scenes. [hop] lifts her off the
     *  ground and flaps the wings. Pass [ballX]/[ballY] and she watches the ball,
     *  leans after it and perks up when it comes close — part of the game. Pass
     *  [flyX]/[flyY] and she is airborne instead: wings spread, banking, feet up
     *  (used by the tap-to-react loop). */
    protected fun owlet(
        c: Canvas, x: Float, groundY: Float, tNorm: Float, hop: Float = 0f,
        ballX: Float? = null, ballY: Float? = null,
        flyX: Float? = null, flyY: Float = 0f, flyBank: Float = 0f,
        flyFlap: Float = 0f, flyLook: Float = 0f
    ) {
        if (flyX != null) {
            val ax = flyX; val ay = flyY
            val alt = ((groundY - ay) / 66f).coerceIn(0f, 1f)
            castShadow(c, x, groundY + 3f, 24f * (1f - 0.6f * alt),
                (30 * (1f - 0.7f * alt)).toInt())
            c.save()
            c.rotate(flyBank, ax, ay)
            // long wings, spread and flapping
            c.save(); c.rotate(-42f - 30f * flyFlap, ax - 6f, ay - 1f)
            capsule(c, ax - 15f, ay + 1f, 10f, 22f, cream, edge); c.restore()
            c.save(); c.rotate(42f + 30f * flyFlap, ax + 6f, ay - 1f)
            capsule(c, ax + 15f, ay + 1f, 10f, 22f, cream, edge); c.restore()
            // ear tufts
            softTri(c, ax - 8f, ay - 10f, ax - 3f, ay - 22f, ax + 1f, ay - 11f, cream, edge)
            softTri(c, ax + 8f, ay - 10f, ax + 3f, ay - 22f, ax - 1f, ay - 11f, cream, edge)
            mass(c, ax, ay, 25f, 30f, cream)
            capsule(c, ax, ay - 3f, 21f, 16f, creamHi)
            // tail fan
            softTri(c, ax - 5f, ay + 12f, ax, ay + 22f, ax + 5f, ay + 12f, creamLo, edge)
            val bl = blink(0.45f)
            owlEye(c, ax - 5.2f, ay - 4f, 4.5f, bl, flyLook, -0.35f)
            owlEye(c, ax + 5.2f, ay - 4f, 4.5f, bl, flyLook, -0.35f)
            c.drawPath(Path().apply {
                moveTo(ax - 2f, ay + 1f); lineTo(ax + 2f, ay + 1f); lineTo(ax, ay + 5f); close()
            }, berry)
            c.restore()
            return
        }
        val tracking = ballX != null
        val toBall = if (tracking) ballX!! - x else 0f
        val track = (toBall / 58f).coerceIn(-1f, 1f)          // -1 ball hard-left … +1 hard-right
        val near = if (tracking) ((46f - abs(toBall)) / 46f).coerceIn(0f, 1f) else 0f
        val cy = groundY - 18f - hop - near * 3f
        val flap = (hop / 8f).coerceIn(0f, 1f) + near * 0.45f
        val lean = track * 8f                                  // body rocks after the ball
        val faceDx = track * 4f                                // and she cranes her face over
        // She takes her turn at meeting your eye too, on her own schedule, so
        // the three of them are never all looking out at once.
        val owlUp = 1f - contact(4)
        val lookX = (if (tracking) track * 2.1f else cos(tNorm * 2.0 * PI).toFloat() * 0.9f) * owlUp
        val lookY = (if (ballY != null) ((ballY - (cy - 4f)) / 19f).coerceIn(-1.3f, 1f) else 0f) * owlUp

        castShadow(c, x, groundY + 3f, 26f * (1f - 0.35f * flap.coerceAtMost(1f)),
            (34 * (1f - 0.5f * flap.coerceAtMost(1f))).toInt())

        // Feet stay planted; the rest of her leans and cranes after the ball.
        if (hop < 3f) {
            c.drawPath(Path().apply {
                moveTo(x - 4f, groundY - 2f); lineTo(x - 7f, groundY + 1f); lineTo(x - 1f, groundY + 1f); close()
            }, berry)
            c.drawPath(Path().apply {
                moveTo(x + 4f, groundY - 2f); lineTo(x + 1f, groundY + 1f); lineTo(x + 7f, groundY + 1f); close()
            }, berry)
        }

        c.save()
        c.rotate(lean, x, groundY)

        c.save(); c.rotate(-18f - 24f * flap.coerceAtMost(1f), x - 8f, cy - 2f)
        capsule(c, x - 11f, cy + 3f, 9f, 17f, cream, edge); c.restore()
        c.save(); c.rotate(18f + 24f * flap.coerceAtMost(1f), x + 8f, cy - 2f)
        capsule(c, x + 11f, cy + 3f, 9f, 17f, cream, edge); c.restore()

        val tx = x + faceDx * 0.45f
        softTri(c, tx - 8f, cy - 11f, tx - 3f, cy - 23f, tx + 1f, cy - 12f, cream, edge)
        softTri(c, tx + 8f, cy - 11f, tx + 3f, cy - 23f, tx - 1f, cy - 12f, cream, edge)

        mass(c, x, cy, 26f, 32f, cream)
        capsule(c, x + faceDx, cy - 3f, 22f, 17f, creamHi)

        val bl = blink(0.45f)
        owlEye(c, x + faceDx - 5.4f, cy - 4f, 4.6f, bl, lookX, lookY)
        owlEye(c, x + faceDx + 5.4f, cy - 4f, 4.6f, bl, lookX, lookY)
        c.drawPath(Path().apply {
            moveTo(x + faceDx - 2f, cy + 1f); lineTo(x + faceDx + 2f, cy + 1f); lineTo(x + faceDx, cy + 5f); close()
        }, berry)
        c.restore()
    }
}

/** The welcome-screen trio: a cat and a dog sitting either side of Tawny the
 *  owlet — breathing, blinking, tails alive. */
private class PetSceneView(ctx: Context) : CritterScene(ctx) {
    override val vw = 260f
    override val vh = 170f
    override val loopMs = 4200L

    override fun drawScene(c: Canvas) {
        val tau = t * 2.0 * PI
        val g = 150f
        val bob = sin(tau).toFloat()

        // ---------------- cat, sitting, left ----------------
        run {
            val x = 70f
            val by = bob * 1.6f
            castShadow(c, x + 2f, g + 4f, 66f, 40)

            // tap → wind-up wiggle, then pounce.
            val cp = reactP(1)
            val crouch = ramp(cp / 0.30f) * (1f - ramp((cp - 0.34f) / 0.18f))
            val spring = hump((cp - 0.28f) / 0.55f)
            val land = hump((cp - 0.82f) / 0.18f)
            val wiggle = sin(reactSecs() * 44f).toFloat() * crouch * 2.6f
            val active = (crouch + spring).coerceAtMost(1f)
            c.save()
            c.translate(wiggle, crouch * 3.6f - spring * 17f + land * 2.4f)
            c.scale(1f + spring * 0.05f, 1f - spring * 0.06f, x, g)

            val flick = sin(t * 4.0 * PI + 1.0).toFloat() +
                active * sin(reactSecs() * 30f).toFloat() * 3.2f
            c.save(); c.rotate(flick * 4f, x + 6f, g - 6f)
            val catTail = Path().apply {
                moveTo(x + 2f, g - 4f)
                cubicTo(x + 30f, g + 2f, x + 34f, g - 26f, x + 20f, g - 34f)
                cubicTo(x + 12f, g - 39f, x + 6f, g - 32f, x + 11f, g - 24f)
                cubicTo(x + 16f, g - 16f, x + 12f, g - 2f, x - 2f, g + 1f)
                close()
            }
            c.drawPath(catTail, dove); c.drawPath(catTail, edge)
            c.restore()

            mass(c, x - 4f, g - 16f + by, 46f, 30f, dove)
            mass(c, x + 2f, g - 40f + by, 34f, 46f, dove)
            capsule(c, x + 4f, g - 30f + by, 18f, 24f, creamHi)
            capsule(c, x - 4f, g - 3f, 12f, 9f, cream, edge)
            capsule(c, x + 10f, g - 3f, 12f, 9f, cream, edge)

            val hx = x + 3f; val hy = g - 64f + by - spring * 3f
            val earTip = -3f - active * 3f          // ears flick back on the pounce
            softTri(c, hx - 16f, hy - 2f, hx - 20f, hy - 22f - earTip, hx - 3f, hy - 12f, dove, edge)
            softTri(c, hx + 16f, hy - 2f, hx + 20f, hy - 22f - earTip, hx + 3f, hy - 12f, dove, edge)
            softTri(c, hx - 13f, hy - 4f, hx - 16f, hy - 17f, hx - 5f, hy - 11f, berry)
            softTri(c, hx + 13f, hy - 4f, hx + 16f, hy - 17f, hx + 5f, hy - 11f, berry)
            mass(c, hx, hy, 32f, 29f, dove)
            capsule(c, hx, hy + 6f, 15f, 12f, creamHi)
            val bl = (blink() - spring).coerceIn(0f, 1f)
            val er = 3.4f + active * 1.0f
            // The owlet and the dog are both to the cat's right, so that is
            // where it is looking when it is not thinking about something else.
            val gx = gazeX(2, 1f) * 1.7f
            val gy = gazeY(2) * 1.2f
            eye(c, hx - 6f, hy - 1f, er, bl, gx, gy)
            eye(c, hx + 6f, hy - 1f, er, bl, gx, gy)
            c.drawPath(Path().apply {
                moveTo(hx, hy + 8f); lineTo(hx - 2.4f, hy + 5.6f); lineTo(hx + 2.4f, hy + 5.6f); close()
            }, berry)
            c.drawLine(hx + 6f, hy + 5f, hx + 20f, hy + 3f, hair)
            c.drawLine(hx + 6f, hy + 8f, hx + 20f, hy + 9f, hair)
            c.drawLine(hx - 6f, hy + 5f, hx - 20f, hy + 3f, hair)
            c.drawLine(hx - 6f, hy + 8f, hx - 20f, hy + 9f, hair)
            c.restore()
        }

        // ---------------- dog, sitting, right ----------------
        run {
            val x = 190f
            val ck = clock
            // The ribcage breathes on the wall clock, not on the scene loop, and
            // the head rides the breath it had an eighth of a second ago. Two
            // offsets of one curve is the whole of follow-through, and it is
            // what stops the dog reading as a single rigid object being nudged
            // up and down.
            val by = (breath(ck) - 0.5f) * 3.2f
            val hby = (breath(ck - 0.13f) - 0.5f) * 3.4f
            castShadow(c, x + 2f, g + 4f, 78f, 40)

            // tap → play-bow, then a couple of happy bounces, tail going mad.
            // The bow drops fast and comes back up slowly, and the bounces hang
            // at the top of the arc the way a real hop does instead of tracing
            // the perfectly even |sin| they used to.
            val dp = reactP(2)
            val bow = ramp(dp / 0.20f) * (1f - ramp((dp - 0.30f) / 0.22f))
            val bounce = if (dp in 0.30f..0.92f)
                hangs(abs(sin(((dp - 0.30f) / 0.62f) * PI * 2f).toFloat()), 0.5f) else 0f
            val dogA = (bow + bounce).coerceAtMost(1f)
            // A sitting dog shifts its weight every now and then. It is barely
            // two degrees and it is the difference between resting and paused.
            val lean = snap(beat(21, 11f, 2.4f, 0.6f), 0.3f) -
                snap(beat(22, 13f, 2.4f, 0.6f), 0.3f)
            c.save()
            c.rotate(-13f * bow + lean * 2.2f, x, g)
            c.translate(0f, -bounce * 9f)
            // Landing squashes; the top of a bounce stretches. Free, and the
            // eye reads it as weight even when it can't say why.
            c.scale(1f + bounce * 0.03f, 1f - bounce * 0.045f, x, g)

            // Wag. A fixed-frequency sine here is the most obviously clockwork
            // thing a drawn dog can do, so this is a carrier whose *phase*
            // wanders — which drifts the rate smoothly instead of jumping it —
            // under an envelope that spends real time at the bottom. The tail
            // therefore comes in bursts: it winds up, works, and then simply
            // stops for a second or two and hangs a little lower, which is a
            // beat no metronome can give you.
            // Tuned so the tail is going about 60% of the time and its longest
            // rest is a few seconds: any deader and the hero screen looks
            // broken rather than calm.
            val zeal = ramp(drift(ck * 0.42f + 2.1f) * 1.15f + 0.66f)
            val wagPh = ck * 3.5f + 1.5f * drift(ck * 0.44f)
            // The stroke itself is asymmetric too: warping the angle by half a
            // sine of itself moves the peak early, so the tail leaves fast and
            // drifts back — a sine gives the flick and the return exactly the
            // same time, which is the tell.
            val th = wagPh * twoPi
            val wag = hangs(sin(th + 0.5f * sin(th)), 0.4f) * (0.18f + 0.82f * zeal)
            val wagArc = wag * (7f + 8f * zeal + dogA * 20f) +
                dogA * sin(reactSecs() * 40f).toFloat() * 6f
            c.save(); c.rotate(wagArc + (1f - zeal) * 3f - dogA * 6f, x + 14f, g - 8f)
            val dogTail = Path().apply {
                moveTo(x + 12f, g - 4f)
                cubicTo(x + 40f, g - 4f, x + 48f, g - 26f, x + 36f, g - 40f)
                cubicTo(x + 31f, g - 46f, x + 21f, g - 44f, x + 22f, g - 36f)
                cubicTo(x + 27f, g - 30f, x + 30f, g - 16f, x + 12f, g - 4f)
                close()
            }
            c.drawPath(dogTail, biscuit); c.drawPath(dogTail, edge)
            c.restore()

            mass(c, x + 4f, g - 16f + by, 52f, 28f, biscuit)
            mass(c, x, g - 40f + by, 42f, 46f, biscuit)
            capsule(c, x, g - 30f + by, 20f, 26f, cream)
            capsule(c, x - 8f, g - 3f, 13f, 10f, cream, edge)
            capsule(c, x + 8f, g - 3f, 13f, 10f, cream, edge)

            // Idle punctuation for the head: every so often it cocks over and
            // holds there before straightening — the "what was that?" a dog
            // does when nothing whatsoever has happened — and now and then it
            // takes three quick sniffs at the air. Two of these on a slow
            // irregular schedule are what turn a loop into an animal.
            val cock = (snap(beat(24, 9.7f, 2.1f, 0.6f), 0.16f) -
                snap(beat(23, 8.2f, 2.1f, 0.6f), 0.16f)) * 11f
            val sniff = beat(25, 12f, 0.9f, 0.5f)
            val sniffY = if (sniff > 0f) sin(sniff * 3f * twoPi) * hump(sniff) * 1.7f else 0f

            val hx = x; val hy = g - 62f + hby + sniffY - bow * 2f
            c.save()
            c.rotate(cock, hx, hy + 17f)                    // pivot at the throat
            // Ears hang from the top corners of the head and splay outward, so
            // they read beside the face. Drawn BEFORE the head: only the part
            // outside the skull shows, exactly like a real floppy ear.
            // (+ve rotates clockwise on screen, so the LEFT ear takes the +ve
            // angle to swing away from the face.)
            //
            // They are lighter than the skull and arrive late: the sway reads
            // the breath from a quarter-second back, so ears and body are never
            // at the extremes of their travel on the same frame. Each ear also
            // flicks on its own schedule, independently — a pair of ears moving
            // as one is a pair of ears bolted to a board.
            val sway = (breath(ck - 0.26f) - 0.5f) * 5.6f + drift(ck * 0.62f) * 1.4f +
                sin(reactSecs() * 24f).toFloat() * dogA * 6f
            val flickL = snap(beat(26, 5.2f, 0.45f, 0.55f), 0.14f) * 12f
            val flickR = snap(beat(27, 6.7f, 0.45f, 0.55f), 0.14f) * 12f
            floppyEar(c, hx - 15f, hy - 9f, 33f, 18f, 22f + sway + flickL, biscuitLo)
            floppyEar(c, hx + 15f, hy - 9f, 33f, 18f, -22f - sway - flickR, biscuitLo)
            mass(c, hx, hy, 34f, 31f, cream)
            capsule(c, hx, hy + 7f, 18f, 14f, creamHi)
            val bl = blinkAt(2)
            // The owlet and the cat are both to the dog's left — the mirror of
            // the cat's own gaze, so at rest the two of them are looking across
            // the owlet at each other rather than out of the screen.
            val gaze = gazeX(3, -1f) * 1.7f
            val gazeVert = gazeY(3) * 1.2f
            eye(c, hx - 6f, hy - 2f, 3.4f, bl, gaze, gazeVert)
            eye(c, hx + 6f, hy - 2f, 3.4f, bl, gaze, gazeVert)
            capsule(c, hx, hy + 4f, 6f, 5f, ink)
            c.drawCircle(hx - 1.6f, hy + 2.6f, 1.1f, creamHi)
            c.drawArc(RectF(hx - 6f, hy + 6f, hx, hy + 13f), 20f, 130f, false, hair)
            c.drawArc(RectF(hx, hy + 6f, hx + 6f, hy + 13f), 30f, 130f, false, hair)
            // The tongue used to be out and pumping on its own fixed cycle for
            // the entire life of the screen, which is the one thing here nobody
            // ever read as an animal. Now the dog pants in bouts: the tongue
            // comes out fast, works for a few seconds, and is drawn back in
            // between times — and a tap brings it straight out, because a happy
            // dog pants.
            // Two overlapping schedules rather than one: a single window leaves
            // half-minute stretches with no tongue at all, and this is the
            // screen the store shots come from.
            val pant = maxOf(
                maxOf(snap(beat(30, 7f, 3.0f, 0.85f), 0.10f),
                    snap(beat(31, 11f, 2.4f, 0.55f), 0.10f)),
                dogA
            )
            val loll = (pant * (4.4f + 1.2f * sin(ck * 12f)) + dogA * 4f).coerceAtLeast(0f)
            if (loll > 0.5f)
                c.drawRoundRect(hx - 2.4f, hy + 9f, hx + 2.4f, hy + 9f + loll, 2.4f, 2.4f, tongue)
            c.restore()
            c.restore()
        }

        // ---------------- owlet, centre (flies a loop when tapped) ----------------
        val op = reactP(3)
        if (op > 0f) {
            val (fx, fy, bank, look) = owlFlight(op, perchX = 130f, perchY = g - 20f,
                cxA = 130f, cyA = 46f, rx = 82f, ry = 34f)
            val flap = 0.55f + 0.45f * abs(sin(reactSecs() * 24f).toFloat())
            owlet(c, 130f, g - 2f, t, flyX = fx, flyY = fy, flyBank = bank, flyFlap = flap, flyLook = look)
        } else {
            // Feet down when still: caught mid-hop she reads as hovering.
            owlet(c, 130f, g - 2f, t,
                hop = if (still) 0f else (0.5f + 0.5f * sin(tau).toFloat()) * 2f)
        }
    }

    override fun critterAt(sx: Float, sy: Float): Int = when {
        sx in 108f..152f && sy in 88f..156f -> 3
        sx in 28f..114f && sy in 56f..158f -> 1
        sx in 146f..244f && sy in 50f..158f -> 2
        else -> 0
    }
}

/** The sessions-home scene: a kitten crouches and springs at a bouncing ball,
 *  Tawny hops, a dog lies gnawing its bone — a bit of life over the list. */
private class PlayfulSceneView(ctx: Context) : CritterScene(ctx) {
    override val vw = 300f
    override val vh = 140f
    override val loopMs = 3000L

    override fun drawScene(c: Canvas) {
        val tau = t * 2.0 * PI
        val g = 116f

        // The ball arcs between the kitten and the dog, lofting high over Tawny
        // (dead centre) and only dropping low at the two ends where they bat it —
        // so a centred owl is never in its way, and her gaze sweeps end to end.
        val swing = sin(tau).toFloat()
        val arc = abs(cos(tau).toFloat())                      // 1 over centre, 0 at the ends
        // Still: the ball is put down, not left wherever the loop happened to
        // stop. At the phase a stopped scene rests on, the swing has it dead
        // centre and the arc has it at the very top of its flight — a ball
        // hanging in mid-air over the owlet's head, holding there forever,
        // which is the single most obviously wrong thing about a paused scene.
        // On the floor by the kitten's paws it reads as a toy between games.
        val ballX = if (still) 104f else 150f + 56f * swing    // 94 (kitten) … 206 (dog)
        // The ball touches down exactly when a paw is there to meet it. At 7
        // half-cycles the bounce was coprime with the swing that carries the
        // ball, so it arrived at each end mid-hop, at whatever height it
        // happened to be — the paw could be perfectly timed and still swipe
        // through empty air under it. 8 puts a bottom of the bounce on t=0.25
        // and t=0.75, which are precisely the instants the dog's swat and the
        // kitten's pounce peak.
        val endBounce = abs(sin(t * 8.0 * PI).toFloat())
        val ballLift = if (still) 0f else arc * 40f + (1f - arc) * endBounce * 13f
        val ballY = g - 8f - ballLift

        // ---------- kitten, crouched, left, springs at the ball ----------
        run {
            val x = 60f
            val pounce = (-sin(tau)).toFloat().coerceAtLeast(0f)
            val by = sin(tau).toFloat() * 1.4f
            val wiggle = sin(t * 18.0 * PI).toFloat() * pounce * 1.6f
            castShadow(c, x + 4f, g + 3f, 66f, 36)

            // tap → its own wind-up wiggle and pounce, over the top of the ball chase.
            val kp = reactP(1)
            val kCrouch = ramp(kp / 0.28f) * (1f - ramp((kp - 0.32f) / 0.18f))
            val kSpring = hump((kp - 0.26f) / 0.56f)
            val kLand = hump((kp - 0.82f) / 0.18f)
            val kAct = (kCrouch + kSpring).coerceAtMost(1f)
            c.save()
            c.translate(sin(reactSecs() * 46f).toFloat() * kCrouch * 2.6f,
                kCrouch * 3.4f - kSpring * 16f + kLand * 2f)
            c.scale(1f + kSpring * 0.05f, 1f - kSpring * 0.06f, x, g)

            c.save()
            c.translate(0f, -7f * pounce)
            c.rotate(11f * pounce, x, g)

            val flick = sin(t * 11.0 * PI).toFloat() +
                kAct * sin(reactSecs() * 30f).toFloat() * 3f
            c.save(); c.rotate(flick * 12f, x - 14f, g - 12f)
            val kitTail = Path().apply {
                moveTo(x - 12f, g - 10f)
                cubicTo(x - 34f, g - 12f, x - 40f, g - 40f, x - 22f, g - 50f)
                cubicTo(x - 12f, g - 55f, x - 4f, g - 47f, x - 11f, g - 39f)
                cubicTo(x - 18f, g - 33f, x - 18f, g - 20f, x - 6f, g - 10f)
                close()
            }
            c.drawPath(kitTail, dove); c.drawPath(kitTail, edge)
            c.restore()

            mass(c, x - 10f + wiggle, g - 16f + by, 34f, 26f, dove)
            mass(c, x + 11f, g - 13f + by, 46f, 24f, dove)
            capsule(c, x + 16f, g - 6f + by, 20f, 12f, creamHi)

            capsule(c, x + 20f, g - 3f, 11f, 8f, cream, edge)
            val px = x + 26f + 16f * pounce
            val py = g - 3f - 12f * pounce
            if (pounce > 0.02f) taper(c, x + 18f, g - 10f + by, px, py, 4.5f, dove, edge)
            capsule(c, px, py, 10f, 8f, cream, edge)

            val hx = x + 26f; val hy = g - 28f + by
            softTri(c, hx - 12f, hy - 1f, hx - 15f, hy - 17f, hx - 2f, hy - 9f, dove, edge)
            softTri(c, hx + 12f, hy - 1f, hx + 15f, hy - 17f, hx + 2f, hy - 9f, dove, edge)
            softTri(c, hx - 10f, hy - 3f, hx - 12f, hy - 12f, hx - 4f, hy - 8f, berry)
            softTri(c, hx + 10f, hy - 3f, hx + 12f, hy - 12f, hx + 4f, hy - 8f, berry)
            mass(c, hx, hy, 26f, 24f, dove)
            capsule(c, hx, hy + 5f, 12f, 10f, creamHi)
            val bl = (blink() + pounce).coerceAtMost(1f)
            // The ball is the whole point of this scene, so the kitten watches
            // it rather than the viewer — and when the scene is stopped the
            // ball is on the floor at its paws, so it is looking down at it.
            // ...but it does look up from the game every so often to check on
            // you, which is the one thing a cat watching a toy actually does.
            val kitUp = 1f - contact(8)
            val kitLook = ((ballX - hx) / 80f).coerceIn(-1f, 1f) * 1.7f * kitUp
            val kitLookY = ((ballY - hy) / 60f).coerceIn(-0.6f, 1f) * 1.4f * kitUp
            eye(c, hx - 4.5f, hy - 1f, 2.9f + 0.5f * pounce, bl, kitLook, kitLookY)
            eye(c, hx + 4.5f, hy - 1f, 2.9f + 0.5f * pounce, bl, kitLook, kitLookY)
            c.drawPath(Path().apply {
                moveTo(hx, hy + 6.5f); lineTo(hx - 2f, hy + 4.5f); lineTo(hx + 2f, hy + 4.5f); close()
            }, berry)
            c.drawLine(hx + 5f, hy + 4f, hx + 16f, hy + 2f, hair)
            c.drawLine(hx + 5f, hy + 6f, hx + 16f, hy + 8f, hair)
            c.drawLine(hx - 5f, hy + 4f, hx - 16f, hy + 2f, hair)
            c.drawLine(hx - 5f, hy + 6f, hx - 16f, hy + 8f, hair)
            c.restore()
            c.restore()
        }

        // ---------- dog, right — its bone, and it bats the ball when it lands ----------
        run {
            val x = 236f
            val ck = clock
            // 0 when the ball is away, 1 when it drops in near the dog's paws.
            val toy = ((ballX - 168f) / 38f).coerceIn(0f, 1f)

            // One swat, timed to the ball — not a shiver.
            //
            // The paw used to be driven by `toy * abs(sin(t * 11 * PI))`: a
            // 5.5Hz flutter switched on by nothing more than the ball being
            // somewhere nearby. Nothing in it knew *where* the ball was, so the
            // paw shook through the whole approach and happened to be wherever
            // it was at the moment of contact — which is precisely why the dog
            // read as fumbling it. The kitten at the far end never had this
            // problem, because its pounce is one clean hump phase-locked to the
            // ball's arrival, and a single gesture aimed at the right instant is
            // all "catching it" has ever been.
            //
            // So: the same idea, with the accent a strike wants. The ball
            // reaches this end at t = 0.25, and `snap` puts the peak there —
            // 0.23 of the loop to swing up into it, and a longer 0.37 to ride
            // back down, so the paw drives and then follows through instead of
            // snapping back like a mousetrap.
            val swat = snap((t - 0.02f) / 0.60f, 0.38f)
            // Gnawing in bouts. A dog does not chew a bone at one unwavering
            // 4 Hz from the moment you open the app: it works at it, pauses
            // with its jaw resting on the thing, and starts again. Each bite
            // closes fast and releases slowly, so the rhythm has a downbeat.
            val chewBout = beat(41, 6.2f, 3.1f, 0.8f) * (1f - toy)
            val gnaw = if (chewBout > 0f) {
                val u = ck * 3.4f
                snap(u - floor(u), 0.3f) * hump(chewBout) * 3.4f
            } else 0f
            castShadow(c, x + 4f, g + 3f, 106f, 40)

            // tap → head snaps up, paws paddle, tail goes wild, a few body bounces.
            val dp = reactP(2)
            val dHead = ramp(dp / 0.14f) * (1f - ramp((dp - 0.72f) / 0.24f))   // up fast, down slow
            val dBounce = if (dp in 0.25f..0.90f)
                hangs(abs(sin(((dp - 0.25f) / 0.65f) * PI * 3f).toFloat()), 0.5f) else 0f
            val dAct = (dHead + dBounce).coerceAtMost(1f)
            // The body used to carry a second flutter of its own here — a 4Hz
            // jitter, again gated only on the ball being near — which had the
            // dog vibrating while it flailed. It leans up into the swat instead:
            // one movement, the whole animal behind it.
            val by = (breath(ck) - 0.5f) * 3f - swat * 2.4f - dBounce * 5f
            val hby = (breath(ck - 0.15f) - 0.5f) * 3f                 // the head arrives late
            c.save()

            // Wag in bursts under a wandering phase — see the sitting dog on the
            // welcome screen for why this isn't a plain sine. A lying dog's tail
            // is mostly still and then suddenly isn't, which is exactly what the
            // envelope's long trips to zero buy.
            val zeal = ramp(drift(ck * 0.39f + 5.3f) * 1.2f + 0.58f)
            val wagPh = ck * 4.1f + 1.6f * drift(ck * 0.47f)
            val th = wagPh * twoPi
            val wag = hangs(sin(th + 0.5f * sin(th)), 0.4f) * (0.14f + 0.86f * zeal)
            c.save(); c.rotate(
                wag * (10f + 10f * zeal + toy * 14f + dAct * 24f) +
                    dAct * sin(reactSecs() * 44f).toFloat() * 7f,
                x + 40f, g - 12f
            )
            val lyingTail = Path().apply {
                moveTo(x + 36f, g - 8f)
                cubicTo(x + 58f, g - 10f, x + 64f, g - 30f, x + 52f, g - 42f)
                cubicTo(x + 47f, g - 47f, x + 38f, g - 45f, x + 39f, g - 37f)
                cubicTo(x + 43f, g - 32f, x + 44f, g - 18f, x + 36f, g - 8f)
                close()
            }
            c.drawPath(lyingTail, biscuit); c.drawPath(lyingTail, edge)
            c.restore()

            mass(c, x + 6f, g - 15f + by, 82f, 32f, biscuit)
            mass(c, x + 30f, g - 16f + by, 30f, 30f, biscuit)
            capsule(c, x + 24f, g - 4f, 30f, 12f, biscuitLo, edge)
            capsule(c, x + 12f, g - 3f, 14f, 9f, cream, edge)

            // Inner front paw planted; the outer one swings up to meet the ball.
            // The ball sits at x = 206 with a radius of 9 when it arrives, and
            // the paw's travel is set so the top of it lands on the underside of
            // the ball at the peak of the swat rather than somewhere near it —
            // contact you can actually see, which is the other half of why this
            // used to look like a miss.
            capsule(c, x - 16f, g - 3f, 24f, 10f, cream, edge)
            val paddle = dHead * abs(sin(reactSecs() * 27f).toFloat())
            val batX = x - 30f - swat * 4f
            val batY = g - 3f - swat * 9f - paddle * 9f
            if (swat > 0.02f || dHead > 0.05f)
                taper(c, x - 6f, g - 6f, batX + 4f, batY, 4.5f, biscuit, edge)
            capsule(c, batX, batY, 22f, 10f, cream, edge)
            c.drawLine(x - 38f, g - 3f, x - 38f, g - 7f, hair)
            c.drawLine(x - 34f, g - 3f, x - 34f, g - 7f, hair)

            bone(c, x - 27f, g + 1f, 7.5f)

            // An occasional head cock, and a lift-and-settle when nothing at all
            // has happened — the punctuation that stops the idle repeating.
            val cock = (snap(beat(43, 10.4f, 2.0f, 0.55f), 0.16f) -
                snap(beat(42, 8.9f, 2.0f, 0.55f), 0.16f)) * 9f
            val perk = snap(beat(44, 13f, 1.8f, 0.5f), 0.18f) * 4f
            val hx = x - 18f
            val hy = g - 30f + hby + gnaw - toy * 4f - dHead * 9f - perk
            c.save()
            c.rotate(cock, hx, hy + 15f)
            // Ears hang from the top corners and splay outward — drawn BEFORE the
            // head, so only the part beside the skull shows. They read the breath
            // from a moment ago and each flicks on its own beat, so they never
            // travel in lockstep with the ribs or with one another.
            val sway = (breath(ck - 0.28f) - 0.5f) * 5.2f + drift(ck * 0.58f + 1.7f) * 1.3f
            val flickL = snap(beat(45, 5.8f, 0.45f, 0.5f), 0.14f) * 11f
            val flickR = snap(beat(46, 7.1f, 0.45f, 0.5f), 0.14f) * 11f
            floppyEar(c, hx - 13f, hy - 8f, 29f, 16f, 22f + sway + flickL, biscuitLo)
            floppyEar(c, hx + 13f, hy - 8f, 29f, 16f, -22f - sway - flickR, biscuitLo)
            mass(c, hx, hy, 30f, 28f, cream)
            capsule(c, hx, hy + 6f, 16f, 13f, creamHi)
            val bl = blinkAt(4)
            // Watching the ball wherever it is: hard left down the length of
            // the scene while the kitten has it, swinging round to its own paws
            // as it arrives. The stopped frame leaves it looking left at the
            // ball on the floor, which is also the kitten's way.
            val dogUp = 1f - contact(9)
            val dogLook = (lerp(-1.5f, 0f, toy) - toy * 0.6f) * dogUp
            val dogLookY = ((ballY - hy) / 70f).coerceIn(-0.5f, 1f) * 1.3f * dogUp
            eye(c, hx - 5.5f, hy - 2f, 3.1f, bl, dogLook, dogLookY)
            eye(c, hx + 5.5f, hy - 2f, 3.1f, bl, dogLook, dogLookY)
            capsule(c, hx + dogLook, hy + 3f, 5.5f, 4.5f, ink)
            c.drawCircle(hx + dogLook - 1.5f, hy + 1.7f, 1f, creamHi)
            c.drawArc(RectF(hx - 5f, hy + 5f, hx, hy + 11f), 20f, 130f, false, hair)
            c.drawArc(RectF(hx, hy + 5f, hx + 5f, hy + 11f), 30f, 130f, false, hair)
            // Panting in bouts rather than permanently — and the ball coming
            // within reach, or a tap, brings the tongue straight out. See the
            // welcome-screen dog: a tongue that never rests is the giveaway.
            val pant = maxOf(
                maxOf(snap(beat(49, 7.5f, 3.0f, 0.8f), 0.10f),
                    snap(beat(50, 11.5f, 2.4f, 0.5f), 0.10f)),
                maxOf(toy, dAct)
            )
            val loll = (pant * (3.4f + 1.3f * sin(ck * 13f)) + dAct * 3f).coerceAtLeast(0f)
            if (loll > 0.5f)
                c.drawRoundRect(hx - 2.2f, hy + 7f, hx + 2.2f, hy + 7f + loll, 2.2f, 2.2f, tongue)
            c.restore()
            c.restore()
        }

        // ---------- Tawny, dead centre — flies a loop when tapped ----------
        val op = reactP(3)
        if (op > 0f) {
            val (fx, fy, bank, look) = owlFlight(op, perchX = 150f, perchY = g - 20f,
                cxA = 150f, cyA = 38f, rx = 96f, ry = 28f)
            val flap = 0.55f + 0.45f * abs(sin(reactSecs() * 24f).toFloat())
            owlet(c, 150f, g - 2f, t, flyX = fx, flyY = fy, flyBank = bank, flyFlap = flap, flyLook = look)
        } else {
            owlet(
                c, 150f, g - 2f, t, hop = abs(sin(t * 4.0 * PI).toFloat()) * 8f,
                ballX = ballX, ballY = ballY
            )
        }

        // ---------- the ball, kept on top so it never hides ----------
        run {
            val bx = ballX
            val by = ballY
            val lift01 = (ballLift / 40f).coerceIn(0f, 1f)
            // The squash is an impact reading — flattest at the moment it meets
            // the floor. A ball that is simply sitting there has not just landed
            // on anything, so it keeps its own shape.
            val sqY = if (still) 1f else 0.74f + 0.26f * lift01
            val sqX = 2f - sqY
            castShadow(c, bx, g + 3f, 22f * (0.55f + 0.45f * (1f - lift01)), (42 * (1f - 0.7f * lift01)).toInt())
            c.save()
            c.translate(bx, by)
            c.scale(sqX, sqY)
            c.drawCircle(0f, 0f, 9f, sky)
            c.drawOval(RectF(-1.8f, -0.9f, 10.4f, 11.3f), lo)
            c.drawOval(RectF(-9.5f, -10.4f, -0.9f, 1.4f), hi)
            c.save(); c.rotate(t * 820f)
            c.drawArc(RectF(-6.5f, -6.5f, 6.5f, 6.5f), 12f, 60f, false, hair)
            c.drawArc(RectF(-6.5f, -6.5f, 6.5f, 6.5f), 192f, 60f, false, hair)
            c.restore()
            c.drawCircle(0f, 0f, 9f, edge)
            c.restore()
        }
    }

    override fun critterAt(sx: Float, sy: Float): Int = when {
        sx in 128f..172f && sy in 74f..120f -> 3
        sx in 24f..120f && sy in 68f..124f -> 1
        sx in 180f..292f && sy in 68f..124f -> 2
        else -> 0
    }
}

/** True on the darker of the app's two palettes — same luminance test
 *  [CritterScene] uses, for the same reason: several drawings here are tuned
 *  differently per ground rather than just recoloured. */
private fun paletteIsDark() =
    (0.299f * Color.red(Hue.BG) + 0.587f * Color.green(Hue.BG) + 0.114f * Color.blue(Hue.BG)) < 128f

/**
 * A trail of paw prints that walks itself around the screen instead of
 * sitting still — the role picker, mainly, where the choice itself
 * (Monitor / Viewer) takes up too much of the frame for the trio in
 * [PetSceneView] to have room to read. On-theme rather than abstract: this
 * app is about a cat, a dog and an owlet, so "alive" should look like that.
 *
 * One walker, a new print roughly once a second, alternating left/right
 * like real footsteps; each print fades in, holds, then erases itself as
 * the trail moves on, capped at [maxPrints] so there is never more than a
 * few steps' worth on screen at once. [avoid] names rectangles — the header
 * and the two role cards, in this view's own coordinates — the walker
 * steers around rather than corrects after the fact: a step into a wall or
 * a card is never committed, a new heading is tried instead, and the seed
 * itself prefers somewhere with room to walk. Reduce-motion lays a short
 * motionless trail instead of walking at all.
 */
private class PawTrailView(ctx: Context) : View(ctx) {
    /** Card rectangles, in this view's local coordinates, the trail must clear. */
    var avoid: List<RectF> = emptyList()

    /** [size] varies a few percent per print so a trail is never a row of
     *  identical stamps. */
    private data class Print(
        val x: Float, val y: Float, val rotDeg: Float, val bornAt: Long, val size: Float
    )

    private val prints = ArrayDeque<Print>()
    private val random = java.util.Random()
    private var heading = random.nextFloat() * 360f
    private var wx = 0f
    private var wy = 0f
    private var seeded = false

    private val onDark = paletteIsDark()
    // Warm tan rather than a tint of Hue.TEXT. A grey print on a cream ground
    // reads as dirt on the screen; the same mark in the pets' own biscuit
    // tone (the dog's colour in [CritterScene]) reads as part of the artwork.
    private val pawColor = if (onDark) 0xFFCC9A63.toInt() else 0xFFC98C63.toInt()
    private val baseAlpha = if (onDark) 88 else 96

    private val reduceMotion: Boolean
        get() = stillModeOn(context) || try {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (e: Exception) { false }

    private val stepMs = 850L
    private val lifeMs = 7L * stepMs
    // Strictly more than lifeMs/stepMs: a print must die of old age (fading
    // out) rather than be dropped off the end of the queue while still fully
    // opaque, which read as a print blinking out of existence.
    private val maxPrints = 9

    private val stepRunnable = object : Runnable {
        override fun run() {
            step()
            invalidate()
            postDelayed(this, stepMs)
        }
    }
    // Repaints every frame so the fade in onDraw() is smooth between the
    // once-a-second steps; only runs while there is something to fade.
    private val fadeTicker = object : Runnable {
        override fun run() {
            invalidate()
            if (prints.isNotEmpty()) postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(stepRunnable)
        removeCallbacks(fadeTicker)
        if (reduceMotion) {
            seedStatic()
        } else {
            post(stepRunnable)
            post(fadeTicker)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(stepRunnable)
        removeCallbacks(fadeTicker)
        super.onDetachedFromWindow()
    }

    /** Set once [seedStatic] has laid a trail: [onDraw] then reads its alpha
     *  off each print's place in the queue rather than off the clock, since
     *  in this mode there is no clock and no second frame. */
    private var staticTrail = false

    /** A short, motionless trail so a reduce-motion screen is not bare.
     *
     *  This used to draw nothing at all: every print was stamped in the same
     *  millisecond, the age-based fade put them all at the very start of
     *  their fade-*in*, and with no ticker running to advance them they sat
     *  at alpha zero for good. Hence [staticTrail]. */
    private var seedTries = 0
    private fun seedStatic() {
        // Wait for both a size and the card rectangles — seeded before the
        // caller has measured them, a static trail would be stamped straight
        // across a card with no later step to walk it off again.
        if (width <= 0 || height <= 0 || (avoid.isEmpty() && seedTries < 24)) {
            seedTries++
            postDelayed({ seedStatic() }, 40L)
            return
        }
        seedStart()
        repeat(6) { step() }
        staticTrail = true
        invalidate()
    }

    // [blocked] tests the print's centre, so this has to clear the print's
    // own circumscribed radius — 0.71 of PAW_DP for the drawing, plus the 6%
    // a print can be scaled up by — or a paw hangs off the screen edge or
    // bleeds onto a card. The original 8dp did exactly that.
    private fun pad() = (PAW_DP * 0.76f + 3f) * resources.displayMetrics.density

    private fun blocked(x: Float, y: Float, pad: Float): Boolean {
        if (x < pad || y < pad || x > width - pad || y > height - pad) return true
        // The version chip sits in the screen's bottom-left corner and belongs
        // to the shell, not to the role screen, so it can never arrive through
        // [avoid] — but a print walking over it is just as unreadable.
        val d = resources.displayMetrics.density
        if (x < 82f * d && y > height - 72f * d) return true
        for (r in avoid) {
            if (x > r.left - pad && x < r.right + pad && y > r.top - pad && y < r.bottom + pad) return true
        }
        return false
    }

    private fun stepLen() = min(width, height) * 0.105f

    /** How many of eight compass directions a full stride from ([x],[y])
     *  could actually be taken in. A spot scoring low is a pocket: legal to
     *  stand in, nowhere to walk. */
    private fun roomAt(x: Float, y: Float, p: Float): Int {
        var n = 0
        val len = stepLen()
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            if (!blocked(x + (cos(a) * len).toFloat(), y + (sin(a) * len).toFloat(), p)) n++
        }
        return n
    }

    /** Somewhere on screen that isn't inside a card — tried a handful of
     *  random spots rather than a fixed corner, so a fresh screen doesn't
     *  always start its trail from the same place.
     *
     *  Being merely legal is not enough: a spot has to have somewhere to walk
     *  to, or the trail paces on the spot for as long as the screen is up.
     *  So the good spots are taken first, and a cramped one only if nothing
     *  better turns up. */
    private fun seedStart() {
        val p = pad()
        var bx = width * 0.5f; var by = height * 0.5f; var best = -1
        for (i in 0 until 40) {
            val x = p + random.nextFloat() * (width - 2 * p)
            val y = p + random.nextFloat() * (height - 2 * p)
            if (blocked(x, y, p)) continue
            val r = roomAt(x, y, p)
            if (r > best) { best = r; bx = x; by = y }
            if (r >= 5) break
        }
        wx = bx; wy = by; seeded = true
    }

    private fun step() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        if (!seeded) seedStart()

        // Roughly a print and a half. Longer than that and consecutive prints
        // stop reading as one animal's stride and start reading as marks
        // sprinkled at random.
        val stepLen = stepLen()
        val pad = pad()
        var nx = wx; var ny = wy
        var found = false
        // The current heading first, then widening random turns, until a
        // step lands somewhere allowed — never taken until it is.
        for (attempt in 0 until 12) {
            val tryHeading = if (attempt == 0) heading
                else heading + (random.nextFloat() - 0.5f) * 80f * attempt
            val rad = Math.toRadians(tryHeading.toDouble())
            val cx = wx + (cos(rad) * stepLen).toFloat()
            val cy = wy + (sin(rad) * stepLen).toFloat()
            if (!blocked(cx, cy, pad)) {
                nx = cx; ny = cy; heading = tryHeading; found = true; break
            }
        }
        if (!found) {
            // Properly boxed in — a fresh random heading next beat. Aiming at
            // the screen's literal centre here used to pull the walker back
            // toward the same spot every time (the cards sit near the middle
            // too), which read as pacing rather than wandering.
            heading = random.nextFloat() * 360f
            return
        }
        heading += (random.nextFloat() - 0.5f) * 14f   // a little wander, even on a clean step
        wx = nx; wy = ny

        // The left/right offset has to be a decent fraction of the stride or
        // the prints land nearly on the centre line and the trail reads as
        // scattered marks rather than something walking.
        val perp = Math.toRadians((heading + 90f).toDouble())
        val footSide = if (prints.size % 2 == 0) 1f else -1f
        val footSpacing = stepLen * 0.26f
        val px = wx + (cos(perp) * footSpacing * footSide).toFloat()
        val py = wy + (sin(perp) * footSpacing * footSide).toFloat()

        // A degree or two of splay on each print, and a few percent of size:
        // a real animal doesn't set its feet down at identical angles, and a
        // trail that does reads as a repeated sprite rather than a walk.
        val splay = (random.nextFloat() - 0.5f) * 16f
        val size = 0.94f + random.nextFloat() * 0.12f
        prints.addLast(Print(px, py, heading + 90f + splay, SystemClock.uptimeMillis(), size))
        while (prints.size > maxPrints) prints.removeFirst()
    }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val toe = RectF()

    /**
     * The metacarpal pad, in a 31-unit space with the toes pointing at -y.
     * Built once and re-used under a canvas transform.
     *
     * Broad and round at the heel, drawing in toward two soft shoulders with
     * a *shallow* dip between them. The dip is the whole trick and it is
     * easy to overdo: the first cut cut it four units deep, on a pad
     * seventeen units tall, and the print came out reading as a cashew nut.
     * A unit and a half is enough to say "pad" and not enough to say "bean".
     */
    private val padPath = Path().apply {
        moveTo(0f, -1.6f)
        cubicTo(2.8f, -3.4f, 6.4f, -3.4f, 8.6f, -0.6f)
        cubicTo(10.6f, 1.9f, 10.8f, 6.4f, 8.2f, 9.8f)
        cubicTo(5.8f, 12.9f, 2.8f, 14.2f, 0f, 14.2f)
        cubicTo(-2.8f, 14.2f, -5.8f, 12.9f, -8.2f, 9.8f)
        cubicTo(-10.8f, 6.4f, -10.6f, 1.9f, -8.6f, -0.6f)
        cubicTo(-6.4f, -3.4f, -2.8f, -3.4f, 0f, -1.6f)
        close()
    }

    /** One toe bean, sat on a short arc above the pad and turned to point out
     *  along its own radius — splayed toes, not a row of dots. The arc is
     *  deliberately tight (r=15 against a pad 11 wide): toes flung out on a
     *  wide orbit stop reading as one foot. */
    private fun toeAt(c: Canvas, angDeg: Float, rx: Float, ry: Float) {
        val a = Math.toRadians(angDeg.toDouble())
        val tx = (sin(a) * 15f).toFloat()
        val ty = 3.5f - (cos(a) * 15f).toFloat()
        c.save()
        c.rotate(angDeg, tx, ty)
        toe.set(tx - rx, ty - ry, tx + rx, ty + ry)
        c.drawOval(toe, ink)
        c.restore()
    }

    /**
     * One paw print. Four splayed toe beans over a heart-shaped pad, with
     * real air between every shape.
     *
     * The first cut was an oval plus four circles, which at 27dp read as a
     * cluster of dots — no silhouette, no species, no craft. What makes a paw
     * legible small is the shape language, not the detail: a notched pad and
     * toes that fan out along their own radii.
     */
    private fun pawPrint(
        c: Canvas, cx: Float, cy: Float, sizePx: Float, rotDeg: Float, alpha: Int
    ) {
        if (alpha <= 0) return
        ink.color = (pawColor and 0x00FFFFFF) or (alpha shl 24)
        c.save()
        c.translate(cx, cy)
        c.rotate(rotDeg)
        val s = sizePx / 31f
        c.scale(s, s)
        c.drawPath(padPath, ink)
        toeAt(c, -46f, 3.3f, 4.5f)     // outer left
        toeAt(c, -15f, 3.6f, 4.9f)     // inner left
        toeAt(c, 15f, 3.6f, 4.9f)      // inner right
        toeAt(c, 46f, 3.3f, 4.5f)      // outer right
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        if (prints.isEmpty()) return
        val baseSize = PAW_DP * resources.displayMetrics.density
        if (staticTrail) {
            // Oldest print faintest, newest full: the same read as the live
            // trail, held still.
            prints.forEachIndexed { i, p ->
                val u = (i + 1f) / prints.size
                pawPrint(c, p.x, p.y, baseSize * p.size, p.rotDeg,
                    (baseAlpha * (0.34f + 0.66f * u)).toInt())
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        for (p in prints) {
            val age = now - p.bornAt
            if (age > lifeMs) continue
            val lifeFrac = age.toFloat() / lifeMs
            // In fast, hold, then erase — real footprints don't blink into
            // being and don't blink out either.
            val a = when {
                lifeFrac < 0.12f -> lifeFrac / 0.12f
                lifeFrac > 0.6f -> (1f - lifeFrac) / 0.4f
                else -> 1f
            }.coerceIn(0f, 1f)
            // …and they press in rather than materialise: the last few percent
            // of size arrive with the ink.
            val press = 0.9f + 0.1f * (if (lifeFrac < 0.12f) a else 1f)
            pawPrint(c, p.x, p.y, baseSize * p.size * press, p.rotDeg, (baseAlpha * a).toInt())
        }
    }

    private companion object {
        /** Nose-to-heel, in dp. */
        const val PAW_DP = 30f
    }
}

/**
 * A little portrait tucked into a role card's top-right corner, clear of the
 * icon (top-left) and the text below — a cat for one card, a dog for the
 * other.
 *
 * A [CritterScene] on purpose, rather than the flat grey silhouette that was
 * here first. That version was one circle and two triangles at 20% alpha,
 * and next to the fully painted trio on the welcome screen it read as a
 * placeholder somebody forgot to finish. Subclassing gets this the app's own
 * pet palette, outline weight, blink and the reduce-motion contract for
 * free, so the corner is the *same* drawing at a quieter volume — which is
 * what "on-brand but subordinate" actually means — instead of a different,
 * worse drawing.
 *
 * Head only: at 60dp a whole sitting pose loses the face, and the face is
 * the entire point. The caller sets the view's alpha, which is what keeps it
 * behind the title and blurb.
 */
private class CritterSilhouetteView(ctx: Context, private val kind: String) : CritterScene(ctx) {
    override val vw = 64f
    override val vh = 64f
    // A dog fidgets; a cat holds still longer.
    override val loopMs = if (kind == "dog") 3400L else 4600L

    /** Decoration, not a toy — the card underneath is the tap target. */
    override fun critterAt(sx: Float, sy: Float) = 0

    /** [CritterScene.hair] is sized for the big scenes; whiskers and a mouth
     *  line at this scale need a finer nib or they read as scars. Warm brown
     *  rather than Hue.DIM's grey: the caller's alpha is already taking a
     *  chunk out of these, and a grey hairline on top of that washed out to
     *  nothing at all. */
    private val nib = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = if (onDark) 0xFF6B5238.toInt() else 0xFF6E5136.toInt()
        strokeWidth = 1.45f
        strokeCap = Paint.Cap.ROUND
        alpha = if (onDark) 210 else 185
    }

    override fun drawScene(c: Canvas) = if (kind == "cat") cat(c) else dog(c)

    private fun cat(c: Canvas) {
        val tau = t * 2.0 * PI
        val sway = sin(tau).toFloat()
        // One ear flicks, once a loop, on its own beat — the cheapest thing
        // that stops a looping idle from looking like a still image.
        val flick = hump((((t + 0.35f) % 1f) - 0.02f) / 0.09f)
        val hx = 32f; val hy = 36f

        c.save()
        c.rotate(sway * 2.2f, hx, 60f)                        // head lolls on the neck
        c.scale(1f, 1f + 0.02f * sin(tau * 2.0).toFloat(), hx, 54f)   // breath

        c.save(); c.rotate(-11f * flick, 17f, 30f)
        softTri(c, 15f, 30f, 10.5f, 6f, 30f, 22f, dove, edge)
        softTri(c, 17.5f, 28f, 14.5f, 13f, 27f, 22.5f, berry)
        c.restore()
        softTri(c, 49f, 30f, 53.5f, 6f, 34f, 22f, dove, edge)
        softTri(c, 46.5f, 28f, 49.5f, 13f, 37f, 22.5f, berry)

        mass(c, hx, hy, 41f, 36f, dove)
        capsule(c, hx, hy + 8.5f, 19f, 13.5f, creamHi)        // muzzle
        val bl = blink()
        // These two sit in the corner of a card with a paragraph of text beside
        // them, so they look *at the card* — inward and slightly down, toward
        // the words — rather than out past the reader's shoulder. The cat's
        // card has its text to the left.
        val gx = gazeX(5, -1f) * 2.1f
        val gy = gazeY(5, 0.25f) * 1.5f
        eye(c, hx - 8f, hy - 1.5f, 4.3f, bl, gx, gy)
        eye(c, hx + 8f, hy - 1.5f, 4.3f, bl, gx, gy)
        c.drawPath(Path().apply {
            moveTo(hx, hy + 10.6f); lineTo(hx - 3.1f, hy + 7.2f); lineTo(hx + 3.1f, hy + 7.2f); close()
        }, berry)
        c.drawArc(RectF(hx - 5.5f, hy + 9.6f, hx, hy + 15.4f), 20f, 130f, false, nib)
        c.drawArc(RectF(hx, hy + 9.6f, hx + 5.5f, hy + 15.4f), 30f, 130f, false, nib)
        // Whiskers spring off the *muzzle*, not the middle of the cheek —
        // started any higher they read as scars across the face.
        for (s in intArrayOf(-1, 1)) {
            c.drawLine(hx + 9.5f * s, hy + 7.5f, hx + 21f * s, hy + 4.5f, nib)
            c.drawLine(hx + 9.5f * s, hy + 11f, hx + 21f * s, hy + 12.5f, nib)
        }
        c.restore()
    }

    private fun dog(c: Canvas) {
        val ck = clock
        val hx = 32f; val hy = 35f

        // Same argument as the two big scenes, at a quieter volume: the sway
        // wanders instead of ticking, and the head cocks over now and then.
        // At 60dp behind a card's title nobody will read the detail — but they
        // will notice a head that pivots on a perfect metronome, because that
        // is the one thing peripheral vision is actually good at.
        val sway = drift(ck * 0.72f) * 2.6f +
            (snap(beat(63, 9.1f, 1.9f, 0.55f), 0.16f) - snap(beat(62, 7.8f, 1.9f, 0.55f), 0.16f)) * 7f
        c.save()
        c.rotate(sway, hx, 62f)
        c.scale(1f, 1f + 0.024f * (breath(ck) - 0.5f) * 2f, hx, 54f)

        // Ears before the head, so only the part outside the skull shows —
        // and they lag the head's sway, which is what sells the weight. Kept
        // deliberately narrower than the first cut: splayed any wider the dog
        // out-massed the cat on the card above and the pair stopped matching.
        // The lag is now a real delay on the same wander rather than a fixed
        // phase offset, and one ear can flick without the other.
        val lag = drift(ck * 0.72f - 0.5f) * 4.4f
        val flickL = snap(beat(64, 5.5f, 0.45f, 0.5f), 0.14f) * 10f
        val flickR = snap(beat(65, 6.9f, 0.45f, 0.5f), 0.14f) * 10f
        floppyEar(c, hx - 14f, hy - 9f, 29f, 16.5f, 19f + lag + flickL, biscuitLo)
        floppyEar(c, hx + 14f, hy - 9f, 29f, 16.5f, -19f + lag - flickR, biscuitLo)

        mass(c, hx, hy, 36f, 32f, biscuit)
        capsule(c, hx, hy + 8.5f, 20f, 14f, cream)            // muzzle
        val bl = blinkAt(6)
        // Same as the cat's card, and the same direction: both icons sit top-
        // right of their card with the words to their left.
        val gx = gazeX(7, -1f) * 2f
        val gy = gazeY(7, 0.25f) * 1.4f
        eye(c, hx - 7.4f, hy - 2.5f, 3.9f, bl, gx, gy)
        eye(c, hx + 7.4f, hy - 2.5f, 3.9f, bl, gx, gy)
        capsule(c, hx, hy + 4.5f, 7.4f, 5.8f, ink)            // nose
        c.drawCircle(hx - 1.9f, hy + 3f, 1.2f, creamHi)
        c.drawArc(RectF(hx - 6f, hy + 7.6f, hx, hy + 14.6f), 20f, 130f, false, nib)
        c.drawArc(RectF(hx, hy + 7.6f, hx + 6f, hy + 14.6f), 30f, 130f, false, nib)
        // Panting in bouts, as on the big scenes — the tongue is in more often
        // than it is out, and it arrives quickly rather than easing out on a
        // cycle you can set your watch by.
        val pant = maxOf(snap(beat(66, 7f, 3.0f, 0.85f), 0.10f),
            snap(beat(67, 11f, 2.4f, 0.55f), 0.10f))
        val loll = (pant * (4.2f + 1.2f * sin(ck * 12f))).coerceAtLeast(0f)
        if (loll > 0.5f)
            c.drawRoundRect(hx - 2.4f, hy + 10.4f, hx + 2.4f, hy + 10.4f + loll, 2.4f, 2.4f, tongue)
        c.restore()
    }
}
