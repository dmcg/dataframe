package org.jetbrains.kotlinx.dataframe.impl.io

import io.deephaven.csv.CsvSpecs
import io.deephaven.csv.parsers.DataType
import io.deephaven.csv.parsers.DataType.BOOLEAN_AS_BYTE
import io.deephaven.csv.parsers.DataType.BYTE
import io.deephaven.csv.parsers.DataType.CHAR
import io.deephaven.csv.parsers.DataType.DATETIME_AS_LONG
import io.deephaven.csv.parsers.DataType.DOUBLE
import io.deephaven.csv.parsers.DataType.FLOAT
import io.deephaven.csv.parsers.DataType.INT
import io.deephaven.csv.parsers.DataType.LONG
import io.deephaven.csv.parsers.DataType.SHORT
import io.deephaven.csv.parsers.DataType.STRING
import io.deephaven.csv.parsers.DataType.TIMESTAMP_AS_LONG
import io.deephaven.csv.parsers.Parser
import io.deephaven.csv.parsers.Parsers
import io.deephaven.csv.reading.CsvReader
import io.deephaven.csv.util.CsvReaderException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.DataRow
import org.jetbrains.kotlinx.dataframe.api.ParserOptions
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.dataframe.api.parse
import org.jetbrains.kotlinx.dataframe.api.tryParse
import org.jetbrains.kotlinx.dataframe.columns.ValueColumn
import org.jetbrains.kotlinx.dataframe.impl.ColumnNameGenerator
import org.jetbrains.kotlinx.dataframe.io.ColType
import org.jetbrains.kotlinx.dataframe.io.Compression
import org.jetbrains.kotlinx.dataframe.io.DEFAULT_COL_TYPE
import org.jetbrains.kotlinx.dataframe.io.defaultNullStrings
import java.io.InputStream
import java.math.BigDecimal
import java.net.URL
import kotlin.reflect.KType
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf
import kotlin.time.Duration

/**
 *
 * @param inputStream Represents the file to read.
 * @param delimiter The field delimiter character. The default is ',' for CSV, 't' for TSV.
 * @param header Optional column titles.
 *   If non-empty, the data will be read with [header] as the column titles
 *   (use [skipLines] if there's a header in the data).
 *   If empty (default), the header will be read from the data.
 *   Default: empty list.
 * @param compression The compression of the data.
 *   Default: [Compression.None][org.jetbrains.kotlinx.dataframe.io.Compression.None], unless detected otherwise from the input file or url.
 * @param colTypes The expected [ColType] per column name.
 *   When given, the parser will read the column as that type, else it will infer the type.
 *   e.g. `colTypes = `[mapOf][mapOf]`("colName" `[to][to]` `[ColType][ColType]`.`[Int][ColType.Int]`)`.
 *   You can also set [DEFAULT_COL_TYPE][org.jetbrains.kotlinx.dataframe.io.DEFAULT_COL_TYPE]` `[to][to]` `[ColType][ColType]`.X`
 *   to set a _default_ column type, like [ColType.String].
 *   Default: empty map, a.k.a. infer every column type.
 * @param skipLines The number of lines to skip before reading the header and data.
 *   Useful for files with metadata, or comments at the beginning, or to give a custom [header].
 *   Default: `0`.
 * @param readLines The maximum number of lines to read from the data.
 *   If `null`, all lines will be read.
 *   Default: `null`, reads all lines.
 * @param parserOptions Optional [parsing options][ParserOptions] for columns initially read as [String].
 *   Can configure locale, date format, double parsing, skipping types, etc.
 *   Default, [defaultDelimParserOptions][org.jetbrains.kotlinx.dataframe.io.defaultDelimParserOptions]:
 *   ```
 *   ParserOptions(nullStrings = ["", "NA", "N/A", "null", "NULL", "None", "none", "NIL", "nil"])
 *   ```
 * @param ignoreEmptyLines If `true`, intermediate empty lines will be skipped.
 *   Default: `false`, empty line will be interpreted as _empty_ values if [allowMissingColumns].
 * @param allowMissingColumns If `true`, rows that are too short
 *   (fewer columns than the header) will be interpreted as _empty_ values.
 *   Default: `true`.
 * @param ignoreExcessColumns If `true`, rows that are too long
 *   (more columns than the header) will have those columns dropped.
 *   Default: `true`.
 * @param quote The quote character.
 *   Used when field- or line delimiters should be interpreted as literal text.
 *
 * For example: `123,"hello, there",456,` would correspond to: `123`; `hello, there`; `456`.
 * Default: `"`.
 * @param ignoreSurroundingSpaces If `true`, leading and trailing blanks around non-quoted fields will be trimmed.
 *   Default: `true`.
 * @param trimInsideQuoted If `true`, leading and trailing blanks inside quoted fields will be trimmed.
 *   Default: `false`.
 * @param parseParallel If `true`, the data will be parsed in parallel.
 *   Can be turned off for debugging.
 *   Default: `true`.
 * @param additionalCsvSpecs Optional [CsvSpecs] to configure additional
 *   parsing options not covered by the other parameters.
 *   The (default) values of other parameters will override the values in [additionalCsvSpecs].
 *   Default: `null`.
 */
