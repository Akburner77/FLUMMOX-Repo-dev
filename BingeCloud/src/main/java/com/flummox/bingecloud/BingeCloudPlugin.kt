package com.flummox.bingecloud

import android.content.Context
import android.os.Build
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BingeCloudPlugin : Plugin() {
    override fun load(context: Context) {
       BingeCloudCtx.context = context
       BCLog.init(context)
       BCLog.setVerbose(Settings.isVerboseLog())
       HostHealth.init(context)
        BCLog.section("BingeCloud boot")
        BCLog.d("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        BCLog.d("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        restoreMbSession()
        com.flummox.bingecore.Prewarm.fire()

        registerMainAPI(BingeCloudProvider())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(GDirect())
        registerExtractorAPI(Filepress())

        this.openSettings = { ctx: Context ->
            Settings.showSettingsDialog(ctx) {
                try { MainActivity.reloadHomeEvent.invoke(true) } catch (_: Exception) {}
            }
        }
    }
}
