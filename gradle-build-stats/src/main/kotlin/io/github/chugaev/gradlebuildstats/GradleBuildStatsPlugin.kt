package io.github.chugaev.gradlebuildstats

import io.github.chugaev.gradlebuildstats.GradleBuildStatsConfig.Companion.readConfig
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
import org.gradle.internal.time.Time
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.DurationUnit
import kotlin.time.toDuration

internal val logger = LoggerFactory.getLogger("io.github.chugaev.gradlebuildstats.GradleBuildStatsPlugin")

interface GradleBuildStatsPluginExtension {
    val disabled: Property<Boolean>
}

class GradleBuildStatsPlugin @Inject constructor(
    private val registry: BuildEventsListenerRegistry,
    private val buildStartedTime: BuildStartedTime,
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
) : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("gradleBuildStats", GradleBuildStatsPluginExtension::class.java)
        project.afterEvaluate {
            doApply(project, extension)
        }
    }

    private fun doApply(project: Project, extension: GradleBuildStatsPluginExtension) {
        if (extension.disabled.getOrElse(false)) {
            logger.info("Plugin disabled")
            return
        }
        val pluginConfig = readConfig(project)
        if (!pluginConfig.disabled) {
            logger.info("Plugin disabled")
            return
        }
        val taskNames = project.gradle.startParameter.taskNames.takeIf { it.isNotEmpty() } ?: project.defaultTasks
        if (!isEnabledForTaskNames(taskNames, pluginConfig)) {
            logger.info("Plugin disabled for tasks '${taskNames.joinToString()}'")
            return
        }

        val buildStartTime = try {
            Instant.ofEpochMilli(buildStartedTime.startTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } catch (e: Throwable) {
            logger.warn("Failed to obtain build started time", e)
            return
        }

        val reportWriterService = project.gradle.sharedServices.registerIfAbsent(
            "com.snapshot.gradle.GradleBuildStatsReportWriterService",
            GradleBuildStatsReportWriterService::class.java
        ) { spec ->
            spec.parameters.buildStartTime = buildStartTime
            spec.parameters.pluginConfig = pluginConfig
            spec.parameters.taskNames = taskNames
            spec.parameters.projectName = project.name
        }.orNull ?: run {
            logger.warn("Failed to retrieve GradleBuildStatsReportWriterService")
            return
        }

        val taskTrackerService = project.gradle.sharedServices.registerIfAbsent(
            "com.snapshot.gradle.GradleBuildStatsTaskCompletionService",
            GradleBuildStatsTaskCompletionService::class.java
        ) { }
        if (!taskTrackerService.isPresent) {
            logger.warn("Failed to initialise GradleBuildStatsTaskCompletionService")
            return
        }

        if (!reportWriterService.initialise()) {
            logger.warn("Failed to initialise GradleBuildStatsReportWriterService")
            return
        }
        logger.debug("GradleBuildStatsPlugin taskNames=$taskNames buildStartTime=$buildStartTime")

        reportWriterService.startReport(project.name, taskNames, buildStartedTime.startTime)

        registry.onTaskCompletion(taskTrackerService)

        flowScope.always(GradleBuildStatsCompletedAction::class.java) { spec ->
            spec.parameters.buildResult.set(flowProviders.buildWorkResult)
            spec.parameters.startTimeMillis.set(buildStartedTime.startTime)
        }
    }

    private fun isEnabledForTaskNames(taskNames: List<String>, pluginConfig: GradleBuildStatsConfig): Boolean {
        if (taskNames.isEmpty()) {
            return true
        }
        if (pluginConfig.enabledForTasksWithName.isNotEmpty()) {
            logger.debug("enabledForTasksWithName ${pluginConfig.enabledForTasksWithName}")
            if (pluginConfig.enabledForTasksWithName.any { enabledTaskName ->
                    taskNames.any { taskName ->
                        taskName.endsWith(enabledTaskName)
                    }
                }) {
                return true
            }
            return false
        }
        if (pluginConfig.disabledForTasksWithName.isNotEmpty()) {
            logger.debug("disabledForTasksWithName ${pluginConfig.disabledForTasksWithName}")
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
}

internal class GradleBuildStatsCompletedAction : FlowAction<GradleBuildStatsCompletedAction.Parameters> {

    interface Parameters : FlowParameters {

        @get:Input
        val buildResult: Property<BuildWorkResult>

        @get:Input
        val startTimeMillis: Property<Long>

        @get:ServiceReference
        val taskCompletionService: Property<GradleBuildStatsTaskCompletionService>

        @get:ServiceReference
        val reportWriterService: Property<GradleBuildStatsReportWriterService>
    }

    override fun execute(parameters: Parameters) {
        val reportWriterService = parameters.reportWriterService.orNull ?: run {
            logger.warn("GradleBuildStatsCompletedAction: missing reportWriterService")
            return
        }

        val startTimeMillis = parameters.startTimeMillis.orNull ?: run {
            logger.warn("GradleBuildStatsCompletedAction: missing startTimeMillis")
            reportWriterService.finish("FAILURE", 0L.toDuration(DurationUnit.MILLISECONDS))
            return
        }
        val duration = (Time.currentTimeMillis() - startTimeMillis).toDuration(
            DurationUnit.MILLISECONDS
        )
        val buildResult = parameters.buildResult.orNull ?: run {
            logger.warn("GradleBuildStatsCompletedAction: missing buildResult")
            reportWriterService.finish("FAILURE", 0L.toDuration(DurationUnit.MILLISECONDS))
            return
        }
        val isBuildSuccess = !buildResult.failure.isPresent

        val status = if (isBuildSuccess) {
            "SUCCESS"
        } else {
            "FAILED"
        }
        reportWriterService.finish(status, duration)
    }
}
