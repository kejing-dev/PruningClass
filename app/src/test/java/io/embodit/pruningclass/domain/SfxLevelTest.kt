package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.model.Volume
import io.embodit.pruningclass.platform.audio.Sfx
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/** Factory rule 2026-10-29 (profile B20): default peak within -1 dBFS; levels written to build/sounds/levels.txt for ui-audit.md. */
class SfxLevelTest {
    private fun db(x: Float) = 20f * log10(x.coerceAtLeast(1e-6f))

    @Test
    fun peaksStayBelowMinusOneDbfs() {
        val sounds = Sfx.Sounds(48_000)
        val lines = ArrayList<String>()
        for (v in Volume.entries) {
            for (k in Sfx.Kind.entries) {
                val a = sounds.of(k)
                val peak = a.maxOf { abs(it) } * v.gain
                val rms = sqrt(a.sumOf { (it * it).toDouble() } / a.size).toFloat() * v.gain
                lines += "%-5s %-5s peak %6.1f dBFS  rms %6.1f dBFS".format(v.name, k.name, db(peak), db(rms))
                assertTrue("$v $k peak ${db(peak)}", db(peak) <= -1f)
            }
        }
        File("build/sounds").mkdirs()
        File("build/sounds/levels.txt").writeText(lines.joinToString("\n") + "\n")
        lines.forEach(::println)
    }
}
