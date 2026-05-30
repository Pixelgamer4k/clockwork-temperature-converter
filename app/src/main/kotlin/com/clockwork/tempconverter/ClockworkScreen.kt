package com.clockwork.tempconverter

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.*

@Composable
fun ClockworkScreen(
    viewModel: GearViewModel,
    soundManager: SoundManager,
    modifier: Modifier = Modifier
) {
    val celsius by viewModel.celsius.collectAsState()
    val fahrenheit by viewModel.fahrenheit.collectAsState()
    val sun2OffsetAngle by viewModel.sun2OffsetAngle.collectAsState()
    val tiltX by viewModel.tiltX.collectAsState()
    val tiltY by viewModel.tiltY.collectAsState()
    val leverActive by viewModel.leverActive.collectAsState()

    val hapticFeedback = LocalHapticFeedback.current

    // Keep track of runtime for thin-film color shifts
    var timeSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()
        while (true) {
            timeSeconds = (System.currentTimeMillis() - startTime) / 1000f
            kotlinx.coroutines.delay(16) // ~60 fps time updates
        }
    }

    // Collect tick events from ViewModel to play sound and trigger haptics
    LaunchedEffect(viewModel) {
        viewModel.tickEvent.collectLatest {
            soundManager.playTick()
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Pre-calculate canonical gear paths once and cache them
    val gearPaths = remember {
        mutableMapOf<Int, Path>().apply {
            // Generate paths for all teeth configurations used (36, 30, 24, 20, 15, 12)
            listOf(36, 30, 24, 20, 15, 12).forEach { teeth ->
                put(teeth, buildGearPath(teeth, GearSystem.MODULE))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1113)) // Deep slate gunmetal background base
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Touch interaction controller
                    var dragMode = 0 // 0: None, 1: Celsius, 2: Fahrenheit, 3: Calibration Wheel, 4: Reset Lever
                    var lastAngle = 0f
                    var lastTime = 0L

                    // Coordinate mapping helper
                    val minDim = min(size.width, size.height)
                    val scale = minDim / GearSystem.VIEWPORT_SIZE
                    val offsetX = (size.width - minDim) / 2f
                    val offsetY = (size.height - minDim) / 2f

                    detectDragGestures(
                        onDragStart = { offset ->
                            val vx = (offset.x - offsetX) / scale
                            val vy = (offset.y - offsetY) / scale

                            // Distance checks
                            val dCelsius = sqrt((vx - 260f).pow(2) + (vy - 260f).pow(2))
                            val dFahrenheit = sqrt((vx - GearSystem.DIFF_X).pow(2) + (vy - GearSystem.DIFF_Y).pow(2))
                            val dLever = sqrt((vx - 500f).pow(2) + (vy - 920f).pow(2))
                            val dCalibrate = sqrt((vx - 820f).pow(2) + (vy - 715f).pow(2))

                            when {
                                dLever < 80f -> {
                                    dragMode = 4
                                    viewModel.pullResetLever()
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                dCalibrate < 60f -> {
                                    dragMode = 3
                                    lastAngle = atan2(vy - 715f, vx - 820f) * 180f / PI.toFloat()
                                    viewModel.onDragStart()
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                dCelsius < 160f -> {
                                    dragMode = 1
                                    lastAngle = atan2(vy - 260f, vx - 260f) * 180f / PI.toFloat()
                                    lastTime = System.nanoTime()
                                    viewModel.onDragStart()
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                dFahrenheit < 200f -> {
                                    dragMode = 2
                                    lastAngle = atan2(vy - GearSystem.DIFF_Y, vx - GearSystem.DIFF_X) * 180f / PI.toFloat()
                                    lastTime = System.nanoTime()
                                    viewModel.onDragStart()
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                else -> {
                                    dragMode = 0
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            if (dragMode == 0 || dragMode == 4) return@detectDragGestures
                            
                            val vx = (change.position.x - offsetX) / scale
                            val vy = (change.position.y - offsetY) / scale
                            val currentTime = System.nanoTime()
                            val dt = (currentTime - lastTime) / 1e9f
                            lastTime = currentTime

                            if (dt < 0.0005f) return@detectDragGestures

                            when (dragMode) {
                                1 -> { // Celsius drag
                                    val currentAngle = atan2(vy - 260f, vx - 260f) * 180f / PI.toFloat()
                                    var delta = currentAngle - lastAngle
                                    if (delta > 180f) delta -= 360f
                                    if (delta < -180f) delta += 360f

                                    val instVelocity = delta / dt
                                    viewModel.onDragCelsius(delta, instVelocity)
                                    lastAngle = currentAngle
                                }
                                2 -> { // Fahrenheit drag
                                    val currentAngle = atan2(vy - GearSystem.DIFF_Y, vx - GearSystem.DIFF_X) * 180f / PI.toFloat()
                                    var delta = currentAngle - lastAngle
                                    if (delta > 180f) delta -= 360f
                                    if (delta < -180f) delta += 360f

                                    val instVelocity = delta / dt
                                    viewModel.onDragFahrenheit(delta, instVelocity)
                                    lastAngle = currentAngle
                                }
                                3 -> { // Calibration Wheel drag
                                    val currentAngle = atan2(vy - 715f, vx - 820f) * 180f / PI.toFloat()
                                    var delta = currentAngle - lastAngle
                                    if (delta > 180f) delta -= 360f
                                    if (delta < -180f) delta += 360f
                                    viewModel.rotateOffsetSun2(delta * 0.35f)
                                    lastAngle = currentAngle
                                }
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (dragMode != 0) {
                                viewModel.onDragEnd()
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragMode = 0
                            }
                        },
                        onDragCancel = {
                            if (dragMode != 0) {
                                viewModel.onDragEnd()
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragMode = 0
                            }
                        }
                    )
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val minDim = min(canvasWidth, canvasHeight)
            val viewportScale = minDim / GearSystem.VIEWPORT_SIZE
            val offsetX = (canvasWidth - minDim) / 2f
            val offsetY = (canvasHeight - minDim) / 2f

            // 1. Draw heavy background plate with dynamic highlights & oil sheen
            drawBackgroundPlate(offsetX, offsetY, minDim, tiltX, tiltY, timeSeconds)

            // 2. Draw Layer 0 Gears (Backpack elements - compound gears, Sun 2)
            withTransform({
                translate(-tiltX * 8f * viewportScale, -tiltY * 8f * viewportScale)
            }) {
                // Background gear trains
                drawGear(GearSystem.gears[2], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Compound A Inner (G3)
                drawGear(GearSystem.gears[3], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Compound B Outer (G4)
                
                // Draw Sun 2 offset gear (underneath carrier)
                val sun2Angle = sun2OffsetAngle
                drawGear(GearSystem.diffSun2, sun2Angle, gearPaths, tiltX, tiltY, timeSeconds)
            }

            // 3. Draw Layer 1 Gears (Middle layers)
            withTransform({
                // Base layer reference parallax
                translate(0f, 0f)
            }) {
                drawGear(GearSystem.gears[1], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Compound A Outer (G2)
                drawGear(GearSystem.gears[4], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Compound B Inner (G5)
                drawGear(GearSystem.gears[5], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Compound C Outer (G6)
                drawGear(GearSystem.gears[8], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Idler 2 (G9)
                drawGear(GearSystem.gears[10], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Idler 4 (G11)
                drawGear(GearSystem.gears[11], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Sun 1 (G12)
            }

            // 4. Draw Layer 2 Gears (Foreground layers - Main input, pointers, scales)
            withTransform({
                translate(tiltX * 8f * viewportScale, tiltY * 8f * viewportScale)
            }) {
                drawGear(GearSystem.gears[0], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Main Celsius Gear (G1)
                drawGear(GearSystem.gears[6], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Compound C Inner (G7)
                drawGear(GearSystem.gears[7], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Idler 1 (G8)
                drawGear(GearSystem.gears[9], celsius, gearPaths, tiltX, tiltY, timeSeconds) // Idler 3 (G10)

                // Draw Celsius Circular Scale
                drawCelsiusScale(offsetX, offsetY, viewportScale, celsius)

                // Draw Fahrenheit Carrier & Planet Assembly (Differential Carrier)
                drawDifferentialCarrier(offsetX, offsetY, viewportScale, celsius, sun2OffsetAngle, gearPaths, tiltX, tiltY, timeSeconds)

                // Draw Fahrenheit Circular Scale
                drawFahrenheitScale(offsetX, offsetY, viewportScale, fahrenheit)
            }

            // 5. Draw Top-most HUD elements (Frame, Lever, Glass reflection)
            withTransform({
                translate(tiltX * 14f * viewportScale, tiltY * 14f * viewportScale)
            }) {
                drawCalibrationThumbwheel(offsetX, offsetY, viewportScale, sun2OffsetAngle, tiltX, tiltY, timeSeconds)
                drawResetLever(offsetX, offsetY, viewportScale, leverActive, tiltX, tiltY, timeSeconds)
            }

            // Machined brass edge frame
            drawIndustrialBorder(canvasWidth, canvasHeight, viewportScale)

            // Glossy reflection glass cover
            drawGlassReflectionOverlay(canvasWidth, canvasHeight)
        }
    }
}

/**
 * Builds the canonical tooth profile path of a spur gear centered at (0,0)
 */
fun buildGearPath(teeth: Int, module: Float): Path {
    val path = Path()
    val rPitch = teeth.toFloat() * module * 0.5f
    val rRoot = rPitch - 1.15f * module
    val rTip = rPitch + 0.8f * module

    val anglePerTooth = 2.0 * PI / teeth.toDouble()

    for (i in 0 until teeth) {
        val baseAngle = i * anglePerTooth
        
        // Profiles angles
        val a0 = baseAngle - anglePerTooth * 0.22
        val a1 = baseAngle - anglePerTooth * 0.12
        val a2 = baseAngle - anglePerTooth * 0.05
        val a3 = baseAngle + anglePerTooth * 0.05
        val a4 = baseAngle + anglePerTooth * 0.12
        val a5 = baseAngle + anglePerTooth * 0.22

        val p0x = (rRoot * cos(a0)).toFloat()
        val p0y = (rRoot * sin(a0)).toFloat()

        val p1x = (rPitch * cos(a1)).toFloat()
        val p1y = (rPitch * sin(a1)).toFloat()

        val p2x = (rTip * cos(a2)).toFloat()
        val p2y = (rTip * sin(a2)).toFloat()

        val p3x = (rTip * cos(a3)).toFloat()
        val p3y = (rTip * sin(a3)).toFloat()

        val p4x = (rPitch * cos(a4)).toFloat()
        val p4y = (rPitch * sin(a4)).toFloat()

        val p5x = (rRoot * cos(a5)).toFloat()
        val p5y = (rRoot * sin(a5)).toFloat()

        if (i == 0) {
            path.moveTo(p0x, p0y)
        } else {
            path.lineTo(p0x, p0y)
        }

        path.lineTo(p1x, p1y)
        path.lineTo(p2x, p2y)
        path.lineTo(p3x, p3y)
        path.lineTo(p4x, p4y)
        path.lineTo(p5x, p5y)

        val nextA0 = (i + 1) * anglePerTooth - anglePerTooth * 0.22
        val midAngle = (a5 + nextA0) * 0.5
        val pMidX = (rRoot * cos(midAngle)).toFloat()
        val pMidY = (rRoot * sin(midAngle)).toFloat()
        path.lineTo(pMidX, pMidY)
    }
    path.close()

    // Add axle circular hole
    val axleCutout = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(-12f, -12f), Size(24f, 24f)))
    }

    // Subtract axle hole
    val gearWithPath = Path.combine(PathOperation.Difference, path, axleCutout)

    // Add gorgeous circular spoke cutouts
    val rCutoutCenter = rRoot * 0.55f
    val rCutout = rRoot * 0.2f
    if (teeth >= 15 && teeth != 20) {
        val numSpokes = if (teeth >= 30) 6 else 4
        val spokeAngle = 2 * PI / numSpokes
        var combinedPath = gearWithPath
        for (j in 0 until numSpokes) {
            val cx = (rCutoutCenter * cos(j * spokeAngle)).toFloat()
            val cy = (rCutoutCenter * sin(j * spokeAngle)).toFloat()
            val sectorCutout = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(Offset(cx - rCutout, cy - rCutout), Size(rCutout * 2f, rCutout * 2f)))
            }
            combinedPath = Path.combine(PathOperation.Difference, combinedPath, sectorCutout)
        }
        return combinedPath
    }

    return gearWithPath
}

/**
 * Draws the heavy industrial iron plate in the background
 */
fun DrawScope.drawBackgroundPlate(
    offsetX: Float,
    offsetY: Float,
    minDim: Float,
    tiltX: Float,
    tiltY: Float,
    time: Float
) {
    val center = Offset(offsetX + minDim / 2f, offsetY + minDim / 2f)

    // Dark gunmetal base plate
    drawRect(
        color = Color(0xFF101214),
        topLeft = Offset(offsetX, offsetY),
        size = Size(minDim, minDim)
    )

    // Gyroscope-shifted highlights (metallic sheen)
    val lightX = center.x + tiltX * minDim * 0.25f
    val lightY = center.y + tiltY * minDim * 0.25f
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x1BFFFFFF),
                Color(0x0CFFD384),
                Color(0x00000000)
            ),
            center = Offset(lightX, lightY),
            radius = minDim * 0.6f
        ),
        topLeft = Offset(offsetX, offsetY),
        size = Size(minDim, minDim)
    )

    // Draw some machined circular grooves in the background
    drawCircle(
        color = Color(0x0BFFFFFF),
        radius = minDim * 0.4f,
        center = center,
        style = Stroke(width = 4f * minDim / 1000f)
    )
    drawCircle(
        color = Color(0x0E000000),
        radius = minDim * 0.3f,
        center = center,
        style = Stroke(width = 6f * minDim / 1000f)
    )

    // Iron corner rivets
    val rivetOffset = 40f * minDim / 1000f
    val rivetRadius = 14f * minDim / 1000f
    val cornerRivets = listOf(
        Offset(offsetX + rivetOffset, offsetY + rivetOffset),
        Offset(offsetX + minDim - rivetOffset, offsetY + rivetOffset),
        Offset(offsetX + rivetOffset, offsetY + minDim - rivetOffset),
        Offset(offsetX + minDim - rivetOffset, offsetY + minDim - rivetOffset)
    )

    cornerRivets.forEach { rivetCenter ->
        // Rivet base shadow
        drawCircle(
            color = Color(0xFF000000),
            radius = rivetRadius + 2f,
            center = rivetCenter + Offset(2f, 2f)
        )
        // Rivet metal (iron)
        drawCircle(
            color = Color(0xFF282C30),
            radius = rivetRadius,
            center = rivetCenter
        )
        // Rivet highlights
        drawCircle(
            color = Color(0xFF4C535A),
            radius = rivetRadius * 0.7f,
            center = rivetCenter - Offset(rivetRadius * 0.2f, rivetRadius * 0.2f)
        )
        // Inner notch (slotted rivet)
        drawLine(
            color = Color(0xFF101112),
            start = rivetCenter - Offset(rivetRadius * 0.6f, rivetRadius * 0.6f),
            end = rivetCenter + Offset(rivetRadius * 0.6f, rivetRadius * 0.6f),
            strokeWidth = 3f
        )
    }
}

/**
 * Renders a specific Gear with its physics-calculated rotation angle
 */
fun DrawScope.drawGear(
    gear: Gear,
    masterCelsiusAngle: Float,
    gearPaths: Map<Int, Path>,
    tiltX: Float,
    tiltY: Float,
    time: Float
) {
    val canonicalPath = gearPaths[gear.teeth] ?: return

    val minDim = min(size.width, size.height)
    val scale = minDim / GearSystem.VIEWPORT_SIZE
    val offsetX = (size.width - minDim) / 2f
    val offsetY = (size.height - minDim) / 2f

    val cx = offsetX + gear.relativeX * scale
    val cy = offsetY + gear.relativeY * scale
    val r = GearSystem.getRadius(gear.teeth) * scale

    // Compute rotational angle based on gear multiplier chain
    // Speed ratios: adj gears counter-rotate, compounds share direction.
    // Half-tooth offset adds mesh alignment (valley fits peak)
    val meshOffset = 180f / gear.teeth.toFloat()
    val baseRotAngle = masterCelsiusAngle * gear.speedRatio
    val drawAngle = if (gear.speedRatio < 0) {
        baseRotAngle + meshOffset
    } else {
        baseRotAngle
    }

    // Shadow underneath gear for 3D depth separation
    withTransform({
        translate(cx + 8f * scale, cy + 8f * scale)
        scale(scale, scale)
        rotate(drawAngle)
    }) {
        drawPath(
            path = canonicalPath,
            color = Color(0x60000000)
        )
    }

    // Select PBR metal brush (Tiramisu RuntimeShader or Gradient Fallback)
    val brush = if (GearShader.isSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = RuntimeShader(GearShader.AGSL_CODE)
        shader.setFloatUniform("uSize", r * 2f, r * 2f)
        shader.setFloatUniform("uGyroOffset", tiltX, tiltY)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uMetallicColor", gear.colorType)
        ShaderBrush(shader)
    } else {
        // High fidelity fallback gradient
        val lightX = 0.5f + tiltX * 0.15f
        val lightY = 0.5f + tiltY * 0.15f
        val colors = when {
            gear.colorType > 1.8f -> listOf(Color(0xFF3A1C12), Color(0xFF753823), Color(0xFFC26344), Color(0xFF3A1C12)) // Copper
            gear.colorType > 0.8f -> listOf(Color(0xFF382E18), Color(0xFF7D6530), Color(0xFFC7A75C), Color(0xFF382E18)) // Brass
            gear.colorType > 0.3f -> listOf(Color(0xFF362B1D), Color(0xFF6B5336), Color(0xFFA5835C), Color(0xFF362B1D)) // Bronze
            else -> listOf(Color(0xFF0F1113), Color(0xFF262B30), Color(0xFF4C5560), Color(0xFF0F1113)) // Gunmetal
        }
        Brush.radialGradient(
            colors = colors,
            center = Offset(r * 2f * lightX, r * 2f * lightY),
            radius = r * 1.2f
        )
    }

    // Draw main gear body
    withTransform({
        translate(cx, cy)
        scale(scale, scale)
        rotate(drawAngle)
    }) {
        drawPath(
            path = canonicalPath,
            brush = brush
        )

        // Draw structural hub line (concentric circles on body)
        val rRoot = GearSystem.getRadius(gear.teeth) - 1.15f * GearSystem.MODULE
        drawCircle(
            color = Color(0x3BFFFFFF),
            radius = rRoot * 0.85f,
            style = Stroke(width = 0.8f)
        )
        drawCircle(
            color = Color(0x28000000),
            radius = rRoot * 0.85f - 1f,
            style = Stroke(width = 0.8f)
        )
    }

    // Draw central axle gold pin & cap
    drawCircle(
        color = Color(0x80000000),
        radius = 16f * scale,
        center = Offset(cx + 2f, cy + 2f)
    )
    drawCircle(
        color = Color(0xFF2E2413),
        radius = 14f * scale,
        center = Offset(cx, cy)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFDF7D), Color(0xFFC59F39), Color(0xFF62480F)),
            center = Offset(cx - 3f * scale, cy - 3f * scale),
            radius = 11f * scale
        ),
        radius = 11f * scale,
        center = Offset(cx, cy)
    )

    // Celsius Crank handle details
    if (gear.hasCrank) {
        val crankRadius = r * 0.65f
        val crankAngleRad = Math.toRadians(drawAngle.toDouble())
        val hx = cx + crankRadius * cos(crankAngleRad).toFloat()
        val hy = cy + crankRadius * sin(crankAngleRad).toFloat()

        // Shadow of crank arm
        drawLine(
            color = Color(0x50000000),
            start = Offset(cx + 5f * scale, cy + 5f * scale),
            end = Offset(hx + 5f * scale, hy + 5f * scale),
            strokeWidth = 22f * scale,
            cap = StrokeCap.Round
        )

        // Crank brass linkage arm
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFE5C158), Color(0xFF8B6C1F)),
                start = Offset(cx, cy),
                end = Offset(hx, hy)
            ),
            start = Offset(cx, cy),
            end = Offset(hx, hy),
            strokeWidth = 18f * scale,
            cap = StrokeCap.Round
        )

        // Machined rivet on crank pin
        drawCircle(
            color = Color(0x60000000),
            radius = 24f * scale,
            center = Offset(hx + 3f * scale, hy + 3f * scale)
        )
        // Main crank handle knob (heavy copper ring)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFFA085), Color(0xFFC45A3C), Color(0xFF531E10)),
                center = Offset(hx - 4f * scale, hy - 4f * scale),
                radius = 20f * scale
            ),
            radius = 20f * scale,
            center = Offset(hx, hy)
        )
        drawCircle(
            color = Color(0x40000000),
            radius = 8f * scale,
            center = Offset(hx, hy),
            style = Stroke(width = 4f * scale)
        )
    }
}

