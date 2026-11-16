pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MyPAI"
// POCKETAGENT_DESKTOP_ONLY=1 builds the Linux and Windows app on a machine without the Android SDK.
if (System.getenv("POCKETAGENT_DESKTOP_ONLY") == null) include(":app")
include(":desktop")
