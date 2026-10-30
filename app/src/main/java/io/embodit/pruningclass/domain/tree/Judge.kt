package io.embodit.pruningclass.domain.tree

/** Why a target got half credit or why a cut was wrong (design `copy.explain` keys). */
enum class Reason { KEEP_BLOOM, KEEP_SPUR, MAJOR, INWARD_BUD, OVER_PRUNE, TOO_MANY, TOO_LOW, IN_DEAD, SECOND_OF_PAIR, PARTIAL }

/** State of one branch after all cuts. */
sealed interface Status {
    data object Intact : Status

    /** Own cut above bud [bud] (bud 0 = removed at its base). */
    data class Cut(val bud: Int) : Status

    /** Removed together with an ancestor that was cut below it. */
    data class Gone(val by: Int) : Status
}

data class TargetResult(val ids: List<Int>, val credit: Float, val reason: Reason?, val hit: Int?)

data class Wrong(val branch: Int, val reason: Reason)

data class Score(
    val lesson: LessonId,
    val credit: Float,
    val must: Int,
    val wrong: Int,
    val major: Int,
    val strongKept: Int,
    val removedPct: Float,
    val targets: List<TargetResult>,
    val wrongs: List<Wrong>,
) {
    val ratio: Float
        get() = if (must == 0) 1f else credit / must

    val percent: Int
        get() = Math.round(ratio * 100f)

    /** Exam pass and practice tick (design `rules.judge.pass`). */
    val pass: Boolean
        get() =
            ratio >= 0.8f - 1e-4f && wrong <= 1 && major == 0 &&
                (lesson.species != Species.ROSE || strongKept >= 3) && (lesson.species != Species.APPLE || removedPct <= 20f + 1e-3f)

    /** The reason shown on the result line: a major cut first, then the apple 20 % rule (the lessons' main rule). */
    val failReason: String?
        get() =
            when {
                pass -> null
                major > 0 -> "major"
                lesson.species == Species.APPLE && removedPct > 20f + 1e-3f -> "removed"
                ratio < 0.8f - 1e-4f -> "ratio"
                wrong > 1 -> "wrong"
                else -> "strong"
            }
}

/**
 * Judging (design `rules.judge`): from the final state only (the lowest cut of a branch counts, undone cuts do not).
 * Credit per MUST target or crossing group 1 / 0.5 / 0; wrong cuts on keep / ok branches; major cuts; rose strong canes
 * kept; apple removal share of the crown.
 */
