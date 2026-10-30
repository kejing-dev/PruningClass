package io.embodit.pruningclass.domain.usecase

import io.embodit.pruningclass.domain.model.Quat4
import io.embodit.pruningclass.domain.model.Vec3

/**
 * Pose of the viewer's head in Stage metres: -Z of [rotation] is the viewing direction. Only read for aiming / stowing;
 * nothing is placed with the full rotation (content goes through `Placement.placeUpright`, yaw only).
 */
data class ViewPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)
}
