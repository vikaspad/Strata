plugins {
    kotlin("jvm") version "2.1.21" apply false
    id("org.jetbrains.intellij.platform") version "2.9.0" apply false
    id("com.gradleup.shadow") version "8.3.8" apply false
}

allprojects {
    group = "dev.strata"
    version = "0.1.0"
}
