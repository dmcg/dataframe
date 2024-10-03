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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.apache.commons.io.input.BOMInputStream
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.ParserOptions
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.dataframe.api.parse
import org.jetbrains.kotlinx.dataframe.api.tryParse
import org.jetbrains.kotlinx.dataframe.columns.ValueColumn
import org.jetbrains.kotlinx.dataframe.impl.ColumnNameGenerator
import org.jetbrains.kotlinx.dataframe.io.ColType
import org.jetbrains.kotlinx.dataframe.io.DEFAULT_COL_TYPE
import org.jetbrains.kotlinx.dataframe.io.toKType
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.reflect.KType
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

/**
 *
 * @include [CsvTsvParams.INPUT_STREAM]
 * @param delimiter The field delimiter character. The default is ',' for CSV, '\t' for TSV.
 * @include [CsvTsvParams.HEADER]
 * @include [CsvTsvParams.IS_COMPRESSED]
 * @include [CsvTsvParams.COL_TYPES]
 * @include [CsvTsvParams.SKIP_LINES]
 * @include [CsvTsvParams.READ_LINES]
 * @include [CsvTsvParams.PARSER_OPTIONS]
 * @include [CsvTsvParams.IGNORE_EMPTY_LINES]
 * @include [CsvTsvParams.ALLOW_MISSING_COLUMNS]
 * @include [CsvTsvParams.IGNORE_EXCESS_COLUMNS]
 * @include [CsvTsvParams.QUOTE]
 * @include [CsvTsvParams.IGNORE_SURROUNDING_SPACES]
 * @include [CsvTsvParams.TRIM_INSIDE_QUOTED]
 * @include [CsvTsvParams.PARSE_PARALLEL]
 * @include [CsvTsvParams.ADDITIONAL_CSV_SPECS]
 */
