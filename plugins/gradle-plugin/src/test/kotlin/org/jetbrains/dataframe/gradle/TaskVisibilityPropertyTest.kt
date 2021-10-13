package org.jetbrains.dataframe.gradle

import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.matchers.shouldBe
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.junit.Test

class TaskVisibilityPropertyTest {
    @Test
    fun `extension sourceSet present in project 1 `() {
        val project = ProjectBuilder.builder().build() as ProjectInternal
        project.plugins.apply(SchemaGeneratorPlugin::class.java)
        project.extensions.getByType(SchemaGeneratorExtension::class.java).apply {
            sourceSet = "main1"
            visibility = DataSchemaVisibility.INTERNAL
            schema {
                src = project.file("src")
                data = "123"
                name = "org.example.my.321"
            }
        }
        shouldNotThrow<ProjectConfigurationException> {
            project.evaluate()
        }
        (project.tasks.getByName("generateDataFrame321") as GenerateDataSchemaTask).schemaVisibility.get()
            .shouldBe(DataSchemaVisibility.INTERNAL)
    }

    @Test
    fun `extension sourceSet present in project 2`() {
        val project = ProjectBuilder.builder().build() as ProjectInternal
        project.plugins.apply(SchemaGeneratorPlugin::class.java)
        project.extensions.getByType(SchemaGeneratorExtension::class.java).apply {
            sourceSet = "main1"
            visibility = DataSchemaVisibility.INTERNAL
            schema {
                src = project.file("src")
                visibility = DataSchemaVisibility.EXPLICIT_PUBLIC
                data = "123"
                name = "org.example.my.321"
            }
        }
        shouldNotThrow<ProjectConfigurationException> {
            project.evaluate()
        }
        (project.tasks.getByName("generateDataFrame321") as GenerateDataSchemaTask).schemaVisibility.get()
            .shouldBe(DataSchemaVisibility.EXPLICIT_PUBLIC)
    }

    @Test
    fun `extension sourceSet present in project`() {
        val project = ProjectBuilder.builder().build() as ProjectInternal
        project.plugins.apply(SchemaGeneratorPlugin::class.java)
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.extensions.getByType(KotlinJvmProjectExtension::class.java).apply {
            sourceSets.create("main1")
            explicitApi()
        }
        project.extensions.getByType(SchemaGeneratorExtension::class.java).apply {
            sourceSet = "main1"
            schema {
                data = "123"
                name = "org.example.my.321"
            }
        }
        shouldNotThrow<ProjectConfigurationException> {
            project.evaluate()
        }
        (project.tasks.getByName("generateDataFrame321") as GenerateDataSchemaTask).schemaVisibility.get()
            .shouldBe(DataSchemaVisibility.EXPLICIT_PUBLIC)
    }
}
