package org.jetbrains.kotlinx.dataframe.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.ParserOptions
import org.jetbrains.kotlinx.dataframe.api.cast
import org.jetbrains.kotlinx.dataframe.api.columnOf
import org.jetbrains.kotlinx.dataframe.api.convertTo
import org.jetbrains.kotlinx.dataframe.api.convertToDouble
import org.jetbrains.kotlinx.dataframe.api.convertToLocalDate
import org.jetbrains.kotlinx.dataframe.api.convertToLocalDateTime
import org.jetbrains.kotlinx.dataframe.api.convertToLocalTime
import org.jetbrains.kotlinx.dataframe.api.parse
import org.jetbrains.kotlinx.dataframe.api.parser
import org.jetbrains.kotlinx.dataframe.api.plus
import org.jetbrains.kotlinx.dataframe.api.times
import org.jetbrains.kotlinx.dataframe.api.tryParse
import org.jetbrains.kotlinx.dataframe.exceptions.TypeConversionException
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale
import kotlin.reflect.typeOf

class ParserTests {

    @Test
    fun `parse datetime with custom format`() {
        val col by columnOf("04.02.2021 -- 19:44:32")
        col.tryParse().type() shouldBe typeOf<String>()
        DataFrame.parser.addDateTimePattern("dd.MM.uuuu -- HH:mm:ss")
        val parsed = col.parse()
        parsed.type() shouldBe typeOf<LocalDateTime>()
        parsed.cast<LocalDateTime>()[0].year shouldBe 2021
        DataFrame.parser.resetToDefault()
    }

    @Test(expected = IllegalStateException::class)
    fun `parse should throw`() {
        val col by columnOf("a", "b")
        col.parse()
    }

    @Test(expected = TypeConversionException::class)
    fun `converter should throw`() {
        val col by columnOf("a", "b")
        col.convertTo<Int>()
    }

    @Test(expected = TypeConversionException::class)
    fun `converter for mixed column should throw`() {
        val col by columnOf(1, "a")
        col.convertTo<Int>()
    }

    @Test
    fun `convert mixed column`() {
        val col by columnOf(1.0, "1")
        val converted = col.convertTo<Int>()
        converted.type() shouldBe typeOf<Int>()
        converted[0] shouldBe 1
        converted[1] shouldBe 1
    }

    @Test
    fun `convert BigDecimal column`() {
        val col by columnOf(BigDecimal(1.0), BigDecimal(0.321))
        val converted = col.convertTo<Float>()
        converted.type() shouldBe typeOf<Float>()
        converted[0] shouldBe 1.0f
        converted[1] shouldBe 0.321f
    }

    @Test
    fun `convert to Boolean`() {
        val col by columnOf(BigDecimal(1.0), BigDecimal(0.0), 0, 1, 10L, 0.0, 0.1)
        col.convertTo<Boolean>().shouldBe(
            DataColumn.createValueColumn("col", listOf(true, false, false, true, true, false, true), typeOf<Boolean>()),
        )
    }

    @Test
    fun `convert to date and time`() {
        val daysToStandardMillis = 24 * 60 * 60 * 1000L
        val longCol = columnOf(1L, 60L, 3600L).times(1000L).plus(daysToStandardMillis * 366)
        val datetimeCol = longCol.convertToLocalDateTime(TimeZone.UTC)

        datetimeCol.shouldBe(
            columnOf(
                java.time.LocalDateTime.of(1971, 1, 2, 0, 0, 1).toKotlinLocalDateTime(),
                java.time.LocalDateTime.of(1971, 1, 2, 0, 1, 0).toKotlinLocalDateTime(),
                java.time.LocalDateTime.of(1971, 1, 2, 1, 0, 0).toKotlinLocalDateTime(),
            ),
        )
        longCol.convertToLocalDate(TimeZone.UTC).shouldBe(
            columnOf(
                java.time.LocalDate.of(1971, 1, 2).toKotlinLocalDate(),
                java.time.LocalDate.of(1971, 1, 2).toKotlinLocalDate(),
                java.time.LocalDate.of(1971, 1, 2).toKotlinLocalDate(),
            ),
        )
        longCol.convertToLocalTime(TimeZone.UTC).shouldBe(
            columnOf(
                LocalTime(0, 0, 1),
                LocalTime(0, 1, 0),
                LocalTime(1, 0, 0),
            ),
        )

        datetimeCol.convertToLocalDate().shouldBe(
            columnOf(
                java.time.LocalDate.of(1971, 1, 2).toKotlinLocalDate(),
                java.time.LocalDate.of(1971, 1, 2).toKotlinLocalDate(),
                java.time.LocalDate.of(1971, 1, 2).toKotlinLocalDate(),
            ),
        )
        datetimeCol.convertToLocalTime().shouldBe(
            columnOf(
                LocalTime(0, 0, 1),
                LocalTime(0, 1, 0),
                LocalTime(1, 0, 0),
            ),
        )
    }

