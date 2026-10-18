import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        val sdk = if (name == "iosArm64") "iphoneos" else "iphonesimulator"
        compilations.getByName("main").cinterops.create("browserHtml") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/browserHtml.def"))
            // long: 使用当前Xcode的系统libxml2，不下载解析器或让Android配置依赖macOS命令。
            if (System.getProperty("os.name").contains("Mac")) {
                val root = providers.exec { commandLine("xcrun", "--sdk", sdk, "--show-sdk-path") }.standardOutput.asText.get().trim()
                compilerOpts("-I$root/usr/include/libxml2")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "com.longdev.xiaoling.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
