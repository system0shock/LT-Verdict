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

// `-PltvVersion=0.1.0` sets the version of a release build; `ltv --version` reads it from ltv-version.properties.
version = providers.gradleProperty("ltvVersion").getOrElse("0.1.0-SNAPSHOT")

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
    implementation("org.postgresql:postgresql:42.7.13")
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

val uiDirectory = layout.projectDirectory.dir("ui")
distributions {
    main {
        contents {
            from(
                files(
                    "tools/advisory_ai_runtime.ps1",
                    "tools/advisory_ai_runtime_local.sh",
                    "tools/advisory_ai_runtime_qwen.sh",
                    "tools/advisory_ai_runtime_relay.mjs",
                    "tools/onboard_test.py",
                ),
            ) {
                into("tools")
            }
            from(
                files(
                    "docs/contracts/advice/v1/system-prompt.md",
                    "docs/contracts/advice/v1/system-prompt-v2.md",
                    "docs/contracts/advice/v1/ai-advice-output.schema.json",
                ),
            ) {
                into("docs/contracts/advice/v1")
            }
            from("skills/lt-verdict-onboard-test") { into("skills/lt-verdict-onboard-test") }
            from(
                files("docs/user/test-onboarding.md", "docs/user/advisory-ai.md", "docs/user/jenkins-and-reports.md"),
            ) { into("docs/user") }
            from(files("docs/user/saved-analytics.md", "docs/user/opensearch-correlation-preparation.md")) { into("docs/user") }
            from("docs/contracts/diagnostics/v1/opensearch-correlation-templates.example.json") { into("docs/contracts/diagnostics/v1") }
        }
    }
}

val npmExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"

val npmCi by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs locked frontend dependencies."
    workingDir(uiDirectory)
    commandLine(npmExecutable, "ci")
    if (providers.gradleProperty("npmOffline").isPresent) args("--offline")
    inputs.files(uiDirectory.file("package.json"), uiDirectory.file("package-lock.json"))
    outputs.dir(uiDirectory.dir("node_modules"))
}

val uiTypecheck by tasks.registering(Exec::class) {
    group = "verification"
    dependsOn(npmCi)
    workingDir(uiDirectory)
    commandLine(npmExecutable, "run", "typecheck")
}

val uiLint by tasks.registering(Exec::class) {
    group = "verification"
    dependsOn(npmCi)
    workingDir(uiDirectory)
    commandLine(npmExecutable, "run", "lint")
}

val uiContractTest by tasks.registering(Exec::class) {
    group = "verification"
    dependsOn(npmCi)
    workingDir(uiDirectory)
    commandLine(npmExecutable, "run", "test:contracts")
}

val uiBuild by tasks.registering(Exec::class) {
    group = "build"
    dependsOn(npmCi)
    workingDir(uiDirectory)
    commandLine(npmExecutable, "run", "build")
    inputs.files(uiDirectory.file("package.json"), uiDirectory.file("package-lock.json"))
    inputs.dir(uiDirectory.dir("src"))
    inputs.file(uiDirectory.file("index.html"))
    inputs.file(uiDirectory.file("vite.config.ts"))
    inputs.file(uiDirectory.file("tsconfig.json"))
    outputs.dir(uiDirectory.dir("dist"))
}

val ltvVersion = project.version.toString()

tasks.processResources {
    dependsOn(uiBuild)
    from(uiDirectory.dir("dist")) { into("web") }
    inputs.property("ltvVersion", ltvVersion)
    filesMatching("ltv-version.properties") { expand("version" to ltvVersion) }
}

val runE2eServer by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Starts the real local server for browser tests."
    dependsOn(tasks.testClasses, tasks.processResources)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.ltverdict.e2e.E2eServerMainKt")
    doFirst {
        systemProperty(
            "e2eDataDir",
            providers.gradleProperty("e2eDataDir").orNull ?: throw GradleException("-Pe2eDataDir is required"),
        )
    }
}

val uiE2e by tasks.registering(Exec::class) {
    group = "verification"
    dependsOn(npmCi)
    workingDir(uiDirectory)
    commandLine(npmExecutable, "run", "e2e")
}
