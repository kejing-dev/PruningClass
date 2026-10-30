package io.embodit.pruningclass.data

import android.content.Context
import io.embodit.pruningclass.domain.model.LessonRecord
import io.embodit.pruningclass.domain.model.Settings
import io.embodit.pruningclass.domain.tree.Cut
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.Species

/**
 * Settings, lesson records, graduation, the cuts of the current lesson and the first-run flag in SharedPreferences
 * (design `persistence`). The tree's place in the room is never saved: it is placed by the current head pose each time.
 */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pruning_class", Context.MODE_PRIVATE)

    fun settings(): Settings {
        val d = Settings()
        return Settings(
            budZoom = prefs.getBoolean("budZoom", d.budZoom),
            floorOffset = prefs.getFloat("floorOffset", d.floorOffset),
            seated = prefs.getBoolean("seated", d.seated),
            sound = prefs.getBoolean("sound", d.sound),
            volume = enumOr(prefs.getString("volume", null), d.volume),
            language = enumOr(prefs.getString("language", null), d.language),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putBoolean("budZoom", s.budZoom)
            .putFloat("floorOffset", s.floorOffset)
            .putBoolean("seated", s.seated)
            .putBoolean("sound", s.sound)
            .putString("volume", s.volume.name)
            .putString("language", s.language.name)
            .apply()
    }

    var welcomed: Boolean
        get() = prefs.getBoolean("welcomed", false)
        set(v) = prefs.edit().putBoolean("welcomed", v).apply()

    fun record(l: LessonId): LessonRecord = LessonRecord(prefs.getInt("best_$l", -1), prefs.getInt("last_$l", -1), prefs.getBoolean("passed_$l", false))

    fun saveResult(l: LessonId, percent: Int, pass: Boolean) {
        val r = record(l)
        prefs.edit().putInt("best_$l", maxOf(r.best, percent)).putInt("last_$l", percent).putBoolean("passed_$l", r.passed || pass).apply()
    }

    /** Graduation never goes away (design `rules.judge.pass`). */
    fun graduated(s: Species): Boolean = prefs.getBoolean("grad_$s", false)

    fun graduate(s: Species) = prefs.edit().putBoolean("grad_$s", true).apply()

    fun cuts(l: LessonId): List<Cut> =
        prefs.getString("cuts_$l", "")!!.split(';').filter { it.contains(':') }.mapNotNull { p -> p.split(':').let { (a, b) -> runCatching { Cut(a.toInt(), b.toInt()) }.getOrNull() } }

    fun saveCuts(l: LessonId, cuts: List<Cut>) = prefs.edit().putString("cuts_$l", cuts.joinToString(";") { "${it.branch}:${it.bud}" }).apply()

    /** "Reset all progress": scores, graduation, cuts (settings stay). */
    fun resetProgress() {
        val e = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith("best_") || k.startsWith("last_") || k.startsWith("passed_") || k.startsWith("grad_") || k.startsWith("cuts_")) e.remove(k)
        e.apply()
    }

    fun clearAll() = prefs.edit().clear().apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
