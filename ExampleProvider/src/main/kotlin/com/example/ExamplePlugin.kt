package com.example

// import android.content.Context (পুরনো API তে এটা ছিল, এখন আর লাগবে না)
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin // Plugin এর বদলে BasePlugin

@CloudstreamPlugin
class ExamplePlugin : BasePlugin() {
    
    // load() এর ভেতরে এখন আর context: Context থাকে না
    override fun load() {
        // আপনার আগের ৩টি এক্সটেনশন 
        registerMainAPI(AnimeThProvider())
        registerMainAPI(AnimeHDProvider())
        registerMainAPI(AnimeSaltProvider())
        
        // আপনার নতুন এক্সটেনশন 
        registerMainAPI(AnimeDriveProvider())
    }
}
