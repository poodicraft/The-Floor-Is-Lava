package com.lava.floorislava

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LavaApp : Application() {
    companion object {
        /**
         * Lives as long as the app process, for cloud writes (scores, match
         * results) that must finish even if the screen that started them closes.
         */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
