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
import org.gradle.internal.time.Time
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

private val logger = getLogger("GradleBuildStatsReportWriterService")

abstract class GradleBuildStatsReportWriterService : BuildService<GradleBuildStatsReportWriterService.Parameters>,
    AutoCloseable {

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
                        "init buildStatsFileWriter ${hashCode()}, buildStartTime: ${
                            DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm-ss").format(buildStartTime)
                        }"
                    )
                    buildStatsFileWriter = GradleBuildStatsReportWriter.createReportWriter(
                        pluginConfig = parameters.pluginConfig,
                        buildStartTime = buildStartTime,
                        projectName = parameters.projectName,
                        taskNames = parameters.taskNames
                    )?.also {
                        it.start(parameters.projectName, parameters.taskNames, buildStartTimeMillis)
                    } ?: NoOpGradleBuildStatsReportWriter
                }
            }
        }
        return buildStatsFileWriter !is NoOpGradleBuildStatsReportWriter
    }

    fun addTask(taskInfo: TaskInfo) {
        if (ensureBuildStatsFileWriter(taskInfo)) {
            buildStatsFileWriter.addTask(taskInfo)
        } else {
            logger.warn("buildStatsFileWriter not initialised (addTask)")
        }
    }

    fun finish(buildStatus: String, buildDuration: Duration) {
        logger.debug("finish")
        if (ensureBuildStatsFileWriter()) {
            buildStatsFileWriter.finish(buildStatus, buildDuration)
        } else {
            logger.warn("buildStatsFileWriter not initialised (finish)")
        }
    }

    fun deleteReport() {
        logger.debug("deleteReport")
        if (ensureBuildStatsFileWriter()) {
            buildStatsFileWriter.deleteReport()
        } else {
            logger.warn("buildStatsFileWriter not initialised (deleteReport)")
        }
    }

    override fun close() {
        logger.debug("close ${hashCode()}")
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
            logger.debug("createBuildStatsFile")
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
                append(".tmp")
            }
            logger.debug("buildStatsFileName: $buildStatsFileName")

            val buildStatsFile = File(buildStatsHomeDir, buildStatsFileName)
            if (!buildStatsFile.createNewFile()) {
                logger.warn("cannot create buildStatsFile $buildStatsFile")
                return null
            }
            return buildStatsFile
        }
    }
}

private data object NoOpGradleBuildStatsReportWriter : GradleBuildStatsReportWriter {
    override fun start(projectName: String, taskNames: List<String>, buildStartTimeMillis: Long) = Unit

    override fun finish(buildStatus: String, buildDuration: Duration) = Unit

    override fun addTask(taskInfo: TaskInfo) = Unit

    override fun deleteReport() = Unit
}

private class BufferedReportFileWriter(private val file: File) : GradleBuildStatsReportWriter {

    private val logger = getLogger("BufferedReportFileWriter")

    init {
        logger.debug("init file: ${file.absolutePath}")
    }

    private val fileWriter by lazy {
        logger.debug("init fileWriter")
        BufferedWriter(FileWriter(file, false))
    }

    override fun start(projectName: String, taskNames: List<String>, buildStartTimeMillis: Long) {
        synchronized(this) {
            logger.debug("start")
            fileWriter.appendLine("version: 1")
            fileWriter.appendLine("project: $projectName")
            fileWriter.appendLine("buildTaskNames:")
            taskNames.forEach { taskName ->
                fileWriter.appendLine("- \"$taskName\"")
            }
            fileWriter.appendLine("buildStartTime: $buildStartTimeMillis")
        }
    }

    override fun finish(buildStatus: String, buildDuration: Duration) {
        synchronized(this) {
            logger.debug("finish")
            fileWriter.appendLine("buildStatus: \"$buildStatus\"")
            fileWriter.appendLine("buildDuration: ${buildDuration.inWholeMilliseconds}")
            fileWriter.close()
            val renameFilePath = file.absolutePath.substringBeforeLast(".") + ".yaml"
            val isSuccess = file.renameTo(File(renameFilePath))
            logger.debug("renameTo result: $isSuccess")
        }
    }

    private var isAddingTasks = false

    override fun addTask(taskInfo: TaskInfo) {
        synchronized(this) {
            logger.debug("addTask ${taskInfo.taskPath} ${taskInfo.duration.inWholeMilliseconds}")
            if (!isAddingTasks) {
                fileWriter.appendLine("taskDetails:")
                isAddingTasks = true
            }
            fileWriter.appendLine("- path: \"${taskInfo.taskPath}\"")
            fileWriter.appendLine("  duration: ${taskInfo.duration.inWholeMilliseconds}")
            fileWriter.appendLine("  status: \"${taskInfo.status.describe()}\"")
        }
    }

    override fun deleteReport() {
        synchronized(this) {
            logger.debug("deleteReport")
            fileWriter.close()
            val isSuccess = file.delete()
            logger.debug("deleteReport result: $isSuccess")
        }
    }
}