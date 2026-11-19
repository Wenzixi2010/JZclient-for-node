plugins {
    id("com.android.application")
}

android {
    namespace = "com.zxi2233.wechatbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zxi2233.wechatbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}

