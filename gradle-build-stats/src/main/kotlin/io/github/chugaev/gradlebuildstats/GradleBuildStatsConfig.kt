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

import org.gradle.api.Project
import java.io.Serializable
import java.util.*

private val logger = getLogger("GradleBuildStatsConfig")

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
            }.also {
                logger.debug(it.toString())
            }
        }

        private fun load(properties: Properties, project: Project): GradleBuildStatsConfig {
            logger.debug("Loading config from properties")
            val buildStatsHomePath =
                properties[BUILD_STATS_HOME_PATH_PROP_NAME]?.toString() ?: getBuildStatsHomePath(project)
            val disabled = properties[DISABLED_PROP_NAME]?.toString()?.toBoolean() ?: false
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
            logger.debug("Loading default config")
            val buildStatsHomePath = getBuildStatsHomePath(project)
            return GradleBuildStatsConfig(
                disabled = false,
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