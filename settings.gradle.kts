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
        // FilterLibrary (Apache-2.0) is not on Maven Central; JitPack builds the
        // tagged 2.0.0 release. See THIRD_PARTY_NOTICES.md.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Relic"
include(":app")
include(":catalog")
include(":renderer")
include(":camera")
include(":core:datastore")
include(":core:designsystem")
