package com.zerointerrupt.clicker

import android.app.Application
import android.os.Process
import android.util.Log
import com.zerointerrupt.clicker.automation.LocalRpcServer
import com.zerointerrupt.clicker.shizuku.ShizukuController

class ZeroInterruptApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Shizuku creates the app Application while starting a UserService in the
        // shell/root process. Do not access app-private preferences or start the
        // regular app's Shizuku/RPC bootstrap from that privileged helper process.
        val appUid = applicationInfo.uid
        val processUid = Process.myUid()
        if (!shouldRunAppProcessBootstrap(processUid, appUid)) {
            Log.i(TAG, "Skipping app bootstrap in Shizuku UserService process (uid=$processUid, appUid=$appUid).")
            return
        }

        ShizukuController.initialize(this)
        LocalRpcServer.syncWithSettings(this)
    }

    private companion object {
        const val TAG = "ZeroInterruptApp"
    }
}

internal fun shouldRunAppProcessBootstrap(processUid: Int, appUid: Int): Boolean = processUid == appUid
