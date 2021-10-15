package org.jetbrains.kotlinx.dataframe.schema

import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.columns.ColumnKind
import org.jetbrains.kotlinx.dataframe.impl.baseType
import org.jetbrains.kotlinx.dataframe.impl.getType
import kotlin.reflect.full.withNullability

public class DataFrameSchema(public val columns: Map<String, ColumnSchema>) {

    public val sortedColumns: List<Map.Entry<String, ColumnSchema>> by lazy { columns.asIterable().sortedBy { it.key } }

    public fun compare(other: DataFrameSchema): CompareResult {
        if (this === other) return CompareResult.Equals
        var result = CompareResult.Equals
        columns.forEach {
            val otherColumn = other.columns[it.key]
            if (otherColumn == null) {
                result = result.combine(CompareResult.IsDerived)
            } else {
                result = result.combine(it.value.compare(otherColumn))
            }
            if (result == CompareResult.None) return CompareResult.None
        }
        other.columns.forEach {
            val thisField = columns[it.key]
            if (thisField == null) {
                result = result.combine(CompareResult.IsSuper)
                if (result == CompareResult.None) return CompareResult.None
            }
        }
        return result
    }

    override fun equals(other: Any?): Boolean {
        return other is DataFrameSchema && compare(other).isEqual()
    }
}

public fun AnyFrame.extractSchema(): DataFrameSchema =
    DataFrameSchema(columns().filter { it.name().isNotEmpty() }.map { it.name() to it.getColumnType() }.toMap())

internal fun Iterable<DataFrameSchema>.intersectSchemas(): DataFrameSchema {
    val collectedTypes = mutableMapOf<String, MutableSet<ColumnSchema>>()
    var first = true
    val toUpdate = mutableSetOf<String>()
    forEach { schema ->
        if (first) {
            schema.columns.forEach { collectedTypes.put(it.key, mutableSetOf(it.value)) }
            first = false
        } else {
            collectedTypes.forEach { entry ->
                val otherType = schema.columns[entry.key]
                if (otherType == null) {
                    toUpdate.add(entry.key)
                } else entry.value.add(otherType)
            }
            toUpdate.forEach { collectedTypes.remove(it) }
            toUpdate.clear()
        }
    }
    val result = collectedTypes.mapValues {
        val columnKinds = it.value.map { it.kind }.distinct()
        val kind = columnKinds.first()
        when {
            columnKinds.size > 1 -> ColumnSchema.Value(getType<Any>().withNullability(it.value.any { it.nullable }))
            kind == ColumnKind.Value -> ColumnSchema.Value(
                baseType(
                    it.value.map { (it as ColumnSchema.Value).type }
                        .toSet()
                )
            )
            kind == ColumnKind.Frame -> ColumnSchema.Frame( // intersect only not empty schemas
                it.value.mapNotNull { (it as ColumnSchema.Frame).schema.takeIf { it.columns.isNotEmpty() } }
                    .intersectSchemas(),
                it.value.any { it.nullable }
            )
            kind == ColumnKind.Group -> ColumnSchema.Map(
                it.value.map { (it as ColumnSchema.Map).schema }
                    .intersectSchemas()
            )
            else -> throw RuntimeException()
        }
    }
    return DataFrameSchema(result)
}
