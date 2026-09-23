import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.smarttext.wkjm"
    minSdk = 24
    targetSdk = 36

    // --- Version Management (Semantic Versioning) ---
    // versionMajor: Breaking changes or architecture re-writes
    // versionMinor: Feature additions or major enhancements
    // versionPatch: Bug fixes, UI polish, and maintenance
    val versionMajor = 1
    val versionMinor = 0
    val versionPatch = 2

    // Version Code: Numeric counter for Google Play / system package manager
    // Supports CI environment override (e.g., GitHub Actions run_number via -PVERSION_CODE)
    val appVersionCode = project.findProperty("VERSION_CODE")?.toString()?.toIntOrNull()
        ?: project.findProperty("versionCode")?.toString()?.toIntOrNull()
        ?: System.getenv("VERSION_CODE")?.toIntOrNull()
        ?: (versionMajor * 10000 + versionMinor * 100 + versionPatch)

    // Version Name: Human-readable SemVer string (e.g., "1.0.2")
    val appVersionName = project.findProperty("VERSION_NAME")?.toString()
        ?: project.findProperty("versionName")?.toString()
        ?: System.getenv("VERSION_NAME")
        ?: "$versionMajor.$versionMinor.$versionPatch"

    versionCode = appVersionCode
    versionName = appVersionName

    buildConfigField("String", "APP_VERSION_NAME", "\"$appVersionName\"")
    buildConfigField("int", "APP_VERSION_CODE", "$appVersionCode")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }

    create("release") {
      val releaseKeystore = listOf(
        file("release.jks"),
        file("${rootDir}/app/release.jks"),
        file("${rootDir}/release.jks")
      ).firstOrNull { it.exists() && it.length() > 0 }

      if (releaseKeystore != null) {
        storeFile = releaseKeystore
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getenv("STORE_PASSWORD") ?: "android"
        keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
        keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD") ?: "android"
      } else {
        // Fallback to debug keystore credentials so the APK build process never crashes
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val hasReleaseKeystore = listOf(
        file("release.jks"),
        file("${rootDir}/app/release.jks"),
        file("${rootDir}/release.jks")
      ).any { it.exists() && it.length() > 0 }

      signingConfig = if (hasReleaseKeystore) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debugConfig")
      }
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  // Core Android & View Architecture
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.lifecycle.runtime.ktx)

  // Jetpack Compose
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)

  // Local persistence & utilities
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  ksp(libs.androidx.room.compiler)

  // Firebase Firestore, Auth & BoM
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.auth.ktx)

  // Google Credential Manager & Identity
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services.auth)
  implementation(libs.googleid)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  // Unit Testing & Robolectric
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.junit.rule)

  // Android Instrumentation Testing
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
}
