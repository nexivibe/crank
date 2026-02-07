package ape.crank.model

import java.util.UUID

data class TerminalSession(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var connectionId: String = "",
    var folderId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var order: Int = 0
)

data class SessionFolder(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "New Folder",
    var parentId: String? = null,
    var order: Int = 0
)
