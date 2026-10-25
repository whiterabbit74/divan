package com.rdsinteractive.divan.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import com.rdsinteractive.divan.R
import kotlinx.coroutines.runBlocking
import java.util.Locale

/** Divan's language, «Оформление» → «Язык»; [tag] null follows the system. */
enum class AppLanguage(val tag: String?, @StringRes val title: Int) {
    System(null, R.string.language_system),
    English("en", R.string.language_english),
    Russian("ru", R.string.language_russian),
    Chinese("zh-CN", R.string.language_chinese),
}

/**
 * The TV runs Android 12, which has no per-app language: the activity's base context is wrapped
 * with the picked locale instead, and the data layer takes its texts from [text].
 */
object AppLocale {
    @Volatile
    var language: AppLanguage = AppLanguage.System
        private set

    @Volatile
    private var loaded = false

    @Volatile
    private var cache: Pair<AppLanguage, Context>? = null

    /** Reads the stored choice once per process; before the activity's first resource, hence blocking. */
    fun load(context: Context) {
        if (loaded) return
        language = runBlocking { SettingsStore(context).language() }
        loaded = true
        applyDefault()
    }

    fun set(language: AppLanguage) {
        this.language = language
        loaded = true
        cache = null
        applyDefault()
    }

    /** The picked locale, or the system's. */
    fun locale(): Locale = language.tag?.let(Locale::forLanguageTag) ?: Resources.getSystem().configuration.locales[0]

    /**
     * Language asked of the geocoders, one of Divan's own: city names come in it as they are.
     * Chinese is "zh" here; ipwho.is wants "zh-CN" (see [LocationResolver]).
     */
    fun geoLanguage(): String = when (locale().language) {
        "ru" -> "ru"
        "zh" -> "zh"
        else -> "en"
    }

    /**
     * [base] in Divan's language. Only the locale is overridden, so night mode and the like still
     * follow the system: the activity's resources keep the override on every configuration change.
     */
    fun wrap(base: Context): Context {
        val tag = language.tag ?: return base
        return base.createConfigurationContext(Configuration().apply { setLocale(Locale.forLanguageTag(tag)) })
    }

    /** Application context in Divan's language, for texts made outside the UI (settings card, cities). */
    fun text(context: Context): Context {
        val app = context.applicationContext
        cache?.let { (cached, wrapped) -> if (cached == language) return wrapped }
        return wrap(app).also { cache = language to it }
    }

    /**
     * DateUtils and String formats read the default locale; Android resets it to the system's on a
     * configuration change, so the activity applies it again then.
     */
    fun applyDefault() {
        val tag = language.tag
        LocaleList.setDefault(if (tag != null) LocaleList(Locale.forLanguageTag(tag)) else Resources.getSystem().configuration.locales)
    }
}
