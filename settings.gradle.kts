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

rootProject.name = "RootMyGalaxy"
include(":app")

// The second APK of the Dirty Frag flow. It is a module of its own rather than a flavour of :app
// because it declares android:sharedUserId="android.uid.system", which is an application-level
// identity that the app install can never carry: adding it to :app would make every build of the app
// a system-uid install, and the inject that earns that identity is made by the install that does not
// have it yet.
include(":dfr")
