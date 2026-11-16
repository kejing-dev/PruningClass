package io.embodit.pruningclass.domain.usecase

/**
 * The total of a drag since the press (D53, factory board-input template). SDK 6.1.9 `SpatialDragValue.dragAmount` is the
 * step since the last event, not since the press: a new [DragTotal] at the press, [add] every event, use the returned sum.
 */
class DragTotal {
    private var x = 0f
    private var y = 0f

    fun add(dx: Float, dy: Float): Pair<Float, Float> {
        x += dx
        y += dy
        return x to y
    }
}
