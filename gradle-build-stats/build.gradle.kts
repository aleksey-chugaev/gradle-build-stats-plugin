/*
 * Copyright (c) 2024 [Aleksey Chugaev]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    // Apply the Java Gradle plugin development plugin to add support for developing Gradle plugins
    `java-gradle-plugin`
    `maven-publish`

    // Apply the Kotlin JVM plugin to add support for Kotlin.
    alias(libs.plugins.jvm)
    alias(libs.plugins.gradle.pluginPublishing)
}

group = "io.github.aleksey-chugaev.gradlebuildstats"
version = "0.0.14"

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

testing {
    suites {
        // Configure the built-in test suite
        val test by getting(JvmTestSuite::class) {
            // Use Kotlin Test framework
            useKotlinTest(libs.versions.kotlin)
        }

        // Create a new test suite
        val functionalTest by registering(JvmTestSuite::class) {
            // Use Kotlin Test framework
            useKotlinTest(libs.versions.kotlin)

            dependencies {
                // functionalTest test suite depends on the production code in tests
                implementation(project())
            }

            targets {
                all {
                    // This test suite should run after the built-in test suite has run its tests
                    testTask.configure { shouldRunAfter(test) }
                }
            }
        }
    }
}

gradlePlugin {
    website = "https://github.com/aleksey-chugaev/gradle-build-stats-plugin"
    vcsUrl = "https://github.com/aleksey-chugaev/gradle-build-stats-plugin"
    // Define the plugin
    plugins {
        create("gradle-build-stats") {
            id = "io.github.aleksey-chugaev.gradlebuildstats"
            displayName = "Gradle Build Stats Plugin"
            description = "A Gradle plugin to track and record tasks that are executed during a Gradle build and the time it takes for tasks to complete."
            tags = listOf("performance")
            implementationClass = "io.github.chugaev.gradlebuildstats.GradleBuildStatsPlugin"
        }
    }
}

gradlePlugin.testSourceSets.add(sourceSets["functionalTest"])

tasks.named<Task>("check") {
    // Include functionalTest as part of the check lifecycle
    dependsOn(testing.suites.named("functionalTest"))
}

publishing {
    repositories {
        mavenLocal()
    }
}