/**
 * Draws the Celsius scale around the Celsius gear (No numbers)
 */
fun DrawScope.drawCelsiusScale(
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    celsius: Float
) {
    val cx = offsetX + 260f * scale
    val cy = offsetY + 260f * scale
    val dialRadius = 185f * scale

    // Dial background copper ring
    drawCircle(
        color = Color(0xFF16191C),
        radius = dialRadius + 14f * scale,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color(0x80000000),
        radius = dialRadius + 14f * scale,
        center = Offset(cx, cy),
        style = Stroke(width = 3f * scale)
    )
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFFC76F4E), Color(0xFF4A2518), Color(0xFFC76F4E)),
            center = Offset(cx, cy)
        ),
        radius = dialRadius + 4f * scale,
        center = Offset(cx, cy),
        style = Stroke(width = 6f * scale)
    )

    // Drawing tick marks for Celsius scale (-50°C to 150°C)
    // Celsius ranges map: 1 degree Celsius = 1 degree rotation
    for (temp in -50..150 step 1) {
        val angleDeg = temp.toFloat()
        val angleRad = Math.toRadians(angleDeg.toDouble())
        
        val isMajor = temp % 10 == 0
        val isMedium = temp % 5 == 0 && !isMajor
        
        val tickLength = when {
            isMajor -> 20f * scale
            isMedium -> 12f * scale
            else -> 6f * scale
        }
        val tickWidth = when {
            isMajor -> 4.5f * scale
            isMedium -> 2.5f * scale
            else -> 1.2f * scale
        }
        val tickColor = when {
            isMajor -> Color(0xFFF9D193) // Brass
            isMedium -> Color(0xFFD3A47C) // Bronze
            else -> Color(0x60D3A47C)
        }

        val startR = dialRadius - tickLength
        val endR = dialRadius

        val sx = cx + startR * cos(angleRad).toFloat()
        val sy = cy + startR * sin(angleRad).toFloat()
        val ex = cx + endR * cos(angleRad).toFloat()
        val ey = cy + endR * sin(angleRad).toFloat()

        drawLine(
            color = tickColor,
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = tickWidth,
            cap = StrokeCap.Round
        )
    }

    // Celsius Pointer (Copper Hand aligned to current Celsius value)
    val pointerAngleRad = Math.toRadians(celsius.toDouble())
    val pointerLen = dialRadius - 8f * scale
    
    val px = cx + pointerLen * cos(pointerAngleRad).toFloat()
    val py = cy + pointerLen * sin(pointerAngleRad).toFloat()

    // Shadow of pointer
    drawLine(
        color = Color(0x50000000),
        start = Offset(cx + 4f, cy + 4f),
        end = Offset(px + 4f, py + 4f),
        strokeWidth = 5f * scale,
        cap = StrokeCap.Round
    )
    // Copper Pointer Needle
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFA085), Color(0xFFC76F4E)),
            start = Offset(cx, cy),
            end = Offset(px, py)
        ),
        start = Offset(cx, cy),
        end = Offset(px, py),
        strokeWidth = 3.5f * scale,
        cap = StrokeCap.Round
    )

    // Center copper cap on pointer hub
    drawCircle(
        color = Color(0xFF38150B),
        radius = 16f * scale,
        center = Offset(cx, cy)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFA388), Color(0xFFC55A38), Color(0xFF471C0F)),
            center = Offset(cx - 3f * scale, cy - 3f * scale),
            radius = 12f * scale
        ),
        radius = 12f * scale,
        center = Offset(cx, cy)
    )
}

