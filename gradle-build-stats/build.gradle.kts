/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Gradle plugin project to get you started.
 * For more details on writing Custom Plugins, please refer to https://docs.gradle.org/8.8/userguide/custom_plugins.html in the Gradle documentation.
 * This project uses @Incubating APIs which are subject to change.
 */

plugins {
    // Apply the Java Gradle plugin development plugin to add support for developing Gradle plugins
    `java-gradle-plugin`
    `maven-publish`

    // Apply the Kotlin JVM plugin to add support for Kotlin.
    alias(libs.plugins.jvm)
}

group = "com.snapshot.gradle"
version = "0.0.2"

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

testing {
    suites {
        // Configure the built-in test suite
        val test by getting(JvmTestSuite::class) {
            // Use Kotlin Test framework
            useKotlinTest("1.9.22")
        }

        // Create a new test suite
        val functionalTest by registering(JvmTestSuite::class) {
            // Use Kotlin Test framework
            useKotlinTest("1.9.22")

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
    // Define the plugin
    plugins {
        create("gradle-build-stats") {
            id = "gradle-build-stats-plugin"
            implementationClass = "com.snapshot.gradle.GradleBuildStatsPlugin"
        }
    }
//    val greeting by plugins.creating {
//        id = "com.snapshot.gradle.GradleBuildStatsPlugin"
//        implementationClass = "com.snapshot.gradle.GradleBuildStatsPlugin"
//    }
}

gradlePlugin.testSourceSets.add(sourceSets["functionalTest"])

tasks.named<Task>("check") {
    // Include functionalTest as part of the check lifecycle
    dependsOn(testing.suites.named("functionalTest"))
}

publishing {
//    publications {
//        create<MavenPublication>("maven") {
//            groupId = "com.snapshot.gradle"
//            artifactId = "gradle-build-stats-plugin"
//            version = "0.0.1"
//
//            from(components["java"])
//        }
//    }
    repositories {
        mavenLocal()
    }
}