plugins {
    alias(libs.plugins.android.application)
}

layout.buildDirectory.set(
    File(System.getProperty("java.io.tmpdir"), "douyin-immersive-gradle/app")
)

android {
    namespace = "com.zz.douyin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zz.douyin"
        minSdk = 28
        targetSdk = 35
        versionCode = 17
        versionName = "1.5.1"
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
    implementation(libs.libxposed.service)
    implementation("de.sciss:jump3r:1.0.5")
    testImplementation(libs.libxposed.api)
    testImplementation("junit:junit:4.13.2")
}
