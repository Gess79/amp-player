import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningProblem: String? = when {
    !keystorePropertiesFile.exists() ->
        "keystore.properties not found in the project root (${keystorePropertiesFile.path})."
    signingKeys.any { keystoreProperties.getProperty(it).isNullOrBlank() } ->
        "keystore.properties is missing: " +
            signingKeys.filter { keystoreProperties.getProperty(it).isNullOrBlank() }.joinToString()
    !file(keystoreProperties.getProperty("storeFile")).exists() ->
        "Keystore not found: ${file(keystoreProperties.getProperty("storeFile")).path}"
    else -> null
}

// Release outputs must never be built unsigned; debug builds don't need the keystore.
gradle.taskGraph.whenReady {
    val releaseTasks = setOf("packageRelease", "signReleaseBundle")
    val wantsRelease = allTasks.any { it.project == project && it.name in releaseTasks }
    if (wantsRelease && releaseSigningProblem != null) {
        throw GradleException(
            "Release signing is not configured: $releaseSigningProblem\n" +
                "Copy keystore.properties to the project root and the keystore to the path in " +
                "storeFile, then run the release build again.",
        )
    }
}

android {
    namespace = "io.github.gess79.amp"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.gess79.amp"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.6-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningProblem == null) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
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
                "proguard-rules.pro",
            )
            if (releaseSigningProblem == null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// amp-player-<versionName>.apk  (debug already has versionNameSuffix "-debug")
android.applicationVariants.configureEach {
    outputs.configureEach {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
            .outputFileName = "amp-player-${versionName}.apk"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.google.truth:truth:1.4.4")
}