internal fun readDelimImpl(
    inputStream: InputStream,
    delimiter: Char,
    header: List<String> = DelimParams.HEADER,
    compression: Compression<*> = DelimParams.COMPRESSION,
    colTypes: Map<String, ColType> = DelimParams.COL_TYPES,
    skipLines: Long = DelimParams.SKIP_LINES,
    readLines: Long? = DelimParams.READ_LINES,
    parserOptions: ParserOptions = DelimParams.PARSER_OPTIONS,
    ignoreEmptyLines: Boolean = DelimParams.IGNORE_EMPTY_LINES,
    allowMissingColumns: Boolean = DelimParams.ALLOW_MISSING_COLUMNS,
    ignoreExcessColumns: Boolean = DelimParams.IGNORE_EXCESS_COLUMNS,
    quote: Char = DelimParams.QUOTE,
    ignoreSurroundingSpaces: Boolean = DelimParams.IGNORE_SURROUNDING_SPACES,
    trimInsideQuoted: Boolean = DelimParams.TRIM_INSIDE_QUOTED,
    parseParallel: Boolean = DelimParams.PARSE_PARALLEL,
    additionalCsvSpecs: CsvSpecs? = DelimParams.ADDITIONAL_CSV_SPECS,
): DataFrame<*> {
    // set up the csv specs
    val csvSpecs = with(CsvSpecs.builder()) {
        if (additionalCsvSpecs != null) from(additionalCsvSpecs)
        customDoubleParser(FastDoubleParser(parserOptions))
        nullValueLiterals(parserOptions.nullStrings ?: defaultNullStrings)
        headerLegalizer(::legalizeHeader)
        numRows(readLines ?: Long.MAX_VALUE)
        ignoreEmptyLines(ignoreEmptyLines)
        allowMissingColumns(allowMissingColumns)
        ignoreExcessColumns(ignoreExcessColumns)
        delimiter(delimiter)
        quote(quote)
        ignoreSurroundingSpaces(ignoreSurroundingSpaces)
        trim(trimInsideQuoted)
        concurrent(parseParallel)

        header(header)

        skipLines(takeHeaderFromCsv = header.isEmpty(), skipLines = skipLines)

        val useDeepHavenLocalDateTime = with(parserOptions) {
            locale == null && dateTimePattern == null && dateTimeFormatter == null
        }
        parsersWithOptions(parserOptions, useDeepHavenLocalDateTime)

        colTypes(colTypes, useDeepHavenLocalDateTime) // this function must be last, so the return value is used
    }.build()

    val csvReaderResult = inputStream.useSafely(compression) { safeInputStream ->
        if (safeInputStream.available() <= 0) {
            return if (header.isEmpty()) {
                DataFrame.empty()
            } else {
                dataFrameOf(
                    header.map {
                        DataColumn.createValueColumn(
                            name = it,
                            values = emptyList<String>(),
                            type = typeOf<String>(),
                        )
                    },
                )
            }
        }

        // read the csv
        try {
            CsvReader.read(
                csvSpecs,
                safeInputStream,
                ListSink.SINK_FACTORY,
            )
        } catch (e: CsvReaderException) {
            throw IllegalStateException("Could not read delimiter-separated data", e)
        }
    }

    val defaultColType = colTypes[DEFAULT_COL_TYPE]

    // convert each ResultColumn to a DataColumn
    val cols =
        if (parseParallel) {
            runBlocking {
                csvReaderResult.map {
                    async {
                        it.toDataColumn(
                            parserOptions = parserOptions,
                            desiredColType = colTypes[it.name()] ?: defaultColType,
                        )
                    }
                }.awaitAll()
            }
        } else {
            csvReaderResult.map {
                it.toDataColumn(
                    parserOptions = parserOptions,
                    desiredColType = colTypes[it.name()] ?: defaultColType,
                )
            }
        }

    return dataFrameOf(cols)
}

