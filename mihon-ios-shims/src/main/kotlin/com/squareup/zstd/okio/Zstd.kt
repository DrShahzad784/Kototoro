@file:JvmName("OkioZstd")
package com.squareup.zstd.okio

import io.airlift.compress.zstd.ZstdInputStream
import io.airlift.compress.zstd.ZstdOutputStream
import okio.Sink
import okio.Source
import okio.buffer
import okio.sink
import okio.source

fun zstdDecompress(source: Source): Source = ZstdInputStream(source.buffer().inputStream()).source()
fun zstdCompress(sink: Sink): Sink = ZstdOutputStream(sink.buffer().outputStream()).sink()
val emptyByteArray: ByteArray = byteArrayOf()
