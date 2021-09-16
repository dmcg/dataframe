package org.jetbrains.dataframe.internal.codeGen

import org.jetbrains.dataframe.internal.schema.DataFrameSchema

public open class Marker(
    public val name: String,
    public val isOpen: Boolean,
    public val fields: List<GeneratedField>,
    base: List<Marker>
) {

    public val shortName: String
        get() = name.substringAfterLast(".")

    public val baseMarkers: Map<String, Marker> = base.associateBy { it.name }

    public val allBaseMarkers: Map<String, Marker> by lazy {
        val result = baseMarkers.toMutableMap()
        baseMarkers.forEach {
            result.putAll(it.value.allBaseMarkers)
        }
        result
    }

    public val allFields: List<GeneratedField> by lazy {

        val fieldsMap = mutableMapOf<String, GeneratedField>()
        baseMarkers.values.forEach {
            it.allFields.forEach {
                fieldsMap[it.fieldName] = it
            }
        }
        fields.forEach {
            fieldsMap[it.fieldName] = it
        }
        fieldsMap.values.sortedBy { it.fieldName }
    }

    public val allFieldsByColumn: Map<String, GeneratedField> by lazy {
        allFields.associateBy { it.columnName }
    }

    public fun containsColumn(columnName: String): Boolean = allFieldsByColumn.containsKey(columnName)

    public val columnNames: List<String> get() = allFields.map { it.columnName }

    public val schema: DataFrameSchema by lazy { DataFrameSchema(allFields.map { it.columnName to it.columnSchema }.toMap()) }

    public fun implements(schema: Marker): Boolean = if (schema.name == name) true else baseMarkers[schema.name]?.let { it === schema } ?: false

    public fun implementsAll(schemas: Iterable<Marker>): Boolean = schemas.all { implements(it) }
}
