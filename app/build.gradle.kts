plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.senseart.subsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.senseart.subsync"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.10"
    }

    signingConfigs {
        // Един и същ ключ при всяко компилиране, за да може новата версия
        // да се инсталира върху старата без деинсталиране.
        create("shared") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
                "META-INF/versions/**",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // SMB2/3 клиент за споделени папки на компютъра (Windows, Samba, NAS).
    implementation("com.hierynomus:smbj:0.14.0")
    // Списък на споделените папки на компютъра (SRVSVC през SMB) – за бутона „Търси в мрежата“.
    implementation("com.rapid7.client:dcerpc:0.12.13")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.slf4j:slf4j-nop:2.0.16")
}
