plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    application
}

group = "icu.torus"
version = "1.0-SNAPSHOT"

val trixnityVersion = "4.3.9"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("io.github.oshai:kotlin-logging:6.0.9")
    implementation("net.folivo:trixnity-client:$trixnityVersion")
    implementation("net.folivo:trixnity-client-repository-exposed:$trixnityVersion")
    implementation("net.folivo:trixnity-client-media-okio:$trixnityVersion")
    implementation("io.ktor:ktor-client-java:2.3.11")
    implementation("com.h2database:h2:2.2.224")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("io.github.irgaly.kottage:kottage:1.7.0")
    implementation("com.aallam.openai:openai-client:3.7.2")
    implementation("org.jetbrains:markdown:0.7.3")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "icu.torus.chatgpt.m.MainKt"
}