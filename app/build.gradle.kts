import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("androidx.navigation.safeargs.kotlin")
    // kapt is used for the Room compiler. KSP is the recommended, faster
    // replacement (Room 2.6.1 supports it); migrate by adding the
    // com.google.devtools.ksp plugin (1.9.20-1.0.14) and switching the
    // dependency below from kapt(...) to ksp(...). Left as kapt for now to
    // avoid an unverified build change (m8).
    id("kotlin-kapt")
}

android {
    namespace = "com.sshtool"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sshtool"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val buildTime = sdf.format(Date())
        val gitSha = providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "GIT_REPO", "\"https://github.com/Dts0/AndroidSSHTerminal\"")
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                props.load(FileInputStream(propsFile))
                storeFile = file(props.getProperty("storeFile", "../release.keystore"))
                storePassword = props.getProperty("storePassword", "")
                keyAlias = props.getProperty("keyAlias", "release")
                keyPassword = props.getProperty("keyPassword", "")
            } else {
                // Surface the missing-keystore case loudly at configuration time
                // rather than failing late at the signing step of assembleRelease (m7).
                logger.lifecycle("WARNING: keystore.properties not found at ${propsFile.absolutePath}; " +
                    "release builds will be unsigned. Create it with storeFile/storePassword/keyAlias/keyPassword.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only sign when a keystore is actually available; otherwise produce an
            // unsigned release APK. CI does not build release (release is a local,
            // developer-owned workflow), so the missing-keystore case must not turn
            // assembleRelease into a green-but-unsigned trap.
            if (rootProject.file("keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Security - Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    // JSch for SSH
    implementation("com.github.mwiede:jsch:0.2.18")

    // Mature terminal emulator stack (vendored Termux components)
    implementation(project(":termux-terminal-emulator"))
    implementation(project(":termux-terminal-view"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // SwipeRefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Room for local database (optional, for more complex storage)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}

// Local-only: copy APK to /workspace/apk after each debug build
// CI does not need this — the task is skipped when CI=true
if (System.getenv("CI") == null) {
    afterEvaluate {
        tasks.register<Copy>("copyApkToOutput") {
            from(layout.buildDirectory.dir("outputs/apk/debug"))
            include("*.apk")
            into("/workspace/apk")
            rename { "SSHTerminal-debug.apk" }
        }
        tasks.named("assembleDebug") {
            finalizedBy("copyApkToOutput")
        }
    }
}