/**
 * Draws the differential carrier assembly (revolving planet gears + Fahrenheit pointer)
 */
fun DrawScope.drawDifferentialCarrier(
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    masterCelsiusAngle: Float,
    sun2Angle: Float,
    gearPaths: Map<Int, Path>,
    tiltX: Float,
    tiltY: Float,
    time: Float
) {
    val cx = offsetX + GearSystem.DIFF_X * scale
    val cy = offsetY + GearSystem.DIFF_Y * scale

    // Carrier Angle: average of Sun 1 (Celsius drive, 3.6 * C) and Sun 2 (offset)
    // angleCarrier = (3.6f * C + sun2Offset) / 2f = 1.8f * C + (sun2Offset / 2f)
    val angleCarrier = 1.8f * masterCelsiusAngle + (sun2Angle / 2f)

    // Draw the Carrier Bridge (A gorgeous thick golden double-arm bridge)
    val carrierLen = GearSystem.planetOrbitRadius * scale
    val angleCarrierRad = Math.toRadians(angleCarrier.toDouble())
    
    // Compute centers of Planet 1 and Planet 2 orbiting the hub
    val p1x = cx + carrierLen * cos(angleCarrierRad).toFloat()
    val p1y = cy + carrierLen * sin(angleCarrierRad).toFloat()
    val p2x = cx - carrierLen * cos(angleCarrierRad).toFloat()
    val p2y = cy - carrierLen * sin(angleCarrierRad).toFloat()

    // 1. Draw Carrier bridge shadow
    drawLine(
        color = Color(0x60000000),
        start = Offset(p2x + 6f * scale, p2y + 6f * scale),
        end = Offset(p1x + 6f * scale, p1y + 6f * scale),
        strokeWidth = 26f * scale,
        cap = StrokeCap.Round
    )

    // 2. Draw brass carrier bridge
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFECCB73), Color(0xFF9E7E32), Color(0xFFECCB73)),
            start = Offset(p2x, p2y),
            end = Offset(p1x, p1y)
        ),
        start = Offset(p2x, p2y),
        end = Offset(p1x, p1y),
        strokeWidth = 20f * scale,
        cap = StrokeCap.Round
    )

    // Center circular plate of carrier bridge
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFECCB73), Color(0xFF886A25)),
            center = Offset(cx - 3f * scale, cy - 3f * scale),
            radius = 34f * scale
        ),
        radius = 34f * scale,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color(0xFF4A3A13),
        radius = 34f * scale,
        center = Offset(cx, cy),
        style = Stroke(width = 1.5f * scale)
    )

    // 3. Draw Planet Gears (12T, dark gunmetal, spinning and revolving)
    // Planet gear spin speed is mathematically driven by the differential:
    // planetSpin = 3.6f * C - angleCarrier = 1.8f * C - (sun2Offset / 2f)
    val planetSpin = 1.8f * masterCelsiusAngle - (sun2Angle / 2f)
    val planetG = Gear(
        id = 14,
        name = "Planet 1 (12T)",
        teeth = GearSystem.planetTeeth,
        relativeX = 0f, // Drawn relative to translated center
        relativeY = 0f,
        speedRatio = 0f,
        colorType = 0.0f, // Gunmetal
        layer = 2
    )

    // Draw Planet 1
    drawPlanetGear(p1x, p1y, planetSpin, gearPaths, scale, tiltX, tiltY, time)
    // Draw Planet 2
    drawPlanetGear(p2x, p2y, -planetSpin, gearPaths, scale, tiltX, tiltY, time)

    // 4. Draw Fahrenheit Pointer (Antique brass needle mounted on Carrier)
    val dialRadius = 185f * scale
    val pointerLen = dialRadius - 8f * scale
    
    val px = cx + pointerLen * cos(angleCarrierRad).toFloat()
    val py = cy + pointerLen * sin(angleCarrierRad).toFloat()

    // Shadow of pointer
    drawLine(
        color = Color(0x50000000),
        start = Offset(cx + 4f, cy + 4f),
        end = Offset(px + 4f, py + 4f),
        strokeWidth = 5f * scale,
        cap = StrokeCap.Round
    )
    // Brass Pointer Hand
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFECCB73), Color(0xFFC7A75C)),
            start = Offset(cx, cy),
            end = Offset(px, py)
        ),
        start = Offset(cx, cy),
        end = Offset(px, py),
        strokeWidth = 4f * scale,
        cap = StrokeCap.Round
    )

    // Center rivet cap locking pointer
    drawCircle(
        color = Color(0xFF1E170C),
        radius = 14f * scale,
        center = Offset(cx, cy)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFEFA7), Color(0xFFECC863), Color(0xFF6B5113)),
            center = Offset(cx - 3f * scale, cy - 3f * scale),
            radius = 10f * scale
        ),
        radius = 10f * scale,
        center = Offset(cx, cy)
    )
}

