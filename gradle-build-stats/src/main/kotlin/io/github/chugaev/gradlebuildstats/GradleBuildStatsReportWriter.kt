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

import io.github.chugaev.gradlebuildstats.GradleBuildStatsTaskCompletionService.TaskInfo
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

abstract class GradleBuildStatsReportWriterService : BuildService<GradleBuildStatsReportWriterService.Parameters> {

    interface Parameters : BuildServiceParameters {
        var buildStartTimeMillis: Long
        var pluginConfig: GradleBuildStatsConfig
        var taskNames: List<String>
        var projectName: String
    }

    private val buildStatsFileWriter: GradleBuildStatsReportWriter? by lazy {
        GradleBuildStatsReportWriter.createReportWriter(
            pluginConfig = parameters.pluginConfig,
            buildStartTime = Instant.ofEpochMilli(parameters.buildStartTimeMillis).atZone(ZoneId.systemDefault())
                .toLocalDateTime(),
            projectName = parameters.projectName,
            taskNames = parameters.taskNames
        )
    }

    fun initialise(): Boolean {
        logger.debug("initialise")
        return buildStatsFileWriter != null
    }

    fun startReport(projectName: String, taskNames: List<String>, buildStartTimeMillis: Long) {
        logger.debug("startReport")
        buildStatsFileWriter?.start(projectName, taskNames, buildStartTimeMillis)
    }

    fun addTask(taskInfo: TaskInfo) {
        buildStatsFileWriter?.addTask(taskInfo)
    }

    fun finish(buildStatus: String, buildDuration: Duration) {
        logger.debug("finish")
        buildStatsFileWriter?.finish(buildStatus, buildDuration)
    }

    fun deleteReport() {
        buildStatsFileWriter?.deleteReport()
    }
}

interface GradleBuildStatsReportWriter {

    fun start(projectName: String, taskNames: List<String>, buildStartTimeMillis: Long)

    fun finish(buildStatus: String, buildDuration: Duration)

    fun addTask(taskInfo: TaskInfo)

    fun deleteReport()

    companion object {
        fun createReportWriter(
            pluginConfig: GradleBuildStatsConfig,
            buildStartTime: LocalDateTime,
            projectName: String,
            taskNames: List<String>,
        ): GradleBuildStatsReportWriter? {
            val reportFile = createBuildStatsFile(
                pluginConfig = pluginConfig,
                buildStartTime = buildStartTime,
                projectName = projectName,
                taskNames = taskNames
            )
            return if (reportFile != null) {
                BufferedReportFileWriter(reportFile)
            } else {
                null
            }
        }

        private fun createBuildStatsFile(
            pluginConfig: GradleBuildStatsConfig,
            buildStartTime: LocalDateTime,
            projectName: String,
            taskNames: List<String>,
        ): File? {
            val buildStatsHomeDir = File(pluginConfig.buildStatsHomePath)
            buildStatsHomeDir.mkdirs()
            if (!buildStatsHomeDir.exists()) {
                logger.warn("buildStatsHomeDir not exists $buildStatsHomeDir")
                return null
            }
            if (!buildStatsHomeDir.canWrite()) {
                logger.warn("cannot write to buildStatsHomeDir $buildStatsHomeDir")
                return null
            }

            val buildStatsFileName = buildString {
                append(DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm-ss").format(buildStartTime))
                append("-").append(projectName.lowercase())
                val tasks = taskNames.mapNotNull {
                    it.substringAfterLast(":").lowercase().takeIf { it.isNotEmpty() }
                }.joinToString("-")
                if (tasks.isNotEmpty()) {
                    append("-").append(tasks)
                }
            }
            logger.debug("buildStatsFileName $buildStatsFileName")

            val buildStatsFile = File(buildStatsHomeDir, "$buildStatsFileName.yaml")
            if (!buildStatsFile.createNewFile()) {
                logger.warn("cannot create buildStatsFile $buildStatsFile")
                return null
            }
            return buildStatsFile
        }
    }
}

private class BufferedReportFileWriter(private val file: File) : GradleBuildStatsReportWriter {

    private val fileWriter by lazy {
        BufferedWriter(FileWriter(file, false))
    }

    override fun start(projectName: String, taskNames: List<String>, buildStartTimeMillis: Long) {
        fileWriter.appendLine("version: 1")
        fileWriter.appendLine("project: $projectName")
        fileWriter.appendLine("buildTaskNames:")
        taskNames.forEach { taskName ->
            fileWriter.appendLine("- \"$taskName\"")
        }
        fileWriter.appendLine("buildStartTime: $buildStartTimeMillis")
    }

    override fun finish(buildStatus: String, buildDuration: Duration) {
        fileWriter.appendLine("buildStatus: \"$buildStatus\"")
        fileWriter.appendLine("buildDuration: ${buildDuration.inWholeMilliseconds}")
        fileWriter.close()
    }

    private var isAddingTasks = false

    override fun addTask(taskInfo: TaskInfo) {
        if (!isAddingTasks) {
            fileWriter.appendLine("taskDetails:")
            isAddingTasks = true
        }
        fileWriter.appendLine("- path: \"${taskInfo.taskPath}\"")
        fileWriter.appendLine("  duration: ${taskInfo.duration.inWholeMilliseconds}")
        fileWriter.appendLine("  status: \"${taskInfo.status.describe()}\"")
    }

    override fun deleteReport() {
        fileWriter.close()
        file.delete()
    }
}