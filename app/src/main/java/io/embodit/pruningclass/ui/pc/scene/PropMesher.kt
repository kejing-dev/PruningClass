package io.embodit.pruningclass.ui.pc.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.embodit.pruningclass.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The rose lesson's plant stand and terracotta pot (QA round 2 q2-3, profile B12 / B24): modelled, not stacked boxes and
 * a plain cylinder. The stand is a 0.40 m oak table: a top with rounded edges, four aprons and four tapered legs with
 * rounded corners; the pot is turned on a lathe: a foot, a tapered wall, a rolled rim, the inner wall down to a domed
 * soil surface. Wood grain, clay and soil are drawn into textures; a soft contact shadow sits under each. Tree-local
 * metres, y up, the floor at 0. Plain CPU work: only [Geo.mesh] touches the engine.
 * (Stand-in until the Tripo models can be generated on this PC: the studio page hangs in Chrome, 2026-11-09.)
 */
internal object PropMesher {
    const val STAND_H = 0.40f
    const val POT_H = 0.30f

    /** Height of the soil surface above the stand top (the canes start there). */
    const val SOIL_Y = 0.272f

    // ---------------------------------------------------------------- stand

    fun stand(): Geo {
        val g = Geo()
        // top 0.40 x 0.032 x 0.40, edges rounded 10 mm
        roundedBox(g, Vec3(0f, STAND_H - 0.016f, 0f), Vec3(0.20f, 0.016f, 0.20f), 0.010f, uvScale = 2.5f)
        // aprons under the top, set 2 cm in
        for (s in listOf(-1f, 1f)) {
            roundedBox(g, Vec3(s * 0.165f, STAND_H - 0.062f, 0f), Vec3(0.010f, 0.030f, 0.150f), 0.004f, uvScale = 4f)
            roundedBox(g, Vec3(0f, STAND_H - 0.062f, s * 0.165f), Vec3(0.150f, 0.030f, 0.010f), 0.004f, uvScale = 4f)
        }
        // legs: 34 mm square under the top tapering to 24 mm at the floor
        for ((x, z) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            taperedLeg(g, Vec3(x * 0.168f, 0f, z * 0.168f), STAND_H - 0.032f, 0.017f, 0.012f, 0.005f)
        }
        return g
    }

    /** A box with every edge and corner rounded by [r] (a grid on each face pushed out round an inner box). */
    private fun roundedBox(g: Geo, c: Vec3, half: Vec3, r: Float, uvScale: Float, n: Int = 6) {
        val inner = Vec3(half.x - r, half.y - r, half.z - r)
        // the six faces: normal axis, sign, the two in-plane axes
        val faces = listOf(
            Triple(0, 1f, 1 to 2), Triple(0, -1f, 2 to 1), Triple(1, 1f, 2 to 0),
            Triple(1, -1f, 0 to 2), Triple(2, 1f, 0 to 1), Triple(2, -1f, 1 to 0),
        )
        val h = floatArrayOf(half.x, half.y, half.z)
        for ((axis, sign, ab) in faces) {
            val (a, b) = ab
            val base = g.pos.size
            for (i in 0..n) for (j in 0..n) {
                val p = FloatArray(3)
                p[axis] = sign * h[axis]
                p[a] = (-1f + 2f * i / n) * h[a]
                p[b] = (-1f + 2f * j / n) * h[b]
                val q = floatArrayOf(p[0].coerceIn(-inner.x, inner.x), p[1].coerceIn(-inner.y, inner.y), p[2].coerceIn(-inner.z, inner.z))
                val d = Vec3(p[0] - q[0], p[1] - q[1], p[2] - q[2])
                val nn = if (d.length < 1e-6f) Vec3(if (axis == 0) sign else 0f, if (axis == 1) sign else 0f, if (axis == 2) sign else 0f) else d.normalized()
                val pos = Vec3(c.x + q[0] + nn.x * r, c.y + q[1] + nn.y * r, c.z + q[2] + nn.z * r)
                g.add(pos, nn, (p[a] + h[a]) * uvScale, (p[b] + h[b]) * uvScale)
            }
            for (i in 0 until n) for (j in 0 until n) {
                val v0 = base + i * (n + 1) + j
                val v1 = v0 + n + 1
                // keep the winding outward whatever the axis order
                if (sign > 0f) { g.tri(v0, v1, v1 + 1); g.tri(v0, v1 + 1, v0 + 1) } else { g.tri(v0, v1 + 1, v1); g.tri(v0, v0 + 1, v1 + 1) }
            }
        }
    }

