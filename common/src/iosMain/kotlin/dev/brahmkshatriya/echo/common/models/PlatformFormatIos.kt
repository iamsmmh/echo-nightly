package dev.brahmkshatriya.echo.common.models

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle

actual fun formatShortDate(epochTimeMs: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateStyle = NSDateFormatterShortStyle
    formatter.timeStyle = NSDateFormatterNoStyle
    val date = NSDate(timeIntervalSince1970 = epochTimeMs / 1000.0)
    return formatter.stringFromDate(date)
}
