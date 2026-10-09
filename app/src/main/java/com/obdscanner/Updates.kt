package com.obdscanner

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * A newer release on GitHub. Whenever the app comes on screen ([check], once an hour at most) one HEAD request to
 * releases/latest: GitHub answers with a redirect to the newest tag (…/releases/tag/v3.17), and the version is read
 * from that address. No API: its 60 requests an hour per address run out behind a mobile operator's shared one.
 * Nothing about the phone or the car goes with the request. The last version seen is kept, so the line on Connect
 * stays without a network too. Turned off on Info ([setEnabled]) — no requests at all.
 *
 * A temporary build for one tester ("1.25-kyron", its own branch) never offers a release: the tester moves on when told.
 */
class Updates(private val prefs: SharedPreferences, private val scope: CoroutineScope, private val current: String) {
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ON, true))
    /** The check is on (Info → About). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _newer = MutableStateFlow(shown(prefs.getString(KEY_LATEST, null)))
    /** The release to update to; null while this build is the newest known, or the check is off. */
    val newer: StateFlow<String?> = _newer.asStateFlow()

    @Volatile private var checkedAt = 0L

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ON, on).apply()
        _enabled.value = on
        checkedAt = 0
        _newer.value = shown(prefs.getString(KEY_LATEST, null))
        check()
    }

    fun check() {
        val now = System.currentTimeMillis()
        if (!_enabled.value || !isRelease(current) || now - checkedAt < HOUR) return
        checkedAt = now
        scope.launch {
            val latest = runCatching { latest() }.getOrNull() ?: return@launch
            prefs.edit().putString(KEY_LATEST, latest).apply()
            _newer.value = shown(latest)
        }
    }

    private fun shown(latest: String?): String? = latest?.takeIf { _enabled.value && isRelease(current) && newer(it, current) }

    private fun latest(): String? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "HEAD"
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.responseCode
            return tagVersion(c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val KEY_ON = "update_check"
        private const val KEY_LATEST = "update_latest"
        private const val HOUR = 3_600_000L
        private const val RELEASES = "https://github.com/parhipov/OBDScanner/releases"
        private const val LATEST = "$RELEASES/latest"
        private val NUMBER = Regex("""^\d+(\.\d+)?""")

        /** The release's page: what's new and the APK. */
        fun page(version: String) = "$RELEASES/tag/v$version"

        /** "…/releases/tag/v3.17" → "3.17"; null for anything else (no release yet, a login page). */
        fun tagVersion(location: String?): String? =
            location?.let { Regex("""/releases/tag/v(\d+(?:\.\d+)?)$""").find(it)?.groupValues?.get(1) }

        /** A plain release number ("3.16"), not a tester's build ("1.25-kyron"). */
        fun isRelease(version: String) = NUMBER.matchEntire(version) != null

        /** [latest] is a later release than [current]. Versions go up by 0.01 (3.09, 3.10 …, 3.99, 4.0) — decimals. */
        fun newer(latest: String, current: String): Boolean {
            val a = NUMBER.find(latest)?.value?.toBigDecimal() ?: return false
            val b = NUMBER.find(current)?.value?.toBigDecimal() ?: return false
            return a > b
        }
    }
}
