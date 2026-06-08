plugins {
    alias(libs.plugins.android.application)
}

val debugServerUrl = providers.gradleProperty("WALKING_RITUAL_DEBUG_SERVER_URL")
    .orElse(providers.environmentVariable("WALKING_RITUAL_DEBUG_SERVER_URL"))
    .orElse("http://10.0.2.2:5000")
val releaseServerUrl = providers.gradleProperty("WALKING_RITUAL_SERVER_URL")
    .orElse(providers.environmentVariable("WALKING_RITUAL_SERVER_URL"))
    .orElse("https://replace-with-your-server.example.com")
val releaseServerUrlConfigured =
    providers.gradleProperty("WALKING_RITUAL_SERVER_URL").isPresent ||
        providers.environmentVariable("WALKING_RITUAL_SERVER_URL").isPresent
val naverMapClientId = providers.gradleProperty("NAVER_MAP_CLIENT_ID")
    .orElse(providers.environmentVariable("NAVER_MAP_CLIENT_ID"))
    .orElse("912b6xe87j")
val releaseKeystorePath = providers.gradleProperty("WALKING_RITUAL_KEYSTORE_PATH")
    .orElse(providers.environmentVariable("WALKING_RITUAL_KEYSTORE_PATH"))
val releaseKeystorePassword = providers.gradleProperty("WALKING_RITUAL_KEYSTORE_PASSWORD")
    .orElse(providers.environmentVariable("WALKING_RITUAL_KEYSTORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("WALKING_RITUAL_KEY_ALIAS")
    .orElse(providers.environmentVariable("WALKING_RITUAL_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("WALKING_RITUAL_KEY_PASSWORD")
    .orElse(providers.environmentVariable("WALKING_RITUAL_KEY_PASSWORD"))
val releaseSigningInputs = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val releaseSigningConfigured = releaseSigningInputs.all { it.isPresent }
val releaseSigningPartiallyConfigured =
    releaseSigningInputs.any { it.isPresent } && !releaseSigningConfigured

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release", ignoreCase = true) }) {
        if (!releaseServerUrlConfigured) {
            throw GradleException(
                "Release build requires -PWALKING_RITUAL_SERVER_URL=https://YOUR_SERVER_URL " +
                    "or WALKING_RITUAL_SERVER_URL environment variable."
            )
        }
        if (!releaseServerUrl.get().startsWith("https://")) {
            throw GradleException("Release server URL must start with https://")
        }
        if (releaseSigningPartiallyConfigured) {
            throw GradleException(
                "Release signing requires all of WALKING_RITUAL_KEYSTORE_PATH, " +
                    "WALKING_RITUAL_KEYSTORE_PASSWORD, WALKING_RITUAL_KEY_ALIAS, " +
                    "and WALKING_RITUAL_KEY_PASSWORD."
            )
        }
        if (releaseSigningConfigured && !file(releaseKeystorePath.get()).isFile) {
            throw GradleException("Release keystore file does not exist: ${releaseKeystorePath.get()}")
        }
    }
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["naverMapClientId"] = naverMapClientId.get()
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("releaseUpload") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", "\"${debugServerUrl.get()}\"")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }

        release {
            buildConfigField("String", "SERVER_URL", "\"${releaseServerUrl.get()}\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("com.naver.maps:map-sdk:3.23.2") // 최신 버전 추천
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}