internal fun readCsvOrTsvImpl(
    inputStream: InputStream,
    delimiter: Char,
    header: List<String> = CsvTsvParams.HEADER,
    isCompressed: Boolean = CsvTsvParams.IS_COMPRESSED,
    colTypes: Map<String, ColType> = CsvTsvParams.COL_TYPES,
    skipLines: Long = CsvTsvParams.SKIP_LINES,
    readLines: Long? = CsvTsvParams.READ_LINES,
    parserOptions: ParserOptions = CsvTsvParams.PARSER_OPTIONS,
    ignoreEmptyLines: Boolean = CsvTsvParams.IGNORE_EMPTY_LINES,
    allowMissingColumns: Boolean = CsvTsvParams.ALLOW_MISSING_COLUMNS,
    ignoreExcessColumns: Boolean = CsvTsvParams.IGNORE_EXCESS_COLUMNS,
    quote: Char = CsvTsvParams.QUOTE,
    ignoreSurroundingSpaces: Boolean = CsvTsvParams.IGNORE_SURROUNDING_SPACES,
    trimInsideQuoted: Boolean = CsvTsvParams.TRIM_INSIDE_QUOTED,
    parseParallel: Boolean = CsvTsvParams.PARSE_PARALLEL,
    additionalCsvSpecs: CsvSpecs = CsvTsvParams.ADDITIONAL_CSV_SPECS,
): DataFrame<*> {
    // edit the parser options to skip types that are already parsed by deephaven
    // and add the correct nullStrings
    @Suppress("NAME_SHADOWING")
    val parserOptions = parserOptions.copy(
        skipTypes = parserOptions.skipTypes + typesDeephavenAlreadyParses,
        nullStrings = (parserOptions.nullStrings ?: emptySet()) + defaultNullStrings,
    )

    // set up the csv specs
    val csvSpecs = with(CsvSpecs.builder()) {
        from(additionalCsvSpecs)
        parsers(Parsers.DEFAULT) // BOOLEAN, INT, LONG, DOUBLE, DATETIME, CHAR, STRING
        customDoubleParser(DoubleParser)
        nullValueLiterals(parserOptions.nullStrings!!)
        headerLegalizer(::legalizeHeader)
        numRows(readLines ?: Long.MAX_VALUE)
        ignoreEmptyLines(ignoreEmptyLines)
        allowMissingColumns(allowMissingColumns)
        ignoreExcessColumns(ignoreExcessColumns)
        delimiter(delimiter)
        quote(quote)
        ignoreSurroundingSpaces(ignoreSurroundingSpaces)
        trim(trimInsideQuoted)

        header(header)

        skipLines(takeHeaderFromCsv = header.isEmpty(), skipLines = skipLines)

        val useDeepHavenLocalDateTime = with(parserOptions) {
            locale == null && dateTimePattern == null && dateTimeFormatter == null
        }
        colTypes(colTypes, useDeepHavenLocalDateTime) // this function must be last, so the return value is used
    }.build()

    val adjustedInputStream = inputStream
        .let { if (isCompressed) GZIPInputStream(it) else it }
        .let { BOMInputStream.builder().setInputStream(it).get() }

    if (adjustedInputStream.available() <= 0) {
        return DataFrame.empty()
    }

    // read the csv
    val csvReaderResult = CsvReader.read(
        csvSpecs,
        adjustedInputStream,
        ListSink.SINK_FACTORY,
    )

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
        column.tryParse(parserOptions)
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

private fun CsvSpecs.Builder.header(header: List<String>): CsvSpecs.Builder =
    if (header.isEmpty()) {
        // take header from csv
        hasHeaderRow(true)
    } else {
        hasHeaderRow(false)
            .headers(header)
    }

/**
 * TODO
 * Hacky reflection-based solution to call internal functions:
 * ```kt
 * val parser = Parsers[type]!!
 * column.parse(parser, options)
 * ```
 */
internal fun parseColumnWithType(column: DataColumn<String?>, type: KType, options: ParserOptions?): DataColumn<*> {
    val clazz = Class.forName("org.jetbrains.kotlinx.dataframe.impl.api.Parsers")
    val objectInstanceField = clazz.getDeclaredField("INSTANCE")
    val parsersObjectInstance = objectInstanceField.get(null)
    val getFunction = clazz.getMethod("get", KType::class.java)
    val stringParser = getFunction.invoke(parsersObjectInstance, type)

    val parseClass = Class.forName("org.jetbrains.kotlinx.dataframe.impl.api.ParseKt")
    val parseMethod = parseClass.getMethod(
        "parse",
        DataColumn::class.java,
        Class.forName("org.jetbrains.kotlinx.dataframe.impl.api.StringParser"),
        ParserOptions::class.java,
    )

    val parsedCol = parseMethod.invoke(null, column, stringParser, options) as DataColumn<*>
    return parsedCol
}

/**
 * Converts a [ColType] to a [Parser] from the Deephaven CSV library.
 * If no direct [Parser] exists, it defaults to [Parsers.STRING] so that [DataFrame.parse] can handle it.
 */
internal fun ColType.toCsvParser(useDeepHavenLocalDateTime: Boolean): Parser<*> =
    when (this) {
        ColType.Int -> Parsers.INT
        ColType.Long -> Parsers.LONG
        ColType.Double -> Parsers.DOUBLE
        ColType.Char -> Parsers.CHAR
        ColType.Boolean -> Parsers.BOOLEAN
        ColType.String -> Parsers.STRING
        ColType.LocalDateTime -> if (useDeepHavenLocalDateTime) Parsers.DATETIME else Parsers.STRING
        else -> Parsers.STRING
    }

/**
 * Types that Deephaven already parses, so we can skip them.
 */
internal val typesDeephavenAlreadyParses =
    setOf(
        typeOf<Int>(),
        typeOf<Long>(),
        typeOf<Double>(),
        typeOf<Char>(),
        typeOf<Boolean>(),
//        typeOf<LocalDateTime>(), it cannot recognize all formats
//        typeOf<java.time.LocalDateTime>(),
    )

/**
 * Default strings that are considered null.
 */
internal val defaultNullStrings: Set<String> =
    setOf("", "NA", "N/A", "null", "NULL", "None", "none", "NIL", "nil")
