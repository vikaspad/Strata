import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm")
    `java-library`
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // The plugin runs core on the IDE's bundled kotlin-stdlib (1.9 on 2024.2).
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api("org.jsoup:jsoup:1.18.3")
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("strata.fixtures", rootProject.file("fixtures").absolutePath)
    // ./gradlew :strata-core:test -PupdateGolden rewrites fixtures/snapshots/expected-locator-coverage.xml
    systemProperty("strata.updateGolden", providers.gradleProperty("updateGolden").isPresent.toString())
}
