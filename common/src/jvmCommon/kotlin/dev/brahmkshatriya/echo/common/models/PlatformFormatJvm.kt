package dev.brahmkshatriya.echo.common.models

import java.text.DateFormat

actual fun formatShortDate(epochTimeMs: Long): String {
    return DateFormat.getDateInstance(DateFormat.SHORT).format(java.util.Date(epochTimeMs))
}
