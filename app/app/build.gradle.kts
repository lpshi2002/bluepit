plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.pitstop"
    compileSdk = 35
    useLibrary("android.car", false)

    defaultConfig {
        applicationId = "app.pitstop"
        // NaviHelper 공식 샘플과 같은 값. 공모전 조건(28 이상)을 만족한다.
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 시연용 APK를 바로 설치할 수 있게 debug 키로 서명한다. 출시용 키는 따로 만든다.
            signingConfig = signingConfigs.getByName("debug")
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
    }
}

dependencies {
    implementation("ai.pleos.playground:SpeechToText:3.3.1") {
        // ON_DEVICE mode uses no server registration or Universal Access credentials.
        exclude(group = "ai.pleos.playground.security", module = "universal-access")
    }
    implementation("ai.pleos.playground:TextToSpeech:3.3.1") {
        // ON_DEVICE mode uses no server registration or Universal Access credentials.
        exclude(group = "ai.pleos.playground.security", module = "universal-access")
    }
    implementation(libs.vehicle.sdk)
    implementation(libs.navi.helper.sdk)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
}
