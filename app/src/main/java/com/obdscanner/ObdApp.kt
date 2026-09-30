package com.obdscanner

import android.app.Application
import android.content.Context
import com.obdscanner.car.CarDb
import com.obdscanner.obd.DtcDb

class ObdApp : Application() {
    lateinit var manager: ObdManager
        private set

    override fun onCreate() {
        super.onCreate()
        CarDb.init(this)
        DtcDb.init(this)
        manager = ObdManager(this)
    }

    companion object {
        fun manager(context: Context) = (context.applicationContext as ObdApp).manager
    }
}
