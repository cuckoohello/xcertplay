plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shilapi.xcertplay.e01"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shilapi.xcertplay.e01"
        minSdk = 22
        targetSdk = 22
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // E01 is an internal Android 5.1 side-load target, not a Play-distributed application.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":e01shared"))
}
