import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Rust core wiring
// ---------------------------------------------------------------------------
val rustWorkspace: File = rootProject.projectDir.resolve("../../core/rust").canonicalFile
val rustTargetDebug: File = rustWorkspace.resolve("target/debug")
val uniffiOutRoot = layout.buildDirectory.dir("generated/uniffi")
val uniffiKotlinDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val ndkJniLibsDir = layout.buildDirectory.dir("generated/rustJniLibs")
val cargo: String = providers.environmentVariable("CARGO").getOrElse("cargo")

// Device .so cross-compilation is CI-only (needs cargo-ndk + an NDK); enable with -Pso.ndk=true.
val ndkEnabled: Boolean = providers.gradleProperty("so.ndk").map(String::toBoolean).getOrElse(false)

val rustInputs = files(
    rustWorkspace.resolve("Cargo.toml"),
    rustWorkspace.resolve("Cargo.lock"),
    rustWorkspace.resolve("so-core/Cargo.toml"),
    rustWorkspace.resolve("so-core/uniffi.toml"),
    fileTree(rustWorkspace.resolve("so-core/src")),
)

/** Generates the Kotlin bindings into build/ (never committed) via the shared script. */
val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates UniFFI Kotlin bindings for so-core into build/generated/uniffi."
    inputs.files(rustInputs).withPropertyName("rustSources")
    inputs.file(rustWorkspace.resolve("scripts/generate-bindings.sh"))
    outputs.dir(uniffiKotlinDir)
    workingDir = rustWorkspace
    environment("BINDINGS_OUT", uniffiOutRoot.get().asFile.absolutePath)
    environment("BINDINGS_LANGUAGES", "kotlin")
    commandLine("bash", "scripts/generate-bindings.sh")
}

/** Host cdylib (JNA) + mock-server binary for the host-JVM integration suite. */
val buildHostRust by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds the host so-core cdylib and the mock-server binary (debug)."
    inputs.files(rustInputs).withPropertyName("rustSources")
    inputs.files(fileTree(rustWorkspace.resolve("mock-server/src")), rustWorkspace.resolve("mock-server/Cargo.toml"))
    inputs.dir(rustWorkspace.resolve("fixtures"))
    workingDir = rustWorkspace
    commandLine(cargo, "build", "-p", "so-core", "--lib", "-p", "mock-server", "--bin", "mock-server")
    // cargo is incremental itself; keep Gradle from caching a stale binary view.
    outputs.upToDateWhen { false }
}

/** arm64-v8a + x86_64 device libraries via cargo-ndk (CI only, see so.ndk). */
val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compiles so-core for Android ABIs with cargo-ndk (-Pso.ndk=true)."
    onlyIf { ndkEnabled }
    inputs.files(rustInputs).withPropertyName("rustSources")
    outputs.dir(ndkJniLibsDir)
    workingDir = rustWorkspace
    commandLine(
        cargo, "ndk",
        "-t", "arm64-v8a", "-t", "x86_64",
        "-o", ndkJniLibsDir.get().asFile.absolutePath,
        "build", "-p", "so-core", "--lib", "--release",
    )
}

android {
    namespace = "dev.filip.stackoverflowusers"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.filip.stackoverflowusers"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Release talks to the real API; the core appends /2.3/users.
        buildConfigField("String", "API_BASE_URL", "\"https://api.stackexchange.com\"")
    }

    buildTypes {
        debug {
            // Debug-only base URL injection (e.g. -Pso.baseUrl=http://10.0.2.2:8080 for the mock server).
            val debugBaseUrl = providers.gradleProperty("so.baseUrl").getOrElse("https://api.stackexchange.com")
            buildConfigField("String", "API_BASE_URL", "\"$debugBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDir(uniffiKotlinDir)
            if (ndkEnabled) jniLibs.srcDir(ndkJniLibsDir)
        }
        // Robolectric smoke renders the shared fixtures used by every other test level.
        getByName("test") {
            resources.srcDir(rustWorkspace.resolve("fixtures"))
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*")
    }
}

androidComponents {
    // Host tests run on debug only: Compose's test activity (ui-test-manifest) is debug-only,
    // and release differs solely in BuildConfig.
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") {
    dependsOn(generateUniffiBindings)
    if (ndkEnabled) dependsOn(cargoNdkBuild)
}

// ---------------------------------------------------------------------------
// Test wiring
// ---------------------------------------------------------------------------
val integrationPackage = "dev.filip.stackoverflowusers.integration.*"

// Plain `test` = store unit tests + Robolectric; the real-core suite runs via hostIntegrationTest.
tasks.withType<Test>().configureEach {
    if (name != "hostIntegrationTest") {
        filter { excludeTestsMatching(integrationPackage) }
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val hostIntegrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Kotlin bindings vs the real Rust core (JNA, host cdylib) and a spawned mock-server."
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    dependsOn(buildHostRust, "compileDebugUnitTestKotlin")
    testClassesDirs = files(unitTest.map { it.testClassesDirs })
    classpath = files(unitTest.map { it.classpath })
    filter { includeTestsMatching(integrationPackage) }
    systemProperty("jna.library.path", rustTargetDebug.absolutePath)
    systemProperty("soCore.mockServer", rustTargetDebug.resolve("mock-server").absolutePath)
    systemProperty("soCore.fixtures", rustWorkspace.resolve("fixtures").absolutePath)
    shouldRunAfter("testDebugUnitTest")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    // JNA for the generated bindings: AAR (Android natives) for the app...
    implementation("${libs.jna.get()}@aar")
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // ...and the plain JAR (desktop libjnidispatch) on the host-JVM test classpath.
    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
