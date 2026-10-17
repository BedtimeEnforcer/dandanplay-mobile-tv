plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

// 轻享版新增：电视端 App（独立包名，和手机端两个版本互不影响）
// 定位：只看两件事 —— 本地媒体库播放 + 投屏接收端
android {
    namespace = "com.xyoye.dandanplay.tv"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.lz.dandanplay.tv"
        minSdk = 21
        targetSdk = 29
        versionCode = 62
        versionName = "4.1.4.1"
        multiDexEnabled = true

        // 电视盒子/电视基本都是这两个架构；不分 abi 出单个通用包，装机最省事
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        dataBinding = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", project.name)
    }
}

dependencies {
    implementation(project(":common_component"))
    implementation(project(":player_component"))
    implementation(project(":storage_component"))
    implementation(project(":local_component"))
    implementation(project(":user_component"))

    kapt("com.alibaba:arouter-compiler:1.5.2")
}
