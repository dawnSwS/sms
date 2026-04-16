import java.util.Properties
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        InputStreamReader(FileInputStream(localPropsFile), Charsets.UTF_8).use { load(it) }
    }
}

val b64Instr = Base64.getEncoder().encodeToString(localProps.getProperty("SYS_INSTR", "").toByteArray(Charsets.UTF_8))
val b64Key = Base64.getEncoder().encodeToString(localProps.getProperty("API_KEY", "").toByteArray(Charsets.UTF_8))

android {
    namespace = "com.example.appcore"
    compileSdk = 35

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "com.example.appcore"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "2.0"
        resourceConfigurations += setOf("zh", "en")
        
        buildConfigField("String", "B64_INSTR", "\"$b64Instr\"")
        buildConfigField("String", "B64_KEY", "\"$b64Key\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}