package com.blueberry.router

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The one architectural rule, enforced.
 *
 * The router is a pure Kotlin island inside an Android module: transcript in, [RouterResult] out,
 * no knowledge of which of the three entry points called it. The design keeps it that way so the
 * routing logic can be tested on a plain JVM and so it cannot quietly grow a dependency on an
 * Activity.
 *
 * This reads the sources rather than the bytecode. That is deliberate and it is the honest scope of
 * the check: unit tests run against a stubbed `android.jar`, so *compiling* proves nothing — a
 * reference to `android.content.Intent` would compile fine and only throw when called. Imports are
 * where the dependency would actually appear, so imports are what this asserts on.
 */
class RouterPurityTest {

    @Test
    fun `router package imports no Android types`() {
        val offences = mutableListOf<String>()

        for (file in routerSources()) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) return@forEachIndexed
                val imported = trimmed.removePrefix("import ").trim()
                if (imported.startsWith("android.") || imported.startsWith("androidx.")) {
                    offences += "${file.name}:${index + 1}  $trimmed"
                }
            }
        }

        if (offences.isNotEmpty()) {
            fail(
                "The router must not depend on Android. Found ${offences.size} import(s):\n" +
                    offences.joinToString("\n") { "  $it" }
            )
        }
    }

    @Test
    fun `router sources are actually being scanned`() {
        val sources = routerSources()
        assertTrue(
            "Expected to find router sources to scan; found ${sources.size}",
            sources.size >= 8,
        )
        assertTrue(
            "Router.kt should be among the scanned sources",
            sources.any { it.name == "Router.kt" },
        )
    }

    private fun routerSources(): List<File> {
        val dir = System.getProperty("blueberry.router.src")
            ?: fail("blueberry.router.src is not set — see the test config in app/build.gradle.kts")
                .let { error("unreachable") }
        val root = File(dir)
        assertTrue("Router source directory does not exist: $dir", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
