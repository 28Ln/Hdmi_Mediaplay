import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sp = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use(::load)
}

android {
    namespace = "com.btf.testcmd"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.btf.testcmd"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    signingConfigs {
        create("rel") {
            storeFile = rootProject.file("app-release.jks")
            storePassword = sp.getProperty("BTF_RELEASE_STORE_PASSWORD")
            keyAlias = sp.getProperty("BTF_RELEASE_KEY_ALIAS")
            keyPassword = sp.getProperty("BTF_RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("rel") }
    }
}
