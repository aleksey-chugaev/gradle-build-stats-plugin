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

import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.ServiceReference
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

abstract class GradleBuildStatsTaskCompletionService : BuildService<BuildServiceParameters.None>,
    OperationCompletionListener {

    @ServiceReference("com.snapshot.gradle.GradleBuildStatsReportWriterService")
    abstract fun getReportWriterService(): Property<GradleBuildStatsReportWriterService>

    private val reportWriterService: GradleBuildStatsReportWriterService by lazy {
        getReportWriterService().get()
    }

    private var lastKnownTask: String? = null

    override fun onFinish(e: FinishEvent?) {
        (e as? TaskFinishEvent)?.let { event ->
            logger.debug("taskFinished ${event.descriptor.taskPath} ${event.result.endTime - event.result.startTime}")
            val status = when (val result = event.result) {
                is TaskSuccessResult -> TaskInfo.TaskStatus.Success(
                    upToDate = result.isUpToDate,
                    fromCache = result.isFromCache
                )

                is TaskSkippedResult -> TaskInfo.TaskStatus.Skipped(skippedMessage = result.skipMessage)
                else -> TaskInfo.TaskStatus.Failed
            }
            val taskInfo = TaskInfo(
                taskPath = event.descriptor.taskPath,
                duration = (event.result.endTime - event.result.startTime).milliseconds,
                status = status
            )
            reportWriterService.addTask(taskInfo)
            lastKnownTask = event.descriptor.taskPath
        }
    }

    fun getLastKnownTask(): String? {
        return lastKnownTask
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