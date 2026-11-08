import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IntelliJ 2024.2 bundles kotlin-stdlib 1.9; the plugin and core use the IDE's copy.
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

val agent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":strata-core")) {
        exclude(group = "org.jetbrains.kotlin")
    }
    agent(project(path = ":strata-agent", configuration = "agentJar"))

    intellijPlatform {
        intellijIdeaCommunity("2024.2.6")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        id = "dev.strata"
        name = "Strata Locator Coverage"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

// The agent jar ships in the plugin's lib/ (CLAUDE.md §7); AgentJar resolves it from there.
tasks.withType<PrepareSandboxTask>().configureEach {
    from(agent) {
        into(pluginName.map { "$it/lib" })
    }
}
