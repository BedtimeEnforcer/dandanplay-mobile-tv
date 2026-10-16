
plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {
    implementation(project(":common_component"))

    kapt("com.alibaba:arouter-compiler:1.5.2")
}
android {
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
    namespace = "com.xyoye.user_component"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 29

        // 原 buildSrc 的 moduleSetup() 会给所有模块注入 BUILD_COMMIT，
        // 内联后丢失，AboutUsActivity 引用了该字段，这里补回轻享版标记
        buildConfigField("String", "BUILD_COMMIT", "\"lite\"")
    }
}
