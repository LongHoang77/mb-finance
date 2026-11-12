plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.mbfinance.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.mbfinance.tracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.4"
    }

    // Khoá ký cố định (nằm trong repo riêng tư) để mỗi lần build mới có thể cài đè bản cũ
    // mà không phải gỡ app — gỡ app sẽ mất hàng đợi và token đã lưu.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // CI chỉ build bản debug: tắt debuggable để không đọc được dữ liệu app (DB, cookie) qua adb run-as
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