    /** A square leg from the floor ([foot]) up [h], half-width [top] under the top and [bottom] at the floor, corners rounded. */
    private fun taperedLeg(g: Geo, foot: Vec3, h: Float, top: Float, bottom: Float, r: Float) {
        val rings = 6
        val around = 24
        val base = g.pos.size
        for (k in 0..rings) {
            val t = k / rings.toFloat()
            val y = foot.y + t * h
            val w = bottom + (top - bottom) * t
            for (s in 0..around) {
                // a rounded square: corner arcs of radius r joined by flats
                val ang = 2f * PI.toFloat() * s / around
                val dir = Vec3(cos(ang), 0f, sin(ang))
                val m = max(abs(dir.x), abs(dir.z))
                val sq = Vec3(dir.x / m, 0f, dir.z / m)
                val inner = Vec3(sq.x * (w - r), 0f, sq.z * (w - r))
                val nn = Vec3(sq.x - inner.x / max(w - r, 1e-4f), 0f, sq.z - inner.z / max(w - r, 1e-4f)).let { if (it.length < 1e-4f) dir else it.normalized() }
                val p = Vec3(foot.x + inner.x + nn.x * r, y, foot.z + inner.z + nn.z * r)
                g.add(p, nn, s / around.toFloat(), t * 4f)
            }
        }
        for (k in 0 until rings) for (s in 0 until around) {
            val a = base + k * (around + 1) + s
            val b = a + around + 1
            g.tri(a, b + 1, b)
            g.tri(a, a + 1, b + 1)
        }
    }

    // ---------------------------------------------------------------- pot

    /** Lathe profile of the pot (radius, height from its base), outside up over the rolled rim and down the inner wall. */
    private val potProfile: List<Pair<Float, Float>> =
        buildList {
            add(0.000f to 0.000f)
            add(0.108f to 0.000f) // base
            add(0.114f to 0.006f) // foot chamfer
            add(0.118f to 0.020f)
            add(0.150f to 0.235f) // tapered wall
            add(0.153f to 0.248f)
            // rolled rim: a half round 26 mm tall standing out 12 mm
            for (k in 0..8) {
                val a = -PI.toFloat() / 2f + PI.toFloat() * k / 8f
                add(0.153f + 0.012f * (cos(a) + 0.2f) to 0.261f + 0.013f * (sin(a) + 1f) * 1.0f - 0.0f)
            }
            add(0.150f to 0.300f) // rim top
            add(0.143f to 0.298f) // inner lip
            add(0.140f to SOIL_Y) // inner wall down to the soil
        }

    fun pot(): Geo {
        val g = Geo()
        val around = 40
        // arc length along the profile for v
        val lens = FloatArray(potProfile.size)
        for (i in 1 until potProfile.size) {
            val (r0, y0) = potProfile[i - 1]
            val (r1, y1) = potProfile[i]
            lens[i] = lens[i - 1] + Vec3(r1 - r0, y1 - y0, 0f).length
        }
        for (i in potProfile.indices) {
            val (r, y) = potProfile[i]
            // profile normal: perpendicular to the neighbouring segment(s), pointing out of the clay
            val (ra, ya) = potProfile[max(0, i - 1)]
            val (rb, yb) = potProfile[minOf(potProfile.size - 1, i + 1)]
            val tr = rb - ra
            val ty = yb - ya
            val nr = ty
            val ny = -tr
            val nl = Vec3(nr, ny, 0f).let { if (it.length < 1e-6f) Vec3(0f, -1f, 0f) else it.normalized() }
            for (s in 0..around) {
                val a = 2f * PI.toFloat() * s / around
                val c = cos(a)
                val sn = sin(a)
                g.add(Vec3(r * c, y, r * sn), Vec3(nl.x * c, nl.y, nl.x * sn), s / around.toFloat() * 3f, lens[i] * 6f)
            }
        }
        for (i in 0 until potProfile.size - 1) for (s in 0 until around) {
            val a = i * (around + 1) + s
            val b = a + around + 1
            g.tri(a, b + 1, b)
            g.tri(a, a + 1, b + 1)
        }
        return g
    }

