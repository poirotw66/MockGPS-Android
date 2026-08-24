package com.sora.mockgps.core.io

import java.io.InputStream

/** Reads at most [maxBytes], rejecting overlong untrusted SAF and HTTP input without API-33 calls. */
fun InputStream.readBoundedUtf8(maxBytes: Int): String {
    require(maxBytes > 0)
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "Input exceeds size limit." }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}
