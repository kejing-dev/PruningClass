package io.embodit.pruningclass.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.embodit.pruningclass.R
import io.embodit.pruningclass.domain.model.LanguagePref
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.Reason
import io.embodit.pruningclass.platform.PcLog
import io.embodit.pruningclass.ui.pc.PcEvent
import io.embodit.pruningclass.ui.pc.Phase
import io.embodit.pruningclass.ui.pc.View
import io.embodit.pruningclass.ui.pc.debug.PcProbe
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `qaHooks`). Every command goes through the same events as the user's input
 * (factory rule 2026-10-29); one log line (tag `PruningClass`) answers each. Forms: `--es cmd 'state=S06:R2'`,
 * `--es cmd 'cut=12:4'`, `--es cmd state --es v S06`.
 *
 * - `state=<Sxx>[:R1..A3]`   S00..S21 (S15b:quit|reset, S13), replaying the user's events; held until `release`
 * - `lesson=R1..A3`          pick that lesson (from the picker)
 * - `dump`                   `PC-TREE id label defect oneOf acceptable allowBase model ...` for every selectable branch
 * - `select=<id>`, `preview=<id>:<bud|base>`, `cut=<id>:<bud|base>` (= tap the branch, tap the ring, tap it again), `undo`
 * - `autocut=perfect|sloppy|half|major|none|all|over25`, `hint=1|0`, `done`, `view=now|yours|model`, `row=<id>`
 * - `explain=keepBloom|keepSpur|major|inwardBud|tooMany|tooLow|inDead|secondOfPair|overPrune`
 * - `rotate=<deg>` (+ = right), `turn=<deg>` (the panel buttons), `dist=<m>`, `pose` (tree onto the head: S21)
 * - `window=close|open` the main window's × / the "open panel" chip, `tab=lesson|card|settings` a TabBar press,
 *   `icons` (ic_* montage -> files/icons.png)
 * - `nofloor=1|0`, `floor=arrive`, `blocked=1|0`, `budzoom=1|0`, `seated=1|0`, `lang=zh|en`, `mute=1|0`, `testtex=1|0`
 * - `settings`, `recall`, `replant`, `quit`, `again`, `next`, `close`, `card`, `release`, `clear`, `ui <control>`
 * - `hmd`, `panels`, `tree`
 */
internal object DebugCommands {

