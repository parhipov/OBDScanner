package com.obdscanner.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.obdscanner.ObdApp
import com.obdscanner.screen.CarScreen

/**
 * Android Auto's door into the app: the host (the Android Auto app on the phone) binds this service when the phone is
 * on a head unit and the user opens the app there, and asks it for a [Session]. Nothing on the phone starts it. The
 * screen itself is [ValuesScreen]; the values come from the same [com.obdscanner.ObdManager] the phone's screens use.
 */
class CarService : CarAppService() {
    /** Only Google's Android Auto hosts; a debug build takes any host (the desktop head unit emulator). */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = CarSession()
}

/**
 * One connection to a head unit. While it lives, the chosen values are polled at Main's pace
 * ([com.obdscanner.ObdManager.carScreenOn]) and the phone remembers the car screen has run. The tiles' keys and titles
 * are fixed here when the first live values come ([slots]) and shared by the pages: on the car a tile that appears or
 * goes is a new screen, not an update, and the host counts screens while driving.
 */
class CarSession : Session() {
    /** The fixed tiles, null until the first live snapshot. */
    var slots: List<CarScreen.Slot>? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val m = ObdApp.manager(carContext)
        m.carScreenOn(true)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = m.carScreenOn(false)
        })
        return ValuesScreen(carContext, m, this, page = 0)
    }
}
