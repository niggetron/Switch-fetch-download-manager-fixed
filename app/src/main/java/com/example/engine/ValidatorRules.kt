package com.example.engine

/**
 * Strong HTTP range validators, adapted from aria2-next's `strongTag()/strongDate()`
 * (src/http/HttpTask.cc:150-168). Both engines need identical rules so a resumed
 * download never splices bytes from a changed representation.
 */
object ValidatorRules {
    /** Reject weak (`W/`) ETags and quote bare ones (RFC 9110 13.1.5). */
    fun strongTag(raw: String?): String? {
        val tag = raw?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        if (tag.startsWith("W/", ignoreCase = true)) return null
        return if (tag.startsWith("\"")) tag else "\"$tag\""
    }

    /** Last-Modified is a range validator only when Date >= LM + 60s (RFC 9110 8.8.2.2). */
    fun strongDate(lastModified: String?, date: String?): String? {
        if (lastModified.isNullOrBlank() || date.isNullOrBlank()) return null
        val lm = parseHttpDate(lastModified) ?: return null
        val now = parseHttpDate(date) ?: return null
        return if (now - lm >= 60_000L) lastModified else null
    }

    private fun parseHttpDate(value: String): Long? = try {
        java.time.ZonedDateTime.parse(
            value,
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
        ).toInstant().toEpochMilli()
    } catch (_: Exception) {
        // Also try the RFC 850 / asctime forms some servers still emit.
        try {
            java.time.ZonedDateTime.parse(
                value,
                java.time.format.DateTimeFormatter.ofPattern(
                    "EEEE, dd-MMM-yy HH:mm:ss 'GMT'",
                    java.util.Locale.US,
                ),
            ).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** Choose ETag first, then strong Last-Modified. */
    fun preferred(etag: String?, lastModified: String?, date: String?): String? =
        strongTag(etag) ?: strongDate(lastModified, date)
}
