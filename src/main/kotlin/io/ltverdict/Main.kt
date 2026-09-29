package io.ltverdict

import io.ltverdict.cli.runCli
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runCli(args))
}
