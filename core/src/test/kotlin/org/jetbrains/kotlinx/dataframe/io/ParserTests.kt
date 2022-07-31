package org.jetbrains.kotlinx.dataframe.io

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.api.columnOf
import org.jetbrains.kotlinx.dataframe.exceptions.TypeConversionException
import org.junit.Test
import java.math.BigDecimal
import java.util.*
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
    fun `converting string to double in different locales`() {
        val currentLocale = Locale.getDefault()
        try {
            val stringValues = listOf("1", "2.3", "4,5")
            val stringColumn = DataColumn.createValueColumn("nums", stringValues, typeOf<String>())
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            stringColumn.convertToDouble().shouldBe(
                DataColumn.createValueColumn("nums", listOf(1.0, 2.3, 4.5), typeOf<Double>())
            )
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            stringColumn.convertToDouble().shouldBe(
                DataColumn.createValueColumn("nums", listOf(1.0, 2.3, 45.0), typeOf<Double>())
            )
        } finally {
            Locale.setDefault(currentLocale)
        }
    }
}