object Judge {
    /** The lowest own cut per branch. */
    fun ownCuts(cuts: List<Cut>): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        for (c in cuts) m[c.branch] = minOf(m[c.branch] ?: Int.MAX_VALUE, c.bud)
        return m
    }

    fun status(tree: Tree, cuts: List<Cut>): Map<Int, Status> {
        val own = ownCuts(cuts)
        val out = HashMap<Int, Status>()
        fun of(b: Branch): Status {
            out[b.id]?.let { return it }
            val parent = b.parent?.let { tree[it] }
            if (parent != null) {
                when (val ps = of(parent)) {
                    is Status.Gone -> return Status.Gone(ps.by).also { out[b.id] = it }
                    is Status.Cut -> if (parent.cutS(ps.bud) <= b.parentS) return Status.Gone(parent.id).also { out[b.id] = it }
                    Status.Intact -> Unit
                }
            }
            val s = own[b.id]?.let { Status.Cut(it) } ?: Status.Intact
            out[b.id] = s
            return s
        }
        tree.branches.forEach { of(it) }
        return out
    }

    /** Credit of one MUST branch in its state; reason when below 1. */
    fun credit(tree: Tree, b: Branch, st: Status): Pair<Float, Reason?> =
        when (st) {
            Status.Intact -> 0f to null
            is Status.Gone -> {
                val by = tree[st.by]
                if (by?.label == Label.MAJOR) 0f to Reason.MAJOR else if (b.allowBase) 1f to null else 0.5f to Reason.TOO_LOW
            }
            is Status.Cut -> {
                val k = st.bud
                val acc = b.acceptable
                when {
                    k == 0 -> if (b.allowBase) 1f to null else 0.5f to Reason.TOO_LOW
                    b.healthyBelow != null && k >= b.healthyBelow -> 0.5f to Reason.IN_DEAD
                    acc == null -> 0.5f to Reason.PARTIAL
                    k in acc -> if (b.needOutward && b.buds.firstOrNull { it.index == k }?.facing == Facing.IN) 0.5f to Reason.INWARD_BUD else 1f to null
                    k > acc.last -> 0.5f to Reason.TOO_MANY
                    else -> 0.5f to Reason.TOO_LOW
                }
            }
        }

    fun score(tree: Tree, cuts: List<Cut>): Score {
        val st = status(tree, cuts)
        val targets = ArrayList<TargetResult>()
        val wrongs = ArrayList<Wrong>()
        var major = 0
        // order of the cuts: in a crossing pair the member cut first is the answer, the other one is the wrong cut
        val firstCut = HashMap<Int, Int>()
        cuts.forEachIndexed { i, c -> firstCut.putIfAbsent(c.branch, i) }
        for (group in tree.targets) {
            val changed = group.filter { st[it.id] != Status.Intact }
            if (changed.isEmpty()) {
                targets += TargetResult(group.map { it.id }, 0f, null, null)
                continue
            }
            val scored = changed.map { Triple(it, credit(tree, it, st.getValue(it.id)).first, credit(tree, it, st.getValue(it.id)).second) }
            val best = scored.minBy { firstCut[it.first.id] ?: Int.MAX_VALUE }
            targets += TargetResult(group.map { it.id }, best.second, best.third, best.first.id)
            // a crossing pair: the second one cut is a wrong cut (its own cut only)
            for (other in scored) if (other !== best && st[other.first.id] is Status.Cut) wrongs += Wrong(other.first.id, Reason.SECOND_OF_PAIR)
        }
        for (b in tree.branches) {
            val s = st.getValue(b.id) as? Status.Cut ?: continue
            when (b.label) {
                Label.MAJOR -> major++
                Label.KEEP -> wrongs += Wrong(b.id, if (b.defect == Defect.SPUR) Reason.KEEP_SPUR else Reason.KEEP_BLOOM)
                Label.OK -> if (b.selectable && s.bud < 2) wrongs += Wrong(b.id, if (tree.species == Species.ROSE) Reason.KEEP_BLOOM else Reason.KEEP_SPUR)
                Label.MUST -> Unit
            }
        }
        // a MUST target taken away by a major cut scores 0 (already in credit())
        val strong = strongKept(tree, st)
        if (tree.species == Species.ROSE && strong < 3) major++
        val credit = targets.sumOf { it.credit.toDouble() }.toFloat()
        return Score(tree.lesson, credit, tree.mustCount, wrongs.size, major, strong, removedPct(tree, st), targets, wrongs)
    }

    /** Rose: canes thicker than 10 mm (not dead) with at least two buds left. */
    fun strongKept(tree: Tree, st: Map<Int, Status>): Int =
        tree.branches.count { b ->
            b.parent == null && b.diameterMm > 10f && b.defect != Defect.DEAD &&
                when (val s = st.getValue(b.id)) {
                    Status.Intact -> true
                    is Status.Cut -> s.bud >= 2
                    is Status.Gone -> false
                }
        }

    /** Share of the crown (all wood but the trunk) removed, percent. */
    fun removedPct(tree: Tree, st: Map<Int, Status>): Float {
        var total = 0f
        var removed = 0f
        for (b in tree.branches) {
            if (b.defect == Defect.TRUNK) continue
            val v = b.volumeFrom(0f)
            total += v
            removed +=
                when (val s = st.getValue(b.id)) {
                    Status.Intact -> 0f
                    is Status.Gone -> v
                    is Status.Cut -> b.volumeFrom(b.cutS(s.bud))
                }
        }
        return if (total <= 0f) 0f else removed / total * 100f
    }

    /**
     * Immediate feedback for practice lessons after [cut] (design `feedbackPractice`): 1 = green, 0.5 = orange + reason,
     * wrong = red + reason. Null reason with credit 1 means a clean cut; a cut on a non-target ok branch that keeps two
     * buds is neutral (credit null).
     */
    fun feedback(tree: Tree, cuts: List<Cut>, cut: Cut): Pair<Float?, Reason?> {
        val score = score(tree, cuts)
        score.wrongs.lastOrNull { it.branch == cut.branch }?.let { return 0f to it.reason }
        if (tree.species == Species.APPLE && score.removedPct > 20f && score(tree, cuts.dropLast(1)).removedPct <= 20f) return 0.5f to Reason.OVER_PRUNE
        val b = tree[cut.branch] ?: return null to null
        if (b.label == Label.MAJOR) return 0f to Reason.MAJOR
        val t = score.targets.firstOrNull { cut.branch in it.ids } ?: return null to null
        return t.credit to t.reason
    }

    // ---------------------------------------------------------------------------------------------
    // answers and the autocut debug sets
    // ---------------------------------------------------------------------------------------------

    fun model(tree: Tree): List<Cut> = tree.branches.mapNotNull { b -> b.modelCut?.let { Cut(b.id, it) } }

    /** Each MUST cut moved to the neighbouring outward / side bud inside its range (design D27 sloppy test). */
    fun sloppy(tree: Tree): List<Cut> =
        model(tree).map { c ->
            val b = tree[c.branch]!!
            val acc = b.acceptable
            if (c.bud == 0 || acc == null) c
            else {
                val alt = b.buds.filter { it.index in acc && it.index != c.bud && it.facing != Facing.IN }.minByOrNull { kotlin.math.abs(it.index - c.bud) }
                if (alt != null) Cut(b.id, alt.index) else c
            }
        }

    fun half(tree: Tree): List<Cut> = model(tree).let { it.take(it.size / 2) }

    /** Model + a major cut: a scaffold (apple) or three strong canes to the ground (rose). */
    fun major(tree: Tree): List<Cut> =
        if (tree.species == Species.APPLE) model(tree) + Cut(tree.branches.first { it.defect == Defect.MAIN }.id, 0)
        else model(tree) + tree.branches.filter { it.parent == null && it.label == Label.OK && it.diameterMm > 10f }.map { Cut(it.id, 0) } +
            tree.branches.filter { it.defect == Defect.REDUCE && it.diameterMm > 10f }.map { Cut(it.id, 0) }

    /** Every selectable top-level branch cut at its base (and every selectable branch for the apple). */
    fun all(tree: Tree): List<Cut> =
        tree.branches.filter { it.selectable && it.label != Label.MAJOR && (tree.species == Species.APPLE || it.parent == null) }.map { Cut(it.id, 0) }

    /**
     * Apple: model + healthy laterals headed back to their second bud until more than 25 % is gone (design acceptance
     * "A3 剪掉 25 %": legal cuts that only over-prune; cutting laterals at the base would be wrong cuts instead).
     */
    fun over25(tree: Tree): List<Cut> {
        val cuts = model(tree).toMutableList()
        val laterals = tree.branches.filter { it.selectable && it.label == Label.OK && it.defect == Defect.HEALTHY }
        for (b in laterals.filter { it.buds.size >= 3 }) {
            if (removedPct(tree, status(tree, cuts)) > 25f) return cuts
            cuts += Cut(b.id, 2)
        }
        // a small tree (A1) does not get there by heading back: then laterals go at the base (wrong cuts as well)
        for (b in laterals) {
            if (removedPct(tree, status(tree, cuts)) > 25f) break
            cuts += Cut(b.id, 0)
        }
        return cuts
    }
}
