// iTantra Message -- offline peer-to-peer messaging.
//
// The include list is deliberately short. This project has one module (app) because the
// whole application is one app; there is no second library module to extract, and a
// multi-module split would add build time for no structural gain at this size.
//
// Repositories: pluginManagement and dependencyResolutionManagement both point at
// google() and mavenCentral(). Nothing here resolves from a private or corporate mirror,
// so a build is reproducible on any machine with network access to those two hosts.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "iTantraMessage"
include(":app")
