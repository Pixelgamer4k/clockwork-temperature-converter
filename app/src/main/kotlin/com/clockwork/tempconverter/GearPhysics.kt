package com.clockwork.tempconverter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round

data class Gear(
    val id: Int,
    val name: String,
    val teeth: Int,
    val relativeX: Float, // Relative X in our 1000x1000 square space
    val relativeY: Float, // Relative Y in our 1000x1000 square space
    val speedRatio: Float, // Angular velocity relative to Celsius main gear
    val colorType: Float, // 0.0: Gunmetal, 0.5: Bronze, 1.0: Brass, 2.0: Copper
    val layer: Int, // 0: Background, 1: Middle, 2: Foreground
    val hasSpokes: Boolean = true,
    val hasCrank: Boolean = false,
    val hasPointer: Boolean = false,
    val pointerColor: Float = 0f
)

object GearSystem {
    // Pitch module in virtual pixels
    const val MODULE = 4.0f
    const val VIEWPORT_SIZE = 1000f

    // List of gears in our dense mechanical multiplier train
    val gears = listOf(
        // Celsius Side (Left-Top region)
        Gear(
            id = 1,
            name = "Main Celsius Gear (36T)",
            teeth = 36,
            relativeX = 260f,
            relativeY = 260f,
            speedRatio = 1.0f,
            colorType = 1.0f, // Brass
            layer = 2,
            hasSpokes = true,
            hasCrank = true,
            hasPointer = true,
            pointerColor = 2.0f // Copper pointer
        ),
        // Compound Shaft 1 (Center-Top region)
        Gear(
            id = 2,
            name = "Compound A Outer (24T)",
            teeth = 24,
            relativeX = 485f,
            relativeY = 180f,
            speedRatio = -1.5f,
            colorType = 0.5f, // Bronze
            layer = 1
        ),
        Gear(
            id = 3,
            name = "Compound A Inner (24T)",
            teeth = 24,
            relativeX = 485f,
            relativeY = 180f,
            speedRatio = -1.5f,
            colorType = 0.0f, // Gunmetal
            layer = 0
        ),
        // Compound Shaft 2 (Right-Top region)
        Gear(
            id = 4,
            name = "Compound B Outer (20T)",
            teeth = 20,
            relativeX = 635f,
            relativeY = 235f,
            speedRatio = 1.8f,
            colorType = 1.0f, // Brass
            layer = 0
        ),
        Gear(
            id = 5,
            name = "Compound B Inner (30T)",
            teeth = 30,
            relativeX = 635f,
            relativeY = 235f,
            speedRatio = 1.8f,
            colorType = 2.0f, // Copper
            layer = 1
        ),
        // Compound Shaft 3 (Far-Right region)
        Gear(
            id = 6,
            name = "Compound C Outer (15T)",
            teeth = 15,
            relativeX = 765f,
            relativeY = 355f,
            speedRatio = -3.6f,
            colorType = 0.0f, // Gunmetal
            layer = 1
        ),
        Gear(
            id = 7,
            name = "Compound C Inner (20T)",
            teeth = 20,
            relativeX = 765f,
            relativeY = 355f,
            speedRatio = -3.6f,
            colorType = 0.5f, // Bronze
            layer = 2
        ),
        // Idler Train winding down towards the differential
        Gear(
            id = 8,
            name = "Idler Gear 1 (20T)",
            teeth = 20,
            relativeX = 635f,
            relativeY = 460f,
            speedRatio = 3.6f,
            colorType = 0.0f, // Gunmetal
            layer = 2
        ),
        Gear(
            id = 9,
            name = "Idler Gear 2 (20T)",
            teeth = 20,
            relativeX = 490f,
            relativeY = 535f,
            speedRatio = -3.6f,
            colorType = 1.0f, // Brass
            layer = 1
        ),
        Gear(
            id = 10,
            name = "Idler Gear 3 (20T)",
            teeth = 20,
            relativeX = 345f,
            relativeY = 610f,
            speedRatio = 3.6f,
            colorType = 0.5f, // Bronze
            layer = 0
        ),
        Gear(
            id = 11,
            name = "Idler Gear 4 (20T)",
            teeth = 20,
            relativeX = 485f,
            relativeY = 715f,
            speedRatio = -3.6f,
            colorType = 0.0f, // Gunmetal
            layer = 1
        ),
        // Differential Sun 1 (Fahrenheit Drive)
        Gear(
            id = 12,
            name = "Differential Sun 1 (20T)",
            teeth = 20,
            relativeX = 645f,
            relativeY = 715f,
            speedRatio = 3.6f,
            colorType = 1.0f, // Brass
            layer = 1
        )
    )

    // Fahrenheit Differential Output Assembly (Right-Bottom region)
    const val DIFF_X = 645f
    const val DIFF_Y = 715f
    
    // Differential Sun 2 (Offset adjustment gear, meshes with calibration lever)
    val diffSun2 = Gear(
        id = 13,
        name = "Differential Sun 2 (20T)",
        teeth = 20,
        relativeX = DIFF_X,
        relativeY = DIFF_Y,
        speedRatio = 0.0f, // Static offset control
        colorType = 2.0f, // Copper
        layer = 0
    )

    // Planet Gears (Spur planet assembly, meshes Sun 1 to Sun 2)
    val planetTeeth = 12
    val planetOrbitRadius = 52f

    // Calculate gear radius based on teeth count
    fun getRadius(teeth: Int): Float {
        return teeth.toFloat() * MODULE * 0.5f
    }

    /**
     * Bidirectional Conversion Math:
     * C = current master angle (representing Celsius temperature)
     * F = 1.8 * C + 32 (representing Fahrenheit scale position)
     * Sun 1 rotates at 3.6 * C degrees.
     * Sun 2 represents the offset. At base calibrator, its rotation is equivalent to 64 degrees.
     * Carrier rotates at (Sun 1 angle + Sun 2 angle) / 2
     * = (3.6 * C + 64) / 2 = 1.8 * C + 32 degrees!
     */
    
    fun getCelsiusFromAngle(angleC: Float): Float {
        return angleC
    }

    fun getFahrenheitFromAngleC(angleC: Float, sun2OffsetAngle: Float = 64f): Float {
        return 1.8f * angleC + (sun2OffsetAngle / 2f)
    }

    fun getAngleCFromCelsius(celsius: Float): Float {
        return celsius
    }

    fun getAngleCFromFahrenheit(fahrenheit: Float, sun2OffsetAngle: Float = 64f): Float {
        val offset = sun2OffsetAngle / 2f
        return (fahrenheit - offset) / 1.8f
    }
}
