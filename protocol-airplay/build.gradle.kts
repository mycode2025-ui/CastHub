plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.casthub.airplay"
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
    api(project(":core"))
    implementation("androidx.annotation:annotation:1.7.1")

    // 播放器：AirPlay 投屏拿到的是 HLS / mp4 直链，用和 DLNA 同一套 Media3 解码，
    // 避免为一个协议引入第二套播放栈（解码能力、格式支持都要再调一遍）。
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.0")

    // Apple property list（bplist00 / XML）。
    // 之前这里是一份手写的 bplist 解析器，trailer 字段偏移写错导致 /play 直接越界崩溃。
    // plist 是成熟格式，没有理由自己解析：dd-plist 是 MIT 许可的老牌实现，
    // 也是其它 AirPlay 开源接收端在用的库。
    implementation("com.googlecode.plist:dd-plist:1.30")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
}
