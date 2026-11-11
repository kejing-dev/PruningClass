package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.model.Volume
import io.embodit.pruningclass.platform.audio.Sfx
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Factory loudness rules (profile B20, build.md 2026-11-05 "响度要有证据文件" / "只有短促反馈音的应用"): every peak within
 * -1 dBFS at every detent; at the default detent the active segment (the sounds played back to back, silence cut) is
 * >= -20 dBFS RMS. Levels go to build/sounds/levels.txt and the rendered active segment to build/sounds/loudness.wav
 * (rendered from the app's own synthesis at the default gain, not a recording).
 */
class SfxLevelTest {
    private fun db(x: Float) = 20f * log10(x.coerceAtLeast(1e-6f))

    private fun rms(a: FloatArray) = sqrt(a.sumOf { (it * it).toDouble() } / a.size).toFloat()

    private val sounds = Sfx.Sounds(Sfx.RATE)

    /** What a lesson plays most: snips, the snap of the ladder, right / wrong. */
    private val sequence = listOf(Sfx.Kind.SNIP, Sfx.Kind.SNAP, Sfx.Kind.SNIP, Sfx.Kind.RIGHT, Sfx.Kind.SNIP, Sfx.Kind.WRONG, Sfx.Kind.SNIP, Sfx.Kind.SNAP)

    private fun active(v: Volume): FloatArray = sequence.map { k -> FloatArray(sounds.of(k).size) { i -> Sfx.limit(sounds.of(k)[i] * v.gain) } }.reduce { a, b -> a + b }

    @Test
    fun peaksStayBelowMinusOneDbfsAndTheDefaultIsLoudEnough() {
        val lines = ArrayList<String>()
        for (v in Volume.entries) {
            for (k in Sfx.Kind.entries) {
                val a = sounds.of(k)
                val peak = a.maxOf { abs(it) } * v.gain
                lines += "%-5s %-5s peak %6.1f dBFS  rms %6.1f dBFS".format(v.name, k.name, db(peak), db(rms(a) * v.gain))
                assertTrue("$v $k peak ${db(peak)}", db(peak) <= -1f)
            }
            val seg = active(v)
            lines += "%-5s active segment (%s, %.2f s, silence cut) peak %6.1f dBFS  rms %6.1f dBFS".format(v.name, sequence.joinToString(" ") { it.name.lowercase() }, seg.size / Sfx.RATE.toFloat(), db(seg.maxOf { abs(it) }), db(rms(seg)))
        }
        val def = active(Volume.MID)
        assertTrue("default active segment rms ${db(rms(def))}", db(rms(def)) >= -20f)
        assertTrue("default active segment peak ${db(def.maxOf { abs(it) })}", db(def.maxOf { abs(it) }) <= -1f)
        // the snip alone (the sound heard most) also reaches -20 dBFS at the default
        val snip = sounds.of(Sfx.Kind.SNIP)
        assertTrue("default snip rms ${db(rms(snip) * Volume.MID.gain)}", db(rms(snip) * Volume.MID.gain) >= -20f)
        File("build/sounds").mkdirs()
        File("build/sounds/levels.txt").writeText(lines.joinToString("\n") + "\n")
        writeWav(File("build/sounds/loudness.wav"), def)
        lines.forEach(::println)
    }

    /** Different sounds at once (a snip on a wrong cut: snip + wrong + snap) at HIGH never pass -1 dBFS after the limiter. */
    @Test
    fun overlappingSoundsAreLimited() {
        val ks = listOf(Sfx.Kind.SNIP, Sfx.Kind.WRONG, Sfx.Kind.SNAP, Sfx.Kind.RIGHT)
        val n = ks.maxOf { sounds.of(it).size }
        var peak = 0f
        for (i in 0 until n) {
            var m = 0f
            for (k in ks) sounds.of(k).getOrNull(i)?.let { m += it * Volume.HIGH.gain }
            peak = maxOf(peak, abs(Sfx.limit(m)))
        }
        assertTrue("overlap peak ${db(peak)}", db(peak) <= -1f)
    }

    private fun writeWav(f: File, a: FloatArray) {
        DataOutputStream(FileOutputStream(f)).use { o ->
            fun le32(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            o.writeBytes("RIFF"); le32(36 + a.size * 2); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1)
            le32(Sfx.RATE); le32(Sfx.RATE * 2); le16(2); le16(16); o.writeBytes("data"); le32(a.size * 2)
            for (x in a) le16((x.coerceIn(-1f, 1f) * 32767f).toInt())
        }
    }
}