    fun run(context: Context, extras: Map<String, String>) {
        val raw = extras["cmd"]?.trim() ?: return
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return
        var name = tokens.removeAt(0)
        var arg = ""
        if (name.contains('=')) {
            arg = name.substringAfter('=')
            name = name.substringBefore('=')
        }
        // only the command is case-blind: arguments such as `explain=overPrune` keep their case
        name = name.lowercase(Locale.US)
        if (arg.isEmpty() && tokens.isNotEmpty()) arg = tokens.joinToString(" ")
        extras["v"]?.let { if (arg.isEmpty()) arg = it }
        val host = PcProbe.host
        if (host == null) {
            PcLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, arg)
        } catch (t: Throwable) {
            PcLog.e("CMD $name failed: $t", t)
        }
    }

    private fun bud(s: String): Int = if (s.equals("base", true)) 0 else s.toInt()

    private fun dispatch(context: Context, host: PcProbe.Host, name: String, arg: String) {
        val scene = host.scene()
        val on = arg != "0" && !arg.equals("off", true)
        when (name) {
            "state", "goto" -> host.dispatch(PcEvent.DebugState(arg))
            "lesson" -> host.dispatch(PcEvent.LessonPicked(LessonId.of(arg) ?: return PcLog.i("CMD lesson needs R1..A3")))
            "select" -> host.dispatch(PcEvent.BranchTapped(arg.toInt()))
            // the user taps the branch only when it is not selected yet (a second tap on it steps back)
            "preview" -> {
                val (b, k) = arg.split(':')
                if (host.uiState().selected != b.toInt()) host.dispatch(PcEvent.BranchTapped(b.toInt()))
                host.dispatch(PcEvent.BudTapped(b.toInt(), bud(k)))
            }
            "cut" -> {
                val (b, k) = arg.split(':')
                if (host.uiState().selected != b.toInt()) host.dispatch(PcEvent.BranchTapped(b.toInt()))
                host.dispatch(PcEvent.BudTapped(b.toInt(), bud(k)))
                host.dispatch(PcEvent.BudTapped(b.toInt(), bud(k)))
            }
            "undo" -> host.dispatch(PcEvent.Undo)
            "autocut" -> host.dispatch(PcEvent.DebugAutocut(arg.lowercase(Locale.US)))
            "hint" -> if (host.uiState().hints != on) host.dispatch(PcEvent.HintToggle)
            "done" -> host.dispatch(PcEvent.Done)
            "view" -> host.dispatch(PcEvent.ViewSelected(when (arg.lowercase(Locale.US)) { "yours" -> View.YOURS; "model" -> View.MODEL; else -> View.NOW }))
            "row" -> host.dispatch(PcEvent.ReviewRow(arg.toInt()))
            "explain" -> {
                val r = when (arg) {
                    "keepBloom" -> Reason.KEEP_BLOOM
                    "keepSpur" -> Reason.KEEP_SPUR
                    "major" -> Reason.MAJOR
                    "inwardBud" -> Reason.INWARD_BUD
                    "tooMany" -> Reason.TOO_MANY
                    "tooLow" -> Reason.TOO_LOW
                    "inDead" -> Reason.IN_DEAD
                    "secondOfPair" -> Reason.SECOND_OF_PAIR
                    "overPrune" -> Reason.OVER_PRUNE
                    else -> return PcLog.i("CMD explain: unknown $arg")
                }
                host.dispatch(PcEvent.DebugExplain(r))
            }
            "rotate" -> scene?.rotate(arg.toFloat())
            "ray" -> PcLog.i("CMD ray ${scene?.aimRay(arg) ?: "no scene"}")
            "turn" -> host.dispatch(PcEvent.Turn(arg.toFloat()))
            "dist" -> scene?.distance(arg.toFloat())
            "window" -> host.window(!arg.equals("close", true))
            "tab" -> host.dispatch(PcEvent.TabSelected(arg.lowercase(Locale.US)))
            "pose" -> {
                scene?.moveTreeOntoHead()
                PcLog.i("CMD pose: tree moved onto the head")
            }
            "nofloor" -> host.dispatch(PcEvent.DebugNoFloor(on))
            "floor" -> host.dispatch(PcEvent.DebugFloorArrive)
            "blocked" -> host.dispatch(PcEvent.DebugBlocked(on))
            "budzoom" -> host.dispatch(PcEvent.BudZoom(on))
            "seated" -> host.dispatch(PcEvent.Seated(on))
            "lang" -> host.dispatch(PcEvent.Language(if (arg == "zh") LanguagePref.ZH else if (arg == "en") LanguagePref.EN else LanguagePref.SYSTEM))
            "mute" -> host.dispatch(PcEvent.Sound(!on))
            "testtex" -> host.dispatch(PcEvent.DebugTestTexture(on))
            "settings" -> host.dispatch(PcEvent.SettingsToggle)
            "recall" -> {
                // [Bring here] sits on the settings page
                if (host.uiState().phase != Phase.SETTINGS) host.dispatch(PcEvent.SettingsToggle)
                host.dispatch(PcEvent.Recall)
            }
            "replant" -> host.dispatch(PcEvent.Replant)
            "quit" -> {
                host.dispatch(PcEvent.AskQuit)
                host.dispatch(PcEvent.ConfirmYes)
            }
            "again" -> host.dispatch(PcEvent.Again)
            "next" -> host.dispatch(PcEvent.NextLesson)
            "close" -> host.dispatch(PcEvent.Close)
            "card" -> host.dispatch(PcEvent.OpenRuleCard)
            "release" -> host.dispatch(PcEvent.DebugRelease)
            "clear" -> host.dispatch(PcEvent.DebugClear)
            "ui" -> PcLog.i(if (host.pressControl(arg)) "CMD ui $arg pressed" else "CMD ui $arg not composed (open its panel first)")
            "dump" -> {
                val text = host.dumpJson()
                runCatching { File(context.filesDir, "dump.txt").writeText(text) }
                text.lines().forEach { PcLog.i(it) }
            }
            "icons" -> PcLog.i("ICONS ${iconMontage(context)}")
            "hmd" -> PcLog.i(scene?.poseLine() ?: "POSE no scene")
            "panels" -> scene?.panelLines()?.forEach { PcLog.i(it) } ?: PcLog.i("PANEL no scene")
            "tree" -> PcLog.i(scene?.treeLine() ?: "TREE no scene")
            else -> PcLog.i("CMD $name unknown")
        }
    }

    /** Every `ic_*` drawable drawn by Android itself, 6 per row with its name (factory D23 montage for ui-audit.md). */
    private fun iconMontage(context: Context): String {
        val icons = R.drawable::class.java.fields.filter { it.name.startsWith("ic_") }.sortedBy { it.name }
        val cell = 200
        val cols = 6
        val rows = (icons.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 18f; textAlign = Paint.Align.CENTER }
        icons.forEachIndexed { i, f ->
            val x = (i % cols) * cell
            val y = (i / cols) * cell
            val d = context.getDrawable(f.getInt(null)) ?: return@forEachIndexed
            if (f.name.startsWith("ic_launcher")) {
                c.drawRect(x + 52f, y + 24f, x + 148f, y + 120f, Paint().apply { color = 0xFF8A9A2B.toInt() })
            } else {
                d.setTint(Color.BLACK)
            }
            d.setBounds(x + 52, y + 24, x + 148, y + 120)
            d.draw(c)
            c.drawText(f.name.removePrefix("ic_"), x + cell / 2f, y + 160f, text)
        }
        val out = File(context.filesDir, "icons.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "${icons.size} -> ${out.absolutePath}"
    }
}
