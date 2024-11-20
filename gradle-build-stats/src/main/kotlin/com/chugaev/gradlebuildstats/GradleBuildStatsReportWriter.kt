package com.chugaev.gradlebuildstats

import com.chugaev.gradlebuildstats.GradleBuildStatsTaskCompletionService.TaskInfo
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

abstract class GradleBuildStatsReportWriterService : BuildService<GradleBuildStatsReportWriterService.Parameters> {

    interface Parameters : BuildServiceParameters {
        var buildStartTime: LocalDateTime
        var pluginConfig: GradleBuildStatsConfig
    }

    private val buildStatsFileWriter: GradleBuildStatsReportWriter? by lazy {
        GradleBuildStatsReportWriter.createReportWriter(parameters.pluginConfig, parameters.buildStartTime)
    }

    fun initialise(): Boolean {
        logger.debug("initialise")
        return buildStatsFileWriter != null
    }

    fun startReport(taskNames: List<String>, buildStartTime: LocalDateTime) {
        logger.debug("startReport")
        buildStatsFileWriter?.start(taskNames, buildStartTime)
    }

    fun addTask(taskInfo: TaskInfo) {
        buildStatsFileWriter?.addTask(taskInfo)
    }

    fun finish(buildStatus: String, buildDuration: Duration) {
        logger.debug("finish")
        buildStatsFileWriter?.finish(buildStatus, buildDuration)
    }
}

interface GradleBuildStatsReportWriter {

    fun start(taskNames: List<String>, buildStartTime: LocalDateTime)

    fun finish(buildStatus: String, buildDuration: Duration)

    fun addTask(taskInfo: TaskInfo)

    companion object {
        fun createReportWriter(
            pluginConfig: GradleBuildStatsConfig,
            buildStartTime: LocalDateTime
        ): GradleBuildStatsReportWriter? {
            val reportFile = createBuildStatsFile(pluginConfig, buildStartTime)
            return if (reportFile != null) {
                BufferedReportFileWriter(reportFile)
            } else {
                null
            }
        }

        private fun createBuildStatsFile(pluginConfig: GradleBuildStatsConfig, buildStartTime: LocalDateTime): File? {
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

            val buildStatsFileName = DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm-ss").format(buildStartTime)
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

    override fun start(taskNames: List<String>, buildStartTime: LocalDateTime) {
        fileWriter.appendLine("version: 1")
        fileWriter.appendLine("buildTaskNames:")
        taskNames.forEach { taskName ->
            fileWriter.appendLine("- \"$taskName\"")
        }
        fileWriter.appendLine("buildStartTime: ${buildStartTime.toInstant(ZoneOffset.UTC).toEpochMilli()}")
//        fileWriter.appendLine()
    }

    override fun finish(buildStatus: String, buildDuration: Duration) {
//        fileWriter.appendLine()
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
}