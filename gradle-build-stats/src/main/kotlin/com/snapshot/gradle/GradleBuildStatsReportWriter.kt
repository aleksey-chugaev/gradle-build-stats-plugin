package com.snapshot.gradle

import com.snapshot.gradle.GradleBuildStatsTaskCompletionService.TaskInfo
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
        logger.log("initialise")
        return buildStatsFileWriter != null
    }

    fun startReport(taskNames: List<String>, buildStartTime: LocalDateTime) {
        logger.log("startReport")
        buildStatsFileWriter?.start(taskNames, buildStartTime)
    }

    fun addTask(taskInfo: TaskInfo) {
        buildStatsFileWriter?.addTask(taskInfo)
    }

    fun finish(buildStatus: String, buildDuration: Duration) {
        logger.log("finish")
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
                logger.log("buildStatsHomeDir not exists $buildStatsHomeDir")
                return null
            }
            if (!buildStatsHomeDir.canWrite()) {
                logger.log("cannot write to buildStatsHomeDir $buildStatsHomeDir")
                return null
            }

            val buildStatsFileName = DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm-ss").format(buildStartTime)
            logger.log("buildStatsFileName $buildStatsFileName")

            val buildStatsFile = File(buildStatsHomeDir, "$buildStatsFileName.dat")
            if (!buildStatsFile.createNewFile()) {
                logger.log("cannot create buildStatsFile $buildStatsFile")
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
        fileWriter.appendLine("build tasks: ${taskNames.joinToString(",")}")
        fileWriter.appendLine("build start time: ${buildStartTime.toInstant(ZoneOffset.UTC).toEpochMilli()}")
        fileWriter.appendLine()
    }

    override fun finish(buildStatus: String, buildDuration: Duration) {
        fileWriter.appendLine()
        fileWriter.appendLine("build status: $buildStatus")
        fileWriter.appendLine("build duration: ${buildDuration.inWholeMilliseconds}")
        fileWriter.close()
    }

    override fun addTask(taskInfo: TaskInfo) {
        fileWriter.appendLine("${taskInfo.taskPath} ${taskInfo.duration.inWholeMilliseconds} ${taskInfo.status.describe()}")
    }
}