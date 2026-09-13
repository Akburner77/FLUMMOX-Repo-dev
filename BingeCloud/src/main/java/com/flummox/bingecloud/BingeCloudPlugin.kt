package com.flummox.bingecloud

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BingeCloudPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VegaMoviesProvider())
        registerExtractorAPI(VCloud())
       
    }
}
