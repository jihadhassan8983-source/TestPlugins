package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class ExamplePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeThProvider())
        registerMainAPI(AnimeHDProvider())
        registerMainAPI(AnimeDriveProvider())
        registerMainAPI(AnimeKaiProvider())
        registerMainAPI(CineFreakProvider())
        registerMainAPI(FlixmetProvider())
    }
}
