// 说明：原项目用 buildSrc + kotlin-dsl 组织构建脚本，但 kotlin-dsl 插件需要联网
// 拉取 kotlin-compiler-embeddable（本机网络不通），因此改为在各模块内联配置。
// 仓库顺序：国内镜像优先，androidx（Room 等）阿里云缺失，回落到官方 google 源。

buildscript {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.3.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.10")
    }
}

allprojects {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}
