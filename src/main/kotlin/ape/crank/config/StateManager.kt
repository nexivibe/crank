package ape.crank.config

import ape.crank.model.AppState
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StateManager {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val saveExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "state-saver").apply { isDaemon = true }
    }
    @Volatile
    private var pendingSave = false

    var state: AppState = load()
        private set

    private fun load(): AppState {
        val file = PlatformPaths.stateFile()
        if (!Files.exists(file)) return AppState()
        return try {
            val json = Files.readString(file)
            val loaded = gson.fromJson(json, AppState::class.java) ?: return AppState()
            // Gson bypasses Kotlin constructors via Unsafe, so non-null defaults
            // may actually be null when fields are missing from the JSON.
            sanitize(loaded)
        } catch (e: Exception) {
            System.err.println("[Crank] Failed to load state: ${e.message}")
            AppState()
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitize(state: AppState): AppState {
        if (state.connections == null) state.connections = mutableListOf()
        if (state.sessions == null) state.sessions = mutableListOf()
        if (state.folders == null) state.folders = mutableListOf()
        if (state.windowWidth <= 0.0) state.windowWidth = 1200.0
        if (state.windowHeight <= 0.0) state.windowHeight = 800.0
        if (state.dividerPosition <= 0.0 || state.dividerPosition >= 1.0) state.dividerPosition = 0.25
        if (state.inactivityThresholdSeconds <= 0) state.inactivityThresholdSeconds = 30
        return state
    }

    fun save() {
        saveNow()
    }

    fun saveLater() {
        if (!pendingSave) {
            pendingSave = true
            saveExecutor.schedule({
                pendingSave = false
                saveNow()
            }, 500, TimeUnit.MILLISECONDS)
        }
    }

    private fun saveNow() {
        try {
            val dir = PlatformPaths.configDir()
            Files.createDirectories(dir)
            val json = gson.toJson(state)
            Files.writeString(PlatformPaths.stateFile(), json)
        } catch (e: Exception) {
            System.err.println("Failed to save state: ${e.message}")
        }
    }

    fun shutdown() {
        saveNow()
        saveExecutor.shutdown()
    }
}