    @Test
    fun `custom nullStrings`() {
        val col by columnOf("1", "2", "null", "3", "NA", "nothing", "4.0", "5.0")

        val parsed = col.tryParse(
            ParserOptions(nullStrings = setOf("null", "NA", "nothing")),
        )
        parsed.type() shouldBe typeOf<Double?>()
        parsed.toList() shouldBe listOf(1, 2, null, 3, null, null, 4.0, 5.0)
    }

    @Test
    fun `converting String to Double in different locales`() {
        val currentLocale = Locale.getDefault()
        try {
            // Test 45 behaviour combinations:

            // 3 source columns
            val columnDot = columnOf("12.345", "67.890")
            val columnComma = columnOf("12,345", "67,890")
            val columnMixed = columnOf("12.345", "67,890")
            // *
            // (3 locales as converting parameter + original converting + original converting to nullable)
            val parsingLocaleNotDefined: Locale? = null
            val parsingLocaleUsesDot: Locale = Locale.forLanguageTag("en-US")
            val parsingLocaleUsesComma: Locale = Locale.forLanguageTag("ru-RU")
            // *
            // 3 system locales

            Locale.setDefault(Locale.forLanguageTag("C.UTF-8"))

            columnDot.convertTo<Double>().shouldBe(columnOf(12.345, 67.89))
            columnComma.convertTo<Double>().shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertTo<Double>().shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertTo<Double?>().shouldBe(columnOf(12.345, 67.89))
            columnComma.convertTo<Double?>().shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertTo<Double?>().shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12.345, 67.89))
            columnComma.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12.345, 67.89))
            columnComma.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12.345, 67890.0))

            shouldThrow<TypeConversionException> { columnDot.convertToDouble(parsingLocaleUsesComma) }
            columnComma.convertToDouble(parsingLocaleUsesComma).shouldBe(columnOf(12.345, 67.89))
            shouldThrow<TypeConversionException> { columnMixed.convertToDouble(parsingLocaleUsesComma) }

            Locale.setDefault(Locale.forLanguageTag("en-US"))

            columnDot.convertTo<Double>().shouldBe(columnOf(12.345, 67.89))
            columnComma.convertTo<Double>().shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertTo<Double>().shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertTo<Double?>().shouldBe(columnOf(12.345, 67.89))
            columnComma.convertTo<Double?>().shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertTo<Double?>().shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12.345, 67.89))
            columnComma.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12.345, 67.89))
            columnComma.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12.345, 67890.0))

            shouldThrow<TypeConversionException> { columnDot.convertToDouble(parsingLocaleUsesComma) }
            columnComma.convertToDouble(parsingLocaleUsesComma).shouldBe(columnOf(12.345, 67.89))
            shouldThrow<TypeConversionException> { columnMixed.convertToDouble(parsingLocaleUsesComma) }

            Locale.setDefault(Locale.forLanguageTag("ru-RU"))

            columnDot.convertTo<Double>().shouldBe(columnOf(12.345, 67.89))
            columnComma.convertTo<Double>().shouldBe(columnOf(12.345, 67.89))
            columnMixed.convertTo<Double>().shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertTo<Double?>().shouldBe(columnOf(12.345, 67.89))
            columnComma.convertTo<Double?>().shouldBe(columnOf(12.345, 67.89))
            columnMixed.convertTo<Double?>().shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12.345, 67.89))
            columnComma.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12.345, 67.89))
            columnMixed.convertToDouble(parsingLocaleNotDefined).shouldBe(columnOf(12.345, 67890.0))

            columnDot.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12.345, 67.89))
            columnComma.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12345.0, 67890.0))
            columnMixed.convertToDouble(parsingLocaleUsesDot).shouldBe(columnOf(12.345, 67890.0))

            shouldThrow<TypeConversionException> { columnDot.convertToDouble(parsingLocaleUsesComma) }
            columnComma.convertToDouble(parsingLocaleUsesComma).shouldBe(columnOf(12.345, 67.89))
            shouldThrow<TypeConversionException> { columnMixed.convertToDouble(parsingLocaleUsesComma) }
        } finally {
            Locale.setDefault(currentLocale)
        }
    }
}
