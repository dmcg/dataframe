package org.jetbrains.kotlinx.dataframe.impl.io

import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.io.readJsonStr
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal fun isCompressed(fileOrUrl: String) = listOf("gz", "zip").contains(fileOrUrl.split(".").last())

internal fun isCompressed(file: File) = listOf("gz", "zip").contains(file.extension)

internal fun isCompressed(url: URL) = isCompressed(url.path)

internal fun catchHttpResponse(url: URL, body: (InputStream) -> AnyFrame): AnyFrame {
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connect()
        val code = connection.responseCode
        if (code != 200) {
            val response = connection.responseMessage
            try {
                // attempt to read response as JSON
                return DataFrame.readJsonStr(response)
            } catch (_: Exception) {
                throw RuntimeException("Server returned HTTP response code: $code. Response: $response")
            }
        }
        return connection.inputStream.use(body)
    } finally {
        connection.disconnect()
    }
}
