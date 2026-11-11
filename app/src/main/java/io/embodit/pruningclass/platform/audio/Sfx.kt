package io.embodit.pruningclass.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.embodit.pruningclass.platform.PcLog
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The four feedback sounds (design `budget.audio`): shears "snip", a soft right-cut chime, a low wrong-cut tone and the
 * "da" of a snap. Rendered once in memory ([Sounds]) and mixed on one long-lived streaming AudioTrack (no repeated native
 * track creation). Muted = nothing is mixed; the volume has three steps. Main thread calls [play]; the mix thread owns
 * the voices.
 */
class Sfx {
    enum class Kind { SNIP, RIGHT, WRONG, SNAP }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var gain = 0.6f
    @Volatile var muted = false
    private val queue = ConcurrentLinkedQueue<Kind>()
    private val sounds = Sounds(RATE)

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t =
            runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(max(minBuf, BLOCK * 2 * 6))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }
                .onFailure { PcLog.e("AUDIO track build failed: $it", it) }
                .getOrNull() ?: return
        track = t
        runCatching { t.play() }
        running = true
        thread = Thread({ loop(t) }, "pruning-sfx").also { it.start() }
        PcLog.i("AUDIO start")
    }

    fun stop() {
        val t = track ?: return
        running = false
        runCatching { thread?.join(300) }
        thread = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
        track = null
        queue.clear()
        PcLog.i("AUDIO stop")
    }

    fun play(kind: Kind) {
        PcLog.i("SOUND ${kind.name.lowercase()} muted=$muted gain=$gain")
        if (!muted && track != null) queue += kind
    }

    private class Voice(val kind: Kind, val s: FloatArray, var at: Int)

    private fun loop(t: AudioTrack) {
        val buf = ShortArray(BLOCK)
        val mix = FloatArray(BLOCK)
        val voices = ArrayList<Voice>()
        while (running) {
            while (true) {
                val k = queue.poll() ?: break
                // the same sound again restarts it instead of stacking a second copy (factory D51)
                voices.removeAll { it.kind == k }
                voices += Voice(k, sounds.of(k), 0)
            }
            java.util.Arrays.fill(mix, 0f)
            val g = gain
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val n = min(BLOCK, v.s.size - v.at)
                for (i in 0 until n) mix[i] += v.s[v.at + i] * g
                v.at += n
                if (v.at >= v.s.size) it.remove()
            }
            for (i in 0 until BLOCK) buf[i] = (limit(mix[i]) * 32767f).toInt().toShort()
            if (runCatching { t.write(buf, 0, BLOCK) }.getOrDefault(-1) < 0) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** The sounds, peak-normalised (snip / snap / right to -1.2 dBFS before the volume step, wrong to -2 dBFS). */
    class Sounds(private val rate: Int) {
        val snip = snip()
        val right = tones(floatArrayOf(880f, 1320f), 0.22f, PEAK)
        val wrong = tones(floatArrayOf(196f, 185f), 0.32f, 0.794f)
        val snap = click()

        fun of(k: Kind) = when (k) { Kind.SNIP -> snip; Kind.RIGHT -> right; Kind.WRONG -> wrong; Kind.SNAP -> snap }

        private fun snip(): FloatArray {
            // two metallic noise bursts (blade closing) 25 ms apart, held long enough to be heard at 1 - 3 m
            val n = (0.13f * rate).toInt()
            val out = FloatArray(n)
            var seed = 0x1234567
            for (i in 0 until n) {
                seed = seed * 1103515245 + 12345
                val white = ((seed ushr 16) and 0x7FFF) / 16384f - 1f
                val t = i.toFloat() / rate
                val e1 = exp(-t / 0.014f)
                val e2 = if (t > 0.025f) exp(-(t - 0.025f) / 0.03f) else 0f
                out[i] = white * (e1 + 0.8f * e2) * 0.6f + sin(2 * PI * 3400.0 * t).toFloat() * (e1 + e2) * 0.4f
            }
            return norm(out, PEAK)
        }

        private fun tones(f: FloatArray, dur: Float, peak: Float): FloatArray {
            val n = (dur * rate).toInt()
            val out = FloatArray(n)
            for (i in 0 until n) {
                val t = i.toFloat() / rate
                val half = if (t < dur / 2f) 0 else 1
                val env = (if (t < 0.008f) t / 0.008f else 1f) * exp(-(t - dur * half / 2f).coerceAtLeast(0f) / 0.09f)
                out[i] = sin(2 * PI * f[half] * t).toFloat() * env
            }
            return norm(out, peak)
        }

        private fun click(): FloatArray {
            val n = (0.06f * rate).toInt()
            return norm(FloatArray(n) { i -> val t = i.toFloat() / rate; sin(2 * PI * 1000.0 * t).toFloat() * exp(-t / 0.02f) }, PEAK)
        }

        private fun norm(a: FloatArray, peak: Float): FloatArray {
            var m = 0f
            for (v in a) m = max(m, kotlin.math.abs(v))
            if (m > 0f) for (i in a.indices) a[i] = a[i] / m * peak
            val fade = min(a.size, (0.002f * rate).toInt())
            for (i in 0 until fade) a[a.size - 1 - i] *= i.toFloat() / fade
            return a
        }
    }

    companion object {
        const val RATE = 48_000

        /** -1.2 dBFS: at the HIGH detent (gain 1) every sound peaks just under -1 dBFS. */
        const val PEAK = 0.871f

        /** The mix never goes past -1 dBFS, however many sounds overlap (factory D51: one bus, limited). */
        const val LIMIT = 0.89f
        private const val KNEE = 0.708f

        /** Soft knee above -3 dBFS, never past [LIMIT] (a hard clip would crackle when sounds overlap). */
        fun limit(x: Float): Float {
            val a = kotlin.math.abs(x)
            if (a <= KNEE) return x
            val over = (a - KNEE) / (LIMIT - KNEE)
            val y = KNEE + (LIMIT - KNEE) * kotlin.math.tanh(over)
            return if (x < 0f) -y else y
        }

        private const val BLOCK = 480
    }
}
