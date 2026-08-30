plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    description = "Miruro Anime Provider for CloudStream"
    authors = listOf("jihadhassan8983-source")
    repositoryUrl = "https://github.com/jihadhassan8983-source/TestPlugins"
}

android {
    defaultConfig {
        minSdk = 21
    }
}
