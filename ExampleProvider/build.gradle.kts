dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 2

cloudstream {
    description = "Egoistrepo - AnimeTH Thai sub and dub"
    authors = listOf("jihadhassan8983-source")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    requiresResources = true
    language = "th"
    iconUrl = "https://raw.githubusercontent.com/jihadhassan8983-source/TestPlugins/master/logo.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
