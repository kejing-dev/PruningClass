# Pruning Class (修枝课)

Learn to prune on a life-size tree standing in your room: a potted rose on a 0.40 m stand or a young apple tree on the
floor. Pick a branch, pick the bud on a numbered ladder beside it, tap again to cut. The lesson judges each cut against
the rules (dead / diseased / damaged first, thin canes, crossing pairs, outward buds, leaders, the 20 % rule) and shows
the tree next year with your cuts against the model answer. Six lessons: Rose 1 - 3, Apple 1 - 3; lesson 3 of each tree
is an exam. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space, passthrough) for the tree plus one
Planar system window for every 2D page, package `io.embodit.pruningclass`, v0.1.1.

Design package (binding): `../spatial-app-factory/apps/pruning-class/design-package.json` (states S00 - S21, interactions
I1 - I25, `rules`, `panels`, `entities3d`, `copy`). Tree look ported from the design's sample `trees.js`. Skeleton copied
from `../juggle-coach` (Stage, PanelRig pool, fonts, debug receiver, harness); `PlaneTracker` from `../figure-model`.
Gate B open items took the design station's recommended defaults: rose on the 0.40 m stand, apple with four scaffolds
(variant A), bud zoom on, no free-practice mode, no VR garden.

Built by the factory's build station on 2026-10-30; v0.1.1 (2026-11-03) is the factory rework "native window template"
(prompts/snippets/native-window-move.md, taste D8 / D21): the docked Stage panels and their self-built 3D drag bar
were replaced by one system window whose own controls (the dot under it: × / move / −) move, close and minimise it.
Self-tested on emulator-5560 (AVD Pico_Emulator_6_1_fresh).

## Structure

```
app/src/main/java/io/embodit/pruningclass/
  Main.kt                      DefaultStage { PcScreen() } + WindowContainer("PruningClassWindow", Planar, 600 x 780 dp) { PcWindow() }
  platform/                    SpatialApplication, LaunchActivity, PcGraph (wiring), PcLog (tag PruningClass)
    tracking/                  HMD pose, block reason (an all-zero pose is invalid)
    input/ControllerInput      stick X / Y (axis lock), trigger, controller ray
    sense/PlaneTracker         FLOOR planes, only while a tree is being placed               [Figure Model]
    audio/Sfx                  snip / right / wrong / snap, synthesised once, one streaming AudioTrack
  domain/model                 Vec3 / Quat4, PcModel (settings, records, enums), Surface
  domain/tree/                 Tree (Branch / Bud / Piece / Cut, labels MUST / OK / KEEP / MAJOR, oneOf groups,
                               acceptable bud ranges), Lessons (R1..A3: species, seeds, must counts, rules),
                               TreeGen (seeded rose / apple generator), Judge (credit 1 / 0.5 / 0, wrong / major cuts,
                               pass rules, answer sets model / sloppy / half / major / all / over25), NextYear (counts)
  domain/usecase/Placement     placeUpright (yaw only), treeAhead / treeBesideWindow (45 deg right), dockExplain;
                               PlaceMachine (LOCKED / PLACING)
  data/Store                   SharedPreferences "pruning_class": settings, records, graduations, cuts per lesson
  ui/theme                     PcColors (the only colour literals), PcTheme (PicoTheme), Noto fonts
  ui/pc                        PcViewModel (state machine + DebugJump), PcUiState / PcEvent / PcPages, PcShared (the one
                               ViewModel + the window's lifecycle, shared by the two containers), PcScreen (Stage)
  ui/pc/window                 MainWindow (factory template, controlled TabBar + exitApp), PcWindow (the three pages)
  ui/pc/components             PcStage (SpatialView + 2 Stage panels: explain card, "open panel" chip), Panels (window
                               pages: control, welcome, picker, rules, card, result, settings; the explain card),
                               BlockedDialog, Common (WindowPage, Glass, buttons, overflow log), PcStrings
  ui/pc/scene                  PcScene (the one ECS System: floor, placement, tree, cuts, ladder, preview, falling
                               pieces, next year, relocate, the chip, the tree drag), PcViews (materials, meshes, colliders),
                               TreeMesher (tube sweep, bud cones, caps, leaves / flowers), PanelRig, PanelSpec, SceneKit
  ui/pc/debug/PcProbe          the debug build's view into the running scene
app/src/debug/                 DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/                  TreeTest (lessons, judge, next year, answer sets), PlacementTest (upright under pitch / roll,
                               four sides, PlaceMachine, explain card), SfxLevelTest (peaks <= -1 dBFS)
tools/                         pc.sh (cmd / shot / log / state / launch / stop / install), shots.sh (every state),
                               shots_panel.sh (one close-up per panel, zh + en), cam.sh (turn / pitch the emulator
                               camera by degrees), contrast.py (measured WCAG ratio on a screenshot), cold_start.sh,
                               stress_panels.sh, make_icon.py, make_strings.py (EN / ZH strings),
                               harness/ (emulator input: fast fps, drags, keys, camera toggles)
```