/**
 * Draws a planet gear at its specific orbital position
 */
fun DrawScope.drawPlanetGear(
    pcx: Float,
    pcy: Float,
    spinAngle: Float,
    gearPaths: Map<Int, Path>,
    scale: Float,
    tiltX: Float,
    tiltY: Float,
    time: Float
) {
    val canonicalPath = gearPaths[GearSystem.planetTeeth] ?: return
    val r = GearSystem.getRadius(GearSystem.planetTeeth) * scale

    // Planet Shadow
    withTransform({
        translate(pcx + 4f * scale, pcy + 4f * scale)
        scale(scale, scale)
        rotate(spinAngle)
    }) {
        drawPath(canonicalPath, Color(0x80000000))
    }

    // Select Planet color (Gunmetal PBR or Gradient)
    val brush = if (GearShader.isSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = RuntimeShader(GearShader.AGSL_CODE)
        shader.setFloatUniform("uSize", r * 2f, r * 2f)
        shader.setFloatUniform("uGyroOffset", tiltX, tiltY)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uMetallicColor", 0.0f) // Gunmetal
        ShaderBrush(shader)
    } else {
        val lightX = 0.5f + tiltX * 0.15f
        val lightY = 0.5f + tiltY * 0.15f
        Brush.radialGradient(
            colors = listOf(Color(0xFF0F1113), Color(0xFF262B30), Color(0xFF4C5560), Color(0xFF0F1113)),
            center = Offset(r * 2f * lightX, r * 2f * lightY),
            radius = r * 1.2f
        )
    }

    withTransform({
        translate(pcx, pcy)
        scale(scale, scale)
        rotate(spinAngle)
    }) {
        drawPath(canonicalPath, brush)
    }

    // Planet retaining rivet
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFB1C0D0), Color(0xFF576878), Color(0xFF202A32)),
            center = Offset(pcx - 2f * scale, pcy - 2f * scale),
            radius = 7f * scale
        ),
        radius = 7f * scale,
        center = Offset(pcx, pcy)
    )
}

