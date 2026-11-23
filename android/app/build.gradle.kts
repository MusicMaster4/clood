plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.slop.clood"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.slop.clood"
        minSdk = 26
        targetSdk = 35

        versionCode = (providers.gradleProperty("cloodVersionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("cloodVersionName").orNull ?: "0.1.0"

        val updateChannel = providers.gradleProperty("cloodChannel").orNull ?: "stable"
        require(updateChannel == "stable" || updateChannel == "testing") {
            "cloodChannel must be stable or testing"
        }
        buildConfigField("String", "UPDATE_CHANNEL", "\"$updateChannel\"")
        buildConfigField("String", "GITHUB_REPO", "\"MusicMaster4/clood\"")
    }

    val releaseKeystore = System.getenv("CLOOD_ANDROID_KEYSTORE")
    val releaseStorePassword = System.getenv("CLOOD_ANDROID_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("CLOOD_ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("CLOOD_ANDROID_KEY_PASSWORD")

    val releaseSigning = if (
        !releaseKeystore.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("cloodRelease") {
            storeFile = file(releaseKeystore)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
