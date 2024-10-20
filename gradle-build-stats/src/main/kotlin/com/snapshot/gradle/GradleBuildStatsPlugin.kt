package com.snapshot.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.flow.BuildWorkResult
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.internal.buildevents.BuildStartedTime
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

internal val logger = Logger { message ->
    println("!!! $message")
}

class GradleBuildStatsPlugin @Inject constructor(
    private val registry: BuildEventsListenerRegistry,
    private val buildStartedTime: BuildStartedTime,
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
) : Plugin<Project> {

    override fun apply(project: Project) {
        val pluginConfig = readConfig(project)
        if (!pluginConfig.enabled) {
            logger.log("Plugin disabled")
            return
        }
        val taskNames = project.gradle.startParameter.taskNames.takeIf { it.isNotEmpty() } ?: project.defaultTasks
        if (!isEnabledForTaskNames(taskNames, pluginConfig)) {
            logger.log("Plugin disabled for tasks '${taskNames.joinToString()}'")
            return
        }

        val buildStartTime = try {
            buildStartedTime.startTime
        } catch (e: Throwable) {
            logger.log("serviceOf failed for BuildStartedTime")
            e.printStackTrace()
            return
        }
        logger.log("GradleBuildStatsPlugin apply, taskNames=$taskNames startTime=$buildStartTime")

        val buildStatsFile = createBuildStatsFile(pluginConfig, buildStartTime) ?: return

        val fileWriter = BufferedWriter(FileWriter(buildStatsFile, false))

        fileWriter.appendLine("version: 1")
        fileWriter.appendLine("build tasks: ${taskNames.joinToString(",")}")
        fileWriter.appendLine("build start time: $buildStartTime")
        fileWriter.appendLine()

        fileWriter.close()

        val taskTrackerService = project.gradle.sharedServices.registerIfAbsent(
            "com.snapshot.gradle.GradleBuildStatsTaskCompletionService",
            GradleBuildStatsTaskCompletionService::class.java
        ) { spec ->
            spec.parameters.buildStatsFile = buildStatsFile
            spec.parameters.pluginConfig = pluginConfig
        }

        registry.onTaskCompletion(taskTrackerService)

        flowScope.always(GradleBuildStatsCompletedAction::class.java) { spec ->
            spec.parameters.buildResult.set(flowProviders.buildWorkResult)
            spec.parameters.startTime.set(buildStartTime)
            spec.parameters.buildStatsFile.set(buildStatsFile)
        }
    }

    private fun isEnabledForTaskNames(taskNames: List<String>, pluginConfig: GradleBuildStatsConfig): Boolean {
        if (taskNames.isEmpty()) {
            return true
        }
        if (pluginConfig.disabledForTasksWithName.isNotEmpty()) {
            if (pluginConfig.disabledForTasksWithName.any { taskNames.contains(it) }) {
                return false
            }
            return true
        } else if (pluginConfig.enabledForTasksWithName.isNotEmpty()) {
            if (pluginConfig.enabledForTasksWithName.any { taskNames.contains(it) }) {
                return true
            }
            return false
        }
        return true
    }

    private fun readConfig(project: Project): GradleBuildStatsConfig {
        val propertiesFile = project.layout.projectDirectory.file("gradle-build-stats.properties").asFile
        return if (propertiesFile.exists() && propertiesFile.canRead()) {
            val properties = Properties()
            properties.load(
                propertiesFile.inputStream()
            )
            GradleBuildStatsConfig.load(properties, project)
        } else {
            GradleBuildStatsConfig.load(project)
        }
    }

    private fun createBuildStatsFile(pluginConfig: GradleBuildStatsConfig, buildStartTime: Long): File? {
        val buildStatsHomeDir = File(pluginConfig.buildStatsHomePath)
        buildStatsHomeDir.mkdirs()
        if (!buildStatsHomeDir.exists()) {
            logger.log("buildStatsHomeDir not exists $buildStatsHomeDir")
            return null
        }
        if (!buildStatsHomeDir.canWrite()) {
            logger.log("cannot write to buildStatsHomeDir $buildStatsHomeDir")
            return null
        }

        val buildDateTime = Instant.ofEpochMilli(buildStartTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val buildStatsFileName = DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm-ss").format(buildDateTime)
        logger.log("buildStatsFileName $buildStatsFileName")

        val buildStatsFile = File(buildStatsHomeDir, "$buildStatsFileName.dat")
        if (!buildStatsFile.createNewFile()) {
            logger.log("cannot create buildStatsFile $buildStatsFile")
            return null
        }
        return buildStatsFile
    }
}

internal fun interface Logger {
    fun log(message: String)
}

class GradleBuildStatsCompletedAction : FlowAction<GradleBuildStatsCompletedAction.Parameters> {

    interface Parameters : FlowParameters {

        @get:Input
        val buildResult: Property<BuildWorkResult>

        @get:Input
        val startTime: Property<Long>

        @get:Input
        val buildStatsFile: Property<File>

        @get:ServiceReference
        val taskCompletionService: Property<GradleBuildStatsTaskCompletionService>
    }

    override fun execute(parameters: Parameters) {
        val taskCompletionService = parameters.taskCompletionService.orNull ?: run {
            logger.log("error: missing taskCompletionService")
            return
        }
        taskCompletionService.onBuildCompleted()

        val startTime = parameters.startTime.orNull ?: run {
            logger.log("error: missing startTime")
            return
        }
        val duration = System.currentTimeMillis() - startTime
        val buildResult = parameters.buildResult.orNull ?: run {
            logger.log("error: missing buildResult")
            return
        }
        val isBuildSuccess = !buildResult.failure.isPresent

        val buildStatsFile = parameters.buildStatsFile.orNull ?: run {
            logger.log("error: missing buildStatsFile")
            return
        }
        val buildStatsFileWriter = BufferedWriter(FileWriter(buildStatsFile, true))
        val status = if (isBuildSuccess) {
            "SUCCESS"
        } else {
            "FAILURE"
        }
        buildStatsFileWriter.appendLine()
        buildStatsFileWriter.appendLine("build status: $status")
        buildStatsFileWriter.appendLine("build duration: $duration")
        buildStatsFileWriter.close()
    }
}