
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

    implementation("org.jsoup:jsoup:1.15.3")

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
    namespace = "com.xyoye.local_component"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 29
    }
}
