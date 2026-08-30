package com.tawny.monitor

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
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
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import kotlin.math.cos
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
        log("app", "── launched v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
            "android ${android.os.Build.VERSION.SDK_INT} on ${android.os.Build.MODEL}")
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${stamp.format(java.util.Date())}  $tag  $msg"
        Log.d("TawnyDiag", line)
        val f = file ?: return
        try {
            f.appendText(line + "\n")
            if (f.length() > MAX_BYTES) {
                f.writeText(f.readLines().takeLast(KEEP_LINES).joinToString("\n") + "\n")
            }
        } catch (e: Exception) { /* diagnostics must never break the app */ }
    }

    @Synchronized fun dump(): String =
        try { file?.takeIf { it.exists() }?.readText().orEmpty() } catch (e: Exception) { "" }

    @Synchronized fun clear() {
        try { file?.writeText("") } catch (e: Exception) {}
    }
}

/**
 * Palette, resolved from res/values(-night)/colors.xml so it tracks light/dark.
 * Light = "strawberry cheesecake"; dark = "brushed leather" with a warm-bronze
 * accent. Mirrors public/style.css. Call [load] before building any UI.
 * (Field names are roles, not literal hues — BERRY is bronze in dark mode.)
 */
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
    const val DIALOG = 22f       // title inside a card
    const val BODY = 16f
    const val SUB = 15f          // supporting line under a title; also links
    const val LABEL = 13f        // tracked-out small caps
    const val CAPTION = 11f
    const val MICRO = 10f        // the build stamp
    const val LEAD_BODY = 1.45f  // line-height multiplier for running text
    const val LEAD_TIGHT = 1.2f  // for headings
}

