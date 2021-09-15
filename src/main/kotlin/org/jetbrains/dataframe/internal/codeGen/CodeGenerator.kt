package org.jetbrains.dataframe.impl.codeGen

import org.jetbrains.dataframe.internal.codeGen.CodeWithConverter
import org.jetbrains.dataframe.internal.codeGen.Marker
import org.jetbrains.dataframe.internal.codeGen.IsolatedMarker
import org.jetbrains.dataframe.internal.codeGen.MarkersExtractor
import org.jetbrains.dataframe.internal.schema.DataFrameSchema
import kotlin.reflect.KClass

public enum class InterfaceGenerationMode {
    NoFields,
    WithFields,
    None
}

public data class CodeGenResult(val code: CodeWithConverter, val newMarkers: List<Marker>)

public interface ExtensionsCodeGenerator {
    public fun generate(marker: IsolatedMarker): CodeWithConverter

    public companion object {
        public fun create(): ExtensionsCodeGenerator = ExtensionsCodeGeneratorImpl()
    }
}

public interface CodeGenerator : ExtensionsCodeGenerator {

    public fun generate(
        schema: DataFrameSchema,
        name: String,
        fields: Boolean,
        extensionProperties: Boolean,
        isOpen: Boolean,
        knownMarkers: Iterable<Marker> = emptyList()
    ): CodeGenResult

    public fun generate(marker: Marker, interfaceMode: InterfaceGenerationMode, extensionProperties: Boolean): CodeWithConverter

    public companion object {
        public fun create(): CodeGenerator = CodeGeneratorImpl()
    }
}

internal fun CodeGenerator.generate(
    markerClass: KClass<*>,
    interfaceMode: InterfaceGenerationMode,
    extensionProperties: Boolean
) = generate(
    MarkersExtractor.get(markerClass),
    interfaceMode,
    extensionProperties
)

internal inline fun <reified T> CodeGenerator.generate(
    interfaceMode: InterfaceGenerationMode,
    extensionProperties: Boolean
) = generate(T::class, interfaceMode, extensionProperties)
