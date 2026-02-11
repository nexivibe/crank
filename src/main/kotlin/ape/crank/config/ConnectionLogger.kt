package ape.crank.config

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ConnectionLogger {

    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .withZone(ZoneId.systemDefault())

    private val logFile = PlatformPaths.configDir().resolve("connection.log")

    @Synchronized
    fun log(state: String, name: String, uuid: String, host: String = "", error: String? = null, durationMs: Long = 0L) {
        try {
            val now = Instant.now()
            val unixMs = now.toEpochMilli()
            val iso = isoFormatter.format(now)
            val escapedError = error?.replace(",", ";")?.replace("\n", " ") ?: ""
            val durationStr = if (durationMs > 0) formatDuration(durationMs) else ""
            val line = "$state,$name,$uuid,$host,$unixMs,$iso,$escapedError,$durationStr\n"

            Files.createDirectories(logFile.parent)
            Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use {
                it.write(line)
            }
        } catch (e: Exception) {
            System.err.println("[ConnectionLogger] failed to write log: ${e.message}")
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (days > 0 || hours > 0) append("${hours}h ")
            if (days > 0 || hours > 0 || minutes > 0) append("${minutes}m ")
            append("${seconds}s")
        }
    }
}
