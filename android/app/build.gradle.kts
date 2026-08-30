import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Upload-key signing. Create android/keystore.properties (NOT committed) with:
//   storeFile=/abs/path/tawny-upload.jks
//   storePassword=...
//   keyAlias=upload
//   keyPassword=...
// Generate the key once:
//   keytool -genkeypair -v -keystore tawny-upload.jks -alias upload \
//     -keyalg RSA -keysize 2048 -validity 10000
// Then enrol the app in Play App Signing on first upload. If the file is
// absent the release build is left unsigned (fine for a CI artifact).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile") != null

// Remote-relay settings. Set these in android/local.properties (NOT committed):
//   tawny.rendezvousUrl=wss://<your-worker>.workers.dev
//   tawny.stunUrls=stun:stun.cloudflare.com:3478,stun:stun.l.google.com:19302
//   tawny.turnMode=auto            (auto = relay only on P2P failure; always = force)
// All optional. With none set the app builds LAN-only, exactly as before.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionCode = versionProps.getProperty("VERSION_CODE", "1").trim().toInt()
val appVersionName = versionProps.getProperty("VERSION_NAME", "0.1.0").trim()
fun localOrProject(key: String): String? =
    localProps.getProperty(key) ?: (project.findProperty(key) as String?)
val rendezvousUrl = localOrProject("tawny.rendezvousUrl").orEmpty()
val turnMode = localOrProject("tawny.turnMode") ?: "auto"
val stunUrls = localOrProject("tawny.stunUrls")
    ?: if (rendezvousUrl.isNotEmpty())
        "stun:stun.cloudflare.com:3478,stun:stun.l.google.com:19302" else ""

android {
    namespace = "com.tawny.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tawny.monitor"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "RENDEZVOUS_URL", "\"$rendezvousUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // QR: encode the pairing code on the Watcher, decode it on the Handheld.
    implementation("com.google.zxing:core:3.5.3")

    // Camera preview + frame analysis for the in-app scanner.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // The Watcher hosts the WebRTC signaling handshake itself, on the LAN.
    implementation("org.java-websocket:Java-WebSocket:1.5.3")
    implementation("org.slf4j:slf4j-nop:1.7.36")
}

// The native shell serves the web app from the APK, so there is no address to
// type. Keep assets/web/ in sync with the shared public/ folder at build time.
val syncWebAssets by tasks.registering(Copy::class) {
    // README.md: public/sounds/ documents which chime clips are still
    // placeholders, which is for whoever replaces them, not for the APK.
    // sounds/_src/: the original recordings the chime clips are derived from,
    // kept for re-editing (see tools/gen-chimes.sh). Not for the APK.
    from(rootProject.file("../public")) {
        exclude("**/.DS_Store", "**/README.md", "**/sounds/_src/**")
    }
    into(layout.projectDirectory.dir("src/main/assets/web"))
    doLast {
        val stunJson = stunUrls.split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(",") { "\"$it\"" }
        layout.projectDirectory.file("src/main/assets/web/config.json").asFile.writeText(
            """{"stun":[$stunJson],"rendezvous":"$rendezvousUrl","turnMode":"$turnMode","authRequired":false}"""
        )
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }
    .configureEach { dependsOn(syncWebAssets) }
tasks.named("preBuild") { dependsOn(syncWebAssets) }
