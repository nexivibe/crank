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
    fun log(state: String, name: String, uuid: String) {
        try {
            val now = Instant.now()
            val unixMs = now.toEpochMilli()
            val iso = isoFormatter.format(now)
            val line = "$state,$name,$uuid,$unixMs,$iso\n"

            Files.createDirectories(logFile.parent)
            Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use {
                it.write(line)
            }
        } catch (e: Exception) {
            System.err.println("[ConnectionLogger] failed to write log: ${e.message}")
        }
    }
}
