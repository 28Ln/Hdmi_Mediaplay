import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val signingProperties = Properties().apply {
    val propsFile = rootProject.file("signing.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use(::load)
    }
}

fun resolveSigningValue(key: String): String? {
    return providers.gradleProperty(key).orNull
        ?: System.getenv(key)
        ?: signingProperties.getProperty(key)
}

val releaseStoreFilePath = resolveSigningValue("BTF_RELEASE_STORE_FILE")
val releaseStorePassword = resolveSigningValue("BTF_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = resolveSigningValue("BTF_RELEASE_KEY_ALIAS")
val releaseKeyPassword = resolveSigningValue("BTF_RELEASE_KEY_PASSWORD")
val hasReleaseSigning =
    !releaseStoreFilePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    rootProject.file(releaseStoreFilePath).exists()

if (!hasReleaseSigning) {
    logger.warn(
        "Release signing is not configured. Define BTF_RELEASE_* env vars or signing.properties for signed release builds."
    )
}

android {
    namespace = "com.btf.rk3568_hdmi_mediaplay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.btf.rk3568_hdmi_mediaplay"
        minSdk = 30  // Android 11
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Debug版本标识
            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
            buildConfigField("int", "MIN_SDK_VERSION", "30")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Release版本标识
            buildConfigField("boolean", "IS_DEBUG_BUILD", "false")
            buildConfigField("int", "MIN_SDK_VERSION", "30")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // Media3 ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    
    // Coil for image loading
    implementation(libs.coil.compose)
    
    // DataStore for settings
    implementation(libs.datastore.preferences)
    
    // ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    
    // Navigation
    implementation(libs.navigation.compose)
    
    // Coroutines
    implementation(libs.coroutines.android)
    
    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
