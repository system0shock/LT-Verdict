package io.ltverdict.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WindowsLauncherScriptTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `windows version guard rejects old Java and accepts Java 21 or newer`() {
        val script = Files.readString(Path.of("build/scripts/ltv.bat"))
        val guard = script.substringAfter(":execute\r\n").substringBefore("@rem Setup the command line")
        assertTrue(guard.contains("%JAVA_EXE%"))
        assertTrue(guard.contains("LT Verdict requires Java 21 or newer"))
        val java = tempDir.resolve("fake java.cmd")
        val launcher = tempDir.resolve("guard.bat")
        Files.writeString(launcher, "@echo off\r\nsetlocal EnableExtensions\r\nset \"JAVA_EXE=$java\"\r\n${guard}echo LAUNCHED\r\n")
        for (version in listOf("1.8.0_402", "17.0.2", "21", "21.0.3+9", "25-ea")) {
            for (vendor in listOf("java", "openjdk")) {
                Files.writeString(
                    java,
                    "@echo off\r\necho $vendor version \"$version\" 1>&2\r\necho Temurin Runtime Environment 1>&2\r\necho GraalVM 64-Bit Server VM 1>&2\r\n",
                )
                val process = ProcessBuilder("cmd.exe", "/d", "/c", launcher.toString()).start()
                assertVersionGuard(process, version)
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `unix version guard rejects old Java and accepts Java 21 or newer`() {
        val script = Files.readString(Path.of("build/scripts/ltv"))
        val guard =
            script
                .substringAfter("# Check the Java version before launching LT Verdict.\n")
                .substringBefore("# Increase the maximum file descriptors")
        assertTrue(guard.contains("\$JAVACMD"))
        assertTrue(guard.contains("LT Verdict requires Java 21 or newer"))
        val java = tempDir.resolve("fake java")
        val launcher = tempDir.resolve("guard.sh")
        Files.writeString(launcher, "#!/bin/sh\nJAVACMD='$java'\n${guard}echo LAUNCHED\n")
        for (version in listOf("1.8.0_402", "17.0.2", "21", "21.0.3+9", "25-ea")) {
            for (vendor in listOf("java", "openjdk")) {
                Files.writeString(
                    java,
                    "#!/bin/sh\nprintf '%s\\n' '$vendor version \"$version\"' 'Temurin Runtime Environment' 'GraalVM 64-Bit Server VM' >&2\n",
                )
                assertTrue(java.toFile().setExecutable(true))
                val process = ProcessBuilder("sh", launcher.toString()).start()
                assertVersionGuard(process, version)
            }
        }
    }

    private fun assertVersionGuard(
        process: Process,
        version: String,
    ) {
        val stdout =
            process.inputStream
                .bufferedReader()
                .readText()
                .trimEnd('\r', '\n')
        val stderr =
            process.errorStream
                .bufferedReader()
                .readText()
                .trimEnd('\r', '\n')
        val rejected = version.startsWith("1.") || version.startsWith("17.")
        assertEquals(if (rejected) 1 else 0, process.waitFor(), version)
        assertEquals(if (rejected) "" else "LAUNCHED", stdout, version)
        assertEquals(
            if (rejected) "LT Verdict requires Java 21 or newer; found $version. Set JAVA_HOME to a JDK/JRE 21+." else "",
            stderr,
            version,
        )
    }

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
