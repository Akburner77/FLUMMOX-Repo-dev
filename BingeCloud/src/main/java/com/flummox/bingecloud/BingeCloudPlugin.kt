/*
 * FLUMMOX Repo — CloudStream 3 Extension Repository
 * Copyright (C) 2026 FlummoxGamer
 * GPL-3.0-or-later
 */

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
        HostHealth.init(context)
        BCLog.section("BingeCloud boot")
        BCLog.d("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        BCLog.d("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        // ── restore MB session from disk (skip bootstrap if token still valid) ──
        restoreMbSession()

        // ── fire TLS/DNS prewarm for known hosts ──
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
