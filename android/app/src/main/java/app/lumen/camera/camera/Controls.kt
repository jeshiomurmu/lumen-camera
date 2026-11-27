package app.lumen.camera.camera

/** Flash behaviour (photo) or torch (video). */
enum class FlashMode { OFF, AUTO, ON, TORCH }

/**
 * Every user-adjustable capture parameter. `null` means "automatic".
 * Ranges are validated against [CameraCaps] when requests are built.
 */
data class Controls(
    val zoomRatio: Float = 1f,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val evSteps: Int = 0,
    val focusDiopters: Float? = null,
    val wbKelvin: Int? = null,
    val wbTint: Int = 0,
    val flash: FlashMode = FlashMode.OFF,
    val ois: Boolean = true,
    val videoStabilization: Boolean = true,
    val noiseReductionMode: Int? = null,
    val edgeMode: Int? = null,
    val logCurve: Boolean = false,
    val fps: Int? = null,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
) {
    val manualExposure: Boolean get() = iso != null || shutterNs != null
}

/** Values reported by the camera for the live preview (Pro mode readouts). */
data class LiveInfo(
    val iso: Int = 0,
    val exposureNs: Long = 0,
    val focusDiopters: Float = 0f,
    val kelvin: Int = 0,
    val aperture: Float = 0f,
    val focalLength: Float = 0f,
    val aeConverged: Boolean = false,
    val afLocked: Boolean = false,
    /** Scene brightness (EV100); < ~3 suggests Night mode. */
    val ev100: Float = 10f,
    val zoomRatio: Float = 1f,
)

/** Pro-mode preset (One UI 8.5-style save/load of manual settings). */
data class ProPreset(
    val name: String,
    val iso: Int?,
    val shutterNs: Long?,
    val evSteps: Int,
    val focusDiopters: Float?,
    val wbKelvin: Int?,
    val wbTint: Int,
)
