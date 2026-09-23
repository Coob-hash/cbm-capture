import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "ai.cbm.capture"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.cbm.capture"
        // ARCore requires 24; Camera2 LENS_INTRINSIC_CALIBRATION requires 23. 26 keeps the
        // adaptive-icon and EncryptedSharedPreferences paths simple with no legacy branches.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The App API's public address, e.g. https://<your-ngrok-domain>/ . Kept out of Git: set
        // cbm.apiBaseUrl in android/local.properties (or pass -Pcbm.apiBaseUrl=...).
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val apiBaseUrl = (project.findProperty("cbm.apiBaseUrl") as String?)
            ?: local.getProperty("cbm.apiBaseUrl") ?: "https://example.invalid/"
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.trimEnd('/')}/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)

    // ARCore: Frame.camera.imageIntrinsics is the Android counterpart of ARFrame.camera.intrinsics.
    implementation(libs.arcore)

    // CameraX drives the Camera2 fallback path on devices without ARCore support.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)   // the reporter's photo on an FM or technician card
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// The words on a screen are written for the person holding the phone. This fails the build when a
// string in a UI file names something only we know: a requirements document, a workflow, a table,
// a protocol, a status code, or the lens arithmetic. Comments are exempt — they are for us — and so
// are ALL_CAPS wire constants, which are values the server sends, not sentences.
val checkVisibleTextIsPlain by tasks.registering {
    val uiSources = files(
        fileTree("src/main/java/ai/cbm/capture/ui") { include("**/*.kt") },
        fileTree("../shared/src/commonMain/kotlin/ai/cbm/capture/ui") { include("**/*.kt") },
        fileTree("../shared/src/androidMain/kotlin/ai/cbm/capture/ui") { include("**/*.kt") },
    )
    inputs.files(uiSources)
    doLast {
        val jargon = Regex(
            """\b(PRD|WF\d|workflow|workflows|guarded|dispatcher|intake|outbox|jsonb|webhook|""" +
                """endpoint|HTTP|ARCore|Camera2|EXIF|intrinsics|bearer|payload|n8n|Drive folder)\b|""" +
                """\u00A7|\b[QTRF]-?\d+\b""",
            RegexOption.IGNORE_CASE
        )
        val wireConstant = Regex("""^[A-Z0-9_]+$""")
        val keepLines = { m: MatchResult -> "\n".repeat(m.value.count { it == '\n' }) }
        val offenders = uiSources.files.sorted().flatMap { file ->
            file.readText()
                .replace(Regex("""/\*[\s\S]*?\*/"""), keepLines)
                .replace(Regex("""//[^\n]*"""), "")
                .lines()
                .mapIndexedNotNull { i, line ->
                    val bad = Regex("\"([^\"\\\\\\n]{4,})\"").findAll(line)
                        .map { it.groupValues[1] }
                        .filterNot { wireConstant.matches(it) }
                        .filter { jargon.containsMatchIn(it) }
                        .toList()
                    if (bad.isEmpty()) null else "${file.name}:${i + 1}: ${bad.joinToString(" | ")}"
                }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "These words are ours, not the reader's. Rewrite them for the person on the screen:\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkVisibleTextIsPlain) }
