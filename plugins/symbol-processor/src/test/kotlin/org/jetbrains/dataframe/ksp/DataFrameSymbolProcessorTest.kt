package org.jetbrains.dataframe.ksp

import com.tschuchort.compiletesting.SourceFile
import io.kotest.assertions.asClue
import io.kotest.inspectors.forExactly
import io.kotest.inspectors.forOne
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.Before
import kotlin.test.Test

class DataFrameSymbolProcessorTest {

    companion object {
        val imports = """
            import org.jetbrains.kotlinx.dataframe.annotations.*
            import org.jetbrains.kotlinx.dataframe.columns.*
            import org.jetbrains.kotlinx.dataframe.* 
        """.trimIndent()

        const val generatedFile = "Hello${'$'}Extensions.kt"
    }

    @Before
    fun setup(){
        KspCompilationTestRunner.compilationDir.deleteRecursively()
    }

    @Test
    fun `all`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
            sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                class OuterClass

                @DataSchema(isOpen = false)
                interface Hello {
                    val name: String
                    val `test name`: InnerClass
                    val nullableProperty: Int?
                    val a: () -> Unit
                    val d: List<List<*>>
                    
                    class InnerClass
                }

                val ColumnsContainer<Hello>.col1: DataColumn<String> get() = name
                val ColumnsContainer<Hello>.col2: DataColumn<Hello.InnerClass> get() = `test name`
                val ColumnsContainer<Hello>.col3: DataColumn<Int?> get() = nullableProperty
                val ColumnsContainer<Hello>.col4: DataColumn<() -> Unit> get() = a
                val ColumnsContainer<Hello>.col5: DataColumn<List<List<*>>> get() = d
                
