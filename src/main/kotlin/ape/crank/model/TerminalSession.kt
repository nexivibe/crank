package ape.crank.model

data class TerminalSession(
    val id: String = IdGenerator.generate(),
    var name: String = "",
    var connectionId: String = "",
    var folderId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var order: Int = 0,
    var mission: String = ""
)

data class SessionFolder(
    val id: String = IdGenerator.generate(),
    var name: String = "New Folder",
    var parentId: String? = null,
    var order: Int = 0
)
