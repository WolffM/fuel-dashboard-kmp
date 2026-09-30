package com.angussoftware.fueldashboard

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Refusing to start when libraries would load from an AppImage's mount.
 *
 * The first case is the environment of the crash that motivated this
 * (hs_err_pid1292924): a JVM launched by an AppImage-based agent, faulting
 * with SIGBUS inside libXtst.so.6 loaded from the agent's mount after it went
 * away.
 */
class EphemeralLibraryPathTest {

    private val linux = "Linux"

    @Test
    fun theCrashEnvironmentIsRefused() {
        val env = mapOf("LD_LIBRARY_PATH" to "/tmp/.mount_letta-E0fY74/usr/lib:")
        val hazard = assertNotNull(EphemeralLibraryPath.find(env, linux))
        assertEquals("/tmp/.mount_letta-E0fY74/usr/lib", hazard.entry)
        assertEquals("/tmp/.mount_letta-E0fY74", hazard.mount)
        val message = assertNotNull(EphemeralLibraryPath.refusal(env, linux))
        assertTrue("/tmp/.mount_letta-E0fY74/usr/lib" in message, message)
        assertTrue(EphemeralLibraryPath.OVERRIDE_ENV in message, "must say how to proceed anyway: $message")
    }

    @Test
    fun anAppDirMountIsRecognisedWhereverItLives() {
        // APPDIR is the runtime's own statement of where it is mounted; it need
        // not be under /tmp or named .mount_ (e.g. an extracted AppImage).
        val env = mapOf("APPDIR" to "/opt/squashfs-root/", "LD_LIBRARY_PATH" to "/opt/squashfs-root/usr/lib")
        assertEquals("/opt/squashfs-root", EphemeralLibraryPath.find(env, linux)?.mount)
    }

    @Test
    fun anyOffendingEntryCountsNotJustTheFirst() {
        val env = mapOf("LD_LIBRARY_PATH" to "/usr/local/lib:/opt/cuda/lib64:/tmp/.mount_tool/usr/lib")
        assertEquals("/tmp/.mount_tool/usr/lib", EphemeralLibraryPath.find(env, linux)?.entry)
    }

    @Test
    fun ordinaryLibraryPathsAreFine() {
        for (path in listOf("", ":", "/usr/local/lib", "/usr/local/lib:/opt/cuda/lib64", "/tmp/build/lib")) {
            assertNull(EphemeralLibraryPath.find(mapOf("LD_LIBRARY_PATH" to path), linux), "'$path'")
        }
        assertNull(EphemeralLibraryPath.find(emptyMap(), linux), "no library path at all")
    }

    @Test
    fun matchingIsOnPathComponentsNotSubstrings() {
        // /opt/app must not claim /opt/application, and a directory whose name
        // merely contains ".mount_" is not the runtime's mount point.
        assertNull(
            EphemeralLibraryPath.find(mapOf("APPDIR" to "/opt/app", "LD_LIBRARY_PATH" to "/opt/application/lib"), linux),
        )
        assertNull(EphemeralLibraryPath.find(mapOf("LD_LIBRARY_PATH" to "/data/backup.mount_old/lib"), linux))
    }

    @Test
    fun anAppImageThatClearedTheLibraryPathIsFine() {
        // APPDIR alone maps nothing from the mount into this process.
        assertNull(EphemeralLibraryPath.find(mapOf("APPDIR" to "/tmp/.mount_tool", "LD_LIBRARY_PATH" to ""), linux))
        assertNull(EphemeralLibraryPath.find(mapOf("APPDIR" to "/tmp/.mount_tool"), linux))
    }

    @Test
    fun onlyLinuxIsChecked() {
        val env = mapOf("LD_LIBRARY_PATH" to "/tmp/.mount_tool/usr/lib")
        for (os in listOf("Windows 11", "Mac OS X")) assertNull(EphemeralLibraryPath.find(env, os), os)
    }

    @Test
    fun theOverrideAcceptsOnlyAnExplicitOne() {
        val env = mapOf("LD_LIBRARY_PATH" to "/tmp/.mount_tool/usr/lib")
        assertNull(EphemeralLibraryPath.refusal(env + (EphemeralLibraryPath.OVERRIDE_ENV to "1"), linux))
        for (value in listOf("", "0", "false", "yes")) {
            assertNotNull(
                EphemeralLibraryPath.refusal(env + (EphemeralLibraryPath.OVERRIDE_ENV to value), linux),
                "'$value' must not bypass the refusal",
            )
        }
    }

    // --- end to end: the real entrypoint in a child JVM --------------------

    /**
     * Runs the real `main` in a child JVM with the given extra environment and
     * arguments. The classpath is this test's, and user.home points at the
     * test sandbox, so nothing touches the real machine even if the check fails
     * open (the timeout then catches the app starting).
     */
    private fun runMain(env: Map<String, String>, vararg args: String): Pair<Int?, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val pb = ProcessBuilder(
            listOf(
                java,
                "-Djava.awt.headless=true",
                "-Duser.home=${System.getProperty("user.home")}",
                "-cp", System.getProperty("java.class.path"),
                "com.angussoftware.fueldashboard.MainKt",
            ) + args,
        ).redirectErrorStream(true)
        pb.environment().remove("LD_LIBRARY_PATH")
        pb.environment().remove("APPDIR")
        pb.environment().remove(EphemeralLibraryPath.OVERRIDE_ENV)
        pb.environment().putAll(env)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(60, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        return (if (finished) p.exitValue() else null) to output
    }

    @Test
    fun theRealEntrypointRefusesBeforeDoingAnything() {
        if (!System.getProperty("os.name").lowercase().startsWith("linux")) return
        val (code, output) = runMain(
            mapOf("LD_LIBRARY_PATH" to "/tmp/.mount_letta-E0fY74/usr/lib:"),
            "--no-such-flag",
        )
        assertEquals(EphemeralLibraryPath.EXIT_CODE, code, output)
        assertTrue("refusing to start" in output, output)
        // The bogus flag would have exited 2 had argument parsing been reached:
        // the refusal comes first.
        assertTrue("unknown option" !in output, output)
    }

    @Test
    fun aCleanEnvironmentGetsPastTheCheck() {
        // Negative control for the test above: same launch, clean environment,
        // reaches argument parsing and fails there instead.
        val (code, output) = runMain(emptyMap(), "--no-such-flag")
        assertEquals(2, code, output)
        assertTrue("unknown option" in output, output)
    }
}
