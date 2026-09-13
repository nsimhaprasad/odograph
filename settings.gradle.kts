pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Odograph"
include(":app")

// The MG iSMART telematics client lives in its own repo; the app consumes it like a real
// dependency but Gradle substitutes the local checkout so we always build against the
// exact code currently next to us (and its 30 golden tests run every build).
includeBuild("../windsor-telematics")
