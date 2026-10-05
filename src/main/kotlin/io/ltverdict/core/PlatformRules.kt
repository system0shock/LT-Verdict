package io.ltverdict.core

internal fun resolveServices(
    scope: PlatformScope,
    catalog: List<String>?,
): List<String> =
    when (scope) {
        is PlatformScope.Services -> scope.names
        is PlatformScope.AllServices -> catalog.orEmpty().filter { it !in scope.except }
    }
