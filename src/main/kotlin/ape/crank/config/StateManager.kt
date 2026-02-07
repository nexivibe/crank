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
            gson.fromJson(json, AppState::class.java) ?: AppState()
        } catch (e: Exception) {
            System.err.println("Failed to load state: ${e.message}")
            AppState()
        }
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
