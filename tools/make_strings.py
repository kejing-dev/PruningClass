"""Writes res/values/strings.xml (EN) and res/values-zh/strings.xml (ZH) from one table, so the two never drift.
Copy from the design package `copy` (+ the RHS rule texts of `content.species[].rules`).

    python tools/make_strings.py
"""
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")

S = [
    ("app_name", "Pruning Class", "修枝课"), ("lang_code", "en", "zh"),
    ("title_app", "Pruning Class", "修枝课"),
    ("lesson_R1", "Rose 1 · Dead & thin", "月季 1 · 枯病伤与细弱枝"), ("lesson_R2", "Rose 2 · Crossing & buds", "月季 2 · 交叉与留芽"), ("lesson_R3", "Rose 3 · Exam", "月季 3 · 考试"),
    ("lesson_A1", "Apple 1 · Dead & shoots", "苹果 1 · 枯枝根蘖徒长"), ("lesson_A2", "Apple 2 · Leaders & load", "苹果 2 · 树心与延长枝"), ("lesson_A3", "Apple 3 · Exam", "苹果 3 · 考试"),
    ("card_R1", "Dead wood & thin stems", "枯病伤与细弱枝"), ("card_R2", "Crossing & bud count", "交叉、芽向、留芽数"), ("card_R3", "Full prune exam", "综合考试"),
    ("card_A1", "Dead wood, suckers, shoots", "枯病伤、根蘖、徒长枝"), ("card_A2", "Centre, leaders, 20% rule", "树心、延长枝、别剪太多"), ("card_A3", "Full prune exam", "综合考试"),
    ("species_rose", "Rose", "月季"), ("species_apple", "Apple", "苹果"),
    ("rule_R1", "Remove dead, diseased and damaged wood first: cut back to healthy green wood.", "先去枯、病、伤：剪回到健康的绿色木质部"),
    ("rule_R2", "Remove crossing canes (one of each pair) and canes growing into the centre: keep an open cup.", "去掉交叉摩擦的枝（一对剪一根）和往树心长的枝，让中心开敞成杯形"),
    ("rule_R3", "Cut no more than 5 mm above an outward bud, sloping away from it (the app snaps the cut).", "剪口在外向芽上方不超过 5 mm，斜口背离芽（应用自动吸附）"),
    ("rule_R4", "Strong canes (over 10 mm) keep 4–6 buds, weak canes (7–10 mm) keep 2–4.", "强枝（> 10 mm）留 4–6 个芽，弱枝（7–10 mm）留 2–4 个芽"),
    ("rule_R5", "Canes thinner than a pencil (under 7 mm) go at the base.", "比铅笔细（< 7 mm）的细弱枝从基部去掉"),
    ("rule_A1", "Remove dead, diseased (canker) and damaged wood first, and suckers from the rootstock.", "先去枯、病、伤（含溃疡病斑）和砧木上冒出的根蘖"),
    ("rule_A2", "Remove strong branches growing into the centre and crossing branches: keep the centre open.", "去掉往树心长的强枝和交叉摩擦的枝，保持中心开敞"),
    ("rule_A3", "Watershoots (upright, vigorous, no flower buds) go at their base.", "徒长枝（直立、粗、无花芽）从基部去掉"),
    ("rule_A4", "Shorten each leader by about a third, to an outward bud.", "主枝延长枝剪掉约 1/3，剪到外向芽"),
    ("rule_A5", "Remove no more than 20% of the crown in one winter; keep short twigs and spurs for fruit.", "一冬去除量不超过树冠的 20%；20 cm 以下的弱枝和短果枝留着结果"),
    ("rule_source", "RHS", "RHS"),
    ("hint_s00", "Getting ready…", "准备中…"),
    ("hint_s03", "Look at the floor…", "看一下地面…"),
    ("hint_s03_timeout", "No floor found · Using a virtual floor", "没找到地面，先用虚拟地面"),
    ("hint_real_floor", "Moved onto the real floor", "已贴到真实地面"),
    ("hint_s06", "Stick: left/right turns · fwd/back moves", "摇杆左右转树 · 前后推远拉近"),
    ("hint_s07", "Tap a bud on the ladder · ✕: whole stem", "在芽梯上点一个芽 · 剪刀环整枝去掉"),
    ("hint_s07_cursor", "Stick up/down picks a bud · Trigger previews", "摇杆上下选芽 · 扳机预览"),
    ("hint_s08", "Tap again to cut · Tap elsewhere to cancel", "再点一下剪 · 点别处取消"),
    ("hint_s10", "Orange branches need a cut", "橙色的枝都该剪"),
    ("hint_s19", "Let go to drop · Snaps to the ring", "松手放下 · 拖回虚线圈附近会吸回去"),
    ("hint_s20", "Point at the floor, pull trigger · or pinch", "手柄指向地面按扳机 · 或看着地面捏合"),
    ("hint_s21", "You are inside the tree · Pull it back", "你在树里面 · 拉远一点或重开面板"),
    ("hint_result", "Tap a row: the tree turns to that branch", "点一行：树转到那根枝"),
    ("exam_note", "Exam: no feedback until Done", "考试：剪的时候不提示"),
    ("ladder_pick", "Tap a bud", "点一个芽"), ("ladder_confirm", "Tap again to cut", "再点一下剪"),
    ("explain_keepBloom", "Wrong cut: a strong flowering cane", "剪错了：这是今年开花的强枝"),
    ("explain_keepSpur", "Wrong cut: a fruiting spur", "剪错了：这是短果枝，剪了明年少结果"),
    ("explain_major", "Wrong cut: a main scaffold branch", "剪错了：这是主枝，要好几年才长回来"),
    ("explain_inwardBud", "Inward bud: next shoot grows inward", "芽朝里：明年新枝会往树心长"),
    ("explain_overPrune", "Too much: over 20% brings watershoots", "剪太多了：超过 20% 明年会冒徒长枝"),
    ("explain_tooMany", "Too many buds: keep 4–6 on strong canes", "留太多：强枝留 4–6 个芽"),
    ("explain_tooManyWeak", "Too many buds: keep 2–4 on weak canes", "留太多：弱枝留 2–4 个芽"),
    ("explain_tooManyLeader", "Too little off: shorten the leader by a third", "剪少了：延长枝剪掉约 1/3"),
    ("explain_tooLow", "Too low: keep 4 buds on strong canes", "剪太狠：强枝至少留 4 个芽"),
    ("explain_tooLowWeak", "Too low: keep 2 buds on weak canes", "剪太狠：弱枝至少留 2 个芽"),
    ("explain_tooLowLeader", "Too much off: take about a third", "剪太多：延长枝只剪约 1/3"),
    ("explain_inDead", "Still dead wood: cut lower to green wood", "没剪干净：再往下剪到绿色木头"),
    ("explain_secondOfPair", "Crossing pair: cut just one of them", "交叉的一对只剪一根"),
    ("explain_partial", "Take this one off at its base", "这根要从基部整枝去掉"),
    ("explain_hint", "Use rule %1$s on this branch", "这根用规则 %1$s"),
    ("explain_ok", "This one is right", "这根剪对了"),
    ("action_done", "Done", "剪完了"), ("action_undo", "Undo", "撤销"),
    ("action_hint_off", "Hints: off", "提示：关"), ("action_hint_on", "Hints: on", "提示：开"),
    ("action_turn_left", "Turn left 45°", "左转 45°"), ("action_turn_right", "Turn right 45°", "右转 45°"),
    ("action_rejoin", "Undo cut", "接回去"), ("action_got_it", "Got it", "知道了"),
    ("action_again", "Try again", "再剪一次"), ("action_next_lesson", "Next lesson", "下一课"),
    ("action_start", "Start pruning", "开始剪"), ("action_pick", "Pick a lesson", "选一课"),
    ("action_bring_here", "Reopen panel", "重开面板"), ("action_replant", "Replant", "重新放树"),
    ("action_rule_card", "Rule card", "口诀卡"), ("action_settings", "Settings", "设置"),
    ("tab_lesson", "Lesson", "修枝"), ("action_open_panel", "Open panel", "打开面板"), ("look_level", "Look straight ahead\nand the panel opens", "平视前方\n面板就出来"), ("rules_this_lesson", "This lesson's rules", "这一课的口诀"),
    ("action_quit", "Quit app", "退出应用"), ("action_cancel", "Cancel", "取消"), ("action_quit_short", "Quit", "退出"),
    ("action_clear", "Clear", "清空"), ("action_close", "Close", "关闭"), ("action_retry", "Retry", "重试"),
    ("action_reset_lesson", "Reset lesson", "重置本课"), ("action_reset_all", "Reset all progress", "重置全部进度"),
    ("confirm_quit", "Quit Pruning Class?", "退出修枝课？"), ("confirm_reset", "Clear all 6 scores and graduation?", "清空 6 课成绩和结业？"),
    ("view_now", "Now", "现在"), ("view_yours", "Yours", "你的明年"), ("view_model", "Model", "标准明年"),
    ("chip_cut", "Cut %1$d · %2$d to go", "剪了 %1$d · 还差 %2$d"), ("chip_cut_exam", "Cut %1$d", "已剪 %1$d"),
    ("chip_removed", "Removed %1$d%%", "去除 %1$d%%"),
    ("chip_last", "Last %1$d%%", "上次 %1$d%%"), ("chip_exam", "Exam", "考试"), ("chip_new", "Not started", "未开始"),
    ("result_big", "Right %1$s / %2$d", "剪对 %1$s / %2$d"),
    ("result_wrong", "Wrong cuts %1$d (main %2$d)", "误剪 %1$d（主枝 %2$d）"),
    ("result_off", "Cuts off target %1$d", "剪口偏了 %1$d 处"),
    ("result_removed", "Removed %1$d%% (max 20%%)", "去除量 %1$d%%（≤ 20%%）"),
    ("result_strong", "Strong canes kept %1$d (min 3)", "保留强枝 %1$d（≥ 3）"),
    ("result_pass", "Passed", "通过"), ("result_practice_pass", "Well done", "练习打勾"),
    ("result_fail_ratio", "Not passed: %1$d%% right (need 80%%)", "没通过：剪对率 %1$d%%（要 ≥ 80%%）"),
    ("result_fail_wrong", "Not passed: %1$d wrong cuts (max 1)", "没通过：误剪 %1$d（要 ≤ 1）"),
    ("result_fail_major", "Not passed: a major wrong cut", "没通过：有重大误剪"),
    ("result_fail_strong", "Not passed: %1$d strong canes left (min 3)", "没通过：只留了 %1$d 根强枝（要 ≥ 3）"),
    ("result_fail_removed", "Not passed: %1$d%% removed (max 20%%)", "没通过：去除量 %1$d%%（要 ≤ 20%%）"),
    ("row_missed", "Missed: %1$s", "漏剪：%1$s"), ("row_half", "Off target: %1$s", "剪口偏了：%1$s"), ("row_wrong", "Wrong cut: %1$s", "误剪：%1$s"),
    ("row_none", "Nothing missed", "没有漏剪和误剪"),
    ("defect_HEALTHY", "healthy branch", "健康枝"), ("defect_DEAD", "dead cane", "枯枝"), ("defect_TIP_DEAD", "dead tip", "梢枯枝"), ("defect_BROKEN", "broken branch", "折断枝"),
    ("defect_DISEASED", "black spot", "黑斑病枝"), ("defect_CANKER", "canker", "溃疡病枝"), ("defect_THIN", "thin stem", "细弱枝"), ("defect_CROSSING", "crossing pair", "交叉枝"),
    ("defect_INWARD", "growing inward", "内向枝"), ("defect_WATERSHOOT", "watershoot", "徒长枝"), ("defect_SUCKER", "sucker", "根蘖"), ("defect_LEADER", "leader", "延长枝"),
    ("defect_REDUCE", "cane to shorten", "要回缩的枝"), ("defect_SPUR", "fruiting spur", "短果枝"), ("defect_MAIN", "main branch", "主枝"), ("defect_TRUNK", "trunk", "主干"),
    ("count_bloom", "Flowering canes", "强开花枝"), ("count_dead", "Dead / diseased left", "枯病枝残留"), ("count_centre", "Crowded centre", "树心郁闭"), ("count_cross", "Crossing rubs", "交叉摩擦"),
    ("count_spurs", "Flowering spurs", "开花短果枝"), ("count_water", "Watershoots", "徒长枝"),
    ("count_header", "Yours · Model", "你的 · 标准"),
    ("label_crossRub", "Crossing rub", "交叉摩擦"), ("label_inward", "Grows inward", "往里长"), ("label_overPrune", "Cut too hard: watershoots", "剪太重冒徒长枝"),
    ("label_deadLeft", "Dead wood left", "枯枝残留"), ("label_centre", "Crowded centre", "树心郁闭"),
    ("welcome_line", "Finish these 6 lessons and you can prune a rose and an apple tree this winter", "学完这 6 课，冬天你能自己修一盆月季和一棵苹果树"),
    ("welcome_1", "Stick left/right turns the tree, fwd/back moves it; you stay put", "① 摇杆左右转树，前后推远拉近，人站着不动"),
    ("welcome_2", "Tap a branch, then a bud on the ladder beside it: the cut goes above that bud", "② 点一根枝，再在旁边的芽梯上点一个芽，就在芽上方剪一刀"),
    ("welcome_3", "When done, see your score, then switch to next year", "③ 剪完看成绩，再切到明年看结果"),
    ("picker_title", "Pick a lesson", "选一课"),
    ("rules_count", "Cut %1$d branches in this lesson", "本课要剪 %1$d 根"),
    ("graduate_title", "%1$s graduated", "%1$s结业"),
    ("graduate_scores", "Lesson 1 %1$s · Lesson 2 %2$s · Exam %3$s", "1 课 %1$s · 2 课 %2$s · 考试 %3$s"),
    ("card_title", "Rule card", "口诀卡"),
    ("set_bud_zoom", "Bud zoom", "芽眼放大"), ("set_floor", "Floor height", "地面高度"), ("set_seated", "I am", "我是"), ("set_stand", "Standing", "站着"), ("set_sit", "Seated", "坐着"),
    ("set_sound", "Sound", "声音"), ("set_volume", "Volume", "音量"), ("vol_low", "Low", "低"), ("vol_mid", "Mid", "中"), ("vol_high", "High", "高"),
    ("set_language", "Language", "语言"), ("set_about", "About", "关于"), ("about_version", "Pruning Class v0.1.4", "修枝课 v0.1.4"),
    ("on", "On", "开"), ("off", "Off", "关"), ("lang_zh", "中", "中"), ("lang_en", "EN", "EN"),
    ("floor_virtual", "Virtual %1$+d cm", "虚拟 %1$+d cm"), ("floor_real", "Real floor", "真实地面"),
    ("dialog_title", "Not available now", "现在用不了"), ("dialog_help_full_space", "Open Pruning Class again from the app list so it runs in Full Space, then tap Retry", "从应用列表重新打开修枝课，让它进入全空间，再点重试"), ("dialog_help_head", "Put the headset on and look around for a moment, then tap Retry", "戴好头显，四处看一下，再点重试"), ("dialog_not_full_space", "Not in Full Space", "不在全空间"), ("dialog_no_head", "Head tracking is not available", "头部追踪不可用"),
]


def esc(v: str) -> str:
    return v.replace("&", "&amp;").replace("'", "\\'").replace("<", "&lt;").replace("\n", "\\n")


def main():
    for path, idx in [("values/strings.xml", 1), ("values-zh/strings.xml", 2)]:
        out = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Generated by tools/make_strings.py (design `copy`). Hints: at most 2 lines of 40 characters. -->", "<resources>"]
        for row in S:
            out.append(f'    <string name="{row[0]}">{esc(row[idx])}</string>')
        out.append("</resources>")
        os.makedirs(os.path.join(RES, os.path.dirname(path)), exist_ok=True)
        with open(os.path.join(RES, path), "w", encoding="utf-8") as f:
            f.write("\n".join(out) + "\n")
    print(len(S), "strings")


if __name__ == "__main__":
    main()
