package org.jetbrains.dataframe

import org.jetbrains.kotlinx.dataframe.AnyCol
import org.jetbrains.kotlinx.dataframe.AnyColumn
import org.jetbrains.kotlinx.dataframe.ColumnSelector
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.ReferenceData
import org.jetbrains.kotlinx.dataframe.RowSelector
import org.jetbrains.kotlinx.dataframe.api.add
import org.jetbrains.kotlinx.dataframe.api.after
import org.jetbrains.kotlinx.dataframe.api.move
import org.jetbrains.kotlinx.dataframe.api.to
import org.jetbrains.kotlinx.dataframe.asDataFrame
import org.jetbrains.kotlinx.dataframe.columns.ColumnAccessor
import org.jetbrains.kotlinx.dataframe.columns.ColumnGroup
import org.jetbrains.kotlinx.dataframe.columns.ColumnPath
import org.jetbrains.kotlinx.dataframe.columns.name
import org.jetbrains.kotlinx.dataframe.getColumnPath
import org.jetbrains.kotlinx.dataframe.impl.ReadonlyTreeNode
import org.jetbrains.kotlinx.dataframe.impl.columns.withDf
import org.jetbrains.kotlinx.dataframe.impl.getAncestor
import org.jetbrains.kotlinx.dataframe.impl.removeAt
import org.jetbrains.kotlinx.dataframe.newColumn
import org.jetbrains.kotlinx.dataframe.typed

public fun <T> DataFrame<T>.insert(path: ColumnPath, column: AnyCol): DataFrame<T> = insertColumns(this, listOf(ColumnToInsert(path, column)))

public fun <T> DataFrame<T>.insert(column: AnyCol): InsertClause<T> = InsertClause(this, column)

public inline fun <T, reified R> DataFrame<T>.insert(noinline expression: RowSelector<T, R>): InsertClause<T> = insert("", expression)

public inline fun <T, reified R> DataFrame<T>.insert(name: String, noinline expression: RowSelector<T, R>): InsertClause<T> = insert(newColumn(name, expression))

public data class InsertClause<T>(val df: DataFrame<T>, val column: AnyCol)

public fun <T> InsertClause<T>.into(path: ColumnPath): DataFrame<T> = df.insert(path, column.rename(path.last()))
public fun <T> InsertClause<T>.into(reference: ColumnAccessor<*>): DataFrame<T> = into(reference.path())

public fun <T> InsertClause<T>.under(path: ColumnPath): DataFrame<T> = df.insert(path + column.name, column)
public fun <T> InsertClause<T>.under(selector: ColumnSelector<T, *>): DataFrame<T> = under(df.getColumnPath(selector))

public fun <T> InsertClause<T>.after(name: String): DataFrame<T> = df.add(column).move(column).after(name)
public fun <T> InsertClause<T>.after(selector: ColumnSelector<T, *>): DataFrame<T> = after(df.getColumnPath(selector))

public fun <T> InsertClause<T>.at(position: Int): DataFrame<T> = df.add(column).move(column).to(position)

public fun <T> InsertClause<T>.after(path: ColumnPath): DataFrame<T> {
    val colPath = ColumnPath(path.removeAt(path.size - 1) + column.name())
    return df.insert(colPath, column).move(colPath).after(path)
}

internal data class ColumnToInsert(
    val insertionPath: ColumnPath,
    val column: AnyCol,
    val referenceNode: ReadonlyTreeNode<ReferenceData>? = null
)

internal fun <T> DataFrame<T>.insert(columns: List<ColumnToInsert>) = insertColumns(this, columns)

internal fun <T> insertColumns(df: DataFrame<T>?, columns: List<ColumnToInsert>) =
    insertColumns(df, columns, columns.firstOrNull()?.referenceNode?.getRoot(), 0)

internal fun insertColumns(columns: List<ColumnToInsert>) =
    insertColumns<Unit>(null, columns, columns.firstOrNull()?.referenceNode?.getRoot(), 0)

