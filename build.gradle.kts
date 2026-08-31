import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("io.ltverdict.MainKt")
    applicationName = "ltv"
}

dependencies {
    implementation(platform("io.ktor:ktor-bom:3.5.2"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("org.slf4j:slf4j-simple:2.0.18")
    implementation("com.univocity:univocity-parsers:2.9.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.configureEach {
    resolutionStrategy {
        eachDependency {
            val version = requested.version ?: return@eachDependency
            if (version.contains('+') || version.startsWith("latest.") || version.startsWith('[') || version.startsWith('(')) {
                throw GradleException("Dynamic dependency versions are forbidden: ${requested.group}:${requested.name}:$version")
            }
        }
        componentSelection {
            all {
                if (metadata?.isChanging == true) {
                    reject("Changing dependencies are forbidden: ${candidate.group}:${candidate.module}:${candidate.version}")
                }
            }
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val csvSpike by tasks.registering(Test::class) {
    description = "Runs the bounded uniVocity CSV dependency spike."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/CsvDependencySpikeTest.class")
    maxHeapSize = "256m"
    maxParallelForks = 1
}
