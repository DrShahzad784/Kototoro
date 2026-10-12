@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package org.skepsun.kototoro.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

/** Chinese TXT files are often GB18030 or Big5 rather than UTF-8; Foundation decodes both. */
internal fun decodeLegacyText(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return ""
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    // kCFStringEncodingGB_18030_2000, kCFStringEncodingBig5_HKSCS_1999
    for (encoding in listOf(0x0632u, 0x0A06u)) {
        val decoded = NSString.create(data = data, encoding = CFStringConvertEncodingToNSStringEncoding(encoding))
        if (decoded != null) return decoded.toString()
    }
    return null
}
