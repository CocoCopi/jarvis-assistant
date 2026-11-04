plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

val jarvisRepo = (project.findProperty("jarvisRepo") as String?) ?: ""
val versionNameOverride = (project.findProperty("versionName") as String?) ?: "0.5.0"
val versionCodeOverride = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

val stagedPython = layout.buildDirectory.dir("generated/python").get().asFile
val stageJarvisPython = tasks.register<Sync>("stageJarvisPython") {
    from(rootProject.projectDir.parentFile.resolve("jarvis")) {
        into("jarvis")
        exclude("__pycache__/**", "**/*.pyc", "**/*.pyo")
    }
    into(stagedPython)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(stageJarvisPython) }
tasks.configureEach {
    if ((name.startsWith("generate") || name.startsWith("merge")) && name.contains("Python")) {
        dependsOn(stageJarvisPython)
    }
}

val signingStore = System.getenv("JARVIS_KEYSTORE")
val signingPassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
val signingAlias = System.getenv("JARVIS_KEY_ALIAS") ?: "jarvis"
val signingKeyPassword = System.getenv("JARVIS_KEY_PASSWORD") ?: signingPassword

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 30
        targetSdk = 36
        versionCode = versionCodeOverride
        versionName = versionNameOverride
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        buildConfigField("String", "JARVIS_REPO", "\"$jarvisRepo\"")
        buildConfigField("String", "BUILD_VERSION", "\"$versionNameOverride\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signingStore != null && File(signingStore).exists()) {
            create("release") {
                storeFile = File(signingStore)
                storePassword = signingPassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

chaquopy {
    defaultConfig {
        version = "3.14"
        buildPython("python3.14")
    }
    sourceSets {
        getByName("main") {
            srcDir("src/main/python")
            srcDir(stagedPython)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
