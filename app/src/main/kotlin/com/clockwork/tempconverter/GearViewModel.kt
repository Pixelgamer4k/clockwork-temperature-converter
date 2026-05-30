package com.clockwork.tempconverter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign

class GearViewModel(application: Application) : AndroidViewModel(application) {

    private val tiltTracker = TiltTracker(application)

    // Master state: current Celsius value (corresponds to Main Gear 1 Angle in degrees)
    private val _celsius = MutableStateFlow(0f)
    val celsius: StateFlow<Float> = _celsius.asStateFlow()

    // Fahrenheit calculated position
    private val _fahrenheit = MutableStateFlow(32f)
    val fahrenheit: StateFlow<Float> = _fahrenheit.asStateFlow()

    // Calibration offset sun gear rotation angle (default 64 degrees = +32 Fahrenheit)
    private val _sun2OffsetAngle = MutableStateFlow(64f)
    val sun2OffsetAngle: StateFlow<Float> = _sun2OffsetAngle.asStateFlow()

    // Sensor tilt flows (reactive gyroscope parallax)
    private val _tiltX = MutableStateFlow(0f)
    val tiltX: StateFlow<Float> = _tiltX.asStateFlow()

    private val _tiltY = MutableStateFlow(0f)
    val tiltY: StateFlow<Float> = _tiltY.asStateFlow()

    // Mechanical reset lever state (active/inactive)
    private val _leverActive = MutableStateFlow(false)
    val leverActive: StateFlow<Boolean> = _leverActive.asStateFlow()

    // Physics constants
    private val friction = 0.95f // Damping ratio per frame
    private val snapStrength = 4.5f // Spring pull force for snapping
    private val minVelocityForCoast = 0.05f

    // Running states
    private var velocity = 0f // Degrees per second
    private var isDragging = false
    private var lastTickAngle = 0f

    // Shared flow to trigger haptic and sound ticks in the view layer
    private val _tickEvent = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    val tickEvent: SharedFlow<Boolean> = _tickEvent.asSharedFlow()

    init {
        // Start sensor tracking
        tiltTracker.start()
        viewModelScope.launch {
            tiltTracker.tilt.collect { (tx, ty) ->
                _tiltX.value = tx
                _tiltY.value = ty
            }
        }

        // Main 60fps physics simulation loop
        viewModelScope.launch {
            var lastTime = System.nanoTime()
            while (true) {
                val now = System.nanoTime()
                val dt = ((now - lastTime) / 1e9f).coerceIn(0.005f, 0.1f) // Cap dt to avoid spikes
                lastTime = now

                if (!isDragging) {
                    updatePhysics(dt)
                }

                delay(16) // roughly 60 fps
            }
        }
    }

    private fun updatePhysics(dt: Float) {
        val currentAngle = _celsius.value
        var currentVelocity = velocity

        // 1. Reset / Spring Return to Zero if lever is activated
        if (_leverActive.value) {
            val distToZero = 0f - currentAngle
            if (abs(distToZero) < 0.1f) {
                _celsius.value = 0f
                velocity = 0f
                _leverActive.value = false
                triggerTick()
            } else {
                // Spring physics with damping
                val springForce = distToZero * 25f
                currentVelocity += (springForce - currentVelocity * 8f) * dt
                _celsius.value = (currentAngle + currentVelocity * dt).coerceIn(-60f, 160f)
                velocity = currentVelocity
                checkTickThreshold(_celsius.value)
            }
            updateFahrenheitState()
            return
        }

        // 2. Momentum coasting (friction damping)
        if (abs(currentVelocity) > minVelocityForCoast) {
            // Apply angular friction
            currentVelocity *= friction
            val nextAngle = (currentAngle + currentVelocity * dt).coerceIn(-60f, 160f)
            _celsius.value = nextAngle
            velocity = currentVelocity
            checkTickThreshold(nextAngle)
        } else {
            // 3. Snap to nearest integer tick mark (1°C minor snaps)
            velocity = 0f
            val targetSnap = round(currentAngle)
            val distToSnap = targetSnap - currentAngle
            if (abs(distToSnap) > 0.001f) {
                // Gentle spring pull to align teeth
                val springForce = distToSnap * snapStrength
                val snapStep = springForce * dt
                val nextAngle = (currentAngle + snapStep).coerceIn(-60f, 160f)
                _celsius.value = nextAngle
                checkTickThreshold(nextAngle)
            } else {
                _celsius.value = targetSnap
            }
        }

        updateFahrenheitState()
    }

    /**
     * Checks if the gear rotation has crossed a tooth-mesh boundary (approx every 10 degrees).
     * Triggers sound and haptics when crossing a 10° mesh tick.
     */
    private fun checkTickThreshold(angle: Float) {
        val toothSpacing = 10.0f
        val diff = angle - lastTickAngle
        if (abs(diff) >= toothSpacing) {
            triggerTick()
            // Snap lastTickAngle to exact 10-degree grid based on direction
            lastTickAngle = round(angle / toothSpacing) * toothSpacing
        }
    }

    private fun triggerTick() {
        viewModelScope.launch {
            _tickEvent.emit(true)
        }
    }

    private fun updateFahrenheitState() {
        _fahrenheit.value = GearSystem.getFahrenheitFromAngleC(_celsius.value, _sun2OffsetAngle.value)
    }

    // --- Interaction Hooks ---

    fun onDragStart() {
        isDragging = true
        velocity = 0f
    }

    /**
     * Updates rotation from Celsius Crank dragging
     */
    fun onDragCelsius(angleDelta: Float, instantaneousVelocity: Float) {
        isDragging = true
        val newAngle = (_celsius.value + angleDelta).coerceIn(-60f, 160f)
        _celsius.value = newAngle
        velocity = instantaneousVelocity
        checkTickThreshold(newAngle)
        updateFahrenheitState()
    }

    /**
     * Updates rotation from Fahrenheit output pointer dragging (bidirectional inverse conversion)
     */
    fun onDragFahrenheit(angleDelta: Float, instantaneousVelocity: Float) {
        isDragging = true
        // Invert delta: 1.8f F degrees = 1.0f C degree
        val angleDeltaC = angleDelta / 1.8f
        val instantaneousVelocityC = instantaneousVelocity / 1.8f

        val newAngle = (_celsius.value + angleDeltaC).coerceIn(-60f, 160f)
        _celsius.value = newAngle
        velocity = instantaneousVelocityC
        checkTickThreshold(newAngle)
        updateFahrenheitState()
    }

    fun onDragEnd() {
        isDragging = false
        // Limit maximum release speed to keep rotation controllable
        velocity = velocity.coerceIn(-300f, 300f)
    }

    /**
     * Pulls the mechanical lever to trigger spring return to zero
     */
    fun pullResetLever() {
        if (!_leverActive.value) {
            _leverActive.value = true
            triggerTick()
        }
    }

    /**
     * Manual calibration shift of the offset gear (Sun 2).
     * Allows rotating Sun 2 slightly to fine-tune zero offset.
     */
    fun rotateOffsetSun2(deltaDegrees: Float) {
        val nextOffset = (_sun2OffsetAngle.value + deltaDegrees).coerceIn(32f, 96f)
        _sun2OffsetAngle.value = nextOffset
        updateFahrenheitState()
        triggerTick()
    }

    override fun onCleared() {
        super.onCleared()
        tiltTracker.stop()
    }
}
