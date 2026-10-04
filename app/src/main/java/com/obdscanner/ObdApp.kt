package com.obdscanner

import android.app.Application
import android.content.Context
import android.util.Log
import com.obdscanner.car.CarDb
import com.obdscanner.obd.DtcDb
import kotlin.concurrent.thread

class ObdApp : Application() {
    lateinit var manager: ObdManager
        private set

    override fun onCreate() {
        super.onCreate()
        AppInfo.versionName = BuildConfig.VERSION_NAME
        AppInfo.versionCode = BuildConfig.VERSION_CODE
        CarDb.init({ thread(name = "CarDb", isDaemon = true, block = it) }, { assetTexts("cars") + assetTexts("cars/obdb") }, ::log)
        DtcDb.init({ thread(name = "DtcDb", isDaemon = true, block = it) }, { assetTexts("dtc") }, ::log)
        manager = ObdManager(this)
    }

    /** Every JSON file in an assets folder, by name. */
    private fun assetTexts(dir: String): List<String> = assets.list(dir).orEmpty().filter { it.endsWith(".json") }.sorted().map { f ->
        assets.open("$dir/$f").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun log(text: String, e: Throwable?) {
        if (e != null) Log.e("OBD", text, e) else Log.i("OBD", text)
    }

    companion object {
        fun manager(context: Context) = (context.applicationContext as ObdApp).manager
    }
}
