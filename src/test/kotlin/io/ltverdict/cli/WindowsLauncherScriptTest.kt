package io.ltverdict.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class WindowsLauncherScriptTest {
    @Test
    fun `windows launcher uses a wildcard classpath so long install paths fit the cmd line limit`() {
        val script = Files.readString(Path.of("build/scripts/ltv.bat"))
        val classpathLines = script.split("\r\n").filter { it.startsWith("set CLASSPATH=") }

        assertEquals(listOf("set CLASSPATH=%APP_HOME%\\lib\\*"), classpathLines)
        assertTrue(script.contains("-classpath \"%CLASSPATH%\" io.ltverdict.MainKt %*"))
        assertFalse(script.replace("\r\n", "").contains("\n"), "CRLF line endings must be preserved")
    }

    @Test
    fun `unix launcher still lists every jar explicitly`() {
        val script = Files.readString(Path.of("build/scripts/ltv"))
        val classpathLines = script.lines().filter { it.startsWith("CLASSPATH=") }

        assertEquals(1, classpathLines.size)
        assertTrue(classpathLines.single().startsWith("CLASSPATH=\$APP_HOME/lib/lt-verdict-"))
        assertFalse(classpathLines.single().contains("lib/*"))
    }
}
