import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Google sign-in for Drive sync: google.clientId / google.clientSecret in local.properties
// (a "Desktop app" OAuth client of your Google Cloud project; see README). Without them the
// app builds as before and Drive sync says how to set it up.
val localProps = rootProject.file("local.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
fun googleProp(name: String) = (localProps?.getProperty("google.$name") ?: System.getenv("INKNOTES_GOOGLE_" + name.uppercase()) ?: "")
    .replace("\\", "\\\\").replace("\"", "\\\"")

// Release key: keystore.properties next to settings.gradle (kept out of version control).
val releaseKey = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "dev.inknotes"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.inknotes"
        minSdk = 29
        targetSdk = 36
        versionCode = 19
        versionName = "0.40"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${googleProp("clientId")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${googleProp("clientSecret")}\"")
    }


    // Device tests against the shrunk app when asked (CI); the debug app otherwise.
    testBuildType = if (project.hasProperty("testMinified")) "minified" else "debug"

    lint {
        // Known findings are in the baseline; anything new fails the build.
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = false
        // New text goes to strings.xml (old text is in the baseline).
        error += "HardcodedText"
    }

    packaging {
        resources {
            // Kotlin coroutines' debugger hook: nothing for an app on a phone.
            excludes += "DebugProbesKt.bin"
        }
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: unused code of the libraries out, the rest shortened; mapping.txt is kept
            // (tools/release.sh copies it next to the APK) to read crash traces.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones and tablets only: the x86 libraries (emulators, a few Chromebooks) would
            // add about 16 MB of native code nobody here uses.
            ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
        // The debug app shrunk as release is: the device tests run against it in CI
        // (-PtestMinified), so that R8 can't break what they check.
        create("minified") {
            initWith(getByName("debug"))
            // Not debuggable: else R8 leaves out its optimisations and the test is not of release.
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-test.pro")
            testProguardFiles("proguard-androidtest.pro")
            matchingFallbacks += listOf("debug")
        }
        // Optimised like release but signed with the debug key: installs over the debug app
        // (keeping its notebooks) to measure speed as users get it.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.ink.authoring)
    implementation(libs.ink.brush)
    implementation(libs.ink.geometry)
    implementation(libs.ink.rendering)
    implementation(libs.ink.strokes)
    implementation(libs.ink.storage)
    implementation(libs.input.motionprediction)
    implementation(libs.mlkit.digital.ink)
    implementation(libs.pdfbox.android)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Android's org.json is only a stub in unit tests: the real one.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
