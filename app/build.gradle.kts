import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        val cleaned = keystorePropsFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        load(cleaned.reader())
    }
}

android {
    namespace = "com.tspolice.runtracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tspolice.runtracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("otaRelease") {
            val envStorePath = System.getenv("OTA_KEYSTORE_PATH")
            if (!envStorePath.isNullOrBlank() && file(envStorePath).exists()) {
                storeFile = file(envStorePath)
                storePassword = System.getenv("OTA_STORE_PASSWORD")
                keyAlias = System.getenv("OTA_KEY_ALIAS")
                keyPassword = System.getenv("OTA_KEY_PASSWORD")
            } else if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val otaConfig = signingConfigs.getByName("otaRelease")
            signingConfig = if (otaConfig.storeFile != null && otaConfig.storeFile!!.exists()) {
                otaConfig
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("com.google.android.material:material:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
