package ape.crank.config

import java.nio.file.Path
import java.nio.file.Paths

object PlatformPaths {
    fun configDir(): Path {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "crank")
            os.contains("mac") -> Paths.get(System.getProperty("user.home"), "Library", "Application Support", "crank")
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")
                if (!xdgConfig.isNullOrBlank()) Paths.get(xdgConfig, "crank")
                else Paths.get(System.getProperty("user.home"), ".config", "crank")
            }
        }
    }

    fun stateFile(): Path = configDir().resolve("state.json")
}