One direction of data: window controls (TabBar presses are `PcEvent.TabSelected`), scene taps / drags / stick, scene
milestones (panels ready, floor found, placed) and debug hooks -> `PcViewModel.onEvent` -> `PcUiState` -> `PcScene.render`
and the window. The page shown follows the phase (`PcPages.of`): lesson (welcome / picker / rules / control / result /
graduation / blocked), rule card, settings. Everything that moves is
written per frame in `PcScene.onFrame` and never goes through Compose.

## Rules as built

- Floor: PlaneTracker FLOOR while placing; after 5 s without a plane a virtual floor at head - 1.60 m (seated 1.20) +
  offset, drawn as a faint grid; a plane arriving later snaps the tree onto it.
- Window: opened by the Stage when it starts; the system places it straight ahead (~1.75 m) and its own controls move,
  close and minimise it (no drag code in the app). × -> the Stage shows an "open panel" chip (0.8 m ahead, 20 deg left,
  15 deg below) -> opens it again. [Bring here] (settings) = close, open again after ON_DESTROY (an open right after a
  close is swallowed), and the tree re-placed. Quit = release + closeWindowContainer + closeStage + finishAndRemoveTask
  + exitProcess (template order).
- Tree: 45 degrees right of the head heading at the moment it is placed (rose 1.10 m, apple 2.00 m), front towards you.
  Placing follows a press in the window (a lesson card, [Try again], [Bring here], which also reopens the window in
  front), so the tree lands beside the window, not between you and it (no API places a window next to Stage content).
- Stick: X turns the tree (60 deg/s, no snap), Y pushes / pulls along the you -> trunk line (rose 1.10 - 2.00 m, apple
  2.00 - 3.20 m); first axis past the dead zone wins. With a branch selected Y moves the bud cursor, trigger previews /
  cuts; B / Y is the back key (leaves the preview, then the selection: no system back key reaches the app). Floor handle
  drag: release within 0.15 m / 20 degrees snaps back onto the plant ring.
- Cuts hide the pieces above the cut, drop a falling piece (0.6 s, fades in 2 s) and show a slanted cap. Mesh work is
  queued: at most 2 meshes per frame (dynamic mesh ANR lesson).
- Judging is lenient (factory D27): a side-facing bud counts as outward; a cut one bud off scores 0.5.

## QA hooks (debug build only)

```
adb -s emulator-5560 shell "am broadcast -a io.embodit.pruningclass.DEBUG -p io.embodit.pruningclass --es cmd 'state=S06:R1'"
tools/pc.sh cmd 'state=S06:R1'      # same, through the helper
```

Every command goes through the same events as the user's input. **Recipe: enter the first lesson the real way
(`state=S02`, then `lesson=R1`) so the scene finds the room's floor (y = 0.40 on the emulator); state jumps that have to
synthesise a floor put a virtual one at 0.05.**

