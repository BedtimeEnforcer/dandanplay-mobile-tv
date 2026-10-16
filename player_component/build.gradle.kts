
plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
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
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("libs")
        }
    }
    namespace = "com.xyoye.player_component"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 29
    }
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {

    implementation(project(":common_component"))
    implementation(project(":repository:panel_switch"))
    implementation(project(":repository:danmaku"))
    implementation(project(":repository:video_cache"))

    implementation("com.github.albfernandez:juniversalchardet:2.4.0")

    // TODO 暂时移除，编译出64位后再考虑重新添加
    //implementation "com.github.ctiao:ndkbitmap-armv7a:0.9.21"

    implementation("com.google.android.exoplayer:exoplayer:2.18.1")
    implementation("com.google.android.exoplayer:exoplayer-core:2.18.1")
    implementation("com.google.android.exoplayer:exoplayer-dash:2.18.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.18.1")
    implementation("com.google.android.exoplayer:exoplayer-smoothstreaming:2.18.1")
    implementation("com.google.android.exoplayer:extension-rtmp:2.18.1")

    implementation("org.videolan.android:libvlc-all:4.0.0-eap9")

    kapt("com.alibaba:arouter-compiler:1.5.2")
}
