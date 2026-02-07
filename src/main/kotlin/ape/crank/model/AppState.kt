package ape.crank.model

data class AppState(
    var connections: MutableList<ConnectionConfig> = mutableListOf(),
    var sessions: MutableList<TerminalSession> = mutableListOf(),
    var folders: MutableList<SessionFolder> = mutableListOf(),
    var lastSelectedSessionId: String? = null,
    var windowWidth: Double = 1200.0,
    var windowHeight: Double = 800.0,
    var dividerPosition: Double = 0.25,
    var inactivityThresholdSeconds: Int = 30
)
