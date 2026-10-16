
plugins {
    id("com.android.library")
    id("kotlin-parcelize")
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
        named("main").configure {
            jniLibs.srcDir("libs")
        }
    }

    defaultConfig {
        minSdk = 21
        targetSdk = 29
        buildConfigField("String", "APPLICATION_ID", "\"com.lz.dandanplay.mobile\"")
    }
    namespace = "com.xyoye.common_component"
    compileSdk = 33
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {
    // 轻享版：移除 LeakCanary。它会在合并后的 Manifest 里额外注册一个 LAUNCHER 入口
    // （leakcanary.internal.activity.LeakLauncherActivity），导致点击桌面图标时
    // 系统弹出「打开方式」选择框，且常驻一个内存泄漏监听通知 —— 自用版不需要。
    // debugImplementation("com.squareup.leakcanary:leakcanary-android:2.10")

    api(project(":data_component"))
    api(project(":repository:seven_zip"))
    api(project(":repository:immersion_bar"))
    api(project(":repository:thunder"))

    api(files("libs/sardine-1.0.2.jar"))
    api(files("libs/simple-xml-2.7.1.jar"))
    implementation(files("libs/mmkv-annotation.jar"))

    api("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.7.21")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    api("androidx.core:core-ktx:1.9.0")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    api("androidx.room:room-ktx:2.4.3")
    api("androidx.constraintlayout:constraintlayout:2.1.4")
    api("androidx.recyclerview:recyclerview:1.2.1")
    api("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    api("androidx.appcompat:appcompat:1.5.1")
    api("androidx.multidex:multidex:2.0.1")
    api("androidx.palette:palette:1.0.0")
    api("androidx.paging:paging-runtime-ktx:3.1.1")
    api("androidx.startup:startup-runtime:1.1.1")
    api("androidx.preference:preference:1.2.0")
    api("androidx.activity:activity-ktx:1.6.1")

    api("com.google.android.material:material:1.8.0-alpha01")
    api("commons-net:commons-net:3.9.0")

    api("com.tencent:mmkv-static:1.2.14")
    implementation("com.tencent.bugly:crashreport:4.1.9")

    api("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")

    api("io.coil-kt:coil:2.2.2")
    api("io.coil-kt:coil-video:2.2.2")
    api("org.nanohttpd:nanohttpd:2.3.1")
    api("com.hierynomus:smbj:0.10.0")
    api("com.rapid7.client:dcerpc:0.10.0")

    api("com.taobao.android:update-main:1.1.14-open")
    api("com.aliyun.ams:alicloud-android-feedback:3.4.0")
    implementation("com.aliyun.ams:alicloud-android-man:1.2.7")

    kapt(files("libs/mmkv-compiler.jar"))
    kapt("androidx.room:room-compiler:2.4.3")
    kapt("com.alibaba:arouter-compiler:1.5.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.7.21")
}
