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

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.internal.time.Time
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = getLogger("GradleBuildStatsTaskCompletionService")

abstract class GradleBuildStatsTaskCompletionService : BuildService<GradleBuildStatsTaskCompletionService.Parameters>,
    OperationCompletionListener, AutoCloseable {

    init {
        logger.debug("init")
    }

    interface Parameters : BuildServiceParameters {
        var pluginConfig: GradleBuildStatsConfig
        var taskNames: List<String>
        var projectName: String
    }

    private lateinit var buildStatsFileWriter: GradleBuildStatsReportWriter

    private fun ensureBuildStatsFileWriter(taskInfo: TaskInfo? = null): Boolean {
        if (!::buildStatsFileWriter.isInitialized) {
            synchronized(this) {
                if (!::buildStatsFileWriter.isInitialized) {
                    val buildStartTimeMillis =
                        Time.currentTimeMillis() - (taskInfo?.duration?.inWholeMilliseconds ?: 0L)
                    val buildStartTime =
                        Instant.ofEpochMilli(buildStartTimeMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    logger.debug(
                        "init buildStatsFileWriter, buildStartTime: ${
                            DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm-ss").format(buildStartTime)
                        }"
                    )
                    buildStatsFileWriter = GradleBuildStatsReportWriter.createReportWriter(
                        pluginConfig = parameters.pluginConfig,
                        buildStartTime = buildStartTime,
                        projectName = parameters.projectName,
                        taskNames = parameters.taskNames
                    )?.also {
                        it.start(parameters.projectName, buildStartTimeMillis)
                    } ?: NoOpGradleBuildStatsReportWriter
                }
            }
        }
        return buildStatsFileWriter !is NoOpGradleBuildStatsReportWriter
    }

    private var buildStartTimeMillis: Long = -1
    private var buildDurationMillis: Long = 0
    private var lastKnownTask: String? = null

    fun getBuildStartTime() = buildStartTimeMillis

    override fun onFinish(e: FinishEvent?) {
        (e as? TaskFinishEvent)?.let { event ->
            if (buildStartTimeMillis < 0) {
                buildStartTimeMillis = event.result.startTime
            }
            val status = when (val result = event.result) {
                is TaskSuccessResult -> TaskInfo.TaskStatus.Success(
                    upToDate = result.isUpToDate,
                    fromCache = result.isFromCache
                )

                is TaskSkippedResult -> TaskInfo.TaskStatus.Skipped(skippedMessage = result.skipMessage)
                else -> TaskInfo.TaskStatus.Failed
            }
            val durationMillis = event.result.endTime - event.result.startTime
            val taskInfo = TaskInfo(
                taskPath = event.descriptor.taskPath,
                duration = durationMillis.milliseconds,
                status = status
            )
            if (ensureBuildStatsFileWriter(taskInfo)) {
                buildStatsFileWriter.addTask(taskInfo)
                lastKnownTask = event.descriptor.taskPath
                buildDurationMillis += durationMillis
            }
        }
    }

    fun getFinalBuildTaskNames(): List<String> {
        val tasks = parameters.taskNames.toMutableList()
        lastKnownTask?.let { lastKnownTask ->
            if (tasks.isEmpty()) {
                tasks.add(lastKnownTask)
            }
        }
        return tasks.toList()
    }

    fun finish(buildStatus: String, buildDuration: Duration) {
        logger.debug("finish")
        if (ensureBuildStatsFileWriter()) {
            buildStatsFileWriter.finish(getFinalBuildTaskNames(), buildStatus, buildDurationMillis.milliseconds)
        }
    }

    fun deleteReport() {
        logger.debug("deleteReport")
        if (ensureBuildStatsFileWriter()) {
            buildStatsFileWriter.deleteReport()
        }
    }

    override fun close() {
        logger.debug("close")
    }

    data class TaskInfo(val taskPath: String, val duration: Duration, val status: TaskStatus) {
        sealed interface TaskStatus {
            data class Success(val upToDate: Boolean, val fromCache: Boolean) : TaskStatus
            data class Skipped(val skippedMessage: String?) : TaskStatus
            data object Failed : TaskStatus
        }
    }
}

internal fun GradleBuildStatsTaskCompletionService.TaskInfo.TaskStatus.describe(): String {
    val status = this
    return buildString {
        when (status) {
            GradleBuildStatsTaskCompletionService.TaskInfo.TaskStatus.Failed -> append("FAILED")
            is GradleBuildStatsTaskCompletionService.TaskInfo.TaskStatus.Skipped -> {
                append("SKIPPED")
                if (!status.skippedMessage.isNullOrEmpty()) {
                    append(" ").append(status.skippedMessage)
                }
            }

            is GradleBuildStatsTaskCompletionService.TaskInfo.TaskStatus.Success -> {
                append("SUCCESS")
                if (status.upToDate) {
                    append(" UP-TO-DATE")
                }
                if (status.fromCache) {
                    append(" FROM-CACHE")
                }
            }
        }
    }
}