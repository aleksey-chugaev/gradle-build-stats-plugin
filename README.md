# Gradle Build Stats Plugin

A Gradle plugin to track and record tasks that are executed during a Gradle build and the time it takes for tasks to complete. The results are written to a file using YAML format. E.g.

```yaml
version: 1
project: "SampleProjectName"
buildTaskNames:
- ":app:clean"
- ":app:assembleDebug"
buildStartTime: 1732100673107
taskDetails:
- path: ":build-logic:plugins:checkKotlinGradlePluginConfigurationErrors"
  duration: 7
  status: "SUCCESS"
- path: ":build-logic:tools:checkKotlinGradlePluginConfigurationErrors"
  duration: 2
  status: "SUCCESS"
...
buildStatus: "SUCCESS"
buildDuration: 12881
```

Duration fields are in milliseconds.
Date/time fields are in epoch milliseconds.

By default, the files are saved in the project build folder, e.g. `{PROJECT_DIR}/build/reports/gradle-build-stats/2024-11-20--10-49-17-app-assembledebug.yaml`.

## Set up

Update your `settings.gradle.kts` file to include Gradle plugin portal repository for plugin dependencies:

```kotlin
pluginManagement {
  repositories {
    ...
    gradlePluginPortal()
  }
}
```

Add the plugin to your root `build.gradle.kts` file:

```kotlin
plugins {
  id("io.github.aleksey-chugaev.gradlebuildstats") version "0.0.14"
}
```

### Project extension

The plugin can also be disabled via a project extension (e.g. for CI/CD builds):

```kotlin
gradleBuildStats {
    disabled.set(true)
}
```

## Configuration

The plugin can be configured using a properties file. To do so, create a `gradle-build-stats.properties` file in the root project folder. Supported properties are:
- `disabled` - disable the plugin ('true'), by default the plugin is enabled
- `buildStatsHomePath` - a custom path to save build results
- `enabledForTasksWithName` - a comma separated list of task names, if a build task matches any of these tasks - the plugin would be enabled for this build
- `disabledForTasksWithName` - a comma separated list of task names, if a build task matches any of these tasks - the plugin would be disabled for this build

If `enabledForTasksWithName` is specified then `disabledForTasksWithName` is ignored.

Sample file:

```
disabled=false
buildStatsHomePath=/Users/myself/reports/gradle-build-stats
disabledForTasksWithName=clean,prepareKotlinBuildScriptModel
enabledForTasksWithName=
```
