package ape.crank

import ape.crank.config.StateManager
import ape.crank.ui.MainWindow
import javafx.application.Application
import javafx.application.Platform
import javafx.stage.Stage

/**
 * JavaFX Application entry point for Crank - SSH Terminal Manager.
 */
class CrankApplication : Application() {

    private lateinit var stateManager: StateManager
    private lateinit var mainWindow: MainWindow

    override fun start(stage: Stage) {
        // Initialize state manager (loads persisted state from disk)
        stateManager = StateManager()

        // Create the main window which assembles all UI components and services
        mainWindow = MainWindow(stateManager)

        // Build the scene from the main window, restoring persisted window dimensions
        val scene = mainWindow.buildScene(stage)

        // Configure the stage
        stage.title = "Crank - SSH Terminal Manager"
        stage.scene = scene
        stage.minWidth = 800.0
        stage.minHeight = 600.0

        // Restore persisted window size
        stage.width = stateManager.state.windowWidth
        stage.height = stateManager.state.windowHeight

        // Handle close request: orderly shutdown of SSH connections and state persistence
        stage.setOnCloseRequest {
            mainWindow.shutdown()
            stateManager.shutdown()
            Platform.exit()
        }

        stage.show()

        // Focus the terminal widget after the stage is shown
        Platform.runLater {
            val lastSessionId = stateManager.state.lastSelectedSessionId
            if (lastSessionId != null && stateManager.state.sessions.any { it.id == lastSessionId }) {
                mainWindow.selectSession(lastSessionId)
            }
        }
    }
}

/**
 * Top-level main function that launches the JavaFX application.
 */
fun main() {
    Application.launch(CrankApplication::class.java)
}
