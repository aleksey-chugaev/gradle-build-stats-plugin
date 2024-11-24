package io.github.chugaev.gradlebuildstats

import org.gradle.api.Project
import java.io.Serializable
import java.util.Properties

data class GradleBuildStatsConfig(
    val disabled: Boolean,
    val buildStatsHomePath: String,
    val enabledForTasksWithName: List<String>,
    val disabledForTasksWithName: List<String>,
) : Serializable {

    companion object {
        private const val DISABLED_PROP_NAME = "disabled"
        private const val BUILD_STATS_HOME_PATH_PROP_NAME = "buildStatsHomePath"
        private const val ENABLED_FOR_TASKS_WITH_NAME_PROP_NAME = "enabledForTasksWithName"
        private const val DISABLED_FOR_TASKS_WITH_NAME_PROP_NAME = "disabledForTasksWithName"

        fun readConfig(project: Project): GradleBuildStatsConfig {
            val propertiesFile = project.layout.projectDirectory.file("gradle-build-stats.properties").asFile
            return if (propertiesFile.exists() && propertiesFile.canRead()) {
                val properties = Properties()
                properties.load(
                    propertiesFile.inputStream()
                )
                load(properties, project)
            } else {
                load(project)
            }
        }

        private fun load(properties: Properties, project: Project): GradleBuildStatsConfig {
            val buildStatsHomePath =
                properties[BUILD_STATS_HOME_PATH_PROP_NAME]?.toString() ?: getBuildStatsHomePath(project)
            val disabled = properties[DISABLED_PROP_NAME]?.toString()?.toBoolean() ?: true
            val enabledForTasksWithName =
                properties[ENABLED_FOR_TASKS_WITH_NAME_PROP_NAME]?.toString()?.split(",") ?: emptyList()
            val disabledForTasksWithName =
                properties[DISABLED_FOR_TASKS_WITH_NAME_PROP_NAME]?.toString()?.split(",") ?: emptyList()
            return GradleBuildStatsConfig(
                disabled = disabled,
                buildStatsHomePath = buildStatsHomePath,
                enabledForTasksWithName = enabledForTasksWithName,
                disabledForTasksWithName = disabledForTasksWithName,
            )
        }

        private fun load(project: Project): GradleBuildStatsConfig {
            val buildStatsHomePath = getBuildStatsHomePath(project)
            return GradleBuildStatsConfig(
                disabled = true,
                buildStatsHomePath = buildStatsHomePath,
                enabledForTasksWithName = emptyList(),
                disabledForTasksWithName = emptyList(),
            )
        }

        private fun getBuildStatsHomePath(project: Project): String {
            return project.layout.buildDirectory.dir("reports/gradle-build-stats").get().asFile.path
        }
    }
}