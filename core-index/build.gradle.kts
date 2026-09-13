plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.niccc2007.filet.index"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-vfs"))
    // SEARCH.md §2.1: FTS5 is not guaranteed on the system SQLite and the trigram
    // tokenizer needs 3.34+, which the platform library only reaches around API 33.
    implementation(libs.requery.sqlite)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
