plugins {
    alias(libs.plugins.android.application)
}

layout.buildDirectory.set(
    File(System.getProperty("java.io.tmpdir"), "douyin-immersive-gradle/app")
)

android {
    namespace = "com.codex.douyin.immersive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.douyin.immersive"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "1.3.3"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    testImplementation(libs.libxposed.api)
    testImplementation("junit:junit:4.13.2")
}
