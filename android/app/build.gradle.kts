plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "app.drivr"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.drivr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Everything is free and open: no API keys anywhere.
        // Routing: public FOSSGIS Valhalla (fair use, no SLA). Point this at your own
        // Valhalla instance (docker run ghcr.io/valhalla/valhalla) for real use.
        buildConfigField("String", "VALHALLA_URL", "\"https://valhalla1.openstreetmap.de/route\"")
        // Base map: OpenFreeMap (free, no key, OSM data). To be replaced by Drivr's own
        // lane-level PMTiles + style.
        buildConfigField("String", "MAP_STYLE_URL", "\"https://tiles.openfreemap.org/styles/liberty\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so CI artifacts install directly; swap for a real key before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.ferrostar.core)
    implementation(libs.ferrostar.ui.compose)
    implementation(libs.ferrostar.ui.maplibre)
    implementation(libs.maplibre.compose)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
