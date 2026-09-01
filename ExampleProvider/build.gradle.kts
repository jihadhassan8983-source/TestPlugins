dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 3

cloudstream {
    description = "Egoistrepo - AnimeTH Thai sub and dub"
    authors = listOf("jihadhassan8983-source")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    requiresResources = false         // ← true থেকে false
    language = "th"
    iconUrl = "https://anime-th.com/assets/image/screen.jpg"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
