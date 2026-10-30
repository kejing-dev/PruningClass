package io.embodit.pruningclass.ui.pc.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.GroundShadowComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import io.embodit.pruningclass.domain.model.Quat4
import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.Branch
import io.embodit.pruningclass.domain.tree.Wood
import io.embodit.pruningclass.platform.PcLog
import io.embodit.pruningclass.ui.theme.PcColors
import kotlin.math.PI
import kotlin.random.Random

/** Plate rotation: an XZ mesh facing +Y turned to face +Z. */
internal val PLATE: Quat4 = Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat())

internal fun model(mesh: MeshResource, material: Material, name: String): Entity = ModelEntity(mesh, material).named(name)

internal fun Entity.show(on: Boolean) {
    if (enabled != on) enabled = on
}

/**
 * Opacity of an entity tree. At 1 the component is removed (factory finding on walk-in orchestra: any opacity component,
 * even at 1.0, sends the entity through blended rendering and the wood looks see-through).
 */
internal fun Entity.fade(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (opacity >= 0.999f) {
        if (existing != null) components.remove(OpacityControllerComponent::class.java)
        return
    }
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

/** Scene-lifetime materials: lit bark (procedural streaks, like the mock-up's `barkTex`), unlit marks with exact design colours. */
internal class PcMaterials(private val shared: SharedResources) {
    private val woods = HashMap<Wood, Material>()

    fun wood(w: Wood): Material = woods.getOrPut(w) { barked(woodColor(w), w.ordinal + 3) }

    // buds are tiny: unlit keeps their exact colour (lit, the key light washed them to pale pink)
    val budRose: Material = unlit(PcColors.BudRose)
    val budApple: Material = unlit(PcColors.BudApple)
    val pot: Material = lit(PcColors.Pot, 0.92f)
    val stand: Material = lit(PcColors.Stand, 0.85f)
    val soil: Material = lit(PcColors.Soil, 1f)
    val leaf: Material = lit(PcColors.Leaf, 0.6f, twoSided = true)
    val bloomRose: Material = unlit(PcColors.BloomRose, twoSided = true)
    val bloomApple: Material = unlit(PcColors.BloomApple, twoSided = true)
    val young: Material = lit(PcColors.Sprout, 0.5f)
    val cap: Material = unlit(PcColors.CutFace, twoSided = true)
    val capOk: Material = unlit(PcColors.Passable, twoSided = true)
    val capOff: Material = unlit(PcColors.Alert, twoSided = true)
    val capWrong: Material = unlit(PcColors.Error, twoSided = true)
    val ring: Material = sheer(PcColors.Interaction, 1f)
    val ringDim: Material = sheer(PcColors.Interaction, 0.25f)
    val ringOk: Material = sheer(PcColors.Passable, 1f)
    val handle: Material = sheer(PcColors.White, 0.12f)
    val grid: Material = sheer(PcColors.White, 0.10f)
    val ladder: Material = sheer(PcColors.White, 0.95f)
    val ladderLine: Material = sheer(PcColors.White, 0.6f)
    val ladderActive: Material = sheer(PcColors.Interaction, 1f)
    val preview: Material = sheer(PcColors.Interaction, 0.6f)
    val scissors: Material = sheer(PcColors.White, 0.5f)
    val hint: Material = unlit(PcColors.Alert)
    val selected: Material = sheer(PcColors.White, 0.55f)
    val wrongGhost: Material = sheer(PcColors.Error, 0.4f)
    val fallenWrong: Material = unlit(PcColors.Error)
    val ghost: Material = sheer(PcColors.Interaction, 0.35f)
    val ghostBad: Material = sheer(PcColors.Error, 0.45f)
    val explainLine: Material = sheer(PcColors.White, 0.6f)
    val handleBar: Material = sheer(PcColors.White, 0.55f)

    private val textures = HashMap<String, Material>()

    /** A text plate (white on charcoal disc or pill) cached by [key]; null when the platform refuses the texture. */
    fun text(key: String, text: String, pill: Boolean): Material? = textures[key] ?: textured(Textures.text(text, pill), key)?.also { textures[key] = it }

    fun testF(): Material? = textures["testF"] ?: textured(Textures.testF(), "testF")?.also { textures["testF"] = it }

    private fun woodColor(w: Wood): Color =
        when (w) {
            Wood.CANE -> PcColors.Cane
            Wood.CANE_WEAK -> PcColors.CaneWeak
            Wood.BARK -> PcColors.Bark
            Wood.YOUNG -> PcColors.Young
            Wood.DEAD -> PcColors.Deadwood
            Wood.DISEASED -> PcColors.Diseased
            Wood.CANKER -> PcColors.Canker
        }

    private fun barked(color: Color, seed: Int): Material =
        runCatching {
                val tex = shared.keep(TextureResource.create(Textures.bark(color, seed)))
                shared.keep(
                    PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                        setBaseColor(Color4(1f, 1f, 1f, 1f))
                        setBaseColorTexture(tex)
                        setMetallic(0f)
                        setRoughness(0.85f)
                        setEmissiveColor(Color4(color.red * 0.05f, color.green * 0.05f, color.blue * 0.05f, 1f))
                    }
                ) as Material
            }
            .onFailure { PcLog.w("TEXTURE bark failed: $it") }
            .getOrElse { lit(color, 0.85f) }

    private fun lit(color: Color, roughness: Float, twoSided: Boolean = false): PhysicallyBasedMaterial =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                setMetallic(0f)
                setRoughness(roughness)
                // a faint self-glow keeps the shadow side readable in a room without ambient light
                setEmissiveColor(Color4(color.red * 0.05f, color.green * 0.05f, color.blue * 0.05f, 1f))
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    private fun unlit(color: Color, twoSided: Boolean = false): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    private fun sheer(color: Color, opacity: Float): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    private fun textured(bitmap: Bitmap, name: String): Material? =
        runCatching {
                val texture = shared.keep(TextureResource.create(bitmap))
                shared.keep(
                    UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                        setBaseColor(Color4(1f, 1f, 1f, 1f))
                        setBaseColorTexture(texture)
                        runCatching { setApplyToneMapping(false) }
                        setCullingMode(MaterialCullingMode.NONE)
                        setDepthWrite(false)
                    }
                ) as Material
            }
            .onFailure { PcLog.w("TEXTURE $name failed: $it") }
            .getOrNull()
}

