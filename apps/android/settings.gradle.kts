// Standalone Gradle build for the Android app. Lives under apps/android (not at
// the repo root) so the Rust workspace and iOS app stay independent; the Rust
// core is reached via relative paths (../../core/rust) from app/build.gradle.kts.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StackOverflowUsers"
include(":app")
