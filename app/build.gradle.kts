import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.tongluosao.gallerysync"
    // libxposed api/service 102.0.0 的 AAR 元数据要求 compileSdk >= 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tongluosao.gallerysync"
        minSdk = 28
        targetSdk = 36
        // 发布必须升 versionCode：框架按目标进程里的 loadedVersionCode 判断那一代代码旧不旧。
        // 注意它不是"热重载失败"的原因（那是 onPackageReady 里的 detach()），号涨了也照样会被跳过
        versionCode = 45
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Hook 侧依赖：由 LSPosed 框架在运行时提供，只参与编译，不能打进 APK
    compileOnly(libs.libxposed.api)
    // 模块 App 侧依赖：与框架通信，用于 RemotePreferences 配置下发
    implementation(libs.libxposed.service)

    // 配置界面：Miuix 的 androidx.compose 版本由它的 POM 决定，不在此另钉一套
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    // 底栏图标（设置/进度）。不加也能编译，只是 MiuixIcons.Settings 这类扩展属性不存在
    implementation(libs.miuix.icons)
}
