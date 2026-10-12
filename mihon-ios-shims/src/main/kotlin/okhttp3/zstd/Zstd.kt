package okhttp3.zstd

import io.airlift.compress.zstd.ZstdInputStream
import okhttp3.CompressionInterceptor
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.source

/**
 * okhttp-zstd's decompression algorithm without the native zstd library (absent on iOS): aircompressor's pure-Java
 * decoder reads the stream. Extensions add it to their clients' CompressionInterceptor.
 */
object Zstd : CompressionInterceptor.DecompressionAlgorithm {
    override val encoding: String get() = "zstd"

    override fun decompress(compressedSource: BufferedSource): Source =
        ZstdInputStream(compressedSource.inputStream()).source().buffer()
}
