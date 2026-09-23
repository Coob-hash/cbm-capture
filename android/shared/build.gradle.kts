import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code shared by every role's UI: the capture contract, the intrinsics arithmetic, and the
// Compose Multiplatform theme and components. Android is the only target for now; iOS targets
// (iosArm64, iosSimulatorArm64) are added here when iOS work resumes (PRD § 11).
// Nothing in commonMain may use android.* or java.*: platform code goes in androidMain.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // A review target, not a product one: it renders the screens to PNG on a PC that has no
    // emulator (./gradlew :shared:screenshots). Nothing in the app depends on it.
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)   // the session countdown and the offer clock
        }
        androidMain.dependencies {
            // The two platform pieces of ui/design: the image loader and the report page's
            // file chooser. Nothing in commonMain sees either.
            implementation(libs.coil.compose)
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopMain by getting {
            dependencies { implementation(compose.desktop.currentOs) }
        }
    }
}

android {
    namespace = "ai.cbm.capture.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// With a single target, Kotlin compiles commonMain as Android code, so android.* and java.* are
// accepted there silently and would only fail the day an iOS target is added. This check fails
// the build instead. Comments and string literals are blanked first, so prose may name packages.
val checkCommonMainIsPlatformFree by tasks.registering {
    val sources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val forbidden = Regex("""(?<![\w.])(java|javax|android)\.[a-z]""")
        val keepLines = { m: MatchResult -> "\n".repeat(m.value.count { it == '\n' }) }
        val offenders = sources.files.sorted().flatMap { file ->
            file.readText()
                // String literals, raw and escaped; \x22 is the double quote.
                .replace(Regex("""\x22{3}[\s\S]*?\x22{3}|\x22(?:\\.|[^\x22\\\n])*\x22"""), keepLines)
                .replace(Regex("""/\*[\s\S]*?\*/"""), keepLines)
                .replace(Regex("""//[^\n]*"""), "")
                .lines()
                .mapIndexedNotNull { i, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(projectDir)}:${i + 1}: ${line.trim()}" else null
                }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Platform APIs in commonMain (move them to androidMain behind expect/actual):\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkCommonMainIsPlatformFree) }

/** Renders every screen to a PNG at phone size, without an emulator. Review only. */
tasks.register<JavaExec>("screenshots") {
    group = "verification"
    description = "Draws each screen to shots/*.png at 390 x 844 dp."
    dependsOn("desktopMainClasses")
    val main = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("ai.cbm.capture.screenshots.ShotsKt")
    args = listOf(project.findProperty("outDir") as String? ?: "${rootDir}/shots")
}
