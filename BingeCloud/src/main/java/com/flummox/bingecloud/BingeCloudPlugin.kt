/*
 * FLUMMOX Repo — CloudStream 3 Extension Repository
 * Copyright (C) 2026 FlummoxGamer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
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

        BCLog.section("BingeCloud boot")
        BCLog.d("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        BCLog.d("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        BCLog.d("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")

        registerMainAPI(BingeCloudProvider())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(GDirect())
        registerExtractorAPI(Filepress())

        this.openSettings = { ctx: Context ->
            Settings.showSettingsDialog(ctx) {
                try {
                    MainActivity.reloadHomeEvent.invoke(true)
                } catch (_: Exception) {}
            }
        }
    }
}
