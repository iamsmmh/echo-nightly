package dev.brahmkshatriya.echo.common.models

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle

actual fun formatShortDate(epochTimeMs: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateStyle = NSDateFormatterShortStyle
    formatter.timeStyle = NSDateFormatterNoStyle
    // NSDate only exposes timeIntervalSinceReferenceDate (2001-01-01 epoch)
    val date = NSDate(timeIntervalSinceReferenceDate = epochTimeMs / 1000.0 + 978307200.0)
    return formatter.stringFromDate(date)
}
