package io.ltverdict.core

/**
 * A rule of the baseline or release API refused a request or a stored document. The rules live here, in the core, and know no
 * HTTP; the web layer picks the status from [kind] (malformed 400, unprocessable 422, corrupt 500) and answers with [code] and
 * [message]. It does not extend IllegalArgumentException, IllegalStateException or NoSuchElementException, so the wrappers that
 * map storage failures never take it for one of theirs.
 */
internal class RuleFailure(
    val kind: RuleFailureKind,
    val code: String,
    override val message: String,
) : RuntimeException(message)

internal enum class RuleFailureKind {
    MALFORMED,
    UNPROCESSABLE,
    CORRUPT,
}

internal fun ruleMalformed(message: String): Nothing = throw RuleFailure(RuleFailureKind.MALFORMED, "MALFORMED_REQUEST", message)

internal fun ruleUnprocessable(
    code: String,
    message: String,
): Nothing = throw RuleFailure(RuleFailureKind.UNPROCESSABLE, code, message)

internal fun ruleCorrupt(
    code: String,
    message: String,
): Nothing = throw RuleFailure(RuleFailureKind.CORRUPT, code, message)
