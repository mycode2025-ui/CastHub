pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CastHub"

// 协议无关的抽象层：会话模型、模块生命周期、事件总线
include(":core")
// DLNA / UPnP 协议模块（基于 jUPnP —— Cling 的官方继任者）
include(":protocol-dlna")
// AirPlay 协议模块（UxPlay 的 JNI 桥接，native 库缺失时自动降级为不可用）
include(":protocol-airplay")
// 应用外壳：模块装配与 UI
include(":app")
