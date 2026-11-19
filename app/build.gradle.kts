plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.larus.pdf"
    compileSdk = 36
    // androidx.pdf needs SDK extension 19 for the text/search APIs it wraps.
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "id.larus.pdf"
        // androidx.pdf, which provides text search and selection, requires 28.
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk { debugSymbolLevel = "FULL" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    androidResources { localeFilters += listOf("en", "in") }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.pdf.core)
    // pdf-viewer comes along with pdf-document-service. It looks like pure UI baggage for an
    // all-Compose app, but the sandboxed renderer's AIDL payload types (androidx.pdf.models.*)
    // live in it: excluding it makes the service process die with NoClassDefFoundError on the
    // first getPageDimensions call, which the app sees as DeadObjectException.
    implementation(libs.androidx.pdf.document.service)
    // Split and merge copy page objects rather than re-rendering them, so text, fonts and
    // vectors survive the operation. That is worth the download: Android's own PdfDocument can
    // only draw pages as images.
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // The real org.json, so Recents can be tested against actual parsing rather than the
    // stubbed android.jar version that returns nulls.
    testImplementation(libs.json)
}
