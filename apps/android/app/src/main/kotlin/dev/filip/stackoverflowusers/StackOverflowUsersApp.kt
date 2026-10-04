package dev.filip.stackoverflowusers

import android.app.Application
import android.content.Context
import dev.filip.stackoverflowusers.core.CoreGateway
import dev.filip.stackoverflowusers.core.RustCoreGateway
import java.io.File

class StackOverflowUsersApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * App-scoped composition root (constructor injection, no DI framework). Holds the single core
 * instance; the native library is loaded lazily on first access, so tests that never touch the
 * real core (Robolectric) don't need it.
 */
class AppContainer(private val context: Context) {
    private var baseUrl: String = BuildConfig.API_BASE_URL
    private var current: CoreGateway? = null

    val followsFile: File get() = File(context.filesDir, "follows.json")

    val gateway: CoreGateway
        @Synchronized get() = current ?: RustCoreGateway.create(baseUrl, followsFile.path).also { current = it }

    /**
     * Debug/test hook: the next [gateway] access builds a fresh core instance, optionally against
     * another base URL (mock server) and/or with cleared follows. A fresh instance re-reads the
     * follow file, which is how acceptance tests simulate a process restart.
     */
    @Synchronized
    fun resetCore(baseUrl: String? = null, clearFollows: Boolean = false) {
        check(BuildConfig.DEBUG) { "resetCore is debug-only" }
        if (baseUrl != null) this.baseUrl = baseUrl
        if (clearFollows) followsFile.delete()
        current = null
    }
}