    /** The soil: a shallow dome inside the rim, a little uneven. */
    fun soil(): Geo {
        val g = Geo()
        val rings = 6
        val around = 32
        val rnd = Random(11)
        val bump = FloatArray((rings + 1) * (around + 1)) { (rnd.nextFloat() - 0.5f) * 0.004f }
        for (k in 0..rings) {
            val t = k / rings.toFloat()
            val r = 0.141f * t
            for (s in 0..around) {
                val a = 2f * PI.toFloat() * s / around
                val y = SOIL_Y + 0.008f * (1f - t * t) + if (k in 1 until rings) bump[k * (around + 1) + s % around] else 0f
                g.add(Vec3(r * cos(a), y, r * sin(a)), Vec3(0f, 1f, 0f), 0.5f + 0.5f * t * cos(a), 0.5f + 0.5f * t * sin(a))
            }
        }
        for (k in 0 until rings) for (s in 0 until around) {
            val a = k * (around + 1) + s
            val b = a + around + 1
            g.tri(a, b, b + 1)
            g.tri(a, b + 1, a + 1)
        }
        return g
    }

    /** A flat disc of radius 1 facing up with planar uv (0..1 across), for the contact shadows. */
    fun shadowDisc(): Geo {
        val g = Geo()
        val around = 40
        val c = g.add(Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f), 0.5f, 0.5f)
        for (s in 0..around) {
            val a = 2f * PI.toFloat() * s / around
            g.add(Vec3(cos(a), 0f, sin(a)), Vec3(0f, 1f, 0f), 0.5f + 0.5f * cos(a), 0.5f + 0.5f * sin(a))
        }
        for (s in 0 until around) {
            g.tri(c, c + 2 + s, c + 1 + s)
        }
        return g
    }

    // ---------------------------------------------------------------- textures

    /** Light oak: long grain lines with a few knots and medullary flecks. */
    fun oak(): Bitmap {
        val w = 256
        val h = 256
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color(0.78f, 0.62f, 0.43f).toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Random(7)
        repeat(70) {
            val y0 = r.nextFloat() * h
            p.color = Color(0.55f, 0.40f, 0.25f, 0.12f + r.nextFloat() * 0.22f).toArgb()
            p.strokeWidth = 0.6f + r.nextFloat() * 2.2f
            var x = 0f
            var y = y0
            while (x < w) {
                val nx = x + 8f
                val ny = y + (r.nextFloat() - 0.5f) * 1.6f
                c.drawLine(x, y, nx, ny, p)
                x = nx
                y = ny
            }
        }
        repeat(3) {
            val cx = r.nextFloat() * w
            val cy = r.nextFloat() * h
            p.style = Paint.Style.STROKE
            for (k in 1..5) {
                p.color = Color(0.45f, 0.30f, 0.18f, 0.25f).toArgb()
                p.strokeWidth = 1f
                c.drawOval(cx - k * 4f, cy - k * 1.6f, cx + k * 4f, cy + k * 1.6f, p)
            }
            p.style = Paint.Style.FILL
        }
        return b
    }

    /** Terracotta: warm clay, mottled, with faint throwing rings round the pot. */
    fun clay(): Bitmap {
        val w = 256
        val h = 256
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color(0.72f, 0.38f, 0.24f).toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Random(5)
        repeat(900) {
            val light = r.nextBoolean()
            p.color = (if (light) Color(0.86f, 0.58f, 0.42f, 0.10f + r.nextFloat() * 0.12f) else Color(0.48f, 0.22f, 0.13f, 0.08f + r.nextFloat() * 0.12f)).toArgb()
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            val s = 1.5f + r.nextFloat() * 6f
            c.drawOval(x, y, x + s * 1.6f, y + s, p)
        }
        // throwing rings: v runs up the pot
        repeat(18) {
            val y = r.nextFloat() * h
            p.color = Color(0.40f, 0.18f, 0.10f, 0.10f).toArgb()
            c.drawRect(0f, y, w.toFloat(), y + 1f + r.nextFloat(), p)
        }
        return b
    }

    /** Potting soil: dark crumbs, a few lighter grit specks. */
    fun soilTexture(): Bitmap {
        val w = 128
        val b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color(0.20f, 0.14f, 0.10f).toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Random(3)
        repeat(700) {
            p.color = (if (r.nextFloat() < 0.15f) Color(0.55f, 0.48f, 0.40f, 0.6f) else Color(0.10f, 0.07f, 0.05f, 0.5f)).toArgb()
            val x = r.nextFloat() * w
            val y = r.nextFloat() * w
            val s = 0.6f + r.nextFloat() * 2.2f
            c.drawCircle(x, y, s, p)
        }
        return b
    }

    /** Contact shadow: black, densest in the middle, gone at the edge (drawn on a transparent disc). */
    fun shadow(): Bitmap {
        val w = 128
        val b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(w / 2f, w / 2f, w / 2f, intArrayOf(0x8C000000.toInt(), 0x55000000, 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(w / 2f, w / 2f, w / 2f, p)
        return b
    }
}
