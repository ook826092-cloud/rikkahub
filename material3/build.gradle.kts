plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.rerere.material3"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        named("main") {
            kotlin.srcDir("material-color-utilities/kotlin")
        }
    }
}

dependencies {
    // 避免 BOM 覆盖 material3 版本，保持使用明确的 material3 版本（包含 surfaceContainer 等新色彩 token）
    implementation("androidx.compose.material3:material3:1.7.0-alpha03")
    implementation("androidx.compose.ui:ui:1.8.0-alpha07")
    implementation("androidx.compose.ui:ui-graphics:1.8.0-alpha07")
}
