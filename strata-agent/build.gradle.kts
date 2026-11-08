plugins {
    java
    id("com.gradleup.shadow")
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

val seleniumVersion = "4.39.0"

sourceSets {
    create("integrationTest")
}

val integrationTestImplementation: Configuration by configurations.getting
val integrationTestRuntimeOnly: Configuration by configurations.getting

dependencies {
    implementation("net.bytebuddy:byte-buddy:1.17.5")
    // The agent uses whatever Selenium the test brings.
    compileOnly("org.seleniumhq.selenium:selenium-remote-driver:$seleniumVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    integrationTestImplementation("org.seleniumhq.selenium:selenium-java:$seleniumVersion")
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    integrationTestImplementation(project(":strata-core"))
    integrationTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveFileName.set("strata-agent.jar")
    archiveClassifier.set("")
    relocate("net.bytebuddy", "dev.strata.shaded.bytebuddy")
    exclude("module-info.class", "META-INF/versions/*/module-info.class", "META-INF/maven/**", "META-INF/LICENSE*", "META-INF/NOTICE*")
    manifest {
        attributes(
            "Premain-Class" to "dev.strata.agent.StrataAgent",
            "Agent-Class" to "dev.strata.agent.StrataAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
            "Multi-Release" to "true",
        )
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}

/** Shared output of the agent jar, consumed by strata-plugin. */
val agentJar: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(agentJar.name, tasks.shadowJar)

val itSourceSet = sourceSets["integrationTest"]

fun registerAgentIt(taskName: String, jdk: Int, unwritable: Boolean, withAgent: Boolean = true) = tasks.register<Test>(taskName) {
    description = "Runs the fixture flow " + (if (withAgent) "under -javaagent" else "without the agent") +
        " on JDK $jdk" + if (unwritable) " with an unwritable output dir" else ""
    group = "verification"
    testClassesDirs = itSourceSet.output.classesDirs
    classpath = itSourceSet.runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk)) })
    dependsOn(tasks.shadowJar)
    outputs.upToDateWhen { false }

    val agent = tasks.shadowJar.flatMap { it.archiveFile }
    val itRoot = layout.buildDirectory.dir("it/$taskName")
    val fixtures = rootProject.file("fixtures/site")
    doFirst {
        val root = itRoot.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        if (unwritable) root.resolve("blocker").writeText("a file where the output dir should be")
    }
    jvmArgumentProviders += CommandLineArgumentProvider {
        val root = itRoot.get().asFile
        val out = if (unwritable) root.resolve("blocker/run") else root.resolve("run")
        listOfNotNull(
            if (withAgent) "-javaagent:${agent.get().asFile.absolutePath}" else null,
            "-Dstrata.out=${out.absolutePath}",
            "-Dstrata.fixtures=${fixtures.absolutePath}",
            "-Dstrata.it.expectCapture=${withAgent && !unwritable}",
            // ./gradlew … -PquietMs=200 measures a shorter settle window (CLAUDE.md §4.2).
            providers.gradleProperty("quietMs").orNull?.let { "-Dstrata.quietMs=$it" },
        )
    }
}

val agentIt17 = registerAgentIt("agentIntegrationTestJdk17", 17, unwritable = false)
val agentIt21 = registerAgentIt("agentIntegrationTestJdk21", 21, unwritable = false)
val agentItUnwritable = registerAgentIt("agentIntegrationTestUnwritable", 21, unwritable = true)

// Not part of check: the same flow without the agent, for measuring overhead (CLAUDE.md §10.2).
registerAgentIt("fixtureFlowBaselineJdk21", 21, unwritable = false, withAgent = false)

tasks.check {
    dependsOn(agentIt17, agentIt21, agentItUnwritable)
}
