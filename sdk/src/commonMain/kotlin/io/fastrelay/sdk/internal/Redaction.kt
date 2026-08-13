package io.fastrelay.sdk.internal

internal object Redaction {

    private val tokenQueryParam = Regex("""(token=)[^&\s"']+""")
    private val authorizationHeader = Regex("""(Authorization[:=]\s*)\S+(\s+\S+)?""", RegexOption.IGNORE_CASE)

    fun redact(message: String?, vararg secrets: String?): String {
        var result = message ?: return ""
        result = tokenQueryParam.replace(result) { "${it.groupValues[1]}***" }
        result = authorizationHeader.replace(result) { "${it.groupValues[1]}***" }
        secrets.filterNotNull().filter { it.isNotBlank() }.forEach { secret ->
            result = result.replace(secret, "***")
        }
        return result
    }
}
