import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val releaseSigningProperties = Properties().apply {
    val signingFile = rootProject.file("local-signing/xiaoling-release.env")
    if (signingFile.exists()) {
        signingFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.longdev.xiaoling"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.longdev.xiaoling"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.1.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("releaseLocal") {
            // long: Release 包必须使用固定证书签名，后续版本才能覆盖安装；密钥只从本机未跟踪配置读取，避免把口令或 keystore 提交到仓库。
            storeFile = releaseSigningProperties.getProperty("XIAOLING_RELEASE_STORE_FILE")
                ?.let { rootProject.file(it) }
            storePassword = releaseSigningProperties.getProperty("XIAOLING_RELEASE_STORE_PASSWORD")
            keyAlias = releaseSigningProperties.getProperty("XIAOLING_RELEASE_KEY_ALIAS")
            keyPassword = releaseSigningProperties.getProperty("XIAOLING_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // long: 调试上游接口兼容性时保留 HTTP 日志能力，debug 包默认开启，便于通过 logcat 复盘请求和流式事件。
            buildConfigField("boolean", "XIAOLING_HTTP_LOGS_ENABLED", "true")
        }
        release {
            isMinifyEnabled = false
            // long: release 包默认关闭 HTTP 日志，避免用户的请求内容和模型返回进入生产日志。
            buildConfigField("boolean", "XIAOLING_HTTP_LOGS_ENABLED", "false")
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

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.41.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.41.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.41.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")

    ksp("androidx.room:room-compiler:2.8.4")
}
