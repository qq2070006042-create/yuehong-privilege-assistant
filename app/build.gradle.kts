import java.util.Properties

val serverProperties = Properties()
val serverPropertiesFile = rootProject.file("server.properties")
if (serverPropertiesFile.exists()) {
    serverPropertiesFile.inputStream().use(serverProperties::load)
}

fun serverProperty(name: String): String =
    serverProperties.getProperty(name, "").trim()

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "in.hridayan.ashell"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "in.hridayan.ashell"
        minSdk = 28
        targetSdk = 37
        versionCode = 63
        versionName = "v8.0.0-shizuku"

        buildConfigField(
            "String",
            "SERVER_ANNOUNCEMENT_ENDPOINT",
            buildConfigString(serverProperty("announcementEndpoint")),
        )
        buildConfigField(
            "String",
            "SERVER_COMPATIBILITY_ENDPOINT",
            buildConfigString(serverProperty("compatibilityEndpoint")),
        )
        buildConfigField(
            "String",
            "SERVER_MODULE_ID",
            buildConfigString(serverProperty("moduleId")),
        )
    }

    signingConfigs {
        create("release") {
            val keystoreProperties = Properties()
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                keystorePropertiesFile.inputStream().use {
                    keystoreProperties.load(it)
                }

                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("月虹提权助手-v8.0.0-release.apk")
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.material3)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