/** Programmatic bitmaps: bark streaks (the mock-up's recipe), number / label plates, the "F↑" orientation test. */
internal object Textures {
    fun bark(base: Color, seed: Int): Bitmap {
        val b = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(base.toArgb())
        val r = Random(seed)
        val p = Paint()
        val dark = Color(base.red * 0.55f, base.green * 0.5f, base.blue * 0.45f)
        repeat(280) {
            p.color = dark.copy(alpha = 0.1f + r.nextFloat() * 0.2f).toArgb()
            val x = r.nextFloat() * 128f
            val y = r.nextFloat() * 256f
            c.drawRect(x, y, x + 1f + r.nextFloat() * 3f, y + 12f + r.nextFloat() * 60f, p)
        }
        repeat(90) {
            p.color = Color.White.copy(alpha = 0.02f + r.nextFloat() * 0.03f).toArgb()
            val x = r.nextFloat() * 128f
            val y = r.nextFloat() * 256f
            c.drawRect(x, y, x + 1f + r.nextFloat() * 2f, y + 4f + r.nextFloat() * 10f, p)
        }
        return b
    }

    /** White text on a charcoal disc ([pill] = false, numbers) or a rounded plate (labels). */
    fun text(text: String, pill: Boolean): Bitmap {
        val w = if (pill) 512 else 128
        val h = if (pill) 128 else 128
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PcColors.Charcoal.copy(alpha = 0.85f).toArgb() }
        if (pill) c.drawRoundRect(2f, 2f, w - 2f, h - 2f, h / 2f, h / 2f, bg) else c.drawCircle(w / 2f, h / 2f, w / 2f - 2f, bg)
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = if (pill) h * 0.5f else h * 0.6f
            }
        while (p.measureText(text) > w * 0.9f) p.textSize *= 0.92f
        val fm = p.fontMetrics
        c.drawText(text, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }

    fun testF(): Bitmap = text("F↑", pill = false)
}

