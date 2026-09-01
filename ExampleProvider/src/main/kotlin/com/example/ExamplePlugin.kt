package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    override fun load(context: Context) {
        // আপনার আগের ৩টি এক্সটেনশন 
        registerMainAPI(AnimeThProvider())
        registerMainAPI(AnimeHDProvider())
        registerMainAPI(AnimeSaltProvider())
        
        // আপনার নতুন এক্সটেনশন
        registerMainAPI(AnimeDriveProvider())
    }
}
