import java.util.Properties

plugins {
    id("com.android.application")
}

// The release key lives outside git: keystore.properties + the .jks it points to (CI restores both from secrets).
val keys = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.plainapk"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.plainapk"
        minSdk = 31 // Android 12: the first version that can update apps without asking
        targetSdk = 36
        // CI sets these from the release tag and the build time, so every build counts up (epoch minutes)
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("VERSION_NAME").orNull ?: "dev"
    }
    signingConfigs {
        if (keys.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
