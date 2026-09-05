package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class ExamplePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeHDProvider())
        registerMainAPI(AnimeDriveProvider())
        registerMainAPI(CineFreakProvider())
        registerMainAPI(FlixmetProvider())
        registerMainAPI(HiAnimeProvider())
        registerMainAPI(MlsbdProvider())
        registerMainAPI(AniWaves())
    }
}
