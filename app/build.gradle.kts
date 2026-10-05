plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.rotatebrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.rotatebrowser"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.webkit)

    testImplementation(libs.junit)
}
