plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.casthub.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api("androidx.core:core-ktx:1.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    // 单测类路径上的 org.json 默认是 android.jar 里的**桩实现**（调用即抛 `Stub!`），
    // 只有换上真实的 org.json 实现才测得了 Release JSON 的解析。
    // 注意不能改用 unitTests.isReturnDefaultValues —— 那只会让桩返回默认值，
    // 解析结果照样是空的，测试会"通过"但什么都没验证。
    testImplementation("org.json:json:20231013")
}
