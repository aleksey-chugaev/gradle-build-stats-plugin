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

//    var lastKnownTask: String? = null

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
//            lastKnownTask = event.descriptor.taskPath
        }
    }

//    fun onBuildCompleted() {
//
//    }

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