/**
 * Draws the Fahrenheit dial ticks (No numbers)
 */
fun DrawScope.drawFahrenheitScale(
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    fahrenheit: Float
) {
    val cx = offsetX + GearSystem.DIFF_X * scale
    val cy = offsetY + GearSystem.DIFF_Y * scale
    val dialRadius = 185f * scale

    // Dial background brass ring
    drawCircle(
        color = Color(0xFF16191C),
        radius = dialRadius + 14f * scale,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color(0x80000000),
        radius = dialRadius + 14f * scale,
        center = Offset(cx, cy),
        style = Stroke(width = 3f * scale)
    )
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFFECCB73), Color(0xFF8C7132), Color(0xFFECCB73)),
            center = Offset(cx, cy)
        ),
        radius = dialRadius + 4f * scale,
        center = Offset(cx, cy),
        style = Stroke(width = 6f * scale)
    )

    // Drawing tick marks for Fahrenheit (-40°F to 212°F)
    for (temp in -40..220 step 1) {
        val angleDeg = temp.toFloat()
        val angleRad = Math.toRadians(angleDeg.toDouble())

        val isMajor = temp % 10 == 0
        val isMedium = temp % 5 == 0 && !isMajor

        val tickLength = when {
            isMajor -> 20f * scale
            isMedium -> 12f * scale
            else -> 6f * scale
        }
        val tickWidth = when {
            isMajor -> 4.5f * scale
            isMedium -> 2.5f * scale
            else -> 1.2f * scale
        }
        val tickColor = when {
            isMajor -> Color(0xFFFFA085) // Copper
            isMedium -> Color(0xFFD67958)
            else -> Color(0x60D67958)
        }

        val startR = dialRadius - tickLength
        val endR = dialRadius

        val sx = cx + startR * cos(angleRad).toFloat()
        val sy = cy + startR * sin(angleRad).toFloat()
        val ex = cx + endR * cos(angleRad).toFloat()
        val ey = cy + endR * sin(angleRad).toFloat()

        drawLine(
            color = tickColor,
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = tickWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Draws the calibration thumbwheel to fine-tune Sun 2 offset
 */
fun DrawScope.drawCalibrationThumbwheel(
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    sun2Angle: Float,
    tiltX: Float,
    tiltY: Float,
    time: Float
) {
    val cx = offsetX + 820f * scale
    val cy = offsetY + 715f * scale
    val r = 50f * scale

    // Rim Shadow
    drawCircle(
        color = Color(0x80000000),
        radius = r + 4f * scale,
        center = Offset(cx + 4f, cy + 4f)
    )

    // Base copper thumbwheel
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFFC76F4E), Color(0xFF4A2518), Color(0xFFC76F4E)),
            center = Offset(cx, cy)
        ),
        radius = r,
        center = Offset(cx, cy)
    )

    // Machined knurled edge teeth (24 teeth)
    val knurls = 24
    for (i in 0 until knurls) {
        val angleDeg = i * (360f / knurls) + sun2Angle * 2f
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val sx = cx + (r - 8f * scale) * cos(angleRad).toFloat()
        val sy = cy + (r - 8f * scale) * sin(angleRad).toFloat()
        val ex = cx + r * cos(angleRad).toFloat()
        val ey = cy + r * sin(angleRad).toFloat()

        drawLine(
            color = Color(0xFF1E0E08),
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = 3f * scale
        )
    }

    // Outer framing slot
    drawCircle(
        color = Color(0xFF1E2125),
        radius = r + 3f * scale,
        center = Offset(cx, cy),
        style = Stroke(width = 4f * scale)
    )

    // Center lock screw
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFECEEF0), Color(0xFF6B7280), Color(0xFF111827)),
            center = Offset(cx - 2f * scale, cy - 2f * scale),
            radius = 12f * scale
        ),
        radius = 12f * scale,
        center = Offset(cx, cy)
    )
    drawLine(
        color = Color(0xFF111827),
        start = Offset(cx - 8f * scale, cy),
        end = Offset(cx + 8f * scale, cy),
        strokeWidth = 2.5f * scale
    )
}

