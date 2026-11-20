package com.sad25kag.drakorkita

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DrakorKitaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DrakorKita())

        // Hydrax / Abyss (video_hydrax.php) dan domain turunannya
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(Shortink())
        registerExtractorAPI(Shorticu())
        registerExtractorAPI(AbyssCdn())
        registerExtractorAPI(AbyssTo())
        registerExtractorAPI(PlayHydrax())
    }
}
