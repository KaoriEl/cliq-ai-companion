import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val pluginGroup: String by project
val pluginVersion: String by project
val platformVersion: String by project

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community as the development target. The `platformVersion`
        // property pins which build of the platform we compile and run against.
        create("IC", platformVersion)

        // Bundled terminal plugin gives us the `TerminalToolWindowManager`
        // API used to spawn shell widgets where CLI agents are launched.
        bundledPlugin("org.jetbrains.plugins.terminal")

        // Required by the `instrumentCode` task to inject nullability assertions.
        instrumentationTools()

        testFramework(TestFrameworkType.Platform)
    }

    // Exclude SLF4J and Coroutines from all dependencies as IntelliJ Platform already provides them.
    configurations.all {
        exclude(group = "org.slf4j", module = "slf4j-api")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    }

    // JSON for the MCP protocol payloads exchanged with the CLI agents.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // MCP Kotlin SDK + Ktor transport for Streamable HTTP server.
    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.2")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Lowest IDE build supported. 243 == 2024.3.
            sinceBuild.set("243")
        }
    }
}

tasks {
    // Searchable options indexing is unnecessary for a small plugin and slows down builds.
    buildSearchableOptions {
        enabled = false
    }

    signPlugin {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    test {
        useJUnit()
    }
}
