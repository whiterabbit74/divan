plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rdsinteractive.divan"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rdsinteractive.divan"
        minSdk = 31
        targetSdk = 37
        versionCode = 15
        versionName = "0.7.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideloaded build: the debug key is enough to install over adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.datastore.prefs)
    implementation(libs.kotlinx.coroutines)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.tv.material)
}