| command | effect |
|---|---|
| `state=S00..S21[:R1..A3]`, `S15b:quit\|reset` | replay the user's events to that state and hold it until `release` (S09 = explain card, S10 = hints, S11 = sloppy answer done, S12 = next year, S13 = graduation, S16 = blocked, S20 = relocating, S21 = inside the crown) |
| `lesson=R1..A3` | pick that lesson on the picker |
| `select=<id>`, `preview=<id>:<bud\|base>`, `cut=<id>:<bud\|base>`, `undo` | tap a branch, tap a ladder ring, tap it again |
| `autocut=perfect\|sloppy\|half\|major\|none\|all\|over25`, `hint=1\|0`, `done`, `view=now\|yours\|model`, `row=<id>` | answer sets and the result page |
| `explain=keepBloom\|keepSpur\|major\|inwardBud\|tooMany\|tooLow\|inDead\|secondOfPair\|overPrune` | the explain card for that reason |
| `rotate=<deg>`, `turn=<deg>`, `dist=<m>`, `pose` | turn / move the tree; `pose` moves it onto the head (S21) |
| `nofloor=1\|0`, `floor=arrive`, `blocked=1\|0`, `budzoom=1\|0`, `seated=1\|0`, `lang=zh\|en`, `mute=1\|0`, `testtex=1\|0` | sensing and settings ("F↑" on every textured plate) |
| `settings`, `recall`, `replant`, `quit`, `again`, `next`, `close`, `card`, `release`, `clear`, `ui <control>` | panel actions; `recall` opens settings first (the button lives there); `card` works from the picker / settings. `ui` names: every worded button by its first argument (`done`, `undo`, `hint`, `set_quit`, `confirm_yes`, `replant_cancel`, ...), `sound`, `card_R1..card_A3`, `row_<id>` |
| `window=close\|open`, `tab=lesson\|card\|settings`, `icons` | the window's × / the "open panel" chip; a TabBar press; every `ic_*` drawn by Android into `files/icons.png` (`adb exec-out run-as io.embodit.pruningclass cat files/icons.png > icons.png`) |
| `dump`, `hmd`, `panels`, `tree` | queries (`PC-TREE` lines for every selectable branch) |

Log tag `PruningClass`: `PC-STATE <qaName> cuts= sel= prev= removed=` on every state change (qaName =
base[+overlays][:lesson], e.g. `S07+S10:R2`), `PC-SCORE` after "Done", `PC-NEXT` (next-year counts, yours / model),
`PC-LAYOUT` at 1 / 3 / 5 s after placing (tree azimuth, window lifecycle), `PC-PLACE`, `WINDOW <lifecycle>` / `WINDOW
recall`, `PAGE <tab>`, `OVERFLOW <panel>/<text>` (a fixed-line text that does not fit), plus `FLOOR`, `PLANES`, `TAP`,
`DRAG`, `SNAP`, `CUT`, `CURSOR`, `EXPLAIN`, `PANEL`, `QUIT`, `EVENT`, `CMD`, and `SCENE job queue N (runaway?)` when more than
300 mesh jobs wait (something re-queues every frame).

Screenshots: `tools/shots.sh` walks every designed state (camera pitched ~30 degrees down for the tree);
`tools/shots_panel.sh` takes one close-up per window page (zh + en) with the camera level, facing the window. The window
is a system layer: both the console screenshot and `adb exec-out screencap` show it. The tree goes 45 degrees right of
the view at the moment it is placed, so for tree shots enter the lesson facing the window, then turn the camera ~22
degrees right and ~25 down (turn back before a jump that starts another lesson). The explain card
and chip are rebuilt 1.5 / 4 / 8 / 12 / 20 s after a change (the idle emulator can keep a half-drawn frame): capture after
15-25 s. `tools/cam.sh left|up <deg>` turns the camera (needs `harness/uia_cam.ps1 set camera_rotation_button on`).
Stress: `tools/cold_start.sh 3`, `tools/stress_panels.sh 30` (in a lesson). Scripts default to emulator-5560. Stopping a
background script from the agent's task tool can leave its bash child running: check for stray `shots*.sh` processes
before the next run.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon --offline
.\gradlew.bat testDebugUnitTest --no-daemon --offline
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` must be
empty. Icon: `python tools/make_icon.py ../spatial-app-factory/apps/pruning-class/icon.png`. Strings:
`python tools/make_strings.py` (EN / ZH from the design's `copy`).

## Deviations (recorded in the factory's state.json)

v0.1.1 rework "native window template": every 2D page in one Planar system window (600 x 780 dp) with the native TabBar
(lesson / rule card / settings) instead of docked Stage panels with a 3D drag bar; the tree 45 degrees right of the window
instead of straight ahead; [Bring here] reopens the window; page-switch buttons removed (settings key, [Rule card]); the
control page also lists this lesson's rules; the 6 m invisible "backstop" shell (tap on empty space -> leave the selection)
is gone (factory D24 hard gate: no InteractableComponent on a room shell): a second tap on the selected branch or B / Y
steps back instead; the explain card swings at most 15 degrees left of the tree line (the window is 45 degrees left).
Earlier: control rows >= 48 dp (result page five review rows); hint / review marks are a whole-branch material tint instead of a dashed
outline tube; bark is a procedural texture and leaves / flowers are mesh geometry (no CC0 textures in the APK); the bud
cursor is stick + trigger; next year has no 0.4 s push-back step; ladder "more" rings show +/- and the base ring shows
"✕" as text.
