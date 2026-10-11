import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Shared versioning (version.properties) + release signing, see gradle/android-common.gradle
apply(from = rootProject.file("gradle/android-common.gradle"))

android {
    namespace = "x.vladgba.sms"
    compileSdk = 36

    defaultConfig {
        applicationId = "x.vladgba.sms"
        minSdk = 24
        targetSdk = 36
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        resValues = false
        shaders = false
    }

    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        resources.excludes += listOf(
            "kotlin/**", "kotlin-tooling-metadata.json", "META-INF/*.version", "META-INF/version-control-info.textproto", "META-INF/*.kotlin_module",
            "**/*.kotlin_builtins", "DebugProbesKt.bin", "META-INF/com/android/build/gradle/**"
        )
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

// No dependencies: only the Android framework and the Kotlin stdlib (added by the Kotlin plugin).
