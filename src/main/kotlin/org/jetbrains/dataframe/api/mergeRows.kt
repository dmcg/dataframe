package org.jetbrains.dataframe

import org.jetbrains.dataframe.columns.values
import org.jetbrains.dataframe.impl.columns.asGroup
import org.jetbrains.dataframe.impl.columns.asTable
import org.jetbrains.dataframe.impl.columns.toColumns
import kotlin.reflect.KProperty

public fun <T> DataFrame<T>.mergeRows(vararg columns: String, dropNulls: Boolean = false): DataFrame<T> = mergeRows(dropNulls) { columns.toColumns() }
public fun <T> DataFrame<T>.mergeRows(vararg columns: Column, dropNulls: Boolean = false): DataFrame<T> = mergeRows(dropNulls) { columns.toColumns() }
public fun <T, C> DataFrame<T>.mergeRows(vararg columns: KProperty<C>, dropNulls: Boolean = false): DataFrame<T> = mergeRows(dropNulls) { columns.toColumns() }

public fun <T, C> DataFrame<T>.mergeRows(dropNulls: Boolean = false, columns: ColumnsSelector<T, C>): DataFrame<T> {
    return groupBy { except(columns) }.mapNotNullGroups {
        replace(columns).with {
            val column = it
            val filterNulls = dropNulls && column.hasNulls()
            val value = when (column.kind()) {
                ColumnKind.Value -> column.toList().let { if (filterNulls) (it as List<Any?>).filterNotNull() else it }.toMany()
                ColumnKind.Group -> column.asGroup().df
                ColumnKind.Frame -> column.asTable().values.union()
            }
            var first = true
            column.map {
                if (first) {
                    first = false
                    value
                } else null
            }
        }[0..0]
    }.union()
}
