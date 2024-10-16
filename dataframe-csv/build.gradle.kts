import nl.jolanrensen.docProcessor.defaultProcessors.ARG_DOC_PROCESSOR_LOG_NOT_FOUND
import nl.jolanrensen.docProcessor.gradle.creatingProcessDocTask
import org.gradle.jvm.tasks.Jar

plugins {
    with(libs.plugins) {
        alias(kotlin.jvm)
        alias(publisher)
        alias(serialization)
        alias(kover)
        alias(ktlint)
        alias(jupyter.api)
        alias(docProcessor)
        alias(kotlinx.benchmark)
    }
    idea
}

group = "org.jetbrains.kotlinx"

val jupyterApiTCRepo: String by project

repositories {
    mavenLocal()
    mavenCentral()
    maven(jupyterApiTCRepo)
}

dependencies {
    implementation(project(":core"))

    // for csv reading
    implementation(libs.deephavenCsv)
    // for csv writing
    implementation(libs.commonsCsv)
    implementation(libs.commonsIo)
    implementation(libs.sl4j)
    implementation(libs.kotlinLogging)
    implementation(libs.kotlin.reflect)

    testApi(project(":core"))
    testImplementation(libs.kotlinx.benchmark.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotestAssertions) {
        exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
    }
}

benchmark {
    targets {
        register("test")
    }
}

val generatedSourcesFolderName = "generated-sources"

// Backup the kotlin source files location
val kotlinMainSources = kotlin.sourceSets.main
    .get()
    .kotlin.sourceDirectories
    .toList()
val kotlinTestSources = kotlin.sourceSets.test
    .get()
    .kotlin.sourceDirectories
    .toList()

fun pathOf(vararg parts: String) = parts.joinToString(File.separator)

// Include both test and main sources for cross-referencing, Exclude generated sources
val processKDocsMainSources = (kotlinMainSources + kotlinTestSources)
    .filterNot { pathOf("build", "generated") in it.path }

// sourceset of the generated sources as a result of `processKDocsMain`, this will create linter tasks
val generatedSources by kotlin.sourceSets.creating {
    kotlin {
        setSrcDirs(
            listOf(
                "$generatedSourcesFolderName/src/main/kotlin",
                "$generatedSourcesFolderName/src/main/java",
            ),
        )
    }
}

// Task to generate the processed documentation
val processKDocsMain by creatingProcessDocTask(processKDocsMainSources) {
    target = file(generatedSourcesFolderName)
    arguments += ARG_DOC_PROCESSOR_LOG_NOT_FOUND to false

    // false, so `runKtlintFormatOverGeneratedSourcesSourceSet` can format the output
    outputReadOnly = false

    exportAsHtml {
        dir = file("../docs/StardustDocs/snippets/kdocs")
    }
    task {
        group = "KDocs"
        finalizedBy("runKtlintFormatOverGeneratedSourcesSourceSet")
    }
}

tasks.named("ktlintGeneratedSourcesSourceSetCheck") {
    onlyIf { false }
}
tasks.named("runKtlintCheckOverGeneratedSourcesSourceSet") {
    onlyIf { false }
}

// If `changeJarTask` is run, modify all Jar tasks such that before running the Kotlin sources are set to
// the target of `processKdocMain`, and they are returned to normal afterward.
// This is usually only done when publishing
val changeJarTask by tasks.creating {
    outputs.upToDateWhen { false }
    doFirst {
        tasks.withType<Jar> {
            doFirst {
                require(generatedSources.kotlin.srcDirs.toList().isNotEmpty()) {
                    logger.error("`processKDocsMain`'s outputs are empty, did `processKDocsMain` run before this task?")
                }
                kotlin.sourceSets.main {
                    kotlin.setSrcDirs(generatedSources.kotlin.srcDirs)
                }
                logger.lifecycle("$this is run with modified sources: \"$generatedSourcesFolderName\"")
            }

            doLast {
                kotlin.sourceSets.main {
                    kotlin.setSrcDirs(kotlinMainSources)
                }
            }
        }
    }
}

// if `processKDocsMain` runs, the Jar tasks must run after it so the generated-sources are there
tasks.withType<Jar> {
    mustRunAfter(changeJarTask, processKDocsMain)
}

// modify all publishing tasks to depend on `changeJarTask` so the sources are swapped out with generated sources
tasks.named { it.startsWith("publish") }.configureEach {
    dependsOn(processKDocsMain, changeJarTask)
}

// Exclude the generated/processed sources from the IDE
idea {
    module {
        excludeDirs.add(file(generatedSourcesFolderName))
    }
}

kotlinPublications {
    publication {
        publicationName = "dataframeCsv"
        artifactId = project.name
        description = "CSV support for Kotlin Dataframe"
        packageName = artifactId
    }
}

kotlin {
    explicitApi()
    sourceSets.all {
        languageSettings {
        }
    }
}
