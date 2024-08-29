/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.dataframe.plugin

import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlinx.dataframe.plugin.extensions.KotlinTypeFacade
import org.jetbrains.kotlinx.dataframe.plugin.impl.Interpreter
import org.jetbrains.kotlinx.dataframe.plugin.impl.PluginDataFrameSchema
import org.jetbrains.kotlinx.dataframe.plugin.impl.api.GroupBy
import org.jetbrains.kotlinx.dataframe.plugin.impl.api.aggregate
import org.jetbrains.kotlinx.dataframe.plugin.impl.api.createPluginDataFrameSchema
import org.jetbrains.kotlinx.dataframe.plugin.utils.Names.DF_CLASS_ID
import org.jetbrains.kotlinx.dataframe.plugin.utils.Names.GROUP_BY_CLASS_ID

fun KotlinTypeFacade.analyzeRefinedCallShape(call: FirFunctionCall, reporter: InterpretationErrorReporter): CallResult? {
    val callReturnType = call.resolvedType
    if (callReturnType.classId != DF_CLASS_ID) return null
    val rootMarker = callReturnType.typeArguments[0]
    // rootMarker is expected to be a token generated by the plugin.
    // it's implied by "refined call"
    // thus ConeClassLikeType
    if (rootMarker !is ConeClassLikeType) {
        return null
    }

    val newSchema: PluginDataFrameSchema = call.interpreterName(session)?.let { name ->
        when (name) {
            else -> name.load<Interpreter<*>>().let { processor ->
                val dataFrameSchema = interpret(call, processor, reporter = reporter)
                    .let {
                        val value = it?.value
                        if (value !is PluginDataFrameSchema) {
                            if (!reporter.errorReported) {
                                reporter.reportInterpretationError(call, "${processor::class} must return ${PluginDataFrameSchema::class}, but was ${value}")
                            }
                            return null
                        }
                        value
                    }
                dataFrameSchema
            }
        }
    } ?: return null

    return CallResult(rootMarker, newSchema)
}

fun KotlinTypeFacade.analyzeRefinedGroupByCallShape(call: FirFunctionCall, reporter: InterpretationErrorReporter): GroupByCallResult? {
    val callReturnType = call.resolvedType
    if (callReturnType.classId != GROUP_BY_CLASS_ID) return null
    val keyMarker = callReturnType.typeArguments[0]
    val groupMarker = callReturnType.typeArguments[1]
    // rootMarker is expected to be a token generated by the plugin.
    // it's implied by "refined call"
    // thus ConeClassLikeType
    if (keyMarker !is ConeClassLikeType || groupMarker !is ConeClassLikeType) {
        return null
    }

    val newSchema = call.interpreterName(session)?.let { name ->
        name.load<Interpreter<*>>().let { processor ->
            val dataFrameSchema = interpret(call, processor, reporter = reporter)
                .let {
                    val value = it?.value
                    if (value !is GroupBy) {
                        if (!reporter.errorReported) {
                            reporter.reportInterpretationError(call, "${processor::class} must return ${PluginDataFrameSchema::class}, but was ${value}")
                        }
                        return null
                    }
                    value
                }

            val keySchema = createPluginDataFrameSchema(dataFrameSchema.keys, dataFrameSchema.moveToTop)
            val groupSchema = PluginDataFrameSchema(dataFrameSchema.df.columns())
            GroupBySchema(keySchema, groupSchema)
        }
    } ?: return null

    return GroupByCallResult(keyMarker, newSchema.keySchema, groupMarker, newSchema.groupSchema)
}

data class GroupByCallResult(
    val keyMarker: ConeClassLikeType,
    val keySchema: PluginDataFrameSchema,
    val groupMarker: ConeClassLikeType,
    val groupSchema: PluginDataFrameSchema,
)

data class GroupBySchema(
    val keySchema: PluginDataFrameSchema,
    val groupSchema: PluginDataFrameSchema,
)

data class CallResult(val rootMarker: ConeClassLikeType, val newSchema: PluginDataFrameSchema)

class RefinedArguments(val refinedArguments: List<RefinedArgument>) : List<RefinedArgument> by refinedArguments

data class RefinedArgument(val name: Name, val expression: FirExpression) {

    override fun toString(): String {
        return "RefinedArgument(name=$name, expression=${expression})"
    }
}

data class SchemaProperty(
    val marker: ConeTypeProjection,
    val name: String,
    val dataRowReturnType: ConeKotlinType,
    val columnContainerReturnType: ConeKotlinType,
    val override: Boolean = false
)
