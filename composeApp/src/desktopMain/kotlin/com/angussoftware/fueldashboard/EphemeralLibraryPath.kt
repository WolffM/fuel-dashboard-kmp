package com.angussoftware.fueldashboard

/**
 * Refuses to start when this process would load shared libraries from an
 * AppImage's mount.
 *
 * An AppImage runs from a FUSE filesystem it mounts at `/tmp/.mount_<name>…`
 * (its `APPDIR`), and sets `LD_LIBRARY_PATH` into that mount so its own
 * bundled libraries win. Anything it launches inherits that path. When the
 * AppImage exits, the mount is detached — and a library this process already
 * mapped from it loses its backing file. The next time a page of it is touched
 * that was never read in, the kernel raises SIGBUS (`BUS_ADRERR`), which kills
 * the JVM with no chance to recover.
 *
 * That is not hypothetical: a desktop session launched by an AppImage-based
 * agent died this way after 19h51m, faulting inside `libXtst.so.6` loaded from
 * the agent's mount (hs_err_pid1292924, removed in 2861897). This app is meant
 * to run for days, so it will always outlive such a parent sooner or later.
 *
 * Only the library path matters. An AppImage that set `APPDIR` but cleared the
 * library path for its child leaves nothing mapped from the mount, and is fine.
 */
internal object EphemeralLibraryPath {

    /** Set to `1` to start anyway, e.g. for a deliberately short session. */
    const val OVERRIDE_ENV = "FUEL_DASHBOARD_ALLOW_EPHEMERAL_LIBS"

    /** The process exit code for a refusal, distinct from usage errors (2). */
    const val EXIT_CODE = 3

    /** An `LD_LIBRARY_PATH` entry inside an AppImage mount, and that mount. */
    data class Hazard(val entry: String, val mount: String)

    /**
     * The first `LD_LIBRARY_PATH` entry that lies inside an AppImage mount, or
     * null. Linux only: AppImages and `LD_LIBRARY_PATH` do not apply elsewhere.
     *
     * A mount is recognised two ways: the parent's `APPDIR`, which the AppImage
     * runtime always exports, and — for a path inherited through something that
     * dropped `APPDIR` — a path component starting `.mount_`, the runtime's
     * naming for its mount point. Both are matched on path-component
     * boundaries, so `/opt/app` does not claim `/opt/application`.
     */
    fun find(env: Map<String, String>, osName: String): Hazard? {
        if (!osName.lowercase().startsWith("linux")) return null
        val entries = env["LD_LIBRARY_PATH"].orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val appDir = env["APPDIR"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

        for (entry in entries) {
            if (appDir != null && (entry == appDir || entry.startsWith("$appDir/"))) {
                return Hazard(entry, appDir)
            }
            val parts = entry.split('/')
            val i = parts.indexOfFirst { it.startsWith(".mount_") }
            if (i >= 0) return Hazard(entry, parts.take(i + 1).joinToString("/"))
        }
        return null
    }

    /**
     * The message to print and exit with, or null to start normally.
     *
     * Separated from [main] so the whole decision — including the override —
     * is tested without starting, or stopping, a JVM.
     */
    fun refusal(env: Map<String, String>, osName: String): String? {
        if (env[OVERRIDE_ENV]?.trim() == "1") return null
        val hazard = find(env, osName) ?: return null
        return """
            |fuel-dashboard: refusing to start — LD_LIBRARY_PATH points into an AppImage mount:
            |  ${hazard.entry}
            |That mount (${hazard.mount}) disappears when the app that launched this one exits,
            |and any library already loaded from it goes with it: the next time one of its
            |pages is touched, this process dies with SIGBUS. This app runs for days, so it
            |will outlive that parent.
            |
            |Start it from a normal shell, or without the inherited path:
            |  env -u LD_LIBRARY_PATH -u APPDIR <command>
            |To start anyway for a short session: $OVERRIDE_ENV=1
        """.trimMargin()
    }
}
