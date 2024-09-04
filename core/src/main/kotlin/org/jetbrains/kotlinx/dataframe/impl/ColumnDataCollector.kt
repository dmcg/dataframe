package org.jetbrains.kotlinx.dataframe.impl

import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.AnyRow
import org.jetbrains.kotlinx.dataframe.ColumnDataHolder
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.DataRow
import org.jetbrains.kotlinx.dataframe.api.concat
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.jetbrains.kotlinx.dataframe.impl.columns.empty
import org.jetbrains.kotlinx.dataframe.impl.columns.emptyForType
import org.jetbrains.kotlinx.dataframe.impl.columns.guessColumnType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.jvmErasure

internal interface DataCollector<T> {

    public val data: List<T?>
    public val hasNulls: Boolean

    public fun add(value: T)

    public fun add(element: Boolean)

    public fun add(element: Byte)

    public fun add(element: Short)

    public fun add(element: Int)

    public fun add(element: Long)

    public fun add(element: Float)

    public fun add(element: Double)

    public fun add(element: Char)

    public fun toColumn(name: String): DataColumn<T>
}

internal abstract class DataCollectorBase<T>(initCapacity: Int) : DataCollector<T> {

    override var hasNulls = false

    override val data = ColumnDataHolder.empty<T>(initCapacity)

    val values: List<T?>
        get() = data

    override fun add(value: T) {
        if (value == null) hasNulls = true
        data.add(value)
    }

    override fun add(element: Boolean) = data.add(element)

    override fun add(element: Byte) = data.add(element)

    override fun add(element: Short) = data.add(element)

    override fun add(element: Int) = data.add(element)

    override fun add(element: Long) = data.add(element)

    override fun add(element: Float) = data.add(element)

    override fun add(element: Double) = data.add(element)

    override fun add(element: Char) = data.add(element)

    protected fun createColumn(name: String, type: KType): DataColumn<T> {
        val classifier = type.classifier as KClass<*>
        if (classifier.isSubclassOf(DataFrame::class) && !hasNulls) {
            return DataColumn.createFrameColumn(name, data as List<AnyFrame>) as DataColumn<T>
        }
        if (classifier.isSubclassOf(DataRow::class) && !hasNulls) {
            val mergedDf = (data as List<AnyRow>).map { it.toDataFrame() }.concat()
            return DataColumn.createColumnGroup(name, mergedDf) as DataColumn<T>
        }
        return DataColumn.createValueColumn(name, data, type.withNullability(hasNulls)) as DataColumn<T>
    }
}

internal open class ColumnDataCollector(initCapacity: Int = 0, val typeOf: (KClass<*>) -> KType) :
    DataCollectorBase<Any?>(initCapacity) {

    override fun toColumn(name: String) = guessColumnType(name, values)
}

internal class TypedColumnDataCollector<T>(initCapacity: Int = 0, val type: KType, val checkTypes: Boolean = true) :
    DataCollectorBase<T?>(initCapacity) {

    internal val kclass = type.jvmErasure

    override val data: ColumnDataHolder<T?> =
        ColumnDataHolder.emptyForType(
            type = type,
            initCapacity = initCapacity,
        )

    override fun add(value: T?) {
        if (data.canAddPrimitively(value) ||
            !checkTypes ||
            value == null ||
            value.javaClass.kotlin.isSubclassOf(kclass)
        ) {
            super.add(value)
        } else {
            throw IllegalArgumentException(
                "Can not add value of class ${value.javaClass.kotlin.qualifiedName} to column of type $type. Value = $value",
            )
        }
    }

    override fun toColumn(name: String) = createColumn(name, type)
}

internal fun createDataCollector(initCapacity: Int = 0) =
    createDataCollector(initCapacity) {
        it.createStarProjectedType(false)
    }

internal fun createDataCollector(initCapacity: Int = 0, typeOf: (KClass<*>) -> KType) =
    ColumnDataCollector(initCapacity, typeOf)

internal fun <T> createDataCollector(initCapacity: Int = 0, type: KType) =
    TypedColumnDataCollector<T>(initCapacity, type)
