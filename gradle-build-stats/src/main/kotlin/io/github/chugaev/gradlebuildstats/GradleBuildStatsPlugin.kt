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
        if (pluginConfig.disabled) {
            logger.info("Plugin disabled")
            return
        }
        var taskNames = project.gradle.startParameter.taskNames.mapNotNull { it.takeIf { it.isNotBlank() } }
        if (taskNames.isEmpty()) {
            taskNames = project.defaultTasks
        }
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
            spec.parameters.buildStartTimeMillis = buildStartedTime.startTime
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
            spec.parameters.pluginConfig.set(pluginConfig)
            spec.parameters.startTimeMillis.set(buildStartedTime.startTime)
            spec.parameters.taskNamesUnknown.set(taskNames.isEmpty())
        }
    }
}

private fun isEnabledForTaskNames(taskNames: List<String>, pluginConfig: GradleBuildStatsConfig): Boolean {
    if (taskNames.isEmpty()) {
        return true
    }
    logger.debug("config enabledForTasksWithName ${pluginConfig.enabledForTasksWithName}")
    if (pluginConfig.enabledForTasksWithName.any { it.isNotBlank() }) {
        if (pluginConfig.enabledForTasksWithName.any { enabledTaskName ->
                taskNames.any { taskName ->
                    taskName.endsWith(enabledTaskName, ignoreCase = true)
                }
            }) {
            return true
        }
        return false
    }
    logger.debug("config disabledForTasksWithName ${pluginConfig.disabledForTasksWithName}")
    if (pluginConfig.disabledForTasksWithName.any { it.isNotBlank() }) {
        if (pluginConfig.disabledForTasksWithName.any { disabledTaskName ->
                taskNames.any { taskName ->
                    taskName.endsWith(disabledTaskName, ignoreCase = true)
                }
            }) {
            return false
        }
        return true
    }
    return true
}

internal class GradleBuildStatsCompletedAction : FlowAction<GradleBuildStatsCompletedAction.Parameters> {

    interface Parameters : FlowParameters {

        @get:Input
        val buildResult: Property<BuildWorkResult>

        @get:Input
        val pluginConfig: Property<GradleBuildStatsConfig>

        @get:Input
        val startTimeMillis: Property<Long>

        @get:Input
        val taskNamesUnknown: Property<Boolean>

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

        val taskNamesUnknown = parameters.taskNamesUnknown.orNull ?: false
        if (taskNamesUnknown) {
            val taskCompletionService = parameters.taskCompletionService.orNull
            val lastKnownTask = taskCompletionService?.getLastKnownTask()
            val pluginConfig = parameters.pluginConfig.orNull
            if (pluginConfig != null && lastKnownTask != null) {
                if (!isEnabledForTaskNames(listOf(lastKnownTask), pluginConfig)) {
                    logger.info("Plugin disabled for task '$lastKnownTask'")
                    reportWriterService.deleteReport()
                    return
                }
            }
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