/**
 * Draws the gorgeous antique spring-loaded reset lever
 */
fun DrawScope.drawResetLever(
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    active: Boolean,
    tiltX: Float,
    tiltY: Float,
    time: Float
) {
    val cx = offsetX + 500f * scale
    val cy = offsetY + 920f * scale
    
    // Vertical mechanical lever
    val leverTravel = if (active) 40f * scale else 0f
    
    // Lever slot base
    drawRoundRect(
        color = Color(0xFF1C1E20),
        topLeft = Offset(cx - 15f * scale, cy - 60f * scale),
        size = Size(30f * scale, 100f * scale),
        cornerRadius = CornerRadius(15f * scale, 15f * scale)
    )
    drawRoundRect(
        color = Color(0xFF0F1012),
        topLeft = Offset(cx - 15f * scale, cy - 60f * scale),
        size = Size(30f * scale, 100f * scale),
        cornerRadius = CornerRadius(15f * scale, 15f * scale),
        style = Stroke(width = 2f * scale)
    )

    // Iron linkage pin
    val knobY = cy - 30f * scale + leverTravel

    // Shadow of the lever bar
    drawLine(
        color = Color(0x60000000),
        start = Offset(cx + 4f, cy - 50f * scale + 4f),
        end = Offset(cx + 4f, knobY + 4f),
        strokeWidth = 10f * scale,
        cap = StrokeCap.Round
    )

    // Steel lever arm
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF9CA3AF), Color(0xFF374151)),
            start = Offset(cx, cy - 50f * scale),
            end = Offset(cx, knobY)
        ),
        start = Offset(cx, cy - 50f * scale),
        end = Offset(cx, knobY),
        strokeWidth = 8f * scale,
        cap = StrokeCap.Round
    )

    // Brass lever knob
    drawCircle(
        color = Color(0x70000000),
        radius = 18f * scale,
        center = Offset(cx + 2f * scale, knobY + 2f * scale)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFE082), Color(0xFFFFB300), Color(0xFF8F6500)),
            center = Offset(cx - 4f * scale, knobY - 4f * scale),
            radius = 14f * scale
        ),
        radius = 14f * scale,
        center = Offset(cx, knobY)
    )
}