/** Plate meshes: a disc in XY facing +Z with uv (v from the bottom: factory finding), a 4:1 rounded label quad. */
internal class PcMeshes {
    lateinit var cube: MeshResource
    lateinit var disc: MeshResource
    lateinit var ring: MeshResource
    lateinit var dashRing: MeshResource
    lateinit var plate: MeshResource
    lateinit var label: MeshResource
    lateinit var band: MeshResource
    lateinit var arrow: MeshResource
    lateinit var cylinder: MeshResource

    suspend fun build(shared: SharedResources, pace: suspend () -> Unit) {
        cube = shared.keep(MeshFactory.unitCube("cube"))
        pace()
        disc = shared.keep(MeshFactory.disc(1f, "disc"))
        ring = shared.keep(MeshFactory.ring(0.86f, 1f, "ring", segments = 40))
        pace()
        dashRing = shared.keep(MeshFactory.ring(0.97f, 1f, "dashRing", dashes = 36, dashFill = 0.6f))
        plate = shared.keep(MeshFactory.discUv(0.5f, "plate"))
        pace()
        label = shared.keep(MeshFactory.quadUv(1f, 0.25f, "label"))
        band = shared.keep(MeshFactory.unitBand("band"))
        pace()
        arrow = shared.keep(MeshFactory.arrowHead("arrow"))
        cylinder = shared.keep(MeshFactory.cylinder(1f, 1f, "cylinder", segments = 28))
    }
}

/** Tap bodies along a branch: a chain of capsules, radius max(r + 1.5 cm, 2.5 cm) (design `entities3d.tree`). */
internal fun branchCollider(b: Branch): CollisionComponent {
    val shapes = ArrayList<ShapeResource>()
    var s = 0f
    val step = 0.08f
    while (s < b.length - 0.005f) {
        val e = minOf(b.length, s + step)
        val p0 = b.pointAt(s)
        val p1 = b.pointAt(e)
        val mid = (p0 + p1) * 0.5f
        val dir = (p1 - p0).normalized()
        val r = maxOf(b.radiusAt(s) + 0.015f, 0.025f)
        val h = (p1 - p0).length
        val q = Quat4.fromTo(Vec3.UP, dir)
        runCatching { shapes += ShapeResource.createCapsule(r, h + 2 * r).offsetByTranslationAndRotation(q.sdk(), mid.sdk()) }
        s = e
    }
    return CollisionComponent(collisionShape = shapes, physicsMaterial = PhysicsMaterialResource())
}

/** One selectable branch (or a major one): its wood entities (one per wood) under a holder carrying the tap body. */
internal class BranchView(parent: Entity, val branch: Branch) {
    val holder: Entity = Entity().named("branch:${branch.id}")
    val parts = ArrayList<Entity>()
    private val partWoods = ArrayList<Wood>()
    var cutS: Float? = Float.NaN
    var gone = false
    private var tinted: Material? = null

    init {
        parent.addChild(holder)
        if (branch.selectable) {
            holder.components.set(branchCollider(branch))
            holder.components.set(InteractableComponent())
        }
    }

    fun setParts(meshes: List<Pair<Wood, MeshResource>>, m: PcMaterials) {
        parts.forEach { runCatching { it.destroy(true) } }
        parts.clear()
        partWoods.clear()
        for ((w, mesh) in meshes) {
            val e = model(mesh, tinted ?: m.wood(w), "wood:${branch.id}:$w")
            runCatching { e.components.set(GroundShadowComponent(true, false)) }
            holder.addChild(e)
            parts += e
            partWoods += w
        }
    }

    /** Material override for the whole branch (hint / review orange, selection white), null = its own wood. */
    fun tint(material: Material?, m: PcMaterials) {
        if (material === tinted) return
        tinted = material
        for ((i, e) in parts.withIndex()) e.setMaterial(material ?: m.wood(partWoods[i]))
    }

    fun isGrab(e: Entity): Boolean = holder.enabled && e.id == holder.id

    fun destroy() {
        runCatching { holder.destroy(true) }
    }
}
