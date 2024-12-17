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
import org.gradle.internal.time.Time
import javax.inject.Inject
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private val logger = getLogger("GradleBuildStatsPlugin")

interface GradleBuildStatsPluginExtension {
    val disabled: Property<Boolean>
}

class GradleBuildStatsPlugin @Inject constructor(
    private val registry: BuildEventsListenerRegistry,
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
) : Plugin<Project> {

    override fun apply(project: Project) {
        logger.debug("apply ${hashCode()}")
        val extension = project.extensions.create("gradleBuildStats", GradleBuildStatsPluginExtension::class.java)
        project.afterEvaluate {
            doApply(project, extension)
        }
    }

    private fun doApply(project: Project, extension: GradleBuildStatsPluginExtension) {
        logger.debug("afterEvaluate")
        if (extension.disabled.getOrElse(false)) {
            logger.info("Plugin disabled (via extension)")
            return
        }
        val pluginConfig = readConfig(project)
        if (pluginConfig.disabled) {
            logger.info("Plugin disabled (via config)")
            return
        }
        var taskNames = project.gradle.startParameter.taskNames.mapNotNull { it.takeIf { it.isNotBlank() } }
        if (taskNames.isEmpty()) {
            taskNames = project.defaultTasks
        }
        taskNames = taskNames.filterNot { it.startsWith("--") }.filterNot { it.contains(".") }
        logger.debug("taskNames=$taskNames")
        if (!isEnabledForTaskNames(taskNames, pluginConfig)) {
            logger.info("Plugin disabled for tasks '${taskNames.joinToString()}'")
            return
        }

        val taskTrackerService = project.gradle.sharedServices.registerIfAbsent(
            "com.snapshot.gradle.GradleBuildStatsTaskCompletionService",
            GradleBuildStatsTaskCompletionService::class.java
        ) { spec ->
            spec.parameters.pluginConfig = pluginConfig
            spec.parameters.taskNames = taskNames
            spec.parameters.projectName = project.name
        }
        if (!taskTrackerService.isPresent) {
            logger.warn("Failed to register GradleBuildStatsTaskCompletionService")
            return
        }

        registry.onTaskCompletion(taskTrackerService)

        flowScope.always(GradleBuildStatsCompletedAction::class.java) { spec ->
            spec.parameters.buildResult.set(flowProviders.buildWorkResult)
            spec.parameters.pluginConfig.set(pluginConfig)
            spec.parameters.taskNamesUnknown.set(taskNames.isEmpty())
        }
    }
}

private fun isEnabledForTaskNames(taskNames: List<String>, pluginConfig: GradleBuildStatsConfig): Boolean {
    if (taskNames.isEmpty()) {
        return true
    }
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

    private val logger = getLogger("GradleBuildStatsCompletedAction")

    interface Parameters : FlowParameters {

        @get:Input
        val buildResult: Property<BuildWorkResult>

        @get:Input
        val pluginConfig: Property<GradleBuildStatsConfig>

        @get:Input
        val taskNamesUnknown: Property<Boolean>

        @get:ServiceReference("com.snapshot.gradle.GradleBuildStatsTaskCompletionService")
        val taskCompletionService: Property<GradleBuildStatsTaskCompletionService>
    }

    override fun execute(parameters: Parameters) {
        logger.debug("execute")
        val taskCompletionService = parameters.taskCompletionService.orNull ?: run {
            logger.warn("missing taskCompletionService")
            return
        }

        val buildTaskNames = taskCompletionService.getFinalBuildTaskNames()
        val pluginConfig = parameters.pluginConfig.orNull
        if (pluginConfig != null) {
            if (!isEnabledForTaskNames(buildTaskNames, pluginConfig)) {
                logger.info("Plugin disabled for tasks '$buildTaskNames'")
                taskCompletionService.deleteReport()
                return
            }
        }

        val buildResult = parameters.buildResult.orNull ?: run {
            logger.warn("missing buildResult")
            taskCompletionService.finish("FAILURE", 0L.toDuration(DurationUnit.MILLISECONDS))
            return
        }
        val isBuildSuccess = !buildResult.failure.isPresent

        val status = if (isBuildSuccess) {
            "SUCCESS"
        } else {
            "FAILED"
        }

        val buildStartTimeMillis = taskCompletionService.getBuildStartTime()
        val duration = (Time.currentTimeMillis() - buildStartTimeMillis).toDuration(
            DurationUnit.MILLISECONDS
        )
        taskCompletionService.finish(status, duration)
    }
}
