dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 4

cloudstream {
    description = "Multi anime + UltraMovieDrive providers"
    authors = listOf("jihadhassan8983-source")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "Movie", "TvSeries")
    requiresResources = false
    language = "hi"
    iconUrl = "https://ultramoviedrive.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
