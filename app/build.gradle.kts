import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ───────────────────────── 版本号（唯一来源） ─────────────────────────
//
// 版本号只在这里定义一次：应用内「检查更新」把 BuildConfig.VERSION_NAME 与
// 两站 Release 的 tag 比较，而 tag 由发布脚本按同一个值生成 ——
// 若这里与 tag 各写一份，迟早会出现"提示有新版本、装完还是旧版本"。
//
// 发布流程：改下面两个值 → 构建 → 打 tag v<versionName> → 传两站 Release。
val appVersionCode = 19
val appVersionName = "1.4.2"

// ───────────────────────── 发布仓库坐标 ─────────────────────────
//
// 升级检测的数据源：GitHub 与 Gitee 的同名仓库。写进 BuildConfig 而不是散在
// 代码里，换仓库时只改一处。
val repoOwner = "mycode2025-ui"
val repoName = "CastHub"

// 首选源：GITEE 或 GITHUB。
//
// 两个源**都会查**（只看一个会漏判，两站的发布节奏并不一致），这里只决定谁优先：
// 版本信息优先取谁的，以及几 MB 的安装包先从哪个站下。
// 默认 Gitee 的理由是国内网络的实际情况：GitHub 未鉴权接口每出口 IP 每小时仅 60 次
// （实测共享出口上长期为 0，检查直接 403），而且下载时 Gitee 的出口带宽明显更宽裕。
val preferredSource = "GITEE"

// ───────────────────────── 发布签名 ─────────────────────────
//
// keystore.properties 与 .jks **不进版本库**（见 .gitignore）。
// 它不存在时回退到 debug 签名：让 clone 下来的人不必拿到发布私钥也能 assembleRelease，
// 代价是产出的包签名不同、无法覆盖安装到正式版上（README 里写明了这一点）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.casthub.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.casthub.app"
        minSdk = 23
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "UPDATE_REPO_OWNER", "\"$repoOwner\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"$repoName\"")
        buildConfigField("String", "UPDATE_PREFERRED_SOURCE", "\"$preferredSource\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // AGP 8 起 buildConfig 默认关闭；界面要显示版本号、升级检测要读仓库坐标，
    // 就得显式打开
    buildFeatures {
        buildConfig = true
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
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol-dlna"))
    implementation(project(":protocol-airplay"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
