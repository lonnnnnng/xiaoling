import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningProperties = Properties().apply {
    val signingFile = rootProject.file("local-signing/endpoint-release.env")
    if (signingFile.exists()) {
        signingFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.longdev.endpointtester"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.longdev.endpointtester"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("releaseLocal") {
            // long: Release 包必须使用固定证书签名，后续版本才能覆盖安装；密钥只从本机未跟踪配置读取，避免把口令或 keystore 提交到仓库。
            storeFile = releaseSigningProperties.getProperty("ENDPOINT_RELEASE_STORE_FILE")
                ?.let { rootProject.file(it) }
            storePassword = releaseSigningProperties.getProperty("ENDPOINT_RELEASE_STORE_PASSWORD")
            keyAlias = releaseSigningProperties.getProperty("ENDPOINT_RELEASE_KEY_ALIAS")
            keyPassword = releaseSigningProperties.getProperty("ENDPOINT_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // long: 调试端点兼容性时保留 HTTP 日志能力，debug 包默认开启，便于通过 logcat 复盘请求和流式事件。
            buildConfigField("boolean", "ENDPOINT_HTTP_LOGS_ENABLED", "true")
        }
        release {
            isMinifyEnabled = false
            // long: release 包默认关闭 HTTP 日志，避免用户的请求内容和模型返回进入生产日志。
            buildConfigField("boolean", "ENDPOINT_HTTP_LOGS_ENABLED", "false")
            signingConfig = signingConfigs.getByName("releaseLocal")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
