plugins {
    // Подключаем только чистый Kotlin JVM
    kotlin("jvm")
    kotlin("plugin.serialization")
}

version = "1.0.0"
group = "pro.azenord.mwb"

repositories {
    mavenCentral()
}

dependencies {
    // 1. Сетевой движок Netty (Полный стек для работы WebSocket)
    implementation("io.netty:netty-all:4.1.100.Final")

    // 2. Нативная Kotlin Сериализация (JSON плагин)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}