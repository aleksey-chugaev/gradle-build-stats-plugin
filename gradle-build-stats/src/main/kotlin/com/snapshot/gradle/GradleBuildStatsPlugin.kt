package com.snapshot.gradle

import com.snapshot.gradle.GradleBuildStatsConfig.Companion.readConfig
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlin.time.DurationUnit
import kotlin.time.toDuration

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
            Instant.ofEpochMilli(buildStartedTime.startTime).atOffset(ZoneOffset.UTC).toLocalDateTime()
        } catch (e: Throwable) {
            logger.log("Failed to get BuildStartedTime")
            e.printStackTrace()
            return
        }
        logger.log("GradleBuildStatsPlugin apply, taskNames=$taskNames startTime=$buildStartTime")

        val reportWriterService = project.gradle.sharedServices.registerIfAbsent(
            "com.snapshot.gradle.GradleBuildStatsReportWriterService",
            GradleBuildStatsReportWriterService::class.java
        ) { spec ->
            spec.parameters.buildStartTime = buildStartTime
            spec.parameters.pluginConfig = pluginConfig
        }.orNull ?: run {
            logger.log("Failed to get GradleBuildStatsReportWriterService")
            return
        }

        if (!reportWriterService.initialise()) {
            logger.log("Failed to initialise GradleBuildStatsReportWriterService")
            return
        }

        reportWriterService.startReport(taskNames, buildStartTime)

        val taskTrackerService = project.gradle.sharedServices.registerIfAbsent(
            "com.snapshot.gradle.GradleBuildStatsTaskCompletionService",
            GradleBuildStatsTaskCompletionService::class.java
        ) { }

        registry.onTaskCompletion(taskTrackerService)

        flowScope.always(GradleBuildStatsCompletedAction::class.java) { spec ->
            spec.parameters.buildResult.set(flowProviders.buildWorkResult)
            spec.parameters.startTime.set(buildStartTime)
        }
    }
}

internal fun isEnabledForTaskNames(taskNames: List<String>, pluginConfig: GradleBuildStatsConfig): Boolean {
    if (taskNames.isEmpty()) {
        return true
    }
    // TODO - check enabled first?
    if (pluginConfig.enabledForTasksWithName.isNotEmpty()) {
        if (pluginConfig.enabledForTasksWithName.any { enabledTaskName ->
                taskNames.any { taskName ->
                    taskName.endsWith(enabledTaskName)
                }
            }) {
            return true
        }
        return false
    }
    // TODO - check for endsWith?
    if (pluginConfig.disabledForTasksWithName.isNotEmpty()) {
        if (pluginConfig.disabledForTasksWithName.any { disabledTaskName ->
                taskNames.any { taskName ->
                    taskName.endsWith(disabledTaskName)
                }
            }) {
            return false
        }
        return true
    }
    return true
}

internal fun interface Logger {
    fun log(message: String)
}

class GradleBuildStatsCompletedAction : FlowAction<GradleBuildStatsCompletedAction.Parameters> {

    interface Parameters : FlowParameters {

        @get:Input
        val buildResult: Property<BuildWorkResult>

        @get:Input
        val startTime: Property<LocalDateTime>

        @get:ServiceReference
        val taskCompletionService: Property<GradleBuildStatsTaskCompletionService>

        @get:ServiceReference
        val reportWriterService: Property<GradleBuildStatsReportWriterService>
    }

    override fun execute(parameters: Parameters) {
        val taskCompletionService = parameters.taskCompletionService.orNull ?: run {
            logger.log("error: missing taskCompletionService")
            return
        }
        val reportWriterService = parameters.reportWriterService.orNull ?: run {
            logger.log("error: missing reportWriterService")
            return
        }
        taskCompletionService.onBuildCompleted()

        val startTime = parameters.startTime.orNull ?: run {
            logger.log("error: missing startTime")
            return
        }
        val duration = (System.currentTimeMillis() - startTime.toInstant(ZoneOffset.UTC).toEpochMilli()).toDuration(
            DurationUnit.MILLISECONDS
        )
        val buildResult = parameters.buildResult.orNull ?: run {
            logger.log("error: missing buildResult")
            return
        }
        val isBuildSuccess = !buildResult.failure.isPresent

        val status = if (isBuildSuccess) {
            "SUCCESS"
        } else {
            "FAILURE"
        }
        reportWriterService.finish(status, duration)
    }
}