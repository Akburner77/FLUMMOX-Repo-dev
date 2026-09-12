plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.flummox.bingecloud"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        buildConfigField("String", "TMDB_API_KEY", "\"${System.getenv("TMDB_API_KEY") ?: ""}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

cloudstream {
    description = "BingeCloud - Movies & TV Series"
    authors = listOf("FlummoxGamer")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
