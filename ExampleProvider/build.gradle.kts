dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "MoviezWap Telugu Tamil Hindi movies"
    authors = listOf("jihadhassan8983-source")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = true
    language = "te"
    iconUrl = "https://www.moviezwap.golf/images/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