/**
 * Renders the machined gold border around the app viewport
 */
fun DrawScope.drawIndustrialBorder(
    width: Float,
    height: Float,
    scale: Float
) {
    val strokeWidth = 10f * scale
    
    // Outer shadow border
    drawRect(
        color = Color(0xFF0F1113),
        topLeft = Offset.Zero,
        size = Size(width, height),
        style = Stroke(width = strokeWidth * 2f)
    )

    // Double frame (Brass outer, bronze inner)
    drawRect(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFFD4AF37), Color(0xFF8C7232), Color(0xFFD4AF37)),
            center = Offset(width / 2f, height / 2f)
        ),
        topLeft = Offset(strokeWidth * 0.5f, strokeWidth * 0.5f),
        size = Size(width - strokeWidth, height - strokeWidth),
        style = Stroke(width = strokeWidth)
    )

    drawRect(
        brush = Brush.sweepGradient(
            colors = listOf(Color(0xFFC76F4E), Color(0xFF4A2518), Color(0xFFC76F4E)),
            center = Offset(width / 2f, height / 2f)
        ),
        topLeft = Offset(strokeWidth * 1.5f, strokeWidth * 1.5f),
        size = Size(width - strokeWidth * 3f, height - strokeWidth * 3f),
        style = Stroke(width = 3f * scale)
    )
}

/**
 * Draws diagonal glass shine across the entire instrument face
 */
fun DrawScope.drawGlassReflectionOverlay(
    width: Float,
    height: Float
) {
    // Glass highlight shine
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0x1AFFFFFF),
                Color(0x05FFFFFF),
                Color(0x00000000),
                Color(0x0CFFFFFF),
                Color(0x00000000)
            ),
            start = Offset.Zero,
            end = Offset(width, height)
        ),
        topLeft = Offset.Zero,
        size = Size(width, height)
    )
}
