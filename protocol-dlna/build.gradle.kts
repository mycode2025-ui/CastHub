plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.casthub.dlna"
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

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core"))

    // ── 关于 DLNA 协议栈的实现选择 ──────────────────────────────────────
    // 最初的方案是引入 Cling（或其继任者 jUPnP）。实际情况：
    //   1. Cling 已停止维护，官方 Maven 源 4thline.org 已下线，Maven Central 亦无产物；
    //   2. jUPnP 的泛型模型（Action<S>、Service<D,S>、StateVariableTypeDetails）
    //      在 Kotlin 下手写 Action 成本极高，且其 Android 适配存在运行时不确定性。
    // 因此本模块改为自研轻量 UPnP 栈（upnp/ 目录）：
    //   SsdpServer + UpnpHttpServer + Soap，共约 600 行，零第三方依赖。
    // 协议行为（device.xml / SCPD / SOAP / SSDP）已用探针在真实设备上验证，
    // 可被夸克网盘等真实发送端发现并推流。分层模型仍与 Cling 一致（DMR / DMC 分离）。

    // 播放器：DLNA 推流后由接收端自行拉流，需要能解 HLS(m3u8)。
    // Android 6.0 原生 MediaPlayer 从 API 26 起才支持 HLS，因此用 Media3(ExoPlayer)。
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")

    implementation("androidx.annotation:annotation:1.7.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
}
