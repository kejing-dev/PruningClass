package io.embodit.pruningclass.domain.model

/** Why the app cannot run right now (S16). */
enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED, NO_HEAD_POSE }

enum class LanguagePref { SYSTEM, ZH, EN }

/**
 * Sound volume (design `panels.settings` row 8): the three detents of the settings slider. MID (the default) puts the
 * sounds' active segment at >= -20 dBFS RMS, HIGH keeps every peak <= -1 dBFS (SfxLevelTest, factory loudness rule).
 */
enum class Volume(val gain: Float) { LOW(0.45f), MID(0.75f), HIGH(1.0f) }

/** Everything the user can set (design `persistence.what`). */
data class Settings(
    val budZoom: Boolean = true,
    /** Virtual floor height offset, metres (design `rules.placement.floor`: +/- 5 cm steps). */
    val floorOffset: Float = 0f,
    val seated: Boolean = false,
    val sound: Boolean = true,
    val volume: Volume = Volume.MID,
    val language: LanguagePref = LanguagePref.SYSTEM,
)

/** Result of one lesson attempt as kept on the device. */
data class LessonRecord(val best: Int = -1, val last: Int = -1, val passed: Boolean = false)
