package ape.crank

import javafx.application.Application

/**
 * Fat-JAR launcher. This class must NOT extend [Application] — when JavaFX
 * detects that the main class is an Application subclass it requires the
 * module-path, which does not exist inside a shaded uber-jar.  Keeping the
 * launcher separate lets `java -jar crank.jar` work on the plain classpath.
 */
object CrankLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        Application.launch(CrankApplication::class.java, *args)
    }
}
