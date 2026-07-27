buildscript {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://storage.googleapis.com/r8-releases/raw")
        }
    }
    dependencies {
        // long: Compose 依赖包含 Kotlin 2.4 metadata；使用官方兼容表要求的 R8，确保 Release 压缩和 Profile 重写不会丢失 Kotlin 元数据。
        classpath("com.android.tools:r8:9.1.29")
    }
}

plugins {
    id("com.android.application") version "8.13.1" apply false
    id("com.android.test") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.7" apply false
    id("androidx.room") version "2.8.4" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
}
