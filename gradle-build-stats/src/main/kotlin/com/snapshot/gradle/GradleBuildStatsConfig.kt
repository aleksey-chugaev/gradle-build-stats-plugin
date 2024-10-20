package com.snapshot.gradle

import org.gradle.api.Project
import java.io.Serializable
import java.util.Properties

data class GradleBuildStatsConfig(
  val enabled: Boolean,
  val buildStatsHomePath: String,
  val enabledForTasksWithName: List<String>,
  val disabledForTasksWithName: List<String>,
) : Serializable {

  companion object {
    private const val ENABLED_PROP_NAME = "enabled"
    private const val BUILD_STATS_HOME_PATH_PROP_NAME = "buildStatsHomePath"
    private const val ENABLED_FOR_TASKS_WITH_NAME_PROP_NAME = "enabledForTasksWithName"
    private const val DISABLED_FOR_TASKS_WITH_NAME_PROP_NAME = "disabledForTasksWithName"


    fun load(properties: Properties, project: Project): GradleBuildStatsConfig {
      val buildStatsHomePath = properties[BUILD_STATS_HOME_PATH_PROP_NAME]?.toString() ?: getBuildStatsHomePath(project)
      val enabled = properties[ENABLED_PROP_NAME]?.toString()?.toBoolean() ?: true
      val enabledForTasksWithName = properties[ENABLED_FOR_TASKS_WITH_NAME_PROP_NAME]?.toString()?.split(",") ?: emptyList()
      val disabledForTasksWithName = properties[DISABLED_FOR_TASKS_WITH_NAME_PROP_NAME]?.toString()?.split(",") ?: emptyList()
      return GradleBuildStatsConfig(
        enabled = enabled,
        buildStatsHomePath = buildStatsHomePath,
        enabledForTasksWithName = enabledForTasksWithName,
        disabledForTasksWithName = disabledForTasksWithName,
      )
    }

    fun load(project: Project): GradleBuildStatsConfig {
      val buildStatsHomePath = getBuildStatsHomePath(project)
      return GradleBuildStatsConfig(
        enabled = true,
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