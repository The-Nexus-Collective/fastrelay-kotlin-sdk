import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.mavenPublish)
}

group = "io.fastrelay"
version = "0.1.0"

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm()

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "FastrelaySDK"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "io.fastrelay.sdk"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "fastrelay-kotlin-sdk", version.toString())
    pom {
        name.set("fastrelay Kotlin SDK")
        description.set("Kotlin Multiplatform client for the fastrelay activity feeds platform: Android, iOS, and JVM.")
        url.set("https://github.com/The-Nexus-Collective/fastrelay-kotlin-sdk")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("robertkoziej")
                name.set("Robert Koziej")
            }
        }
        scm {
            url.set("https://github.com/The-Nexus-Collective/fastrelay-kotlin-sdk")
            connection.set("scm:git:git://github.com/The-Nexus-Collective/fastrelay-kotlin-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/The-Nexus-Collective/fastrelay-kotlin-sdk.git")
        }
    }
}

tasks.register("verifySdkExtractable") {
    group = "verification"
    description = "Asserts :sdk has no project dependencies so it stays extractable to a standalone repo"
    val projectDeps = configurations
        .flatMap { configuration ->
            configuration.dependencies
                .filterIsInstance<ProjectDependency>()
                .filter { it.path != project.path }
                .map { "${configuration.name} -> ${it.path}" }
        }
    doLast {
        check(projectDeps.isEmpty()) {
            ":sdk must not depend on other project modules, found:\n" + projectDeps.joinToString("\n")
        }
    }
}
