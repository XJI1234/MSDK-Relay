package com.skycommand.relay.device.sdk.android

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

/** Installs the DJI runtime before any SDK manager can be accessed. */
class DjiSdkApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Helper.install(this)
    }
}
