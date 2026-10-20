pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "NSPUWidget"
include(":app")

// Standalone emulator-only widget host; no dependency from the production APK.
include(":widgethost")
project(":widgethost").projectDir = file("tools/widget-host")
