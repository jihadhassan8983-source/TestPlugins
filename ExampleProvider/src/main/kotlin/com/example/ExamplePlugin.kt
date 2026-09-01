package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class ExamplePlugin : BasePlugin() {
    override fun load() {
        // তোমার আগের ৩টা (অপরিবর্তিত)
        registerMainAPI(AnimeThProvider())
        registerMainAPI(AnimeHDProvider())
        registerMainAPI(AnimeSaltProvider())

        // নতুন AnimeDrive
        registerMainAPI(AnimeDriveProvider())
    }
}
