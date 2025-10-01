plugins {
    alias(libs.plugins.android.application)
}

import java.util.Properties

android {
    namespace = "qing.albatross.manager"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "qing.albatross.manager"
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreProps = Properties()
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { keystoreProps.load(it) }
            }

            val storeFileName = (keystoreProps.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("RELEASE_STORE_FILE")) ?: "qing.jks"
            val storePwd: String? = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAliasValue: String? = keystoreProps.getProperty("RELEASE_KEY_ALIAS")
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPwd: String? = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
                ?: System.getenv("RELEASE_KEY_PASSWORD")

            storeFile = file("../$storeFileName")
            storePassword = storePwd
            keyAlias = keyAliasValue
            keyPassword = keyPwd
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "DAlbatross"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["appName"] = "Albatross"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.valueOf(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.valueOf(libs.versions.javaVersion.get())
    }
    buildFeatures {
    }
}

val releaseApkRename by tasks.registering {
    doLast {
        val versionNameValue = libs.versions.versionName.get()
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        if (!apkDir.exists()) return@doLast
        val candidates = listOf(
            "app-release.apk",
            "app-release-unsigned.apk"
        )
        val dest = layout.buildDirectory.file("outputs/apk/release/AlbatrossManager-$versionNameValue.apk").get().asFile
        for (name in candidates) {
            val src = layout.buildDirectory.file("outputs/apk/release/$name").get().asFile
            if (src.exists()) {
                src.copyTo(dest, overwrite = true)
                src.delete()
                break
            }
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(releaseApkRename)
}

dependencies {
    compileOnly(files("../lib/albatross.jar"))
    implementation(libs.appcompat)
    implementation(libs.material)
//    implementation(libs.core.ktx)
    implementation(libs.constraintlayout)
    // 视图分页和标签布局
    implementation(libs.viewpager2)
    // 卡片视图
    implementation(libs.cardview)
    implementation(libs.recyclerview)
}