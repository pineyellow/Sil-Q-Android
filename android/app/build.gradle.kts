plugins { id("com.android.application") }

// Package only game resources, never desktop caches or player files.
val stageGameAssets by tasks.registering(Sync::class) {
    from("../../lib") {
        include("edit/**", "pref/**", "xtra/graf/16x16.bmp", "xtra/tutorial")
        exclude("**/.DS_Store", "**/.gitkeep")
        into("lib")
    }
    from("../../LICENSE.md") { into("lib") }
    from("../vendor/SDL2/LICENSE.txt") {
        into("lib")
        rename { "SDL2-LICENSE.txt" }
    }
    from("../licenses") { into("lib/licenses") }
    into(layout.buildDirectory.dir("generated/gameAssets"))
}

android {
    namespace = "com.pineyellow.silq"
    compileSdk = 36
    ndkVersion = "28.0.13004108"
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "com.pineyellow.silq"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "1.5.1b2.9"
        resValue("string", "app_name", "Sil-Q")
        ndk { abiFilters += listOf("x86_64", "arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Sil-Q Debug")
            externalNativeBuild {
                cmake {
                    // Compile debug support; the in-game preference enables it.
                    arguments += "-DSILQ_DEBUG_TUTORIAL_SKILLS=ON"
                }
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets.getByName("main") {
        assets.srcDir(layout.buildDirectory.dir("generated/gameAssets"))
        java.srcDir("../vendor/SDL2/android-project/app/src/main/java")
    }
}
tasks.named("preBuild") { dependsOn(stageGameAssets) }