@Suppress("UNCHECKED_CAST")
private fun CsvReader.ResultColumn.toDataColumn(
    parserOptions: ParserOptions,
    desiredColType: ColType?,
): DataColumn<*> {
    val listSink = data()!! as ListSink
    val columnData: List<Any?> = listSink.data
    val dataType = listSink.dataType
    val hasNulls = listSink.hasNulls
    val type = dataType().toKType().withNullability(hasNulls)

    val column = DataColumn.createValueColumn(
        name = name(),
        values = columnData,
        type = type,
    )
    if (dataType != STRING) return column

    // perform additional parsing if necessary
    column as ValueColumn<String?>

    return if (desiredColType == null) {
        // no need to check for types that Deephaven already parses
        val adjustedParserOptions = parserOptions.copy(
            skipTypes = parserOptions.skipTypes + typesDeephavenAlreadyParses,
        )
        column.tryParse(adjustedParserOptions)
    } else {
        column.tryParse(
            parserOptions.copy(
                skipTypes = ParserOptions.allTypesExcept(desiredColType.toKType()),
            ),
        )
    }
}

private fun DataType?.toKType(): KType =
    when (this) {
        BOOLEAN_AS_BYTE -> typeOf<Boolean>()

        // unused in Parsers.DEFAULT
        BYTE -> typeOf<Byte>()

        // unused in Parsers.DEFAULT
        SHORT -> typeOf<Short>()

        INT -> typeOf<Int>()

        LONG -> typeOf<Long>()

        // unused in Parsers.COMPLETE and Parsers.DEFAULT
        FLOAT -> typeOf<Float>()

        DOUBLE -> typeOf<Double>()

        DATETIME_AS_LONG -> typeOf<LocalDateTime>()

        CHAR -> typeOf<Char>()

        STRING -> typeOf<String>()

        // unused in Parsers.COMPLETE and Parsers.DEFAULT
        TIMESTAMP_AS_LONG -> typeOf<LocalDateTime>()

        DataType.CUSTOM -> error("custom data type")

        null -> error("null data type")
    }

private fun legalizeHeader(header: Array<String>): Array<String> {
    val generator = ColumnNameGenerator()
    return header.map { generator.addUnique(it) }.toTypedArray()
}

/**
 * Sets correct parsers per name in [colTypes]. If [DEFAULT_COL_TYPE] is present, it sets the default parser.
 *
 * CAREFUL: Unlike the other functions on [CsvSpecs.Builder], this function can return a NEW builder instance.
 * Make sure to use the return value.
 */
private fun CsvSpecs.Builder.colTypes(
    colTypes: Map<String, ColType>,
    useDeepHavenLocalDateTime: Boolean,
): CsvSpecs.Builder {
    if (colTypes.isEmpty()) return this

    colTypes.entries.fold(this) { it, (colName, colType) ->
        it.putParserForName(colName, colType.toCsvParser(useDeepHavenLocalDateTime))
    }

    return if (DEFAULT_COL_TYPE in colTypes) {
        this.withDefaultParser(
            colTypes[DEFAULT_COL_TYPE]!!.toCsvParser(useDeepHavenLocalDateTime),
        )
    } else {
        this
    }
}

private fun CsvSpecs.Builder.skipLines(takeHeaderFromCsv: Boolean, skipLines: Long): CsvSpecs.Builder =
    if (takeHeaderFromCsv) {
        skipHeaderRows(skipLines)
    } else {
        skipRows(skipLines)
    }

/**
 * Sets the correct parsers for the csv, based on the [ParserOptions.skipTypes].
 */
