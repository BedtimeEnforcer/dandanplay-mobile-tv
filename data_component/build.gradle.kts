
plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    id("kotlin-parcelize")
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.7.21")

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.room:room-runtime:2.4.3")

    api("com.alibaba:arouter-api:1.5.2")
    api("com.squareup.moshi:moshi:1.14.0")

    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.14.0")
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
    namespace = "com.xyoye.data_component"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 29
    }
}
