plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    kotlin("kapt")
}

android {
    namespace = "com.relic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.relic"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":catalog"))
    implementation(project(":renderer"))
    implementation(project(":camera"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.android)
    implementation(libs.camera.view)
    implementation(libs.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    // Filter Lab. Used for date-stamp/watermark rasterization and palette
    // extraction; the grading math is ported to GLSL in :catalog. Apache-2.0.
    implementation(libs.filterlibrary)
}