private fun CsvSpecs.Builder.parsersWithOptions(
    parserOptions: ParserOptions,
    useDeepHavenLocalDateTime: Boolean,
): CsvSpecs.Builder =
    if (parserOptions.skipTypes.isEmpty()) {
        parsers(Parsers.DEFAULT) // BOOLEAN, INT, LONG, DOUBLE, DATETIME, CHAR, STRING
    } else {
        parsers(
            Parsers.DEFAULT.toSet() -
                parserOptions.skipTypes
                    .mapNotNull { it.toColType().toCsvParserOrNull(useDeepHavenLocalDateTime) }
                    .toSet(),
        )
    }

private fun CsvSpecs.Builder.header(header: List<String>): CsvSpecs.Builder =
    if (header.isEmpty()) {
        // take header from csv
        hasHeaderRow(true)
    } else {
        hasHeaderRow(false)
            .headers(header)
    }

internal fun ColType.toCsvParserOrNull(useDeepHavenLocalDateTime: Boolean): Parser<*>? =
    when (this) {
        ColType.Int -> Parsers.INT
        ColType.Long -> Parsers.LONG
        ColType.Double -> Parsers.DOUBLE
        ColType.Char -> Parsers.CHAR
        ColType.Boolean -> Parsers.BOOLEAN
        ColType.String -> Parsers.STRING
        ColType.LocalDateTime -> if (useDeepHavenLocalDateTime) Parsers.DATETIME else null
        else -> null
    }

/**
 * Converts a [ColType] to a [Parser] from the Deephaven CSV library.
 * If no direct [Parser] exists, it defaults to [Parsers.STRING] so that [DataFrame.parse] can handle it.
 */
internal fun ColType.toCsvParser(useDeepHavenLocalDateTime: Boolean): Parser<*> =
    toCsvParserOrNull(useDeepHavenLocalDateTime) ?: Parsers.STRING

// has issues when calling to :core
internal fun ColType.toKType(): KType =
    when (this) {
        ColType.Int -> typeOf<Int>()
        ColType.Long -> typeOf<Long>()
        ColType.Double -> typeOf<Double>()
        ColType.Boolean -> typeOf<Boolean>()
        ColType.BigDecimal -> typeOf<BigDecimal>()
        ColType.LocalDate -> typeOf<LocalDate>()
        ColType.LocalTime -> typeOf<LocalTime>()
        ColType.LocalDateTime -> typeOf<LocalDateTime>()
        ColType.String -> typeOf<String>()
        ColType.Instant -> typeOf<Instant>()
        ColType.Duration -> typeOf<Duration>()
        ColType.Url -> typeOf<URL>()
        ColType.JsonArray -> typeOf<DataFrame<*>>()
        ColType.JsonObject -> typeOf<DataRow<*>>()
        ColType.Char -> typeOf<Char>()
    }

internal fun KType.toColType(): ColType =
    when (this.withNullability(false)) {
        typeOf<Int>() -> ColType.Int
        typeOf<Long>() -> ColType.Long
        typeOf<Double>() -> ColType.Double
        typeOf<Boolean>() -> ColType.Boolean
        typeOf<BigDecimal>() -> ColType.BigDecimal
        typeOf<LocalDate>() -> ColType.LocalDate
        typeOf<LocalTime>() -> ColType.LocalTime
        typeOf<LocalDateTime>() -> ColType.LocalDateTime
        typeOf<String>() -> ColType.String
        typeOf<Instant>() -> ColType.Instant
        typeOf<Duration>() -> ColType.Duration
        typeOf<URL>() -> ColType.Url
        typeOf<DataFrame<*>>() -> ColType.JsonArray
        typeOf<DataRow<*>>() -> ColType.JsonObject
        typeOf<Char>() -> ColType.Char
        else -> ColType.String
    }

/**
 * Types that Deephaven already parses, so we can skip them when
 * defaulting to DataFrame's String parsers.
 */
internal val typesDeephavenAlreadyParses: Set<KType> =
    setOf(
        typeOf<Int>(),
        typeOf<Long>(),
        typeOf<Double>(),
        typeOf<Char>(),
        typeOf<Boolean>(),
//        typeOf<LocalDateTime>(), it cannot recognize all formats
//        typeOf<java.time.LocalDateTime>(), it cannot recognize all formats
    )
