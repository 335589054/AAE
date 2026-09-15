plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.local.arcaea.apkmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.local.arcaea.apkmanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 固定的发布密钥（随仓库提供），保证任何机器上打出的包签名一致、可以互相覆盖安装更新。
        // 注意：这把密钥只用于本工具自身；导出修改后的 Arcaea 包用的是应用内置的 auto-sign.p12。
        create("releaseKey") {
            storeFile = file("../keystore/release.p12")
            storePassword = "arcaeamod"
            keyAlias = "arcaeaapkmanager"
            keyPassword = "arcaeamod"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug 也使用仓库内的固定密钥，保证任何机器、任何时间打出的包签名一致，
            // 便于用「adb install -r」无缝覆盖升级（不会因签名变化要求卸载重装）。
            signingConfig = signingConfigs.getByName("releaseKey")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("releaseKey")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 调试构建下用它判断是否自动跑自检
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // APK 签名（v1+v2+v3），与 SDK build-tools 内使用的是同一套实现
    implementation("com.android.tools.build:apksig:8.5.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
