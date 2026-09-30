package com.angussoftware.fueldashboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.angussoftware.fueldashboard.settings.ThemeController
import com.angussoftware.theming.compose.ui.theme.AngusTheme
import com.angussoftware.theming.compose.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun DashboardTheme(
    content: @Composable () -> Unit,
) {
    val darkTheme = when (ThemeController.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> {
            // Track the system theme so switches propagate while running
            // (Compose's isSystemInDarkTheme() always returns false on JVM).
            // Event-driven on Linux (gsettings monitor emits instantly).
            // Windows: no event stream — poll the registry every 2s
            // (cheap: one `reg query` spawn; Settings flips rarely).
            // Non-GNOME Linux without gsettings: poll every 30s.
            var systemDark by remember { mutableStateOf(isSystemDarkMode()) }
            LaunchedEffect(Unit) {
                val windows = System.getProperty("os.name", "").lowercase().contains("windows")
                val monitor = if (windows) null else try {
                    ProcessBuilder("gsettings", "monitor", "org.gnome.desktop.interface", "color-scheme")
                        .redirectErrorStream(true)
                        .start()
                } catch (_: Exception) {
                    null
                }
                if (monitor == null) {
                    // No gsettings (or Windows) — poll fallback
                    while (true) {
                        delay(if (windows) 2_000 else 30_000)
                        systemDark = isSystemDarkMode()
                    }
                } else {
                    // Kill the monitor process the moment this effect cancels
                    // (unblocks the read and prevents a leaked process).
                    coroutineContext[Job]?.invokeOnCompletion { monitor.destroyForcibly() }
                    withContext(Dispatchers.IO) {
                        monitor.inputStream.bufferedReader().forEachLine { line ->
                            systemDark = line.contains("dark", ignoreCase = true) &&
                                !line.contains("prefer-light", ignoreCase = true)
                        }
                    }
                }
            }
            systemDark
        }
    }
    // Resolve the palette FROM the resolved dark/light state — community
    // palettes (Gruvbox, Catppuccin, …) carry their own light/dark identity
    // and ignore the darkTheme flag, so SYSTEM mode must pick the palette,
    // not just the flag.
    val colorTheme = ThemeController.colorThemeFor(darkTheme)
    AngusTheme(
        darkTheme = darkTheme,
        colorTheme = colorTheme,
        content = content,
    )
}

/**
 * Detects system dark mode on desktop (Windows/Linux).
 * Compose's isSystemInDarkTheme() always returns false on JVM — this is a workaround.
 * Windows: registry AppsUseLightTheme. Linux: gsettings (GNOME), then GTK/KDE fallbacks.
 */
private fun isSystemDarkMode(): Boolean {
    return try {
        if (System.getProperty("os.name", "").lowercase().contains("windows")) {
            return isWindowsDarkMode()
        }
        // GNOME: check org.gnome.desktop.interface color-scheme
        val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.contains("dark", ignoreCase = true)) {
            return true
        }

        // Fallback: check GTK_THEME env var or ~/.config/gtk-3.0/settings.ini
        val gtkTheme = System.getenv("GTK_THEME") ?: ""
        if (gtkTheme.contains("dark", ignoreCase = true) || gtkTheme.contains("-dark", ignoreCase = true)) {
            return true
        }

        // Fallback: read GTK settings file
        val gtkSettings = File(System.getProperty("user.home"), ".config/gtk-3.0/settings.ini")
        if (gtkSettings.exists()) {
            val content = gtkSettings.readText()
            if (content.contains("gtk-application-prefer-dark-theme=1", ignoreCase = true)) {
                return true
            }
        }

        // KDE: check ~/.config/kdeglobals
        val kdeGlobals = File(System.getProperty("user.home"), ".config/kdeglobals")
        if (kdeGlobals.exists()) {
            val content = kdeGlobals.readText()
            // Look for dark color schemes
            if (content.contains("ColorScheme=", ignoreCase = true) &&
                content.lowercase().let {
                    it.contains("dark") || it.contains("breeze-dark") || it.contains("night")
                }) {
                return true
            }
        }

        false
    } catch (_: Exception) {
        false
    }
}

/**
 * Windows dark-mode detection via registry:
 * HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme
 * 0x1 = light, 0x0 = dark. Reg-free read through `reg query` (available on all
 * Windows 10+). Missing value (older builds) → light, matching OS default.
 */
private fun isWindowsDarkMode(): Boolean {
    return try {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme"
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        // Match "0x0" (dark). Any other value (0x1, missing, error text) → light.
        val match = Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x(\\d+)").find(output)
        match?.groupValues?.get(1) == "0"
    } catch (_: Exception) {
        false
    }
}
