// 仅作为版本号的唯一真相源；本项目用 build-apk.sh 直接构建，不经过 Gradle。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.changliang.screenon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.changliang.screenon"
        minSdk = 26
        targetSdk = 33
        versionCode = 7
        versionName = "1.1.3"
    }
}