internal fun <T> insertColumns(
    df: DataFrame<T>?,
    columns: List<ColumnToInsert>,
    treeNode: ReadonlyTreeNode<ReferenceData>?,
    depth: Int
): DataFrame<T> {
    if (columns.isEmpty()) return df ?: DataFrame.empty().typed()

    val childDepth = depth + 1

    val columnsMap = columns.groupBy { it.insertionPath[depth] }.toMutableMap() // map: columnName -> columnsToAdd

    val newColumns = mutableListOf<AnyColumn>()

    // insert new columns under existing
    df?.columns()?.forEach {
        val subTree = columnsMap[it.name()]
        if (subTree != null) {
            // assert that new columns go directly under current column so they have longer paths
            val invalidPath = subTree.firstOrNull { it.insertionPath.size == childDepth }
            assert(invalidPath == null) { "Can not insert column `" + invalidPath!!.insertionPath.joinToString(".") + "` because column with this path already exists in DataFrame" }
            val group = it as? ColumnGroup<*>
            assert(group != null) { "Can not insert columns under a column '${it.name()}', because it is not a column group" }
            val newDf = insertColumns(group!!.df, subTree, treeNode?.get(it.name()), childDepth)
            val newCol = group.withDf(newDf)
            newColumns.add(newCol)
            columnsMap.remove(it.name())
        } else newColumns.add(it)
    }

    // collect new columns to insert
    val columnsToAdd = columns.mapNotNull {
        val name = it.insertionPath[depth]
        val subTree = columnsMap[name]
        if (subTree != null) {
            columnsMap.remove(name)

            // look for columns in subtree that were originally located at the current insertion path
            // find the minimal original index among them
            // new column will be inserted at that position
            val minIndex = subTree.minOf {
                if (it.referenceNode == null) Int.MAX_VALUE
                else {
                    var col = it.referenceNode
                    if (col.depth > depth) col = col.getAncestor(depth + 1)
                    if (col.parent === treeNode) {
                        if (col.data.wasRemoved) col.data.originalIndex else col.data.originalIndex + 1
                    } else Int.MAX_VALUE
                }
            }

            minIndex to (name to subTree)
        } else null
    }.sortedBy { it.first } // sort by insertion index

    val removedSiblings = treeNode?.children
    var k = 0 // index in 'removedSiblings' list
    var insertionIndexOffset = 0

    columnsToAdd.forEach { (insertionIndex, pair) ->
        val (name, columns) = pair

        // adjust insertion index by number of columns that were removed before current index
        if (removedSiblings != null) {
            while (k < removedSiblings.size && removedSiblings[k].data.originalIndex < insertionIndex) {
                if (removedSiblings[k].data.wasRemoved) insertionIndexOffset--
                k++
            }
        }

        val nodeToInsert =
            columns.firstOrNull { it.insertionPath.size == childDepth } // try to find existing node to insert
        val newCol = if (nodeToInsert != null) {
            val column = nodeToInsert.column
            if (columns.size > 1) {
                assert(columns.count { it.insertionPath.size == childDepth } == 1) { "Can not insert more than one column into the path ${nodeToInsert.insertionPath}" }
                val group = column as ColumnGroup<*>
                val newDf = insertColumns(
                    group.df,
                    columns.filter { it.insertionPath.size > childDepth },
                    treeNode?.get(name),
                    childDepth
                )
                group.withDf(newDf)
            } else column.rename(name)
        } else {
            val newDf = insertColumns<Unit>(null, columns, treeNode?.get(name), childDepth)
            DataColumn.create(name, newDf) // new node needs to be created
        }
        if (insertionIndex == Int.MAX_VALUE) {
            newColumns.add(newCol)
        } else {
            newColumns.add(insertionIndex + insertionIndexOffset, newCol)
            insertionIndexOffset++
        }
    }

    return newColumns.asDataFrame()
}