/** One radius language. 3-4dp "equipment panel" everywhere; nothing rounder. */
object Radius {
    const val CARD = 4      // dp
    const val CONTROL = 3   // dp
}

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
 * request, hold the screen on during a call, route audio through the hardware
 * echo canceller, and catch snapshot downloads as base64.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var pairOverlay: View? = null
    /** The user asked to see the camera instead of the pairing code. */
    private var pairOverlayHidden = false
    private var pairChip: View? = null   // QR shown over the Watcher's live view while waiting
    private var isLive = false

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
            toast("Tawny needs the camera and microphone for this.")
            return
        }
        themedDialog(
            title = "Permission needed",
            body = "Tawny can't stream without the camera and microphone, and " +
                "Android won't ask again from here. You can switch them on in " +
                "this app's system settings.",
            primaryLabel = "Open settings",
            onPrimary = {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                } catch (e: Exception) { toast("Could not open settings.") }
            },
            secondaryLabel = "Not now"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
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

        // Land on a real screen first, so declining a pairing link below leaves
        // the user somewhere sensible instead of on a blank activity.
        val role = prefs.getString("role", null)
        val key = prefs.getString("channelKey", null)
        val signal = prefs.getString("signalUrl", null)
        val canViewerResume = !key.isNullOrBlank() && (!signal.isNullOrBlank() || hasRendezvous)
        // A recreate (theme flip, system light/dark change) carries the screen
        // in the instance state — honour it instead of re-running the cold-start
        // routing, which would send the user back to the front door.
        val resumed = savedInstanceState?.getString("screen")?.let { restoreScreen(it) } == true
        // A permission request that was in flight when the Activity was
        // recreated: keep what it was for, so the grant still leads somewhere.
        consentTag = savedInstanceState?.getString("consentTag")
        pendingScan = savedInstanceState?.getBoolean("pendingScan", false) == true
        when {
            resumed -> Unit
            role == "station" && !key.isNullOrBlank() -> goLive("station")
            role == "viewer" && canViewerResume -> showHandheldHome()
            loadRecentSessions().isNotEmpty() -> showSessionsHome()
            // Onboarding is a one-time thing: it shows on the very first launch
            // and never auto-appears again. A returning user who never finished
            // setting up a session lands straight on the role screen instead.
            prefs.getBoolean("seenWelcome", false) -> showRole()
            else -> showWelcome()
        }

        handlePairLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only the native screens are worth restoring; a live call is rebuilt by
        // the normal resume path instead of being re-entered blind.
        if (!isLive) outState.putString("screen", screen)
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
            "about" -> showAbout()
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
            title = if (watching) "Stop watching?" else "End the call?",
            body = if (watching)
                "Viewers won't be able to check in until you start monitoring again."
            else
                "You can watch again from the home screen.",
            primaryLabel = if (watching) "Stop" else "End call",
            onPrimary = {
                saveRecentSession()
                endLive()
                if (watching) stopServers()
                afterSession()
            },
            secondaryLabel = "Keep going"
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

    /** Register this screen's swipe-left (back) and swipe-right (forward) actions. */
    private fun swipeNav(back: (() -> Unit)?, forward: (() -> Unit)?) {
        onSwipeBack = back
        onSwipeForward = forward
    }

    private fun haptic() {
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
        val isViewer = prefs.getString("role", null) == "viewer" ||
            !prefs.getString("signalUrl", null).isNullOrBlank()
        if (isViewer) {
            prefs.edit().putString("role", "viewer").apply()
            showHandheldHome()
        } else {
            prefs.edit().putString("role", "station").apply()
            goLive("station")
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
                title = "That link didn\u2019t work",
                body = "It looks like a Tawny link but Tawny can\u2019t read it. Ask for a " +
                    "fresh code from the monitor phone.",
                primaryLabel = "OK",
                onPrimary = {}
            )
            return false
        }
        confirmPairing(p)
        return true
    }

    /** "Connect to this monitor?" — the gate on every externally supplied link. */
    private fun confirmPairing(p: Pairing) {
        val paired = prefs.getString("channelKey", null)
        val replacing = !paired.isNullOrBlank() && paired != p.key
        val where = p.signal?.removePrefix("ws://")?.let { "$it on your Wi-Fi" }
            ?: "your monitor over the internet"
        themedDialog(
            title = "Connect to “${p.name}”?",
            body = "This code connects to $where.\n\n" + (
                if (replacing)
                    "Connecting will replace the monitor this phone is paired with now."
                else
                    "Only connect if this is your own monitor."
            ),
            primaryLabel = "Connect",
            onPrimary = { joinAsHandheld(p) },
            secondaryLabel = "Not now"
        )
    }

    private fun joinAsHandheld(p: Pairing) {
        stopScanner()
        Diag.log("shell", "pair accepted name=\"${p.name}\" lan=${p.signal ?: "-"} " +
            "ticket=${if (p.token.isNullOrBlank()) "MISSING" else "yes"}")
        prefs.edit()
            .apply { if (p.signal != null) putString("signalUrl", p.signal) else remove("signalUrl") }
            .apply { if (p.token != null) putString("pairToken", p.token) else remove("pairToken") }
            .putString("channelKey", p.key)
            .putString("channelName", p.name)
            .putString("role", "viewer")
            .apply()
        goLive("viewer")
    }

    /** Manual fallback when the camera can't get a clean read. */
    private fun promptPairLink() {
        val input = EditText(this).apply {
            hint = "tawny://pair?h=\u2026"
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
            clipboardText()?.let { if (it.startsWith("tawny://pair")) setText(it) }
        }
        themedDialog(
            title = "Paste the pairing link",
            body = "On the monitor phone, tap \u201cShow as link\u201d and send it to yourself.",
            primaryLabel = "Connect",
            onPrimary = {
                val p = parsePairing(input.text.toString())
                if (p == null) {
                    themedDialog(
                        title = "That link didn\u2019t work",
                        body = "It doesn\u2019t look like a Tawny pairing link. Copy the whole " +
                            "thing \u2014 it starts with tawny://pair \u2014 and try again.",
                        primaryLabel = "Try again",
                        onPrimary = { promptPairLink() },
                        secondaryLabel = "Cancel"
                    )
                } else joinAsHandheld(p)
            },
            secondaryLabel = "Cancel",
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
            title = "The camera is busy",
            body = "Tawny couldn\u2019t open this phone\u2019s camera \u2014 another app may be " +
                "using it. Close that app and try again, or paste the monitor\u2019s " +
                "pairing link instead.",
            primaryLabel = "Paste a link",
            onPrimary = { promptPairLink() },
            secondaryLabel = "Back",
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
            "This phone will use its camera and microphone to stream your pet to " +
                "your other phone while Tawny is open. The video and sound are sent " +
                "encrypted, directly between your devices, and are never recorded or " +
                "stored anywhere."
        else
            "Tawny will use this phone's microphone so you can talk back to your " +
                "pet. Your voice is sent encrypted, directly to the monitor phone, and is " +
                "never recorded or stored anywhere."

        themedDialog(
            title = if (needCamera) "Camera & microphone" else "Microphone",
            body = body,
            primaryLabel = "Continue",
            onPrimary = {
                pendingConsent = then
                consentTag = tag
                askPermissions.launch(needed.toTypedArray())
            },
            secondaryLabel = "Not now"
        )
    }

    // -------------------------------------------------------- screen frame

    private val animScale: Float
        get() = try {
            Settings.Global.getFloat(
                contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            )
        } catch (e: Exception) { 1f }

    private fun clearScreen() {
        scene?.stop(); scene = null
        playScene?.stop(); playScene = null
        scannerStop?.invoke(); scannerStop = null
        swipeNav(null, null)
        (pairOverlay?.parent as? ViewGroup)?.removeView(pairOverlay)
        pairOverlay = null
        (pairChip?.parent as? ViewGroup)?.removeView(pairChip)
        pairChip = null
        pairOverlayHidden = false
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
     * An on-theme replacement for the stock Material AlertDialog: the same
     * parchment "equipment panel" card the rest of the app uses.
     */
    /**
     * The one dialog in the app.
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
            text = "Pet Monitor"
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

    /** Press feedback with weight: dips to 97% under the finger, springs back
     *  with a small overshoot on release. Non-consuming, so the click still
     *  fires and the ripple still draws. */
    private fun pressScale(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(240)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.6f))
                        .start()
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
        sub: String? = null, onClick: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(roundRect(0, Color.TRANSPARENT, 0), 0, Hue.BERRY)
        val ph = dp(16); val pv = dp(15)
        setPadding(ph, pv, ph, pv)
        minimumHeight = dp(54)
        addView(IconView(this@MainActivity, kind, behind = Hue.PANEL).apply {
            layoutParams = LinearLayout.LayoutParams(dp(19), dp(19))
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).also { it.leftMargin = dp(13) }
            addView(TextView(this@MainActivity).apply {
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
        addView(TextView(this@MainActivity).apply {
            text = trail
            setTextColor(Hue.DIM)
            textSize = 18f
            typeface = uiFontSemi
        })
        isClickable = true; isFocusable = true
        pressScale(this)
        setOnClickListener { haptic(); onClick() }
    }

    /** One rounded panel grouping the meta rows, hairline-divided. */
    private fun metaPanel(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(Hue.PANEL, Hue.LINE, Radius.CARD)
        clipToOutline = true
        layoutParams = lp(topMargin = 16)
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MP, dp(1))
                setBackgroundColor(Hue.LINE)
            })
            addView(r)
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
        contentDescription = "App version — long-press for diagnostics"
        layoutParams = FrameLayout.LayoutParams(WC, WC).also {
            it.gravity = Gravity.START or Gravity.BOTTOM
            it.leftMargin = dp(12); it.bottomMargin = dp(8)
        }
        // Deliberately hidden behind a long-press: a support hatch, not a feature.
        isLongClickable = true
        setOnLongClickListener { haptic(); showDiagnostics(); true }
    }

    /**
     * The bug reporter. Shows the flight recorder ([Diag]) and hands it off —
     * "Send report" opens the share sheet so the log can be mailed or messaged
     * out from a phone that is on cellular and unreachable by adb.
     */
    private fun showDiagnostics() {
        clearScreen()
        screen = "diag"
        swipeNav(back = { afterSession() }, forward = null)
        val report = Diag.dump().ifBlank { "(nothing recorded yet)" }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16); setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(MP, MP)
        }
        outer.addView(backLink { afterSession() })
        outer.addView(heading("Diagnostics", "The last few sessions, as the app saw them."))

        // Vertical scroller wrapping a horizontal one: the lines are long and
        // must not wrap, so the log pans in both directions.
        val vScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, 0, 1f).also { it.topMargin = dp(12) }
            background = roundRect(Hue.PANEL, Hue.LINE)
            val p = dp(10); setPadding(p, p, p, p)
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

        outer.addView(primary("Send report") {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Tawny diagnostics v${BuildConfig.VERSION_NAME}")
                        putExtra(Intent.EXTRA_TEXT, report)
                    },
                    "Send Tawny diagnostics"
                )
            )
        })
        outer.addView(link("Copy to clipboard") {
            copyToClipboard("Tawny diagnostics", report)
            toast("Copied")
        })
        outer.addView(link("Clear log") {
            themedDialog(
                title = "Clear the log?",
                body = "The recorded history is deleted from this phone.",
                primaryLabel = "Clear",
                onPrimary = { Diag.clear(); Diag.init(applicationContext); showDiagnostics() },
                secondaryLabel = "Keep it"
            )
        })
        root.addView(outer)
    }

    /** Small round Light ↔ Dark toggle, pinned top-right of the screen. */
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
            contentDescription = "Toggle light / dark theme"
            text = if (currentTheme() == "dark") "☾" else "☀"
            setOnClickListener {
                val next = if (currentTheme() == "dark") "light" else "dark"
                prefs.edit().putString("theme", next).apply()
                applyNightMode(next)   // recreates the activity
            }
        }
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
        contentDescription = "Back"
        layoutParams = LinearLayout.LayoutParams(WC, WC).also {
            it.gravity = Gravity.START
            it.leftMargin = -dp(6)               // keep the glyph optically aligned
            it.bottomMargin = dp(2)
        }
        tapFeedback(onClick)
    }

    private fun roleCard(
        tag: String, title: String, blurb: String, kind: String, onClick: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable(roundRect(Hue.PANEL, Hue.LINE), tint = Hue.BERRY)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = lp(topMargin = 12)
        tapFeedback(onClick)

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

    /**
     * Bring the LAN relay up without blocking the UI thread.
     *
     * Starting it inline meant a `CountDownLatch.await(3, SECONDS)` plus up to
     * fifty blocking socket binds ran on the main thread from a tap — well
     * inside ANR territory on a cold device. The server is usually already up
     * by the time this is called a second time, so the fast path stays sync.
     */
    /** The LAN relay would not bind. Offer a retry that re-enters as a Monitor. */
    /**
     * Go edge-to-edge for a call and back to the framed layout afterwards. The
     * bars stay visible (people need the clock and the battery on a monitor
     * that is left running) but they float over the video instead of cutting
     * it, and their icons flip to light because the video behind them is dark.
     */
    /** True when the video itself is what is under the system bars. */
    private fun videoIsBehindBars() =
        isLive && pairOverlay?.visibility != View.VISIBLE

    /**
     * Edge-to-edge over the video, framed everywhere else.
     *
     * The pairing QR is a full-screen cream sheet mounted *over* the live
     * WebView, so keying this off `isLive` alone put white status-bar icons on
     * a cream background — the clock all but disappeared.
     */
    /** Reveal the live camera; leave one obvious way back to the code. */
    private fun hidePairOverlay() {
        pairOverlayHidden = true
        pairOverlay?.visibility = View.GONE
        showPairChip()
        refreshSystemBars()
    }

    private fun showPairOverlay() {
        pairOverlayHidden = false
        pairChip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pairChip = null
        pairOverlay?.visibility = View.VISIBLE
        refreshSystemBars()
    }

    /** A small chip over the video: the way back to the pairing code. */
    private fun showPairChip() {
        if (pairChip != null) return
        val chip = TextView(this).apply {
            text = "Show pairing code"
            textSize = Type.LABEL
            typeface = uiFontSemi
            letterSpacing = 0.06f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = pressable(roundRect(0x99000000.toInt(), 0x33FFFFFF))
            setPadding(dp(14), dp(9), dp(14), dp(9))
            minHeight = dp(44)
            tapFeedback { showPairOverlay() }
        }
        val lp = FrameLayout.LayoutParams(WC, WC).also {
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Clear of the page's own status rail, which sits under the system
            // bar inset plus its own padding.
            it.topMargin = (lastInsets.first) + dp(64)
        }
        pairChip = chip
        root.addView(chip, lp)
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

    private fun refreshSystemBars() {
        val over = videoIsBehindBars()
        val bar = if (over) Color.TRANSPARENT else Hue.BG
        @Suppress("DEPRECATION")
        window.statusBarColor = bar
        @Suppress("DEPRECATION")
        window.navigationBarColor = bar
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

    private fun relayFailed() {
        Diag.log("shell", "signal server failed to bind")
        themedDialog(
            title = "Could not start the monitor",
            body = "Tawny could not open a connection on this network. Check this " +
                "phone is on Wi-Fi, then try again.",
            primaryLabel = "Try again",
            onPrimary = { onWatcher() },
            secondaryLabel = "Back",
            onSecondary = { showRole() }
        )
    }

    private fun withSignalServer(onReady: (Int) -> Unit) {
        signalServer?.let { onReady(it.boundPort); return }
        showBusy("Starting the monitor")
        io.execute {
            val port = ensureSignalServer()
            runOnUiThread { if (!isFinishing && !isDestroyed) onReady(port) }
        }
    }

    private fun waitingRow(label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = lp(topMargin = 8)
        addView(ProgressBar(this@MainActivity).apply {
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
        val petName = prefs.getString("channelName", "your pet") ?: "your pet"
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
        val petName = session.optString("petName", "your pet")
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
            d < 60_000L -> "just now"
            d < 3_600_000L -> "${d / 60_000L}m ago"
            d < 86_400_000L -> "${d / 3_600_000L}h ago"
            d < 172_800_000L -> "yesterday"
            else -> "${d / 86_400_000L}d ago"
        }
    }

    // ------------------------------------------------- sessions home

    /** After a session ends: go to sessions home if there are any, else role select. */
    private fun afterSession() {
        if (loadRecentSessions().isNotEmpty()) showSessionsHome() else showRole()
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
            text = "Pick up where you left off"
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
            val petName = session.optString("petName", "your pet")
            val roleLabel = if (role == "station") "Monitor" else "Viewer"
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
                    text = "Delete"
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
                    title = "Remove session?",
                    body = "\"$petName\" will be removed from your recent sessions.",
                    primaryLabel = "Remove",
                    onPrimary = { deleteRecentSession(key, role); showSessionsHome() },
                    secondaryLabel = "Cancel"
                )
            }
            card.setOnLongClickListener {
                haptic()
                themedDialog(
                    title = petName,
                    body = "What would you like to do?",
                    primaryLabel = "Edit name",
                    onPrimary = {
                        askName(
                            title = "Rename session",
                            body = "The pet\u2019s name, or the room the monitor is in.",
                            initial = petName,
                            primaryLabel = "Save",
                        ) { n ->
                            updateRecentSessionName(key, n)
                            showSessionsHome()
                        }
                    },
                    secondaryLabel = "Delete session",
                    onSecondary = {
                        themedDialog(
                            title = "Remove session?",
                            body = "\"$petName\" will be removed from your recent sessions.",
                            primaryLabel = "Remove",
                            onPrimary = { deleteRecentSession(key, role); showSessionsHome() },
                            secondaryLabel = "Cancel"
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
        col.addView(ghost("+ Set up a new session") { showRole() })

        // The only two places money is ever mentioned, both below the fold of
        // the thing the user came here to do. Deliberately not on welcome, role
        // or pairing: an ask inside the setup funnel reads as a paywall, which
        // is the exact complaint the whole category earns (docs/DIRECTION.md,
        // part 2). Nothing here unlocks anything — see [showAbout].
        col.addView(gap(4))
        col.addView(metaPanel(
            metaRow("heart", "Support Tawny", Hue.BERRY, "↗") { openExternal(SUPPORT_URL) },
            metaRow("info", "About Tawny", Hue.TEXT, "›") { showAbout() },
        ))

        scroll.addView(col)
        root.addView(scroll)
        root.addView(themeToggleView())
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
    private val SUPPORT_EMAIL = "tawnyapp.radar137@passinbox.com"

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
            toast("No browser to open $url — copied instead")
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
                "About Tawny",
                "Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}"
            )
        )
        col.addView(aboutBody(
            "Tawny turns two phones into a private pet monitor. One stays with " +
                "your pet and sends its camera and microphone; the other watches, " +
                "listens, and talks back."
        ))
        col.addView(aboutBody(
            "Video and audio travel straight between your own devices, encrypted " +
                "end to end. Nothing is recorded, there is no account and no " +
                "sign-up, and no server ever sees your pairing key or a single " +
                "frame of your video. On your home Wi-Fi nothing leaves the house " +
                "at all."
        ))

        col.addView(aboutBody(
            "Tawny is free and stays free. Watching from outside your home goes " +
                "through a small relay, and that relay costs bandwidth every " +
                "month. If Tawny is useful to you, chipping in keeps it running. " +
                "Supporters get nothing extra in the app — no badge, no locked " +
                "features, nothing. That is the point."
        ))
        col.addView(metaPanel(
            metaRow("heart", "Support Tawny", Hue.BERRY, "↗", sub = "ko-fi.com/tawnyone") {
                openExternal(SUPPORT_URL)
            },
            metaRow("mail", "Email us", Hue.TEXT, "↗", sub = SUPPORT_EMAIL) {
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
                    copyToClipboard("Tawny support email", SUPPORT_EMAIL)
                    toast("No email app — address copied")
                }
            },
        ))
        col.addView(aboutBody(
            "Questions, bug reports, or anything that went wrong. If it is a " +
                "connection problem, long-press the version number on any screen " +
                "and send the diagnostics log with it."
        ))

        col.addView(gap(10))
        // CC BY 4.0 on the "psp psp psp" clip is the only sound licence that
        // needs a credit; the rest are here so it does not read as an oddity.
        col.addView(aboutBody(
            "Chime sounds: “Psp psp psp” is “Female calling a " +
                "cat” by Jolindi, trimmed and filtered, used under CC BY 4.0 " +
                "(creativecommons.org/licenses/by/4.0). “Meow” is CC0. " +
                "The dog-toy and “good boy” sounds are from Pixabay. " +
                "The bell is synthesised."
        ))
        col.addView(link("freesound.org/s/654284") {
            openExternal("https://freesound.org/s/654284/")
        })
        col.addView(gap(16))

        scroll.addView(col)
        root.addView(scroll)
    }

    // -------------------------------------------------------- welcome

    private fun showWelcome() {
        clearScreen()
        screen = "welcome"
        // Not committed to a role here — don't let onCreate auto-resume into one.
        // Also mark onboarding as seen so a cold start never lands here again.
        prefs.edit().remove("role").putBoolean("seenWelcome", true).apply()
        swipeNav(back = null, forward = { if (!resumeSession()) showRole() })
        val col = column(scroll = false)
        scene = PetSceneView(this).also {
            it.layoutParams = LinearLayout.LayoutParams(dp(300), dp(200))
        }
        col.addView(scene)
        col.addView(wordmark())
        col.addView(
            body(
                "Keep an eye on your pet from the next room or across town. " +
                    "Two phones, no accounts — just open the app and connect.",
                maxW = 300
            )
        )
        col.addView(gap(4))
        col.addView(primary("Get started") { showRole() })
        col.addView(link("I want to watch a monitor") { onHandheld() })
        mountCentered(col)
        root.addView(themeToggleView())
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
        val name = prefs.getString("channelName", "your pet") ?: "your pet"
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
                "Connected to $name's monitor. Tap below to check in from anywhere.",
                maxW = 300
            )
        )
        col.addView(gap(6))
        col.addView(primary("Watch $name now") { goLive("viewer") })
        col.addView(link("Connect to a different monitor") { onHandheld() })
        mountCentered(col)
    }

    // -------------------------------------------------------- role choice

    private fun showRole() {
        clearScreen()
        screen = "role"
        prefs.edit().remove("role").apply()
        swipeNav(back = { showWelcome() }, forward = { if (!resumeSession()) onWatcher() })
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP) }
        val col = column(scroll = true)
        col.addView(backLink { showWelcome() })
        col.addView(heading("Set up Tawny", "How will you use this phone?"))
        col.addView(
            roleCard(
                "The Monitor", "Stays with your pet",
                "Plug it in and point the camera. It streams live video and " +
                    "sound, and shows a code so others can watch too.",
                "camera"
            ) { onWatcher() }
        )
        col.addView(
            roleCard(
                "The Viewer", "Watch from this phone",
                "Check in on your pet from here — around the house on Wi-Fi, " +
                    "or from out and about.",
                "phone"
            ) { onHandheld() }
        )
        scroll.addView(col)
        root.addView(scroll)
    }

    // -------------------------------------------------------- the watcher

    private fun onWatcher() {
        val ip = lanIp()
        if (ip == null && !hasRendezvous) {
            themedDialog(
                title = "Connect to Wi-Fi",
                body = "Tawny couldn't find a network connection. " +
                    "Connect this phone to Wi-Fi and try again.",
                primaryLabel = "OK", onPrimary = {}
            )
            return
        }
        disclose(needCamera = true, tag = "monitor") {
            if (prefs.getString("channelKey", null).isNullOrBlank()) {
                promptRoomName { name ->
                    prefs.edit()
                        .putString("channelKey", newKey())
                        .putString("channelName", name)
                        .remove("myToken")        // fresh channel → fresh admission ticket
                        .apply()
                    startWatcher(ip)
                }
            } else {
                startWatcher(ip)
            }
        }
    }

    /**
     * Name the spot the Watcher is aimed at; the Handheld shows "<name> monitor".
     * Built as a bare equipment-panel card so it matches the rest of the app
     * rather than the stock Material dialog.
     */
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
            onPrimary = { onName(input.text.toString().trim().ifBlank { "your pet" }) },
            secondaryLabel = "Cancel",
            content = input,
            onShow = { dialog ->
                submit = {
                    dialog.dismiss()
                    onName(input.text.toString().trim().ifBlank { "your pet" })
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

    private fun promptRoomName(onName: (String) -> Unit) {
        val current = prefs.getString("channelName", "")
            ?.takeUnless { it == "Pet camera" || it == "your pet" }.orEmpty()
        askName(
            title = "What\u2019s your pet\u2019s name?",
            body = "",
            initial = current,
            primaryLabel = "Continue",
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

    private fun showPairText(payload: String) {
        themedDialog(
            title = "Pairing link",
            body = "Send this to the other phone. Treat it like a key to the camera \u2014 " +
                "anyone who has it can watch.\n\n" + payload,
            primaryLabel = "Copy",
            onPrimary = {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Tawny pairing", payload))
                toast("Copied")
            },
            secondaryLabel = "Close"
        )
    }

    // -------------------------------------------------------- the handheld

    private fun onHandheld() {
        disclose(needCamera = false, tag = "handheld") {
            if (has(android.Manifest.permission.CAMERA)) {
                showScanner()
                return@disclose
            }
            themedDialog(
                title = "Connect to your monitor",
                body = "Scan the monitor's QR code with the camera, or paste its " +
                    "pairing link instead.",
                primaryLabel = "Use camera",
                onPrimary = {
                    pendingScan = true
                    askPermissions.launch(arrayOf(android.Manifest.permission.CAMERA))
                },
                secondaryLabel = "Paste a link",
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
            text = "Scan your monitor's QR code"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 21f
            letterSpacing = 0f
            typeface = uiFontSemi
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            layoutParams = lp(topMargin = 4)
        })
        overlay.addView(TextView(this).apply {
            text = "Hold your pet monitor's QR code in frame."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            typeface = uiFont
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            layoutParams = lp(topMargin = 6)
        })
        overlay.addView(TextView(this).apply {
            text = "PASTE A LINK INSTEAD"
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
                title = "Not a Tawny code",
                body = "That QR code isn\u2019t a Tawny pairing code. On the monitor phone, " +
                    "the code to scan is the one on its pairing screen.",
                primaryLabel = "Keep scanning",
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

    /** Whether this build can reach a Handheld off the LAN. */
    private val hasRendezvous get() = BuildConfig.RENDEZVOUS_URL.isNotBlank()

    /**
     * `tawny://pair?k=&n=&h=<lan ip:port>&t=<token>`. `h` is dropped when Wi-Fi
     * is down or the Watcher is relay-only; `t` (a short per-pairing admission
     * ticket for the rendezvous) is added only when this build has one.
     */
    private fun pairingPayload(ip: String?, sigPort: Int, key: String, name: String, token: String?) =
        buildString {
            append("tawny://pair?k=${Uri.encode(key)}&n=${Uri.encode(name)}")
            if (ip != null) append("&h=$ip:$sigPort")
            if (token != null) append("&t=${Uri.encode(token)}")
        }

    private data class Pairing(
        val signal: String?,   // ws://<lan-ip>:<port>, or null for relay-only
        val key: String,
        val name: String,
        val token: String?
    )

    /** RFC1918 / link-local only — `h` in a pairing link is always a home-LAN address. */
    private fun isPrivateHost(hostPort: String): Boolean {
        val ip = hostPort.substringBeforeLast(':')
        val o = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (o.size != 4 || o.any { it !in 0..255 }) return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)
    }

    private fun parsePairing(raw: String): Pairing? {
        val uri = try { Uri.parse(raw.trim()) } catch (e: Exception) { return null }
        if (uri.scheme != "tawny" || uri.host != "pair") return null
        val key = uri.getQueryParameter("k") ?: return null
        if (!Regex("^[A-Za-z0-9_-]{16,64}$").matches(key)) return null
        var h = uri.getQueryParameter("h")
        if (h != null && (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{2,5}$").matches(h) || !isPrivateHost(h))) {
            h = null   // a public IP in a pairing link is not something we dial
        }
        val token = uri.getQueryParameter("t")
        if (token != null && !Regex("^[A-Za-z0-9_-]{8,64}$").matches(token)) return null
        // Nothing to dial: no usable LAN address and this build has no internet relay.
        if (h == null && !hasRendezvous) return null
        val name = (uri.getQueryParameter("n") ?: "Pet camera").take(40)
        return Pairing(h?.let { "ws://$it" }, key, name, token)
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
        val s = assetServer ?: AssetHttpServer(applicationContext, 8809).also { assetServer = it }
        return s.port
    }

    /**
     * The Watcher's signaling relay, reachable on the LAN. The port is kept
     * stable across restarts so a Handheld paired earlier can still reconnect
     * (as long as the Watcher keeps the same Wi-Fi address).
     */
    /** Returns the bound port, or -1 if the relay could not start. */
    private fun ensureSignalServer(): Int {
        signalServer?.let { return it.boundPort }
        // The relay proves each LAN peer holds the channel key before letting it
        // into the room; read the key fresh so a re-pair takes effect at once.
        val s = SignalServer(prefs.getInt("sigPort", 8820)) {
            prefs.getString("channelKey", null)
        }.apply {
            isReuseAddr = true
            start()
        }
        if (!s.ready.await(3, TimeUnit.SECONDS)) {
            try { s.stop(200) } catch (e: Exception) {}
            return -1
        }
        signalServer = s
        prefs.edit().putInt("sigPort", s.boundPort).apply()
        return s.boundPort
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
        if (role == "station") {
            withSignalServer { port ->
                if (port < 0) { relayFailed(); return@withSignalServer }
                goLiveWith(role, "ws://127.0.0.1:$port")
            }
            return
        }
        val lan = prefs.getString("signalUrl", null)
        if (lan == null && !hasRendezvous) return showWelcome()
        goLiveWith(role, lan)   // may be null — app.js then uses the rendezvous only
    }

    private fun goLiveWith(role: String, signal: String?) {
        val key = prefs.getString("channelKey", null) ?: return showWelcome()
        val name = prefs.getString("channelName", "your pet") ?: "your pet"
        val httpPort = ensureAssetServer()
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
        val ip = lanIp()      // was enumerated three times in a row, on the UI thread
        val pairPayload = if (role == "station")
            pairingPayload(ip, signalServer?.boundPort ?: 0, key, name, token)
        else null
        Diag.log("shell", "goLive role=$role lan=${ip ?: "-"} signal=${signal ?: "-"} " +
            "rv=${BuildConfig.RENDEZVOUS_URL.ifBlank { "NONE" }} " +
            "ticket=${if (token.isNullOrBlank()) "MISSING" else "yes"}")
        showWeb("http://127.0.0.1:$httpPort/#native", role, key, name, signal,
            BuildConfig.RENDEZVOUS_URL, token, pairPayload)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(
        url: String, role: String, key: String, name: String,
        signal: String?, rendezvous: String, token: String?, pairPayload: String? = null
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
                    toast("No app can open that link.")
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
                        "${token?.let { jsStr(it) } ?: "null"},{theme:${jsStr(currentTheme())}})",
                    null
                )
            }

            override fun onReceivedError(
                v: WebView, req: WebResourceRequest, err: WebResourceError
            ) {
                if (!req.isForMainFrame) return
                endLive()
                showError(err.description?.toString() ?: "Could not load the app")
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
            refreshSystemBars()   // a cream sheet is now over the video
        }
        // Station live view: swipe / back ends the session.
        if (role == "station") swipeNav(back = { confirmEndCall() }, forward = null)

        view.loadUrl(url)
    }

    /** The pairing QR + links, shown over the Watcher's live view until a
     *  Handheld connects (Bridge "watching"/"waiting" toggle its visibility). */
    private fun buildPairOverlay(name: String, payload: String): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, MP)
            setBackgroundColor(Hue.BG)
            // root is unpadded while live, so this sheet carries its own insets.
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(0, b.top, 0, b.bottom)
                insets
            }
        }
        val col = column(scroll = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(backLink { confirmEndCall() })
        // Centred: this column centres everything else, and heading() defaults to
        // START, so the screen's own title used to be the one thing out of line.
        col.addView(heading(name, "Scan this to start watching", center = true))
        val qr = ImageView(this).apply {
            val s = dp(260)
            layoutParams = LinearLayout.LayoutParams(s, s).also { it.topMargin = dp(16) }
        }
        col.addView(qr)
        // A 640x640 ZXing encode is not free, and this runs on the way into a
        // live session where the UI thread is already busy.
        io.execute {
            val bmp = try { qrBitmap(payload, 640) } catch (e: Exception) { null }
            runOnUiThread { if (bmp != null && qr.isAttachedToWindow) qr.setImageBitmap(bmp) }
        }
        col.addView(
            body(
                "On the other phone, open Tawny and tap \u201cI want to watch a " +
                    "monitor\u201d, then point its camera at this code.",
                maxW = 300
            )
        )
        col.addView(waitingRow("Waiting for a viewer to connect"))
        // You cannot aim a pet camera through a full-screen QR code. Let the
        // person setting the Monitor up check the framing without giving up the
        // pairing screen.
        col.addView(link("See what the camera sees") { hidePairOverlay() })
        col.addView(link("Show as link") { showPairText(payload) })
        col.addView(link("Rename this monitor") {
            promptRoomName { newName ->
                prefs.edit().putString("channelName", newName).apply()
                goLive("station")   // rebuild the live view + a fresh QR
            }
        })
        scroll.addView(col)
        return scroll
    }

    /** The palette the WebView should use right now — "light" or "dark". Honours
     *  an explicit user choice; otherwise follows the resolved OS setting. */
    private fun currentTheme(): String {
        prefs.getString("theme", null)?.let { if (it == "light" || it == "dark") return it }
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    private fun jsStr(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun showError(message: String) {
        val role = prefs.getString("role", "viewer") ?: "viewer"
        themedDialog(
            title = "Something went wrong",
            body = "$message\n\nTry restarting the app, or check that this phone has a network connection.",
            primaryLabel = "Retry",
            onPrimary = { goLive(role) },
            secondaryLabel = "Start over",
            onSecondary = {
                prefs.edit().clear().apply()
                stopServers()
                showWelcome()
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
            ?.takeUnless { it.isBlank() } ?: "your pet"
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
            text = "Monitor isn't on yet"
            setTextColor(Hue.TEXT)
            textSize = 24f
            typeface = uiFontSemi
            gravity = Gravity.CENTER
            layoutParams = lp(topMargin = 12)
        })
        col.addView(
            body(
                "Start Tawny on $petName's monitor phone and leave it open,\n" +
                    "then tap Retry here.",
                maxW = 300
            )
        )
        col.addView(gap(8))
        col.addView(primary("Retry") { goLive("viewer") })
        col.addView(link("Go back") { showHandheldHome() })
        mountCentered(col)
        root.addView(themeToggleView())
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
                    // Monitor: a Viewer connected / all disconnected — show or
                    // hide the pairing-QR overlay over the live view.
                    "watching" -> {
                        // Someone is actually watching: the code is done with,
                        // and so is the chip that offers to bring it back.
                        pairOverlayHidden = false
                        pairChip?.let { c -> (c.parent as? ViewGroup)?.removeView(c) }
                        pairChip = null
                        pairOverlay?.visibility = View.GONE
                        refreshSystemBars()
                    }
                    "waiting" -> {
                        if (!pairOverlayHidden) pairOverlay?.visibility = View.VISIBLE
                        refreshSystemBars()
                    }
                    // The OS took the camera back (screen off / backgrounded).
                    // The page handles the UX; record it so the diagnostics log
                    // can explain a "it froze" report after the fact.
                    "paused" -> Diag.log("shell", "capture paused — monitor left the foreground")
                    "resumed" -> Diag.log("shell", "capture resumed")
                    // Theme changed from the in-session web toggle.
                    "theme" -> {
                        val mode = obj.optString("mode")
                        if (mode == "light" || mode == "dark") {
                            prefs.edit().putString("theme", mode).apply()
                            if (!isLive) applyNightMode(mode)   // don't recreate mid-call
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
                        if (prefs.getString("role", null) == "viewer") showMonitorOffline()
                        else showError(message ?: "Could not start the session")
                    }
                }
            }
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
                    runOnUiThread { toast("Saved to Pictures/Tawny") }
                } catch (e: Exception) {
                    Log.w("Tawny", "snapshot failed", e)
                    runOnUiThread { toast("Could not save the snapshot") }
                }
            }
        }
    }

    private fun beginLive() {
        isLive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        isLive = false
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

    // -------------------------------------------------------- lifecycle

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // System light/dark flip (uiMode is in configChanges so we aren't
        // recreated). Reload the palette and refresh what's on screen.
        Hue.load(this)
        root.setBackgroundColor(Hue.BG)
        val w = web
        if (w != null) {
            w.evaluateJavascript(
                "window.tawnySetTheme && window.tawnySetTheme(${jsStr(currentTheme())})", null
            )
        } else if (scannerStop == null) {
            recreate()   // rebuild the current native screen with the new palette
        }
    }

    override fun onPause() {
        super.onPause()
        web?.evaluateJavascript(
            "window.dispatchEvent(new Event('tawny:background'))", null
        )
    }

    override fun onResume() {
        super.onResume()
        if (isLive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web?.evaluateJavascript(
            "window.dispatchEvent(new Event('tawny:foreground'))", null
        )
    }

    override fun onDestroy() {
        endLive()
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
 * Flat, rounded camera / phone glyph for the role cards — same soft filled
 * language as the owlet mascot. Body in the accent colour, details punched in
 * the card colour behind it, one tiny accent highlight.
 */
/**
 * @param behind the colour actually behind this icon. The cut-out details are
 *   painted in it, so they read as holes. It used to be hard-coded to
 *   Hue.PANEL, which is right on a card but wrong on the two screens that put
 *   the icon straight onto Hue.BG — in dark mode the phone's "screen" and
 *   "home bar" rendered as visibly lighter brown rectangles and the icon just
 *   looked broken.
 */
private class IconView(
    ctx: Context,
    private val kind: String,
    behind: Int = Hue.PANEL,
) : View(ctx) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hue.BERRY }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = behind }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hue.BERRY }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, p: Paint) =
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 48f
        canvas.save()
        canvas.scale(s, s)
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
            "info" -> {
                canvas.drawCircle(24f, 24f, 20f, body)
                canvas.drawCircle(24f, 15.5f, 2.7f, cut)         // dot
                rr(canvas, 21.4f, 20.5f, 26.6f, 34f, 2.6f, cut)  // stem
            }
            "mail" -> {
                rr(canvas, 6f, 11f, 42f, 37f, 5f, body)          // envelope
                val flap = Path().apply {
                    moveTo(8f, 13.5f); lineTo(24f, 26f); lineTo(40f, 13.5f)
                    lineTo(38f, 12f); lineTo(24f, 22.5f); lineTo(10f, 12f); close()
                }
                canvas.drawPath(flap, cut)
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
    protected val berry = paint(Hue.BERRY)                   // noses, beak, inner ear, tongue
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
        get() = try {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (e: Exception) { false }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.duration = loopMs
        if (!reduceMotion && !animator.isStarted) animator.start()
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

    /** Dark bean eye with a catch-light; [open] 1..0 squashes it shut, [look]
     *  shifts the whole eye toward what it's watching. */
    protected fun eye(c: Canvas, cx: Float, cy: Float, r: Float, open: Float, look: Float = 0f) {
        val o = open.coerceIn(0f, 1f)
        c.drawOval(RectF(cx - r + look, cy - r * o, cx + r + look, cy + r * o), ink)
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
        val lookX = if (tracking) track * 2.1f else cos(tNorm * 2.0 * PI).toFloat() * 0.9f
        val lookY = if (ballY != null) ((ballY - (cy - 4f)) / 19f).coerceIn(-1.3f, 1f) else 0f

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
            eye(c, hx - 6f, hy - 1f, er, bl)
            eye(c, hx + 6f, hy - 1f, er, bl)
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
            val by = sin(tau + 0.6).toFloat() * 1.6f
            castShadow(c, x + 2f, g + 4f, 78f, 40)

            // tap → play-bow, then a couple of happy bounces, tail going mad.
            val dp = reactP(2)
            val bow = ramp(dp / 0.26f) * (1f - ramp((dp - 0.30f) / 0.16f))
            val bounce = if (dp in 0.30f..0.92f)
                abs(sin(((dp - 0.30f) / 0.62f) * PI * 2f).toFloat()) else 0f
            val dogA = (bow + bounce).coerceAtMost(1f)
            c.save()
            c.rotate(-13f * bow, x, g)
            c.translate(0f, -bounce * 9f)

            val wag = sin(t * 7.0 * PI + dogA * reactSecs() * 40f).toFloat()
            c.save(); c.rotate(wag * (8f + dogA * 18f), x + 14f, g - 8f)
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

            val hx = x; val hy = g - 62f + by
            val sway = sin(tau + 0.6).toFloat() * 3f + sin(reactSecs() * 24f).toFloat() * dogA * 6f
            // Ears hang from the top corners of the head and splay outward, so
            // they read beside the face. Drawn BEFORE the head: only the part
            // outside the skull shows, exactly like a real floppy ear.
            // (+ve rotates clockwise on screen, so the LEFT ear takes the +ve
            // angle to swing away from the face.)
            floppyEar(c, hx - 15f, hy - 9f, 33f, 18f, 22f + sway, biscuitLo)
            floppyEar(c, hx + 15f, hy - 9f, 33f, 18f, -22f - sway, biscuitLo)
            mass(c, hx, hy, 34f, 31f, cream)
            capsule(c, hx, hy + 7f, 18f, 14f, creamHi)
            val bl = blink(0.12f)
            eye(c, hx - 6f, hy - 2f, 3.4f, bl)
            eye(c, hx + 6f, hy - 2f, 3.4f, bl)
            capsule(c, hx, hy + 4f, 6f, 5f, ink)
            c.drawCircle(hx - 1.6f, hy + 2.6f, 1.1f, creamHi)
            c.drawArc(RectF(hx - 6f, hy + 6f, hx, hy + 13f), 20f, 130f, false, hair)
            c.drawArc(RectF(hx, hy + 6f, hx + 6f, hy + 13f), 30f, 130f, false, hair)
            val loll = 4f + 1.5f * (0.5f + 0.5f * sin(t * 6.0 * PI).toFloat()) + dogA * 5f
            c.drawRoundRect(hx - 2.4f, hy + 9f, hx + 2.4f, hy + 9f + loll, 2.4f, 2.4f, berry)
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
            owlet(c, 130f, g - 2f, t, hop = (0.5f + 0.5f * sin(tau).toFloat()) * 2f)
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
        val ballX = 150f + 56f * swing                         // 94 (kitten) … 206 (dog)
        val arc = abs(cos(tau).toFloat())                      // 1 over centre, 0 at the ends
        val endBounce = abs(sin(t * 7.0 * PI).toFloat())
        val ballLift = arc * 40f + (1f - arc) * endBounce * 13f
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
            val kitLook = ((ballX - hx) / 80f).coerceIn(-1f, 1f) * 1.7f
            eye(c, hx - 4.5f, hy - 1f, 2.9f + 0.5f * pounce, bl, kitLook)
            eye(c, hx + 4.5f, hy - 1f, 2.9f + 0.5f * pounce, bl, kitLook)
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
            // 0 when the ball is away, 1 when it drops in near the dog's paws.
            val toy = ((ballX - 168f) / 38f).coerceIn(0f, 1f)
            val bat = toy * abs(sin(t * 11.0 * PI).toFloat())          // paw-swat rhythm
            val gnaw = sin(t * 8.0 * PI).toFloat().coerceAtLeast(0f) * 3f * (1f - toy)
            castShadow(c, x + 4f, g + 3f, 106f, 40)

            // tap → head snaps up, paws paddle, tail goes wild, a few body bounces.
            val dp = reactP(2)
            val dHead = ramp(dp / 0.20f) * (1f - ramp((dp - 0.76f) / 0.20f))
            val dBounce = if (dp in 0.25f..0.90f)
                abs(sin(((dp - 0.25f) / 0.65f) * PI * 3f).toFloat()) else 0f
            val dAct = (dHead + dBounce).coerceAtMost(1f)
            val by = sin(tau).toFloat() * 1.5f - toy * abs(sin(t * 8.0 * PI).toFloat()) * 3f -
                dBounce * 5f
            c.save()

            val wag = sin(t * 9.0 * PI + dAct * reactSecs() * 44f).toFloat()
            c.save(); c.rotate(wag * (12f + toy * 14f + dAct * 24f), x + 40f, g - 12f)
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

            // inner front paw planted; the outer one lifts to swat the ball / paddle
            capsule(c, x - 16f, g - 3f, 24f, 10f, cream, edge)
            val paddle = dHead * abs(sin(reactSecs() * 27f).toFloat())
            val batX = x - 30f - toy * 5f
            val batY = g - 3f - toy * 6f - bat * 12f - paddle * 9f
            if (toy > 0.02f || dHead > 0.05f)
                taper(c, x - 6f, g - 6f, batX + 4f, batY, 4.5f, biscuit, edge)
            capsule(c, batX, batY, 22f, 10f, cream, edge)
            c.drawLine(x - 38f, g - 3f, x - 38f, g - 7f, hair)
            c.drawLine(x - 34f, g - 3f, x - 34f, g - 7f, hair)

            bone(c, x - 27f, g + 1f, 7.5f)

            val hx = x - 18f; val hy = g - 30f + by + gnaw - toy * 4f - dHead * 9f
            val sway = sin(tau + 0.5).toFloat() * 3f
            // Ears hang from the top corners and splay outward — drawn BEFORE the
            // head, so only the part beside the skull shows.
            floppyEar(c, hx - 13f, hy - 8f, 29f, 16f, 22f + sway, biscuitLo)
            floppyEar(c, hx + 13f, hy - 8f, 29f, 16f, -22f - sway, biscuitLo)
            mass(c, hx, hy, 30f, 28f, cream)
            capsule(c, hx, hy + 6f, 16f, 13f, creamHi)
            val bl = blink(0.1f)
            val dogLook = -toy * 2f
            eye(c, hx - 5.5f, hy - 2f, 3.1f, bl, dogLook)
            eye(c, hx + 5.5f, hy - 2f, 3.1f, bl, dogLook)
            capsule(c, hx + dogLook, hy + 3f, 5.5f, 4.5f, ink)
            c.drawCircle(hx + dogLook - 1.5f, hy + 1.7f, 1f, creamHi)
            c.drawArc(RectF(hx - 5f, hy + 5f, hx, hy + 11f), 20f, 130f, false, hair)
            c.drawArc(RectF(hx, hy + 5f, hx + 5f, hy + 11f), 30f, 130f, false, hair)
            val loll = 3f + 1.6f * (0.5f + 0.5f * sin(t * 7.0 * PI).toFloat()) + toy * 2f + dAct * 4f
            c.drawRoundRect(hx - 2.2f, hy + 7f, hx + 2.2f, hy + 7f + loll, 2.2f, 2.2f, berry)
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
            val sqY = 0.74f + 0.26f * lift01
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
