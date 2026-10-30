package io.embodit.pruningclass.domain.tree

/** The six lessons (design `content.lessons`): fixed seed per lesson, so every visit is the same tree. */
enum class LessonId(val species: Species, val mustCuts: Int, val exam: Boolean, val rules: List<String>, val seed: Int) {
    R1(Species.ROSE, 7, false, listOf("R-1", "R-5"), 305),
    R2(Species.ROSE, 6, false, listOf("R-2", "R-3", "R-4"), 711),
    R3(Species.ROSE, 9, true, listOf("R-1", "R-2", "R-3", "R-4", "R-5"), 1207),
    A1(Species.APPLE, 8, false, listOf("A-1", "A-3"), 4242),
    A2(Species.APPLE, 6, false, listOf("A-2", "A-4", "A-5"), 9137),
    A3(Species.APPLE, 9, true, listOf("A-1", "A-2", "A-3", "A-4", "A-5"), 5153);

    /** 1..3 within its species. */
    val number: Int
        get() = ordinal % 3 + 1

    val next: LessonId?
        get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun of(name: String): LessonId? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        fun of(species: Species): List<LessonId> = entries.filter { it.species == species }
    }
}

/** The five rules of each species (design `content.species[].rules`), ids only; the words live in the string resources. */
object Rules {
    val rose = listOf("R-1", "R-2", "R-3", "R-4", "R-5")
    val apple = listOf("A-1", "A-2", "A-3", "A-4", "A-5")

    fun of(species: Species) = if (species == Species.ROSE) rose else apple
}
