package com.kowalski.humdinger.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Light or dark colours. The default switches to dark in the evening. */
enum class ThemeMode(val key: String, val label: String) {
    SCHEDULE("schedule", "Automatic: dark from 8pm to 8am"),
    SYSTEM("system", "Same as my phone"),
    LIGHT("light", "Always light"),
    DARK("dark", "Always dark"),
    ;

    companion object {
        /** Dark mode starts at this hour and ends at [DARK_UNTIL] (phone's time). */
        const val DARK_FROM = 20
        const val DARK_UNTIL = 8

        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SCHEDULE
    }
}

/** Which clips the feed shows. */
enum class FeedFilter(val key: String, val label: String, val matches: (MediaType) -> Boolean) {
    ALL("all", "Hums and mimes", { true }),
    HUMS("hums", "Hums only", { it == MediaType.AUDIO }),
    MIMES("mimes", "Mimes only", { it == MediaType.VIDEO }),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: ALL
    }
}

/**
 * Settings that belong to this phone rather than the account (colours, autoplay, feed filter).
 * [init] is called once when the app starts.
 */
object AppPrefs {
    private var prefs: SharedPreferences? = null

    private val _theme = MutableStateFlow(ThemeMode.SCHEDULE)
    private val _autoplay = MutableStateFlow(false)
    private val _feedFilter = MutableStateFlow(FeedFilter.ALL)

    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()
    val feedFilter: StateFlow<FeedFilter> = _feedFilter.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("humdinger_settings", Context.MODE_PRIVATE)
        prefs = p
        _theme.value = ThemeMode.fromKey(p.getString("theme", null))
        _autoplay.value = p.getBoolean("autoplay", false)
        _feedFilter.value = FeedFilter.fromKey(p.getString("feedFilter", null))
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs?.edit()?.putString("theme", mode.key)?.apply()
    }

    fun setAutoplay(on: Boolean) {
        _autoplay.value = on
        prefs?.edit()?.putBoolean("autoplay", on)?.apply()
    }

    fun setFeedFilter(filter: FeedFilter) {
        _feedFilter.value = filter
        prefs?.edit()?.putString("feedFilter", filter.key)?.apply()
    }
}
