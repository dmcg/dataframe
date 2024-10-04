package org.jetbrains.kotlinx.dataframe.impl.io

import org.apache.commons.io.input.BOMInputStream
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.io.CsvCompression
import org.jetbrains.kotlinx.dataframe.io.CsvCompression.Custom
import org.jetbrains.kotlinx.dataframe.io.CsvCompression.Gzip
import org.jetbrains.kotlinx.dataframe.io.CsvCompression.None
import org.jetbrains.kotlinx.dataframe.io.CsvCompression.Zip
import org.jetbrains.kotlinx.dataframe.io.isURL
import org.jetbrains.kotlinx.dataframe.io.readJson
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

internal fun compressionStateOf(fileOrUrl: String): CsvCompression<*> =
    when (fileOrUrl.split(".").last()) {
        "gz" -> CsvCompression.Gzip
        "zip" -> CsvCompression.Zip
        else -> CsvCompression.None
    }

internal fun compressionStateOf(file: File): CsvCompression<*> =
    when (file.extension) {
        "gz" -> CsvCompression.Gzip
        "zip" -> CsvCompression.Zip
        else -> CsvCompression.None
    }

internal fun compressionStateOf(url: URL): CsvCompression<*> = compressionStateOf(url.path)

internal fun catchHttpResponse(url: URL, body: (InputStream) -> AnyFrame): AnyFrame {
    val connection = url.openConnection()
    if (connection !is HttpURLConnection) {
        return connection.inputStream.use(body)
    }
    try {
        connection.connect()
        val code = connection.responseCode
        if (code != 200) {
            val response = connection.responseMessage
            try {
                // attempt to read error response as JSON
                return DataFrame.readJson(connection.errorStream)
            } catch (_: Exception) {
                throw RuntimeException("Server returned HTTP response code: $code. Response: $response")
            }
        }
        return connection.inputStream.use(body)
    } finally {
        connection.disconnect()
    }
}

public fun asURL(fileOrUrl: String): URL =
    if (isURL(fileOrUrl)) {
        URL(fileOrUrl).toURI()
    } else {
        File(fileOrUrl).also {
            require(it.exists()) { "File not found: \"$fileOrUrl\"" }
            require(it.isFile) { "Not a file: \"$fileOrUrl\"" }
        }.toURI()
    }.toURL()

/**
 * Adjusts the input stream to be safe to use with the given compression algorithm as well
 * as any potential BOM characters.
 *
 * Also closes the stream after the block is executed.
 */
internal inline fun <T> InputStream.useSafely(compression: CsvCompression<*>, block: (InputStream) -> T): T {
    var zipInputStream: ZipInputStream? = null

    // first wrap the stream in the compression algorithm
    val unpackedStream = when (compression) {
        None -> this

        Zip -> compression(this).also {
            it as ZipInputStream
            // make sure to call nextEntry once to prepare the stream
            if (it.nextEntry == null) error("No entries in zip file")

            zipInputStream = it
        }

        Gzip -> compression(this)

        is Custom<*> -> compression(this)
    }

    val bomSafeStream = BOMInputStream.builder()
        .setInputStream(unpackedStream)
        .get()

    try {
        return block(bomSafeStream)
    } finally {
        close()
        // if we were reading from a ZIP, make sure there was only one entry, as to
        // warn the user of potential issues
        if (compression == Zip && zipInputStream!!.nextEntry != null) {
            throw IllegalArgumentException("Zip file contains more than one entry")
        }
    }
}
