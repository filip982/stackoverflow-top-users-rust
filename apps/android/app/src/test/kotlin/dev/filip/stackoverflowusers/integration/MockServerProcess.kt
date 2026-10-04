package dev.filip.stackoverflowusers.integration

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The real `mock-server` binary (built by the `buildHostRust` Gradle task) on an ephemeral port.
 * One process per test, so scenarios never leak between tests.
 */
class MockServerProcess private constructor(private val process: Process, val baseUrl: String) : AutoCloseable {

    override fun close() {
        runCatching { post("/__shutdown", "") }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    fun post(path: String, body: String): Int {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray()) }
        return connection.responseCode.also { connection.disconnect() }
    }

    fun get(path: String): Pair<Int, ByteArray> {
        val connection = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        return try {
            connection.responseCode to connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val LISTENING = Regex("""listening on (http://\S+)""")

        fun start(scenario: String = "success"): MockServerProcess {
            val binary = File(checkNotNull(System.getProperty("soCore.mockServer")) {
                "soCore.mockServer not set — run via ./gradlew hostIntegrationTest"
            })
            check(binary.canExecute()) { "mock-server binary missing: $binary" }
            val process = ProcessBuilder(binary.path, "--host", "127.0.0.1", "--port", "0", "--scenario", scenario)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val line = process.inputStream.bufferedReader().readLine()
                ?: error("mock-server exited before announcing its address")
            val baseUrl = LISTENING.find(line)?.groupValues?.get(1) ?: error("unexpected banner: $line")
            return MockServerProcess(process, baseUrl).also { it.awaitReady() }
        }

        /** A local URL guaranteed to refuse connections. */
        fun refusedBaseUrl(): String {
            val port = ServerSocket(0).use { it.localPort }
            return "http://127.0.0.1:$port"
        }
    }

    private fun awaitReady() {
        repeat(50) {
            if (runCatching { get("/__ready").first }.getOrNull() == 200) return
            Thread.sleep(100)
        }
        error("mock-server at $baseUrl never became ready")
    }
}
