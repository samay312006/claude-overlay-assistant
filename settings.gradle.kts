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

rootProject.name = "claude-overlay-assistant"

include(":core")

// The :app module needs the Android SDK and Google's Maven repository. In
// environments that have neither (e.g. CI sandboxes limited to Maven Central),
// skip it so `./gradlew test` can still build and test :core.
val sdkFromEnv = sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    .filterNotNull()
    .any { file(it).exists() }
val sdkFromLocalProps = file("local.properties").takeIf { it.exists() }
    ?.readLines()
    ?.any { it.trim().startsWith("sdk.dir=") } == true

if (sdkFromEnv || sdkFromLocalProps) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found - skipping :app (only :core is configured).")
}
