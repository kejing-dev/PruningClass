package io.embodit.pruningclass.ui.pc.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.simulation.CollisionCastHitMode
import com.pico.spatial.core.ecs.simulation.CollisionGroup
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.pruningclass.domain.model.PointerRay
import io.embodit.pruningclass.domain.model.Quat4
import io.embodit.pruningclass.domain.model.SurfaceKind
import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.Cut
import io.embodit.pruningclass.domain.tree.Judge
import io.embodit.pruningclass.domain.tree.Label
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.NextYear
import io.embodit.pruningclass.domain.tree.Species
import io.embodit.pruningclass.domain.tree.Status
import io.embodit.pruningclass.domain.tree.Tree
import io.embodit.pruningclass.domain.usecase.Clearance
import io.embodit.pruningclass.domain.usecase.PlaceMachine
import io.embodit.pruningclass.domain.usecase.Placement
import io.embodit.pruningclass.domain.usecase.Upright
import io.embodit.pruningclass.platform.PcGraph
import io.embodit.pruningclass.platform.PcLog
import io.embodit.pruningclass.platform.audio.Sfx
import io.embodit.pruningclass.platform.input.ControllerSample
import io.embodit.pruningclass.platform.sense.PlaneTracker
import io.embodit.pruningclass.platform.tracking.HeadPose
import io.embodit.pruningclass.ui.pc.PcEvent
import io.embodit.pruningclass.ui.pc.PcShared
import io.embodit.pruningclass.ui.pc.PcUiState
import io.embodit.pruningclass.ui.pc.Phase
import io.embodit.pruningclass.ui.pc.TreeCache
import io.embodit.pruningclass.ui.pc.View
import io.embodit.pruningclass.ui.pc.debug.PcProbe
import io.embodit.pruningclass.ui.theme.PcColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: PcScene? = null
}

/** The one per-frame ECS system: floor, placement, the stick, drags, the bud ladder, cuts, next year and the panels. */
class PcFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the lesson tree (one entity per branch, meshes per wood, one bud mesh), the plant ring and its
 * floor handle, the bud ladder, the cut preview, cut caps, falling pieces, review marks, next year's growth, the replant
 * ghost, and where every pre-built panel stands. Compose state arrives through [render]; everything that moves is written
 * in [onFrame]. The user's 3D input (taps on branches / buds / the preview, the stick, drags) goes back as events; nothing
 * here changes the model directly. Mesh work is queued, at most two meshes per frame (factory ANR finding). Main thread only.
 */
class PcScene(private val sink: (PcEvent) -> Unit) : PcProbe.SceneProbe {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    /** Localised words for 3D labels (set by the screen when the language changes). */
    @Volatile var words: (String) -> String = { it }

    private val main = Handler(Looper.getMainLooper())
    private val head = PcGraph.head
    private val controller = PcGraph.controller
    private val planes = PcGraph.planes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var shared: SharedResources? = null
    private var m: PcMaterials? = null
    private var ms: PcMeshes? = null
    private var rig: PanelRig? = null
    private var attached = false
    private var sensing = false
    private var panelsReported = false

    private var ui = PcUiState()
    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var firstHeadMs = NEVER
    private var lastFrameMs = 0L
    private var lastFrameDtMs = 0L
    private var lastHeadSentMs = 0L
    private var blockSinceMs = NEVER
    private var blockSent = false

    // mesh / texture work queue (at most 2 per fast frame, 1 per 0.5 s when the emulator idles)
    private val jobs = ArrayDeque<() -> Unit>()
    private var lastQueueWarnMs = 0L
    private var lastJobMs = 0L

    // ---- the tree
    private var treeRoot: Entity? = null
    private var tree: Tree? = null

    /** The tree's reach this year and next (Clearance.reach), at least the rose's stand and pot: D41 clearances. */
    private var treeReach = 0.5f
    private var treeKey: Pair<LessonId, Int>? = null
    private val branchViews = HashMap<Int, BranchView>()
    private var budEntity: Entity? = null
    private val potParts = ArrayList<Entity>()
    private var treeBuilt = false
    private var appliedCuts: List<Cut>? = null
    private var appliedZoom: Boolean? = null
    private var shownCuts: List<Cut> = emptyList()
    private val caps = ArrayList<Entity>()
    private val fallen = ArrayList<Triple<Entity, Long, Float>>()
    private val wrongGhosts = ArrayList<Entity>()
    private var lastCutCount = 0

    // ---- placement
    private var placed: Upright? = null
    private var ringPose: Upright? = null
    private var spin = 0f
    private var spinFrom = 0f
    private var spinTo = 0f
    private var spinStartMs = NEVER
    private var spinMs = 400f
    private var placeSerialSeen = -1
    private var fadeStartMs = NEVER
    private var placedSent = true
    private var floorY: Float? = null
    private var floorVirtual = false
    private var planesStartMs = NEVER
    private var findingFor = -1
    private var ring: Entity? = null
    private var ringParts = ArrayList<Entity>()
    private var handle: Entity? = null
    private var grid: Entity? = null
    private var ghost: Entity? = null
    private val ghostParts = ArrayList<Entity>()
    private var relocTriggerWas = false
    private var floorCatcher: Entity? = null
    private val placeMachine = PlaceMachine()

    // ---- input
    private var stickAxis = 0 // 0 none, 1 x, 2 y
    private var stickRate = 0f
    private var cursor: Int? = null
    private var cursorArmed = true
    private var triggerWas = false
    private var cursorTapMs = 0L
    private var turningSent = false
    private var movingStickSent = false
    private var drag: DragKind? = null
    private var rayBeam: Entity? = null
    private var rayDot: Entity? = null
    private var rayTriggerWas = false
    private var rayShown = false
    private var lastTapId = -1L
    private var lastTapMs = 0L
    private var lastTapSource = ""
    private var dragPxToM = 0.001f
    private var insideSent = false
    private var rotateSeen = 0
    private var focusSeen = 0
    private var hintSeen = 0
    private var selectedSeen: Int? = null

    // the only drag: the tree by its floor ring (D21: the 3D object's own handle); panels are moved by the system window
    private enum class DragKind { TREE }

    // ---- ladder / preview
    private var ladderRoot: Entity? = null
    private val ladderRings = ArrayList<Entity>()
    private val ladderPlates = ArrayList<Entity>()
    private val ladderLines = ArrayList<Strip>()
    private val ladderBud = ArrayList<Int>()
    private var ladderTag: Entity? = null
    private var ladderWindow = 0
    private var previewDisc: Entity? = null
    private var scissors: Entity? = null

    // ---- next year
    private var nyRoot: Entity? = null
    private var nyKey: String? = null
    private var nyShown: View = View.NOW
    private val nyLabels = ArrayList<Entity>()
    private var nyFadeMs = NEVER

    // ---- Stage panels (explain card, open-window chip)
    private var chipPose: Upright? = null
    private var hintShown = false
    private var explainGrown = false
    private var explainSpin = 0f
    @Volatile private var debugAim: String? = null
    private var rayTargetLogged: String? = null
    @Volatile private var debugPressFrames = 0