                val DataRow<Hello>.row1: String get() = name
                val DataRow<Hello>.row2: Hello.InnerClass get() = `test name`
                val DataRow<Hello>.row3: Int? get() = nullableProperty
                val DataRow<Hello>.row4: () -> Unit get() = a
                val DataRow<Hello>.row5: List<List<*>> get() = d
            """.trimIndent()))
        ))
        result.successfulCompilation shouldBe true
    }

    @Test
    fun `all data class`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                class OuterClass

                @DataSchema(isOpen = false)
                data class Hello(
                    val name: String,
                    val `test name`: InnerClass,
                    val nullableProperty: Int?,
                ) {
                    val a: () -> Unit = TODO()
                    val d: List<List<*>> = TODO() 
                    class InnerClass
                }

                val ColumnsContainer<Hello>.col1: DataColumn<String> get() = name
                val ColumnsContainer<Hello>.col2: DataColumn<Hello.InnerClass> get() = `test name`
                val ColumnsContainer<Hello>.col3: DataColumn<Int?> get() = nullableProperty
                val ColumnsContainer<Hello>.col4: DataColumn<() -> Unit> get() = a
                val ColumnsContainer<Hello>.col5: DataColumn<List<List<*>>> get() = d
                
                val DataRow<Hello>.row1: String get() = name
                val DataRow<Hello>.row2: Hello.InnerClass get() = `test name`
                val DataRow<Hello>.row3: Int? get() = nullableProperty
                val DataRow<Hello>.row4: () -> Unit get() = a
                val DataRow<Hello>.row5: List<List<*>> get() = d
            """.trimIndent()))
            ))
        result.successfulCompilation shouldBe true
    }

    @Test
    fun `functional type`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: () -> Unit?
                }

                val ColumnsContainer<Hello>.test1: DataColumn<() -> Unit?> get() = a
                val DataRow<Hello>.test2: () -> Unit? get() = a
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forOne {
                it
                    .shouldContain("ColumnsContainer<Hello>.a")
                    .shouldContain("DataColumn<kotlin.Function0<kotlin.Unit?>>")
            }
            codeLines.forOne {
                it
                    .shouldContain("DataRow<Hello>.a")
                    .shouldContain("kotlin.Function0<kotlin.Unit?>")
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `suspend functional type`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: suspend () -> Unit?
                }

                val ColumnsContainer<Hello>.test1: DataColumn<suspend () -> Unit?> get() = a
                val DataRow<Hello>.test2: suspend () -> Unit? get() = a
            """.trimIndent()))
            ))

        result.inspectLines { codeLines ->
            codeLines.forOne {
                it
                    .shouldContain("ColumnsContainer<Hello>.a")
                    .shouldContain("DataColumn<kotlin.coroutines.SuspendFunction0<kotlin.Unit?>>")
            }
            codeLines.forOne {
                it
                    .shouldContain("DataRow<Hello>.a")
                    .shouldContain("kotlin.coroutines.SuspendFunction0<kotlin.Unit?>")
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `nullable functional type`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: (() -> String)?
                }

                val ColumnsContainer<Hello>.test1: DataColumn<(() -> String)?> get() = a
                val DataRow<Hello>.test2: (() -> String)? get() = a
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forOne {
                it
                    .shouldContain("ColumnsContainer<Hello>.a")
                    .shouldContain("DataColumn<kotlin.Function0<kotlin.String>?>")
            }
            codeLines.forOne {
                it
                    .shouldContain("DataRow<Hello>.a")
                    .shouldContain("kotlin.Function0<kotlin.String>?")
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `functional type with receiver`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: (Int.() -> String)?
                }

                val ColumnsContainer<Hello>.test1: DataColumn<(Int.() -> String)?> get() = a
                val DataRow<Hello>.test2: (Int.() -> String)? get() = a
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forOne {
                it
                    .shouldContain("ColumnsContainer<Hello>.a")
                    .shouldContain("DataColumn<kotlin.Function1<kotlin.Int, kotlin.String>?>")
            }
            codeLines.forOne {
                it
                    .shouldContain("DataRow<Hello>.a")
                    .shouldContain("kotlin.Function1<kotlin.Int, kotlin.String>?")
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `named lambda parameter`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: (a: String) -> Unit
                }

                val ColumnsContainer<Hello>.test1: DataColumn<(a: String) -> Unit> get() = a
                val DataRow<Hello>.test2: (a: String) -> Unit get() = a
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forOne {
                it
                    .shouldContain("ColumnsContainer<Hello>.a")
                    .shouldContain("DataColumn<kotlin.Function1<kotlin.String, kotlin.Unit>>")
            }
            codeLines.forOne {
                it
                    .shouldContain("DataRow<Hello>.a")
                    .shouldContain("kotlin.Function1<kotlin.String, kotlin.Unit>")
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `inferred type`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: Int
                    val b get() = a
                }

                val ColumnsContainer<Hello>.test1: DataColumn<Int> get() = b
                val DataRow<Hello>.test2: Int get() = b
            """.trimIndent()))
            ))
        result.kspGeneratedFiles.find { it.name == generatedFile }?.readText().asClue {
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `typealias`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                class OuterClass
                typealias A = OuterClass

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: A
                }

                val ColumnsContainer<Hello>.col1: DataColumn<A> get() = a
                val DataRow<Hello>.row1: A get() = a
                
            """.trimIndent()))
            ))
        result.kspGeneratedFiles.find { it.name == generatedFile }?.readText().asClue {
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `column name from annotation is used`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                @DataSchema(isOpen = false)
                interface Hello {
                    @ColumnName("test-name")
                    val `test name`: Int
                }

                val ColumnsContainer<Hello>.test2: DataColumn<Int> get() = `test name`
                val DataRow<Hello>.test4: Int get() = `test name`
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forExactly(2) {
                it.shouldContain("this[\"test-name\"]")
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `jvm name`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports


                @DataSchema(isOpen = false)
                interface Hello {
                    val a: Int
                }

                val ColumnsContainer<Hello>.col1: DataColumn<Int> get() = a
                val DataRow<Hello>.row1: Int get() = a
                
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forExactly(2) {
                it.shouldContain("@JvmName(\"Hello_a\")")
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `DataRow property`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                interface Marker

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: DataRow<Marker>
                }

                val ColumnsContainer<Hello>.col1: ColumnGroup<Marker> get() = a
                val DataRow<Hello>.row1: DataRow<Marker> get() = a
                
            """.trimIndent()))
            ))
        result.kspGeneratedFiles.find { it.name == generatedFile }?.readText().asClue {
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `DataFrame property`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                $imports

                interface Marker

                @DataSchema(isOpen = false)
                interface Hello {
                    val a: DataFrame<Marker>
                }

                val ColumnsContainer<Hello>.col1: DataColumn<DataFrame<Marker>> get() = a
                val DataRow<Hello>.row1: DataFrame<Marker> get() = a
                
            """.trimIndent()))
            ))
        result.successfulCompilation shouldBe true
    }


    @Test
    fun `extension accessible from same package`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                package org.example

                $imports

                @DataSchema(isOpen = false)
                interface Hello {
                    val name: String
                }

                val ColumnsContainer<Hello>.test1: DataColumn<String> get() = name
                val DataRow<Hello>.test2: String get() = name
            """.trimIndent()))
            ))
        result.successfulCompilation shouldBe true
    }

    @Test
    fun `interface with type parameters`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                package org.example

                $imports

                @DataSchema(isOpen = false)
                interface Hello <T> {
                    val name: T
                }
            """.trimIndent()))
            ))
        result.successfulCompilation shouldBe false
    }

    @Test
    fun `nested interface`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                package org.example

                $imports
                class A {
                    @DataSchema(isOpen = false)
                    interface Hello {
                        val name: String
                    }
                }
               
            """.trimIndent()))
            ))
        result.successfulCompilation shouldBe true
    }

    @Test
    fun `redeclaration in different scopes`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                package org.example

                $imports
                class A {
                    @DataSchema(isOpen = false)
                    interface Hello {
                        val name: String
                    }
                }

               class B {
                    @DataSchema(isOpen = false)
                    interface Hello {
                        val name: String
                    }
                }

               
            """.trimIndent()))
            ))
        println(result.kspGeneratedFiles)
        result.successfulCompilation shouldBe true
    }

    @Test
    fun `interface with internal visibility`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                package org.example

                $imports

                @DataSchema
                internal interface Hello {
                    val name: Int
                }
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forExactly(2) {
                it.shouldContain("""internal val """)
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `interface with public visibility`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                package org.example

                $imports

                @DataSchema
                public interface Hello {
                    val name: Int
                }
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forExactly(2) {
                it.shouldContain("""public val""")
            }
            result.successfulCompilation shouldBe true
        }
    }

    @Test
    fun `interface with implicit visibility`() {
        val result = KspCompilationTestRunner.compile(
            TestCompilationParameters(
                sources = listOf(annotations, dataColumn, dataFrame, dataRow, SourceFile.kotlin("MySources.kt", """
                package org.example

                $imports

                @DataSchema
                interface Hello {
                    val name: Int
                }
            """.trimIndent()))
            ))
        result.inspectLines { codeLines ->
            codeLines.forExactly(2) {
                it.shouldStartWith("""val """)
            }
            result.successfulCompilation shouldBe true
        }
    }

    private fun KotlinCompileTestingCompilationResult.inspectLines(f: (List<String>) -> Unit) {
        inspectLines(generatedFile, f)
    }

    private fun KotlinCompileTestingCompilationResult.inspectLines(filename: String, f: (List<String>) -> Unit) {
        kspGeneratedFiles.single { it.name == filename }.readLines().asClue(f)
    }
}
