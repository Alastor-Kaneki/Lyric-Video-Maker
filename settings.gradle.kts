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

rootProject.name = "Lyric Video Maker"
include(":app")

val whisperModule = file(".whispercpp/examples/whisper.android/lib")
check(whisperModule.isDirectory) {
    "whisper.cpp is missing. Run scripts/prepare-whisper.sh before building."
}
include(":whispercpp")
project(":whispercpp").projectDir = whisperModule