    /** Where each ladder ring was last put (debug `ray=ring`). */
    private val ringAt = HashMap<Int, Vec3>()
    private var explainLine: Strip? = null
    private var explainSeen = -1
    private var explainPose: Pair<Upright, Float>? = null
    private var explainInViewMs = NEVER
    private var explainTimeoutSent = -1
    private var logTimes = longArrayOf(1_000L, 3_000L, 5_000L)
    private var logIndex = 0

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h).also { it.autoFace = false }
        }
        startSensing()
        ActiveScene.scene = this
        bindPanels(attachments)
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) PcLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** Head tracking, the controller and the sound stream; plane detection only while a tree is being placed (D24). */
    private fun startSensing() {
        if (sensing) return
        sensing = true
        head.start()
        controller.start()
        PcGraph.sfx.start()
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        head.stop()
        controller.stop()
        stopPlanes()
        PcGraph.sfx.stop()
        hasHead = false
    }

    private fun startPlanes() {
        if (planesStartMs != NEVER) return
        planesStartMs = SystemClock.elapsedRealtime()
        planes.start(PlaneTracker.Listener { _, _, _ -> })
        PcLog.i("FLOOR planes started")
    }

    private fun stopPlanes() {
        if (planesStartMs == NEVER) return
        planesStartMs = NEVER
        planes.stop()
        PcLog.i("FLOOR planes stopped")
    }

    fun onBackground() {
        stopSensing()
        drag = null
        PcLog.i("SENSING stopped (background)")
    }

    fun onForeground() {
        if (stageRoot == null) return
        startSensing()
        PcLog.i("SENSING started (foreground)")
    }

    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val mats = PcMaterials(sh)
        m = mats
        val meshes = PcMeshes()
        meshes.build(sh) { pace() }
        ms = meshes
        // wood materials (bark texture each) one at a time
        for (w in io.embodit.pruningclass.domain.tree.Wood.entries) {
            mats.wood(w)
            pace()
        }
        val tr = Entity().named("treeRoot")
        root.addChild(tr)
        tr.enabled = false
        treeRoot = tr
        ring = Entity().named("plantRing").also { root.addChild(it); it.enabled = false }
        ringParts += model(meshes.dashRing, mats.ring, "ring_dashes").also { ring!!.addChild(it) }
        ringParts += model(meshes.band, mats.ring, "ring_line").also { ring!!.addChild(it) }
        ringParts += model(meshes.arrow, mats.ring, "ring_arrow").also { ring!!.addChild(it) }
        handle = model(meshes.ring, mats.handle, "floorHandle").also {
            it.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createCapsule(0.06f, 0.6f).offsetByRotation(PLATE.sdk())), physicsMaterial = PhysicsMaterialResource()))
            it.components.set(InteractableComponent())
            root.addChild(it)
            it.enabled = false
        }
        grid = model(meshes.dashRing, mats.grid, "virtualFloor").also { root.addChild(it); it.enabled = false }
        ghost = Entity().named("replantGhost").also { g ->
            root.addChild(g)
            ghostParts += model(meshes.ring, mats.ghost, "ghost_ring").also { it.setLocal(scale = Vec3(0.6f, 1f, 0.6f)); g.addChild(it) }
            ghostParts += model(meshes.cylinder, mats.ghost, "ghost_body").also { it.setLocal(scale = Vec3(0.25f, 1.2f, 0.25f)); g.addChild(it) }
            g.enabled = false
        }
        floorCatcher = Entity().named("floorCatcher").also {
            it.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(12f, 0.02f, 12f))), physicsMaterial = PhysicsMaterialResource()))
            it.components.set(InteractableComponent())
            root.addChild(it)
            it.enabled = false
        }
        pace()
        ladderRoot = Entity().named("ladder").also { root.addChild(it); it.enabled = false }
        for (i in 0 until LADDER_POOL) {
            val r = model(meshes.ring, mats.ladder, "ladder_ring_$i")
            // the ring is scaled (dia / 2, 1, dia / 2): a flat box, 1.2 x the plate, 3 cm thick (createBox takes half sizes:
            // 2.4 overlapped the next ring and the ray picked the bud above, QA v0.1.3) (a unit sphere stayed 1 m tall
            // in Y and caught rays and taps a metre away from the ladder, v0.1.3 self-test)
            r.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(1.2f, 0.015f, 1.2f))), physicsMaterial = PhysicsMaterialResource()))
            r.components.set(InteractableComponent())
            ladderRoot!!.addChild(r)
            r.enabled = false
            ladderRings += r
            val p = model(meshes.plate, mats.ladder, "ladder_plate_$i")
            ladderRoot!!.addChild(p)
            p.enabled = false
            ladderPlates += p
            ladderLines += Strip(ladderRoot!!, meshes.band, mats.ladderLine, "ladder_line_$i")
            ladderBud += -1
            if (i % 8 == 7) pace()
        }
        ladderTag = model(meshes.label, mats.ladder, "ladder_tag").also { ladderRoot!!.addChild(it); it.enabled = false }
        previewDisc = model(meshes.disc, mats.preview, "preview_disc").also {
            // scaled (r, 1, r) like the ladder rings: a flat box, not a sphere that stays 1.4 m in Y
            it.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(1.4f, 0.015f, 1.4f))), physicsMaterial = PhysicsMaterialResource()))
            it.components.set(InteractableComponent())
            root.addChild(it)
            it.enabled = false
        }
        // the app's own controller ray: the device showed none in this Stage (v0.1.0 device feedback), so the ray is drawn
        // here from the controller pose and the trigger acts on what it hits, like the system pointer
        rayBeam = model(meshes.cube, mats.rayBeam, "controllerRay").also { root.addChild(it); it.enabled = false }
        rayDot = model(meshes.disc, mats.rayDot, "controllerRayDot").also { root.addChild(it); it.enabled = false }
        scissors = Entity().named("scissors").also { s ->
            root.addChild(s)
            for (k in 0 until 2) s.addChild(model(meshes.cube, mats.scissors, "blade_$k").also { it.setLocal(rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), (if (k == 0) 0.45f else -0.45f)), scale = Vec3(0.012f, 0.12f, 0.004f)) })
            s.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(0.10f, 0.14f, 0.04f))), physicsMaterial = PhysicsMaterialResource()))
            s.components.set(InteractableComponent())
            s.enabled = false
        }
        explainLine = Strip(root, meshes.band, mats.explainLine, "explainLine")
        root.addChild(Entity().named("keyLight").also {
            it.components.set(DirectionalLightComponent(PcColors.White.color4(), 2500f, false))
            it.setLocal(rotation = Quat4.fromTo(Vec3.AHEAD, Vec3(0.55f, -0.65f, -0.5f).normalized()))
        })
        attached = true
        PcLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
    }

    private suspend fun pace() {
        delay(if (lastFrameDtMs in 1..FAST_FRAME_MS) 16L else 500L)
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 150L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        stopSensing()
        panelShown.clear()
        jobs.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        treeRoot = null
        branchViews.clear()
        caps.clear()
        fallen.clear()
        wrongGhosts.clear()
        ladderRings.clear()
        ladderPlates.clear()
        ladderLines.clear()
        ladderBud.clear()
        ringParts.clear()
        potParts.clear()
        nyLabels.clear()
        tree = null
        treeKey = null
        treeBuilt = false
        appliedCuts = null
        rig = null
        m = null
        ms = null
        PcLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> scene
    // =========================================================================================

    fun render(state: PcUiState) {
        ui = state
        if (!attached) return
        val key = state.lesson to state.treeSerial
        if (key != treeKey && (state.phase.inLesson || state.phase == Phase.PLACING || state.phase == Phase.FINDING || state.phase == Phase.RELOCATING || state.phase == Phase.SETTINGS)) rebuildTree(state.lesson)
        if (state.placeSerial != placeSerialSeen && state.phase == Phase.PLACING) placedSent = false
    }

    private fun rebuildTree(lesson: LessonId) {
        val mats = m ?: return
        val meshes = ms ?: return
        val root = treeRoot ?: return
        treeKey = lesson to ui.treeSerial
        // tear down the old tree
        jobs.clear()
        branchViews.values.forEach { it.destroy() }
        branchViews.clear()
        runCatching { budEntity?.destroy(true) }
        budEntity = null
        potParts.forEach { runCatching { it.destroy(true) } }
        potParts.clear()
        caps.forEach { runCatching { it.destroy(true) } }
        caps.clear()
        wrongGhosts.forEach { runCatching { it.destroy(true) } }
        wrongGhosts.clear()
        ghostsFor = emptyList()
        clearNextYear()
        treeBuilt = false
        appliedCuts = null
        lastCutCount = ui.cuts.size
        val t = TreeCache.get(lesson)
        tree = t
        treeReach = reaches.getOrPut(lesson) { maxOf(Clearance.reach(t), 0.30f) }
        PcLog.i("TREE build lesson=$lesson branches=${t.branches.size} selectable=${t.selectable.size}")
        if (t.species == Species.ROSE) {
            // the 0.40 m oak stand and the terracotta pot with soil (PropMesher, QA round 2 q2-3: no stacked boxes), each with a
            // soft contact shadow; one mesh per job (dynamic mesh pacing)
            jobs += { potParts += model(PropMesher.stand().mesh("stand"), mats.stand, "stand").also { root.addChild(it) } }
            jobs += { potParts += model(PropMesher.pot().mesh("pot"), mats.pot, "pot").also { it.setLocal(position = Vec3(0f, PropMesher.STAND_H, 0f)); root.addChild(it) } }
            jobs += {
                potParts += model(PropMesher.soil().mesh("soil"), mats.soil, "soil").also { it.setLocal(position = Vec3(0f, PropMesher.STAND_H, 0f)); root.addChild(it) }
                val disc = PropMesher.shadowDisc().mesh("shadow_disc")
                potParts += model(disc, mats.shadow, "stand_shadow").also { it.setLocal(position = Vec3(0f, 0.003f, 0f), scale = Vec3(0.33f, 1f, 0.33f)); root.addChild(it) }
                potParts += model(disc, mats.shadow, "pot_shadow").also { it.setLocal(position = Vec3(0f, PropMesher.STAND_H + 0.002f, 0f), scale = Vec3(0.17f, 1f, 0.17f)); root.addChild(it) }
            }
        }
        // one entity per selectable / major branch; non-selectable ones are drawn with their parent
        for (b in t.branches.filter { it.selectable || it.label == Label.MAJOR }) {
            jobs += {
                val v = BranchView(root, b)
                branchViews[b.id] = v
                v.setParts(TreeMesher.branch(t, b, null).map { (w, g) -> w to g.mesh("b${b.id}_$w") }, mats)
                v.cutS = null
            }
        }
        jobs += {
            treeBuilt = true
            PcLog.i("TREE built lesson=$lesson entities=${branchViews.size}")
        }
    }

    /** Applies the cuts (and the bud zoom) to the branch meshes, the bud mesh and the caps. */
    private fun applyCuts(cuts: List<Cut>, zoom: Boolean, feedback: Boolean) {
        val t = tree ?: return
        val mats = m ?: return
        val meshes = ms ?: return
        val root = treeRoot ?: return
        val st = Judge.status(t, cuts)
        val visible = HashMap<Int, Float?>()
        for (b in t.branches) {
            when (val s = st.getValue(b.id)) {
                Status.Intact -> visible[b.id] = null
                is Status.Cut -> if (s.bud > 0) visible[b.id] = b.cutS(s.bud)
                is Status.Gone -> Unit
            }
        }
        for ((id, v) in branchViews) {
            val b = v.branch
            val gone = !visible.containsKey(id)
            v.holder.show(!gone)
            v.gone = gone
            if (gone) continue
            val want = visible[id]
            if (v.cutS != want) {
                v.cutS = want
                jobs += { v.setParts(TreeMesher.branch(t, b, want).map { (w, g) -> w to g.mesh("b${b.id}_${w}_c") }, mats) }
            }
        }
        // buds: one mesh for the whole tree
        jobs += {
            runCatching { budEntity?.destroy(true) }
            val g = TreeMesher.buds(t, visible, zoom)
            budEntity = if (g.empty) null else model(g.mesh("buds"), if (t.species == Species.ROSE) mats.budRose else mats.budApple, "buds").also { root.addChild(it) }
        }
        // caps where a branch is cut (above a bud or at its base)
        caps.forEach { runCatching { it.destroy(true) } }
        caps.clear()
        val newest = cuts.lastOrNull()
        val review = ui.phase == Phase.RESULT
        for (c in Judge.ownCuts(cuts)) {
            val b = t[c.key] ?: continue
            if (st[b.id] is Status.Gone) continue
            val s = b.cutS(c.value)
            val p = b.pointAt(s)
            val n = b.tangentAt(s)
            val r = b.radiusAt(s)
            val mat =
                when {
                    ui.exam && !review -> mats.cap
                    else -> capMaterial(t, cuts, b.id, mats)
                }
            caps += model(meshes.disc, mat, "cap_${b.id}").also {
                it.setLocal(position = p, rotation = Quat4.fromTo(Vec3.UP, n), scale = Vec3(r * 1.05f, 1f, r * 1.05f))
                root.addChild(it)
            }
        }
        // a new cut: the piece falls (red when wrong in practice)
        if (feedback && newest != null && cuts.size > lastCutCount) {
            val b = t[newest.branch]
            if (b != null) {
                val wrong = !ui.exam && Judge.feedback(t, cuts, newest).first == 0f
                val s = b.cutS(newest.bud)
                jobs += {
                    val g = TreeMesher.removed(t, b, s)
                    if (!g.empty) {
                        val e = model(g.mesh("fallen"), if (wrong) mats.fallenWrong else mats.wood(b.pieces.first().wood), "fallen")
                        root.addChild(e)
                        val dropBy = b.pointAt((s + b.length) / 2f).y - 0.05f
                        fallen += Triple(e, SystemClock.elapsedRealtime(), dropBy)
                    }
                }
            }
        }
        lastCutCount = cuts.size
        appliedCuts = cuts
        appliedZoom = zoom
    }

    private fun capMaterial(t: Tree, cuts: List<Cut>, branch: Int, mats: PcMaterials): Material {
        val score = Judge.score(t, cuts)
        if (score.wrongs.any { it.branch == branch }) return mats.capWrong
        val target = score.targets.firstOrNull { branch in it.ids } ?: return mats.cap
        return if (target.credit >= 1f) mats.capOk else mats.capOff
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        if (lastFrameMs != 0L) lastFrameDtMs = now - lastFrameMs
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.2f)
        lastFrameMs = now
        readHead(now)
        // the window opens only with the head level (PcShared.askOpen); null = no valid head pose yet
        PcShared.stepGate(now, if (hasHead) Placement.deg(asin(pose.forward.y.coerceIn(-1f, 1f))) else null)
        val rig = rig ?: return
        rig.onFrame(now)
        if (hasHead) rig.stowBehind(pose.view)
        reportPanels(rig)
        pollBlock(now)
        if (hasHead && now - lastHeadSentMs >= 100L) {
            lastHeadSentMs = now
            sink(PcEvent.HeadSample(pose.position, Placement.yawOf(pose.forward)))
        }
        runJobs(now)
        if (!attached || !hasHead) {
            placePanels(rig, now)
            return
        }
        floorFrame(now)
        placeFrame(now)
        stickFrame(now, dt)
        rayFrame(now)
        spinFrame(now)
        treeFrame(now)
        ladderFrame()
        previewFrame()
        fallenFrame(now)
        nextYearFrame(now)
        relocateFrame()
        insideFrame()
        placePanels(rig, now)
        explainFrame(now)
        launchLogs(now)
    }

    private fun runJobs(now: Long) {
        if (jobs.isEmpty()) return
        // a tree is ~50 jobs and a cut a few; hundreds means something re-queues every frame
        if (jobs.size > 300 && now - lastQueueWarnMs > 5_000L) {
            lastQueueWarnMs = now
            PcLog.i("SCENE job queue ${jobs.size} (runaway?)")
        }
        val fast = lastFrameDtMs in 1..FAST_FRAME_MS
        if (!fast && now - lastJobMs < SLOW_JOB_MS) return
        var n = if (fast) 2 else 1
        while (n-- > 0 && jobs.isNotEmpty()) {
            val job = jobs.removeFirst()
            runCatching { job() }.onFailure { PcLog.e("SCENE job failed: $it", it) }
        }
        lastJobMs = now
    }

    /** Head pose; an all-zero position (the tracker's first frames) is not a pose (factory finding on sign-hands). */
    private fun readHead(now: Long) {
        val p = head.pose() ?: return
        if (p.position.length < 1e-4f) return
        pose = p
        if (!hasHead) {
            hasHead = true
            firstHeadMs = now
        }
    }

    private fun reportPanels(rig: PanelRig) {
        // again whenever the app is back at S00 ([Retry] on the blocked dialog), not only at launch
        if (panelsReported && ui.phase != Phase.STARTING) return
        if (PanelSpec.pool.all { rig[it]?.panel != null } && hasHead && SystemClock.elapsedRealtime() - firstHeadMs > HEAD_SETTLE_MS) {
            panelsReported = true
            sink(PcEvent.PanelsReady)
        }
    }

    private fun pollBlock(now: Long) {
        val reason = head.blockReason()
        if (reason == null || ui.phase == Phase.BLOCKED || ui.debugHold) {
            blockSinceMs = NEVER
            if (ui.phase != Phase.BLOCKED) blockSent = false
            return
        }
        if (blockSinceMs == NEVER) blockSinceMs = now
        if (!blockSent && now - blockSinceMs >= 2_000L) {
            blockSent = true
            sink(PcEvent.BlockChanged(reason))
        }
    }

    // ---- floor (design `rules.placement.floor`) -------------------------------------------

    private fun virtualFloorY(): Float = pose.position.y - (if (ui.settings.seated) 1.20f else 1.60f) + ui.settings.floorOffset

    private fun floorFrame(now: Long) {
        val finding = ui.phase == Phase.FINDING
        val retry = ui.phase == Phase.PLACING && floorVirtual && findingFor != ui.placeSerial
        if (finding || retry) {
            if (retry) findingFor = ui.placeSerial
            startPlanes()
        }
        if (planesStartMs != NEVER) {
            val floor = if (ui.debugNoFloor) null else planes.all.filter { it.kind == SurfaceKind.FLOOR }.minByOrNull { it.minY }
            if (floor != null) {
                val y = floor.minY
                floorY = y
                val wasVirtual = floorVirtual
                floorVirtual = false
                stopPlanes()
                PcLog.i("FLOOR real y=${PcLog.f(y, 3)}")
                sink(PcEvent.FloorFound(y, virtual = false))
                if (wasVirtual) placed?.let { p -> placed = p.copy(position = Vec3(p.position.x, y, p.position.z)); ringPose = placed }
            } else if (now - planesStartMs >= FLOOR_TIMEOUT_MS && !(ui.debugHold && finding)) {
                stopPlanes()
                if (finding || floorY == null) {
                    floorVirtual = true
                    floorY = virtualFloorY()
                    PcLog.i("FLOOR virtual y=${PcLog.f(floorY!!, 3)}")
                    sink(PcEvent.FloorFound(floorY!!, virtual = true))
                }
            }
        }
        // the virtual floor follows "floor height" / "seated"
        if (floorVirtual && floorY != null) {
            val y = virtualFloorY()
            if (abs(y - floorY!!) > 0.002f) {
                floorY = y
                placed?.let { p -> placed = p.copy(position = Vec3(p.position.x, y, p.position.z)) }
                ringPose?.let { p -> ringPose = p.copy(position = Vec3(p.position.x, y, p.position.z)) }
            }
        }
    }

    // ---- placement (design `rules.placement.autoPlace`) -----------------------------------

    // placed at the first value, which is also the nearest the stick pulls it: nearer, the crown would reach into the
    // window's 10 degrees (D41); the apple starts at 2.60 (design 2.00 - 3.20) so it stands about 61 degrees right, not 74
    private fun distances(t: Tree): Pair<Float, Float> = if (t.species == Species.ROSE) 1.10f to 2.00f else 2.60f to 3.20f

    private fun placeFrame(now: Long) {
        val t = tree ?: return
        // the model may already be past S04 (a debug jump, a fast replay): place once for this serial anyway
        if (floorY == null && ui.floorReady) {
            floorY = virtualFloorY()
            floorVirtual = ui.virtualFloor
        }
        val wantsPlace = ui.phase == Phase.PLACING || ui.phase.inLesson
        if (wantsPlace && ui.placeSerial != placeSerialSeen && floorY != null && treeBuilt) {
            placeSerialSeen = ui.placeSerial
            // beside the main window, not under it: placing follows a press in the window (a lesson card, [Try again],
            // [Bring here], which also reopens the window in front), so the user faces the window now (native-window-move §4)
            placeTree(t, now, "press")
            fadeStartMs = now
        }
        windowCheck(t, now)
        if (!placedSent && ui.phase == Phase.PLACING && fadeStartMs != NEVER && now - fadeStartMs >= FADE_MS && placeSerialSeen == ui.placeSerial) {
            placedSent = true
            sink(PcEvent.Placed)
        }
    }

    private fun placeTree(t: Tree, now: Long, why: String) {
        val p = Placement.treeBesideWindow(pose.position, pose.forward, distances(t).first, floorY!!, treeReach)
        placed = p
        ringPose = p
        placeMachine.set(p.position)
        spin = 0f
        spinStartMs = NEVER
        explainSeen = -1
        PcLog.i("PC-PLACE why=$why tree=${PcLog.pos(p.position)} yaw=${PcLog.f(Math.toDegrees(p.yaw.toDouble()).toFloat(), 1)} head=${PcLog.pos(pose.position)} dist=${PcLog.f(distances(t).first)} reach=${PcLog.f(treeReach)} right=${PcLog.f(Clearance.treeRightDeg(treeReach, distances(t).first), 1)} floorY=${PcLog.f(floorY!!, 3)} virtual=$floorVirtual")
        logIndex = 0
        placedAtMs = now
    }

    /**
     * A window that is created again (the chip after its x, [Bring here]'s reopen) opens straight ahead of the head at
     * that moment, wherever the tree is: when it would come within [Clearance.CLEAR_DEG] of the tree, the tree goes
     * beside the new window (D41, QA v0.1.3). A restored (minimised) window keeps its old place.
     */
    private fun windowCheck(t: Tree, now: Long) {
        // ON_CREATE .. ON_RESUME can pass between two frames: "was destroyed, is open now" is a new window
        if (PcShared.window == "ON_DESTROY") windowGone = true
        if (!windowGone || !PcShared.windowOpen) return
        windowGone = false
        val p = placed ?: return
        if (!ui.phase.inLesson || floorY == null) return
        val gap = Clearance.windowGapDeg(pose.position, Placement.yawOf(pose.forward), p.position, treeReach)
        PcLog.i("PC-CLEAR window created gap=${PcLog.f(gap, 1)}")
        if (gap < Clearance.CLEAR_DEG) placeTree(t, now, "window")
    }

    private var windowGone = false

    private var placedAtMs = NEVER

    /** Tree transform: placement + the user's spin; visible / faded by state. */
    private fun treeFrame(now: Long) {
        val tr = treeRoot ?: return
        val p = placed
        val t = tree
        val showTree = p != null && t != null && treeBuilt && when (ui.phase) {
            Phase.PLACING, Phase.RULES, Phase.PRUNING, Phase.RESULT, Phase.GRADUATED, Phase.RELOCATING -> true
            Phase.SETTINGS, Phase.RULE_CARD -> ui.back.inLesson
            else -> false
        }
        tr.show(showTree)
        val r = ring
        val h = handle
        if (!showTree || p == null) {
            r?.show(false)
            h?.show(false)
            grid?.show(false)
            return
        }
        tr.setLocal(position = p.position, rotation = Quat4.axisAngle(Vec3.UP, p.yaw + spin))
        var opacity = 1f
        if (fadeStartMs != NEVER) {
            opacity = ((now - fadeStartMs) / FADE_MS).coerceIn(0f, 1f)
            if (opacity >= 1f) fadeStartMs = NEVER
        }
        if (ui.phase == Phase.RULES || ui.phase == Phase.GRADUATED || ui.phase == Phase.RULE_CARD || ui.inside) opacity *= 0.3f
        if (nyFadeMs != NEVER) opacity *= nyFadeOpacity(now)
        tr.fade(opacity)
        // the cuts as the model says (the "model" next-year view shows the model answer)
        val wantCuts = if (ui.phase == Phase.RESULT && ui.view == View.MODEL) Judge.model(t) else ui.cuts
        if (treeBuilt && (wantCuts != appliedCuts || ui.settings.budZoom != appliedZoom)) applyCuts(wantCuts, ui.settings.budZoom, feedback = ui.phase == Phase.PRUNING)
        tintFrame(t)
        // plant ring (stays where the tree was placed) and the floor handle (under the tree)
        val rp = ringPose ?: p
        val rose = t.species == Species.ROSE
        val ringR = if (rose) 0.30f else 0.60f
        r?.show(true)
        r?.setLocal(position = rp.position + Vec3(0f, 0.002f, 0f), rotation = Quat4.axisAngle(Vec3.UP, rp.yaw))
        ringParts.getOrNull(0)?.setLocal(scale = Vec3(ringR, 1f, ringR))
        ringParts.getOrNull(1)?.setLocal(position = Vec3(0f, 0.001f, ringR), rotation = Quat4.axisAngle(Vec3.RIGHT, -(Math.PI / 2).toFloat()) * Quat4.axisAngle(Vec3(0f, 0f, 1f), (Math.PI / 2).toFloat()), scale = Vec3(0.12f, 0.02f, 1f))
        ringParts.getOrNull(2)?.setLocal(position = Vec3(0f, 0.001f, ringR + 0.12f), scale = Vec3(0.05f, 1f, 0.05f))
        val onRing = rp.position.distanceTo(p.position) < 0.02f && drag == null && !movingStick
        val ringMat = if (now < snapFlashUntil) m?.ringOk else if (onRing) m?.ringDim else m?.ring
        ringMat?.let { mat -> ringParts.forEach { it.setMaterial(mat) } }
        val hOuter = if (rose) 0.50f else 0.60f
        h?.show(ui.phase == Phase.PRUNING || ui.phase == Phase.RESULT)
        h?.setLocal(position = p.position + Vec3(0f, 0.004f, 0f), scale = Vec3(hOuter, 1f, hOuter))
        grid?.show(floorVirtual)
        grid?.setLocal(position = Vec3(pose.position.x, (floorY ?: 0f) + 0.001f, pose.position.z), scale = Vec3(1.5f, 1f, 1.5f))
    }

    private var snapFlashUntil = 0L
    private var ghostsFor: List<Int> = emptyList()
    private var movingStick = false

    /** Hints / review marks / the selection, by material swap (design `hint`, `review`, S07). */
    private fun tintFrame(t: Tree) {
        val mats = m ?: return
        val review = ui.phase == Phase.RESULT && ui.view == View.NOW
        val st = Judge.status(t, ui.cuts)
        for ((id, v) in branchViews) {
            val b = v.branch
            val uncutMust = b.label == Label.MUST && st[id] == Status.Intact
            val mat =
                when {
                    ui.selected == id && ui.phase == Phase.PRUNING -> mats.selected
                    (ui.hints && ui.phase == Phase.PRUNING || review) && uncutMust && (ui.phase != Phase.RESULT || ui.view == View.NOW) -> mats.hint
                    else -> null
                }
            v.tint(mat, mats)
            v.holder.fade(if (ui.selected != null && ui.selected != id && ui.phase == Phase.PRUNING) 0.7f else 1f)
        }
        // wrong cuts come back as red ghosts in the review
        // keyed on what is wanted, not on how many exist: the ghosts are built later by queued jobs
        val wantGhosts = if (review) ui.score?.wrongs?.map { it.branch }.orEmpty() else emptyList()
        if (wantGhosts != ghostsFor) {
            ghostsFor = wantGhosts
            wrongGhosts.forEach { runCatching { it.destroy(true) } }
            wrongGhosts.clear()
            val root = treeRoot ?: return
            for (id in wantGhosts) {
                val b = t[id] ?: continue
                val k = Judge.ownCuts(ui.cuts)[id] ?: continue
                jobs += job@{
                    if (id !in ghostsFor) return@job
                    val g = TreeMesher.removed(t, b, b.cutS(k))
                    if (!g.empty) wrongGhosts += model(g.mesh("ghost_$id"), mats.wrongGhost, "wrongGhost").also { root.addChild(it) }
                }
            }
        }
    }

    // ---- the stick (design `rules.placement.rotate` / `distance`, `judge.budCursor`) ------

    private fun stickFrame(now: Long, dt: Float) {
        val allowed = ui.phase in setOf(Phase.PRUNING, Phase.RESULT) && drag == null && placed != null
        // the controller's back key (B / Y) leaves the selection like a tap on empty space (design S07 / budCursor)
        if (controller.takeBackPresses() > 0 && allowed && ui.selected != null) sink(PcEvent.TappedElsewhere)
        val sample = if (allowed) controller.sample() else null
        val x = sample?.stickX ?: 0f
        val y = sample?.stickY ?: 0f
        // axis lock: the first axis past the dead zone wins until both are back in the middle
        if (abs(x) < DEAD && abs(y) < DEAD) stickAxis = 0
        else if (stickAxis == 0) stickAxis = if (abs(x) >= abs(y)) 1 else 2
        // X: turn about the trunk, right = clockwise seen from above (yaw down)
        val wanted = if (stickAxis == 1) -(if (x > 0) 1f else -1f) * (abs(x) - DEAD) / (1f - DEAD) * TURN_RATE else 0f
        if (wanted != 0f) stickRate = wanted
        else if (stickRate != 0f) {
            val step = TURN_RATE / 0.2f * dt
            stickRate = if (abs(stickRate) <= step) 0f else stickRate - step * (if (stickRate > 0) 1f else -1f)
        }
        if (stickRate != 0f) {
            spin += stickRate * dt
            spinStartMs = NEVER
        }
        val turning = stickRate != 0f
        if (turning != turningSent) {
            turningSent = turning
            sink(PcEvent.Turning(turning))
        }
        // Y: the bud cursor while a branch is selected, else push / pull the tree
        val t = tree ?: return
        val sel = ui.selected?.let { t[it] }
        var moving = false
        if (stickAxis == 2 && sel != null) {
            if (cursorArmed && abs(y) > 0.6f) {
                cursorArmed = false
                val n = sel.buds.size
                val c = (cursor ?: ui.preview ?: 0) + (if (y > 0) 1 else -1)
                cursor = c.coerceIn(0, n)
                PcLog.i("CURSOR bud=$cursor")
            }
        } else if (stickAxis == 2) {
            val p = placed
            if (p != null) {
                val (lo, hi) = distances(t)
                val dir = Vec3(p.position.x - pose.position.x, 0f, p.position.z - pose.position.z)
                val d = dir.length
                if (d > 1e-3f) {
                    val nd = (d + (if (y > 0) 1f else -1f) * ((abs(y) - DEAD) / (1f - DEAD)) * 0.5f * dt).coerceIn(lo, hi)
                    val np = pose.position.let { Vec3(it.x, 0f, it.z) } + dir.normalized() * nd
                    placed = p.copy(position = Vec3(np.x, p.position.y, np.z))
                    moving = true
                }
            }
        }
        if (abs(y) < 0.3f) cursorArmed = true
        movingStick = moving
        if (moving != movingStickSent) {
            movingStickSent = moving
            sink(PcEvent.Moving(moving))
        }
        // trigger with the cursor: first press previews, the second cuts (same as tapping the ring twice)
        val pressed = sample?.pressed == true
        if (pressed && !triggerWas && sel != null && cursor != null) {
            cursorTapMs = now
            sink(PcEvent.BudTapped(sel.id, cursor!!))
        }
        triggerWas = pressed
    }

    /** Animated turns: the 45-degree buttons, turning a branch to the user (hint, review row, selecting a branch around the back). */
    private fun spinFrame(now: Long) {
        val t = tree ?: return
        fun turnTo(target: Float, ms: Float) {
            spinFrom = spin
            spinTo = spin + Placement.angle(target - spin)
            spinStartMs = now
            spinMs = ms
        }
        if (ui.rotateSerial != rotateSeen) {
            rotateSeen = ui.rotateSerial
            spinFrom = spin
            spinTo = spin - Placement.rad(ui.rotateBy)
            spinStartMs = now
            spinMs = 400f
        }
        fun facing(id: Int?): Float? {
            val b = id?.let { t[it] } ?: return null
            val mid = b.pointAt(b.length * 0.5f)
            return -atan2(mid.x, mid.z)
        }
        if (ui.focusSerial != focusSeen) {
            focusSeen = ui.focusSerial
            facing(ui.focusBranch)?.let { turnTo(it, 600f) }
        }
        if (ui.hintSerial != hintSeen) {
            hintSeen = ui.hintSerial
            facing(ui.explain?.branch)?.let { turnTo(it, 600f) }
        }
        if (ui.selected != selectedSeen) {
            selectedSeen = ui.selected
            cursor = null
            ladderWindow = 0
            val f = facing(ui.selected)
            if (f != null && abs(Placement.angle(f - spin)) > Placement.rad(60f)) turnTo(f, 600f)
        }
        if (spinStartMs != NEVER) {
            val q = ((now - spinStartMs) / spinMs).coerceIn(0f, 1f)
            spin = spinFrom + (spinTo - spinFrom) * Ease.inOutCubic(q)
            if (q >= 1f) spinStartMs = NEVER
        }
    }

    // ---- bud ladder (design `rules.judge.budLadder`) ---------------------------------------

    private fun treeToWorld(local: Vec3): Vec3 {
        val p = placed ?: return local
        return p.position + Quat4.axisAngle(Vec3.UP, p.yaw + spin).rotate(local)
    }

    private fun ladderFrame() {
        val lr = ladderRoot ?: return
        val mats = m ?: return
        val t = tree
        val sel = ui.selected?.let { t?.get(it) }
        if (sel == null || ui.phase != Phase.PRUNING || t == null) {
            lr.show(false)
            return
        }
        lr.show(true)
        val mid = treeToWorld(sel.pointAt(sel.length * 0.5f))
        val toUser = Vec3(pose.position.x - mid.x, 0f, pose.position.z - mid.z).normalized()
        val right = Vec3.UP.cross(toUser).normalized()
        val column = mid + toUser * 0.16f + right * 0.06f
        val dist = column.distanceTo(pose.position)
        val dia = (dist * 0.0314f).coerceAtLeast(0.03f)
        val gap = dia * 1.25f
        // entries: base (0) then buds; at most 16 shown, the rest by "more" rings
        val all = listOf(0) + sel.buds.map { it.index }
        val window = if (all.size <= MAX_RINGS) all else all.drop(ladderWindow).take(MAX_RINGS - 2)
        val entries = ArrayList<Int>()
        if (all.size > MAX_RINGS) entries += MORE_DOWN
        entries += window
        if (all.size > MAX_RINGS) entries += MORE_UP
        val baseY = column.y - (entries.size - 1) * gap / 2f
        val yaw = atan2(toUser.x, toUser.z)
        val face = Quat4.axisAngle(Vec3.UP, yaw)
        for (i in 0 until LADDER_POOL) {
            val r = ladderRings[i]
            val pl = ladderPlates[i]
            val line = ladderLines[i]
            if (i >= entries.size) {
                r.show(false)
                pl.show(false)
                line.hide()
                ladderBud[i] = -1
                continue
            }
            val k = entries[i]
            ladderBud[i] = k
            val at = Vec3(column.x, baseY + i * gap, column.z)
            val active = (ui.preview == k && k >= 0) || cursor == k
            r.show(true)
            r.setMaterial(if (active) mats.ladderActive else mats.ladder)
            r.setLocal(position = at, rotation = face * PLATE, scale = Vec3(dia / 2f, 1f, dia / 2f))
            ringAt[i] = at
            val text = when (k) {
                0 -> "✕"
                MORE_UP -> "+"
                MORE_DOWN -> "-"
                else -> k.toString()
            }
            val plateMat = if (ui.testTexture) mats.testF() else mats.text("n_$text", text, pill = false)
            if (plateMat != null) pl.setMaterial(plateMat)
            pl.show(true)
            pl.setLocal(position = at + toUser * 0.002f, rotation = face, scale = Vec3(dia * 0.72f, dia * 0.72f, 1f))
            if (k > 0) {
                val bud = sel.buds.first { it.index == k }
                line.draw(at - right * (dia / 2f), treeToWorld(bud.pos), 0.001f, toUser)
            } else if (k == 0) {
                line.draw(at - right * (dia / 2f), treeToWorld(sel.points.first()), 0.001f, toUser)
            } else line.hide()
        }
        // the tag over the column: what to do now
        val tag = ladderTag ?: return
        val key = if (ui.preview != null) "ladder_confirm" else "ladder_pick"
        val word = words(key)
        mats.text("tag_${key}_$word", word, pill = true)?.let { tag.setMaterial(it) }
        tag.show(true)
        val w = dia * 4f
        tag.setLocal(position = Vec3(column.x, baseY + entries.size * gap + dia * 0.4f, column.z), rotation = face, scale = Vec3(w, w, 1f))
    }

    // ---- cut preview (design `entities3d.cutPreview`) --------------------------------------

    private fun previewFrame() {
        val disc = previewDisc ?: return
        val sc = scissors ?: return
        val t = tree
        val b = ui.selected?.let { t?.get(it) }
        val k = ui.preview
        if (b == null || k == null || ui.phase != Phase.PRUNING) {
            disc.show(false)
            sc.show(false)
            return
        }
        val s = b.cutS(k)
        val p = treeToWorld(b.pointAt(s))
        val tan = Quat4.axisAngle(Vec3.UP, (placed?.yaw ?: 0f) + spin).rotate(b.tangentAt(s))
        val away = if (k > 0) Quat4.axisAngle(Vec3.UP, (placed?.yaw ?: 0f) + spin).rotate(b.buds.first { it.index == k }.dir) else Vec3.UP
        val n = (tan - away.rejectFrom(tan).normalized() * 0.9f).normalized() // 45 degrees, sloping away from the bud
        val r = (b.radiusAt(s) * 1.6f).coerceAtLeast(0.008f)
        disc.show(true)
        disc.setLocal(position = p, rotation = Quat4.fromTo(Vec3.UP, n), scale = Vec3(r, 1f, r))
        // scissors ghost beside the ladder, not over the rings
        val toUser = Vec3(pose.position.x - p.x, 0f, pose.position.z - p.z).normalized()
        val right = Vec3.UP.cross(toUser).normalized()
        sc.show(true)
        sc.setLocal(position = p + toUser * 0.12f + right * 0.22f, rotation = Quat4.axisAngle(Vec3.UP, atan2(toUser.x, toUser.z)))
    }

    // ---- falling pieces --------------------------------------------------------------------

    private fun fallenFrame(now: Long) {
        val it = fallen.iterator()
        while (it.hasNext()) {
            val (e, start, dropBy) = it.next()
            val age = (now - start) / 1000f
            if (age > 2.6f) {
                runCatching { e.destroy(true) }
                it.remove()
                continue
            }
            val q = (age / 0.6f).coerceIn(0f, 1f)
            e.setLocal(position = Vec3(0f, -dropBy * q * q, 0.05f * q), rotation = Quat4.axisAngle(Vec3.RIGHT, 0.6f * q))
            e.fade(if (age < 0.6f) 1f else (1f - (age - 0.6f) / 2f).coerceIn(0f, 1f))
        }
    }

    // ---- next year (design `rules.nextYear`) -----------------------------------------------

    private fun clearNextYear() {
        runCatching { nyRoot?.destroy(true) }
        nyRoot = null
        nyKey = null
        nyLabels.clear()
    }

    private fun nyFadeOpacity(now: Long): Float {
        val q = ((now - nyFadeMs) / 1200f).coerceIn(0f, 1f)
        if (q >= 1f) nyFadeMs = NEVER
        return if (q < 0.5f) 1f - q * 2f else (q - 0.5f) * 2f
    }

    private fun nextYearFrame(now: Long) {
        val t = tree ?: return
        val mats = m ?: return
        val meshes = ms ?: return
        val root = treeRoot ?: return
        val view = if (ui.phase == Phase.RESULT) ui.view else View.NOW
        if (view != nyShown) {
            nyShown = view
            nyFadeMs = now
        }
        if (view == View.NOW) {
            nyRoot?.show(false)
            return
        }
        val cuts = if (view == View.MODEL) Judge.model(t) else ui.cuts
        val key = "${t.lesson}|$view|${cuts.hashCode()}|${ui.english}"
        if (key != nyKey) {
            clearNextYear()
            nyKey = key
            val ny = NextYear(t, cuts)
            val r = Entity().named("nextYear")
            root.addChild(r)
            nyRoot = r
            PcLog.i("PC-NEXT view=$view counts=${ny.counts}")
            val (wood, leaves, flowers) = TreeMesher.nextYear(ny.shoots, ny.blossoms.map { it.pos to it.half }, t.species)
            PcLog.i("PC-NEXT mesh shoots=${ny.shoots.size} blossoms=${ny.blossoms.size} verts wood=${wood.pos.size} leaves=${leaves.pos.size} flowers=${flowers.pos.size}")
            jobs += { if (!wood.empty) r.addChild(model(wood.mesh("ny_wood"), mats.young, "ny_wood")) }
            jobs += { if (!leaves.empty) r.addChild(model(leaves.mesh("ny_leaves"), mats.leaf, "ny_leaves")) }
            jobs += { if (!flowers.empty) r.addChild(model(flowers.mesh("ny_flowers"), if (t.species == Species.ROSE) mats.bloomRose else mats.bloomApple, "ny_flowers")) }
            for (l in ny.labels.distinctBy { it.key + (it.pos.x * 10).toInt() }.take(8)) {
                jobs += {
                    val word = words("label_${l.key}")
                    val mat = if (ui.testTexture) mats.testF() else mats.text("label_${l.key}_$word", word, pill = true)
                    if (mat != null) {
                        val e = model(meshes.label, mat, "ny_label")
                        r.addChild(e)
                        e.setLocal(position = l.pos + Vec3(0f, 0.12f, 0f), scale = Vec3(0.24f, 0.24f, 1f))
                        nyLabels += e
                    }
                }
            }
        }
        nyRoot?.show(true)
        // labels billboard about the vertical (in tree space: undo the tree's turn)
        val treeYaw = (placed?.yaw ?: 0f) + spin
        for (l in nyLabels) {
            val w = treeToWorld(runCatching { l.convertPositionTo(Vector3.ZERO, root).domain() }.getOrNull() ?: continue)
            val yawTo = atan2(pose.position.x - w.x, pose.position.z - w.z)
            l.setLocal(rotation = Quat4.axisAngle(Vec3.UP, yawTo - treeYaw))
        }
    }

    // ---- replanting (S20, design `rules.placement.relocate`) -------------------------------

    private fun relocateFrame() {
        val g = ghost ?: return
        val fc = floorCatcher ?: return
        val t = tree
        if (ui.phase != Phase.RELOCATING || t == null || floorY == null) {
            if (placeMachine.mode == PlaceMachine.Mode.PLACING) placeMachine.cancel()
            g.show(false)
            fc.show(false)
            return
        }
        if (placeMachine.mode == PlaceMachine.Mode.LOCKED) placeMachine.replant()
        fc.show(true)
        fc.setLocal(position = Vec3(pose.position.x, floorY!! - 0.01f, pose.position.z))
        val sample = controller.sample()
        val ray = sample?.ray
        var hit: Vec3? = null
        if (ray != null && ray.direction.y < -0.05f) {
            val d = (floorY!! - ray.origin.y) / ray.direction.y
            if (d in 0f..10f) hit = ray.origin + ray.direction * d
        }
        placeMachine.aim(hit ?: placeMachine.ghost)
        val gp = placeMachine.ghost
        if (gp == null) {
            g.show(false)
        } else {
            val ok = allowedSpot(t, gp)
            g.show(true)
            g.setLocal(position = gp, rotation = Quat4.axisAngle(Vec3.UP, Placement.yawOf(gp - pose.position) + Math.PI.toFloat()))
            val mat = if (ok) m?.ghost else m?.ghostBad
            mat?.let { mm -> ghostParts.forEach { it.setMaterial(mm) } }
            val pressed = sample?.pressed == true
            if (pressed && !relocTriggerWas) dropGhost(t)
        }
        relocTriggerWas = sample?.pressed == true
    }

    private fun allowedSpot(t: Tree, at: Vec3): Boolean {
        val d = Vec3(at.x - pose.position.x, 0f, at.z - pose.position.z).length
        return d - t.crownRadius >= clearance(t)
    }

    private fun clearance(t: Tree): Float = if (t.species == Species.ROSE) 0.6f else 0.8f

    private fun dropGhost(t: Tree) {
        val at = placeMachine.ghost ?: return
        if (!placeMachine.drop(allowedSpot(t, at))) return
        val yaw = Placement.yawOf(Vec3(at.x - pose.position.x, 0f, at.z - pose.position.z))
        placed = Upright(Vec3(at.x, floorY ?: at.y, at.z), yaw)
        ringPose = placed
        spin = 0f
        PcLog.i("PC-PLACE replant tree=${PcLog.pos(placed!!.position)}")
        sink(PcEvent.Replanted)
    }

    // ---- inside the crown (S21) ------------------------------------------------------------

    private fun insideFrame() {
        val t = tree ?: return
        val p = placed ?: return
        val inLesson = ui.phase.inLesson || ui.phase == Phase.PLACING
        val horiz = Vec3(pose.position.x - p.position.x, 0f, pose.position.z - p.position.z).length
        val inside = inLesson && horiz < t.crownRadius * 0.9f && pose.position.y < p.position.y + t.height + 0.1f
        if (inside != insideSent) {
            insideSent = inside
            sink(PcEvent.Inside(inside))
        }
    }

    // =========================================================================================
    // Stage panels: the main window is the system's (moved, closed and minimised by its own controls); the Stage only
    // places the "open panel" chip and the explain card
    // =========================================================================================

    /**
     * The "open panel" chip while the main window is closed (its ×) or minimised: 0.8 m ahead, 15 degrees below the
     * eyes and 20 degrees left (the tree stands to the right), yaw only; placed when it appears, again only if it ends up
     * more than 70 degrees off the view (factory rule 2026-10-29). The same slot is the "look straight ahead" plate
     * while an open waits for a level head (PcShared.levelHint): on the line of sight (pitch clamped to 40 degrees),
     * 0.75 m away, upright, following the view.
     */
    private fun placePanels(rig: PanelRig, now: Long) {
        if (!hasHead) return
        val slot = rig[PanelSpec.OPEN] ?: return
        if (PcShared.levelHint) {
            if (!hintShown) PcLog.i("PANEL level hint shown pitch=${PcLog.f(Placement.deg(asin(pose.forward.y.coerceIn(-1f, 1f))), 1)}")
            hintShown = true
            chipPose = null
            val pitch = asin(pose.forward.y.coerceIn(-1f, 1f)).coerceIn(-Placement.rad(HINT_PITCH_MAX), Placement.rad(HINT_PITCH_MAX))
            val yaw = Placement.yawOf(pose.forward)
            val p = pose.position + Placement.dirOfYaw(yaw) * (HINT_DIST * cos(pitch)) + Vec3(0f, HINT_DIST * sin(pitch), 0f)
            val u = Upright(p, yaw)
            slot.place(u.position, u.rotation, PanelSpec.SCALE)
            return
        }
        if (hintShown) {
            hintShown = false
            PcLog.i("PANEL level hint hidden")
        }
        val opening = PcShared.openWaiting || now - PcShared.openedMs < CHIP_AFTER_OPEN_MS
        if (!PcShared.windowClosed || PcShared.recallPending || opening) {
            if (chipPose != null) PcLog.i("PANEL chip hidden (window ${PcShared.window}${if (opening) ", opening" else ""})")
            chipPose = null
            slot.hide()
            return
        }
        val cp = chipPose
        val off = cp?.let { abs(Placement.angle(Placement.yawOf(it.position - pose.position) - Placement.yawOf(pose.forward))) } ?: 0f
        if (cp == null || off > Placement.rad(70f)) {
            val chipW = PanelSpec.size(PanelSpec.OPEN).first * PanelRig.DP_M * PanelSpec.SCALE
            val treeAt = placed?.position?.takeIf { treeRoot?.enabled == true }
            chipPose = Placement.chipBesideTree(pose.position, pose.forward, 0.8f, 15f, 20f, chipW, treeAt, treeReach)
            PcLog.i("PANEL chip at=${PcLog.pos(chipPose!!.position)} (window ${PcShared.window})")
        }
        val p = chipPose!!
        slot.place(p.position, p.rotation, PanelSpec.SCALE)
    }

    private fun explainFrame(now: Long) {
        val rig = rig ?: return
        val slot = rig[PanelSpec.EXPLAIN] ?: return
        val e = ui.explain
        val t = tree
        val line = explainLine
        if (e == null || t == null || placed == null || !(ui.phase == Phase.PRUNING || ui.phase == Phase.RESULT)) {
            slot.hide()
            line?.hide()
            explainSeen = -1
            return
        }
        val b = t[e.branch] ?: return
        val cutLocal = Judge.ownCuts(ui.cuts)[b.id]?.let { b.pointAt(b.cutS(it)) } ?: b.pointAt(b.length * 0.5f)
        val cut = treeToWorld(cutLocal)
        // beside what is shown now: this year's branches while pruning, next year's growth too on the result page; again
        // when the tree has been turned (stick X) more than 5 degrees since
        val grown = ui.phase == Phase.RESULT && ui.view != View.NOW
        if (e.serial != explainSeen || grown != explainGrown || abs(spin - explainSpin) > Placement.rad(5f)) {
            explainSeen = e.serial
            explainGrown = grown
            explainSpin = spin
            val pts = treePoints.getOrPut(t.lesson to grown) { Clearance.points(t, nextYear = grown) }
            val edge = Clearance.rightEdgeDeg(pose.position, placed!!.position, placed!!.yaw + spin, pts)
            explainPose = Placement.dockExplain(pose.position, cut, placed!!.position, treeReach, edge, PanelSpec.size(PanelSpec.EXPLAIN).first * PanelRig.DP_M)
            explainInViewMs = NEVER
            PcLog.i("EXPLAIN reason=${e.reason} branch=${e.branch} treeRight=${PcLog.f(edge, 1)} grown=$grown at=${PcLog.pos(explainPose!!.first.position)} scale=${PcLog.f(explainPose!!.second)}")
        }
        val (p, scale) = explainPose ?: return
        slot.place(p.position, p.rotation, scale)
        // 3 mm line from the card's left edge to the cut
        val (w, _) = PanelSpec.size(PanelSpec.EXPLAIN)
        val left = p.position + p.rotation.rotate(Vec3(-w * PanelRig.DP_M * scale / 2f, 0f, 0f))
        line?.draw(left, cut, 0.003f, (pose.position - cut).normalized())
        // practice: fades 8 s after it was first in view
        if (!ui.exam && ui.phase == Phase.PRUNING) {
            val toCard = (p.position - pose.position).normalized()
            val inView = toCard.dot(pose.forward) > cos(Placement.rad(35f))
            if (inView && explainInViewMs == NEVER) explainInViewMs = now
            if (explainInViewMs != NEVER && now - explainInViewMs > 8_000L && explainTimeoutSent != e.serial && !ui.debugHold) {
                explainTimeoutSent = e.serial
                sink(PcEvent.ExplainTimeout)
            }
        }
    }

    /** Logs where the panel and the tree are relative to the head 1 / 3 / 5 s after placing (factory rule 2026-10-29). */
    private fun launchLogs(now: Long) {
        if (placedAtMs == NEVER || logIndex >= logTimes.size) return
        if (now - placedAtMs < logTimes[logIndex]) return
        logIndex++
        val p = placed ?: return
        val toTree = p.position - pose.position
        val treeAz = Math.toDegrees(Placement.angle(Placement.yawOf(toTree) - Placement.yawOf(pose.forward)).toDouble())
        // the main window's pose is the system's (no API reports it): its lifecycle is logged instead
        val treeHalf = Clearance.treeHalfDeg(treeReach, Vec3(toTree.x, 0f, toTree.z).length)
        PcLog.i(String.format(Locale.US, "PC-LAYOUT t=%ds tree=%s dist=%.2f az=%.1f half=%.1f windowHalf=%.1f gap=%.1f visible=%s window=%s", logTimes[logIndex - 1] / 1000, PcLog.pos(p.position), Vec3(toTree.x, 0f, toTree.z).length, treeAz, treeHalf, Clearance.windowHalfDeg, kotlin.math.abs(treeAz) - treeHalf - Clearance.windowHalfDeg, treeRoot?.enabled, PcShared.window))
    }

    // =========================================================================================
    // system pointer: taps and drags
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity ?: return
        // which input the system pointer reports (Pointer = controller ray, GazePinch / RayBasedPinch / DirectPinch = hands)
        PcLog.i("TAP system kind=${runCatching { value.interactionKind }.getOrNull()} target=${runCatching { target.getName() }.getOrNull()}")
        tapEntity(target, value.position.let { Vec3(it.x, it.y, it.z) }, "system")
    }

    /**
     * A tap on [target] from the system pointer or the app's controller ray ([source]). One press can reach the app both
     * ways when the system pointer also works: the second tap on the same entity within 400 ms is dropped.
     */
    private fun tapEntity(target: Entity, worldPos: Vec3?, source: String) {
        val name = runCatching { target.getName() }.getOrNull() ?: "none"
        val now = SystemClock.elapsedRealtime()
        // one trigger press may arrive twice (our ray and the system pointer), not always on the same entity: whatever the
        // other source hit within 600 ms is the same press
        if (source != lastTapSource && now - lastTapMs < 600L) {
            PcLog.i("TAP $source dropped (same press as $lastTapSource ${now - lastTapMs} ms ago)")
            return
        }
        lastTapId = target.id
        lastTapMs = now
        lastTapSource = source
        // ladder rings
        val ri = ladderRings.indexOfFirst { it.id == target.id && it.enabled }
        if (ri >= 0) {
            val k = ladderBud[ri]
            val sel = ui.selected ?: return
            when (k) {
                MORE_UP -> ladderWindow += 8
                MORE_DOWN -> ladderWindow = (ladderWindow - 8).coerceAtLeast(0)
                else -> if (k >= 0) {
                    PcLog.i("TAP ladder bud=$k branch=$sel")
                    sink(PcEvent.BudTapped(sel, k))
                }
            }
            return
        }
        if (target.id == previewDisc?.id || target.id == scissors?.id) {
            PcLog.i("TAP preview")
            sink(PcEvent.PreviewTapped)
            return
        }
        if (ui.phase == Phase.RELOCATING && target.id == floorCatcher?.id) {
            val w = worldPos ?: return
            val p = toStage(w.x, w.y, w.z) ?: return
            placeMachine.aim(Vec3(p.x, floorY ?: p.y, p.z))
            tree?.let { dropGhost(it) }
            return
        }
        val bv = branchViews.values.firstOrNull { it.isGrab(target) }
        if (bv != null && ui.phase == Phase.PRUNING) {
            PcLog.i("TAP branch=${bv.branch.id} ${bv.branch.label} ${bv.branch.defect}")
            sink(PcEvent.BranchTapped(bv.branch.id))
            return
        }
        if (now - cursorTapMs < 250L) return
        PcLog.i("TAP elsewhere target=$name")
        sink(PcEvent.TappedElsewhere)
    }

    /**
     * The controller ray (design D1: controller ray + trigger, eye-hand + pinch): a 2.5 mm beam from the aiming controller
     * to the first collider it hits (at most [RAY_MAX] m), a dot on the hit; a trigger press taps the hit entity. Hidden
     * without a controller (hand mode: the system's gaze + pinch). The bud cursor (stick) and re-planting keep their own
     * trigger use.
     */
    private fun rayFrame(now: Long) {
        val beam = rayBeam ?: return
        val dot = rayDot ?: return
        val root = stageRoot ?: return
        val live = ui.phase != Phase.STARTING && ui.phase != Phase.BLOCKED
        val real = if (live) controller.sample() else null
        val s = real ?: if (live) debugRaySample() else null
        if (s == null) {
            if (rayShown) PcLog.i("RAY hidden (${if (!live) "phase ${ui.phase}" else "no controller pose"})")
            rayShown = false
            beam.show(false)
            dot.show(false)
            rayTriggerWas = false
            return
        }
        val o = s.ray.origin
        val d = s.ray.direction
        if (!rayShown) {
            // where the ray starts relative to the head (yaw frame: right / up / ahead): a hand is ~0.2 / -0.3 / 0.3 m,
            // anything else means the controller pose is not in the Stage's space
            val rel = o - pose.position
            val yaw = Placement.yawOf(pose.forward)
            val ahead = Placement.dirOfYaw(yaw)
            val right = Placement.dirOfYaw(yaw - Placement.rad(90f))
            PcLog.i("RAY shown (${if (real == null) "debug aim $debugAim" else "controller ${if (s.right) "right" else "left"}"}) from head right=${PcLog.f(rel.dot(right))} up=${PcLog.f(rel.y)} ahead=${PcLog.f(rel.dot(ahead))} phase=${ui.phase}")
        }
        rayShown = true
        val hit =
            runCatching {
                root.scene?.rayCast(o.sdk(), d.sdk(), RAY_MAX, CollisionCastHitMode.ALL, CollisionGroup(CollisionGroup.COLLISION_GROUP_ALL), root)
            }.getOrNull()?.results?.filter { it.entity.id != beam.id && it.entity.id != dot.id && it.entity.enabled }?.minByOrNull { it.distance }
        // the floor has no collider (D24): the ray meets its plane by arithmetic
        val fy = floorY
        val floorT = if (fy != null && d.y < -0.02f) ((fy - o.y) / d.y).takeIf { it in 0.05f..RAY_MAX } else null
        val onFloor = floorT != null && (hit == null || floorT < hit.distance)
        val len = (if (onFloor) floorT!! else hit?.distance ?: RAY_IDLE).coerceIn(0.02f, RAY_MAX)
        beam.show(true)
        beam.setLocal(position = o + d * (len / 2f), rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), d), scale = Vec3(RAY_WIDTH, RAY_WIDTH, len))
        if (onFloor || hit != null) {
            dot.show(true)
            val normal = if (onFloor) Vec3.UP else -d
            dot.setLocal(position = o + d * (len - 0.002f) + normal * 0.002f, rotation = Quat4.fromTo(Vec3.UP, normal), scale = Vec3(RAY_DOT, 1f, RAY_DOT))
        } else {
            dot.show(false)
        }
        val target = if (onFloor) "floor" else hit?.let { runCatching { it.entity.getName() }.getOrNull() } ?: "none"
        if (target != rayTargetLogged) {
            rayTargetLogged = target
            PcLog.i("RAY hit=$target dist=${PcLog.f(len)} phase=${ui.phase}")
        }
        val down = s.pressed || s.trigger > 0.6f
        // only the app's own 3D targets: a panel (the explain card) is pressed by the system pointer, and an unknown hit
        // must not turn into "tapped elsewhere" (it would drop the selection)
        if (down && !rayTriggerWas && !onFloor && hit != null && cursor == null && ui.phase != Phase.RELOCATING && isRayTarget(hit.entity)) {
            PcLog.i("TAP ray target=${runCatching { hit.entity.getName() }.getOrNull()} dist=${PcLog.f(hit.distance)}")
            tapEntity(hit.entity, null, "ray")
        }
        rayTriggerWas = down
    }

    /** Debug `ray=`: where the right hand usually is, aimed at the chosen target (null when not aiming). */
    private fun debugRaySample(): ControllerSample? {
        val aim = debugAim ?: return null
        val yaw = Placement.yawOf(pose.forward)
        val ahead = Placement.dirOfYaw(yaw)
        val right = Placement.dirOfYaw(yaw - Placement.rad(90f))
        val origin = pose.position + right * 0.18f + ahead * 0.30f - Vec3(0f, 0.30f, 0f)
        val t = tree
        val p = placed
        val target: Vec3 =
            when {
                aim == "floor" && p != null && floorY != null -> {
                    val toUser = Vec3(pose.position.x - p.position.x, 0f, pose.position.z - p.position.z).normalized()
                    Vec3(p.position.x, floorY!!, p.position.z) + toUser * (treeReach * 0.5f + 0.4f)
                }
                aim == "floor" && floorY != null -> pose.position.let { Vec3(it.x, floorY!!, it.z) } + ahead * 1.2f
                aim.startsWith("ring") -> {
                    val k = aim.removePrefix("ring").toIntOrNull()
                    ladderRings.indices.firstOrNull { ladderRings[it].enabled && if (k == null) ladderBud[it] > 0 else ladderBud[it] == k }?.let { ringAt[it] } ?: return null
                }
                aim == "tree" && t != null && p != null -> t.selectable.firstOrNull()?.let { treeToWorld(it.pointAt(it.length * 0.6f)) } ?: p.position
                aim.toIntOrNull() != null && t != null && p != null -> t[aim.toInt()]?.let { treeToWorld(it.pointAt(it.length * 0.6f)) } ?: return null
                else -> pose.position + ahead * 2f
            }
        val dir = (target - origin).normalized()
        // `ray=tap`: the trigger down for two frames, then up (the ray's own press edge)
        val down = debugPressFrames > 0
        if (down) debugPressFrames--
        return ControllerSample(PointerRay(origin, dir), trigger = if (down) 1f else 0f, pressed = down, stickX = 0f, stickY = 0f, right = true)
    }

    override fun aimRay(target: String): String {
        if (target == "tap") {
            debugPressFrames = 2
            return "trigger pressed on aim=$debugAim"
        }
        debugAim = target.takeIf { it != "off" && it.isNotBlank() }
        rayTargetLogged = null
        return "aim=${debugAim ?: "real controller"}"
    }

    private fun isRayTarget(e: Entity): Boolean =
        ladderRings.any { it.id == e.id && it.enabled } || e.id == previewDisc?.id || e.id == scissors?.id || branchViews.values.any { it.isGrab(e) }

    fun onSpatialDrag(phase: String, value: SpatialDragValue) {
        when (phase) {
            "start" -> {
                val target = value.targetEntity ?: return
                when {
                    target.id == handle?.id && (ui.phase == Phase.PRUNING || ui.phase == Phase.RESULT) && placed != null -> {
                        drag = DragKind.TREE
                        dragPxToM = PX_M * (0.67f + 1.186f * pose.position.distanceTo(placed!!.position).coerceAtLeast(0.3f))
                        sink(PcEvent.Moving(true))
                        PcLog.i("DRAG tree start")
                    }
                }
            }
            "move" -> {
                val kind = drag ?: return
                val a = value.dragAmount
                val right = pose.right.let { Vec3(it.x, 0f, it.z).normalized() }
                val fwd = Vec3(pose.forward.x, 0f, pose.forward.z).normalized()
                when (kind) {
                    DragKind.TREE -> placed?.let { p -> placed = p.copy(position = p.position + right * (a.x * dragPxToM) - fwd * (a.y * dragPxToM)) }
                }
            }
            "end", "cancel" -> {
                val kind = drag ?: return
                drag = null
                when (kind) {
                    DragKind.TREE -> {
                        endTreeDrag()
                        sink(PcEvent.Moving(false))
                    }
                }
            }
        }
    }

    /** Let go of the floor handle: snap back onto the ring within 0.15 m / 20 degrees; pushed out of the clearance. */
    private fun endTreeDrag() {
        val t = tree ?: return
        val p = placed ?: return
        val r = ringPose ?: p
        var np = p
        if (Vec3(p.position.x - r.position.x, 0f, p.position.z - r.position.z).length <= 0.15f && abs(Placement.angle(spin)) <= Placement.rad(20f)) {
            np = r
            spin = 0f
            snapFlashUntil = SystemClock.elapsedRealtime() + 300L
            PcGraph.sfx.play(Sfx.Kind.SNAP)
            PcLog.i("SNAP tree back on the ring")
        }
        val toTree = Vec3(np.position.x - pose.position.x, 0f, np.position.z - pose.position.z)
        val minD = t.crownRadius + clearance(t)
        if (toTree.length < minD) {
            val dir = if (toTree.length < 1e-3f) Vec3(pose.forward.x, 0f, pose.forward.z).normalized() else toTree.normalized()
            val pushed = Vec3(pose.position.x, 0f, pose.position.z) + dir * minD
            np = np.copy(position = Vec3(pushed.x, np.position.y, pushed.z))
            PcLog.i("DRAG pushed out of the clearance")
        }
        placed = np
        PcLog.i("DRAG tree end at=${PcLog.pos(np.position)}")
    }

    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val root = stageRoot ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                root.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String = "POSE hmd pos=${PcLog.pos5(pose.position)} ${PcLog.quat5(pose.rotation)} fwd=${PcLog.pos5(pose.forward)} valid=$hasHead floorY=${floorY?.let { PcLog.f(it, 3) }} virtual=$floorVirtual"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            val to = slot.position - pose.position
            val az = Math.toDegrees(Placement.angle(Placement.yawOf(to) - Placement.yawOf(pose.forward)).toDouble())
            val el = Math.toDegrees(atan2(to.y, Vec3(to.x, 0f, to.z).length).toDouble())
            String.format(Locale.US, "PANEL id=%s pos=%s dist=%.3f az=%.1f el=%.1f scale=%.3f", slot.id, PcLog.pos(slot.position), to.length, az, el, slot.scale)
        }
    }

    override fun treeLine(): String = "TREE lesson=${tree?.lesson} at=${PcLog.pos(placed?.position)} yaw=${placed?.let { PcLog.f(Math.toDegrees(it.yaw.toDouble()).toFloat(), 1) }} spin=${PcLog.f(Math.toDegrees(spin.toDouble()).toFloat(), 1)} built=$treeBuilt views=${branchViews.size} jobs=${jobs.size}"

    override fun rotate(deg: Float) {
        spin += Placement.rad(-deg)
    }

    override fun distance(m: Float) {
        val p = placed ?: return
        val t = tree ?: return
        val (lo, hi) = distances(t)
        val dir = Vec3(p.position.x - pose.position.x, 0f, p.position.z - pose.position.z).normalized()
        val np = Vec3(pose.position.x, 0f, pose.position.z) + dir * m.coerceIn(lo, hi)
        placed = p.copy(position = Vec3(np.x, p.position.y, np.z))
    }

    override fun moveTreeOntoHead() {
        val p = placed ?: return
        placed = p.copy(position = Vec3(pose.position.x, p.position.y, pose.position.z - 0.05f))
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val FAST_FRAME_MS = 40L
        private const val SLOW_JOB_MS = 500L
        private const val HEAD_SETTLE_MS = 600L
        private const val FLOOR_TIMEOUT_MS = 5_000L
        private const val FADE_MS = 400f

        /** Controller ray: farthest hit, and the beam length when it hits nothing (m). */
        private const val RAY_MAX = 4f
        private const val RAY_WIDTH = 0.003f
        private const val RAY_DOT = 0.014f

        /** Clearance.reach per lesson (seven next-year builds: once per process). */
        private val reaches = HashMap<io.embodit.pruningclass.domain.tree.LessonId, Float>()

        /** Clearance.points per lesson, this year / with next year. */
        private val treePoints = HashMap<Pair<io.embodit.pruningclass.domain.tree.LessonId, Boolean>, List<Vec3>>()
        private const val HINT_DIST = 0.75f
        private const val HINT_PITCH_MAX = 40f
        private const val CHIP_AFTER_OPEN_MS = 8_000L
        private const val RAY_IDLE = 1.2f
        private const val DEAD = 0.15f
        private val TURN_RATE = Math.toRadians(60.0).toFloat()
        private const val LADDER_POOL = 32
        private const val MAX_RINGS = 16
        private const val MORE_UP = -2
        private const val MORE_DOWN = -3
        private const val PX_M = 0.0004f
    }
}
