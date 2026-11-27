package app.lumen.camera.processing

import android.content.Context
import app.lumen.camera.camera.ProPreset
import app.lumen.camera.camera.VideoCodec
import app.lumen.camera.core.NativeCore
import org.json.JSONArray
import org.json.JSONObject

/** Which pipeline makes the photo. */
enum class PhotoEngine(val label: String) {
    LUMEN("Lumen burst (open-source HDR+)"),
    DEVICE("Device ISP (fastest)"),
}

/** User settings, persisted in SharedPreferences as JSON. */
data class AppSettings(
    // photo
    val photoEngine: PhotoEngine = PhotoEngine.LUMEN,
    val saveRaw: Boolean = false,
    val maxResolution: Boolean = false,
    val burstFrames: Int = 0,            // 0 = automatic
    val profile: Int = NativeCore.PROFILE_NATURAL,
    val aiDenoise: Boolean = false,      // off: photos keep real texture; opt-in in settings
    val aiZoom: Boolean = false,
    val jpegQuality: Int = 97,
    val displayP3: Boolean = false,
    val ispNoiseReduction: Int = -1,     // -1 = camera default, else CaptureRequest NR mode
    val ispSharpening: Int = -1,         // -1 = camera default, else EDGE mode
    val aspect: Float = 4f / 3f,         // 4/3, 16/9, 1 (square) or 0 (full screen)
    // video
    val videoModeKey: String = "",       // "WxH@fps[h]"
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val bitrateMbps: Int = 0,            // 0 = automatic
    val tenBitHlg: Boolean = false,
    val logProfile: Boolean = false,
    val cbr: Boolean = false,
    val stabilization: Boolean = true,
    val recordAudio: Boolean = true,
    // astro
    val astroFrames: Int = 20,
    val astroExposureS: Float = 10f,
    val astroIso: Int = 1600,
    val astroStackMode: Int = NativeCore.STACK_SIGMA_CLIP,
    val astroAlign: Boolean = true,
    val astroDarkFrames: Int = 0,
    // overlays & misc
    val grid: Boolean = true,
    val histogram: Boolean = false,
    val focusPeaking: Boolean = false,
    val zebras: Boolean = false,
    val level: Boolean = true,
    val volumeShutter: Boolean = true,
    val timerSeconds: Int = 0,
    // cinema tools
    val shutterAngle: Boolean = true,     // video shutter shown as angle (180° = 1/(2·fps))
    val antibanding: Int = 0,             // 0 auto, 50 or 60 Hz flicker-free
    val frameGuide: Float = 0f,           // 0 off, else aspect ratio (e.g. 2.39)
    val safeArea: Int = 0,                // 0 off, else % of the frame
    val falseColor: Boolean = false,
    val audioGainDb: Int = 0,
    val audioMeters: Boolean = true,
    val timecodeTod: Boolean = false,     // false: record run, true: time of day
    val lockWbOnRecord: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("photoEngine", photoEngine.name); put("saveRaw", saveRaw); put("maxResolution", maxResolution)
        put("burstFrames", burstFrames); put("profile", profile); put("aiDenoise", aiDenoise); put("aiZoom", aiZoom)
        put("jpegQuality", jpegQuality); put("displayP3", displayP3); put("ispNR", ispNoiseReduction)
        put("ispEdge", ispSharpening); put("aspect", aspect.toDouble())
        put("videoModeKey", videoModeKey); put("videoCodec", videoCodec.name); put("bitrateMbps", bitrateMbps)
        put("tenBitHlg", tenBitHlg); put("logProfile", logProfile); put("cbr", cbr); put("stabilization", stabilization)
        put("recordAudio", recordAudio)
        put("astroFrames", astroFrames); put("astroExposureS", astroExposureS.toDouble()); put("astroIso", astroIso)
        put("astroStackMode", astroStackMode); put("astroAlign", astroAlign); put("astroDarkFrames", astroDarkFrames)
        put("grid", grid); put("histogram", histogram); put("focusPeaking", focusPeaking); put("zebras", zebras)
        put("level", level); put("volumeShutter", volumeShutter); put("timerSeconds", timerSeconds)
        put("shutterAngle", shutterAngle); put("antibanding", antibanding); put("frameGuide", frameGuide.toDouble())
        put("safeArea", safeArea); put("falseColor", falseColor); put("audioGainDb", audioGainDb)
        put("audioMeters", audioMeters); put("timecodeTod", timecodeTod); put("lockWbOnRecord", lockWbOnRecord)
    }

    companion object {
        /** Bumped when defaults change in a way old saved settings should not override. */
        const val SCHEMA = 2

        fun fromJson(o: JSONObject): AppSettings {
            val d = AppSettings()
            // v1 turned AI denoise / AI zoom / histogram on by default: drop those saved values
            val old = o.optInt("schema", 1) < SCHEMA
            if (old) { o.remove("aiDenoise"); o.remove("aiZoom"); o.remove("histogram") }
            return AppSettings(
                photoEngine = runCatching { PhotoEngine.valueOf(o.optString("photoEngine")) }.getOrDefault(d.photoEngine),
                saveRaw = o.optBoolean("saveRaw", d.saveRaw),
                maxResolution = o.optBoolean("maxResolution", d.maxResolution),
                burstFrames = o.optInt("burstFrames", d.burstFrames),
                profile = o.optInt("profile", d.profile),
                aiDenoise = o.optBoolean("aiDenoise", d.aiDenoise),
                aiZoom = o.optBoolean("aiZoom", d.aiZoom),
                jpegQuality = o.optInt("jpegQuality", d.jpegQuality),
                displayP3 = o.optBoolean("displayP3", d.displayP3),
                ispNoiseReduction = o.optInt("ispNR", d.ispNoiseReduction),
                ispSharpening = o.optInt("ispEdge", d.ispSharpening),
                aspect = o.optDouble("aspect", d.aspect.toDouble()).toFloat(),
                videoModeKey = o.optString("videoModeKey", d.videoModeKey),
                videoCodec = runCatching { VideoCodec.valueOf(o.optString("videoCodec")) }.getOrDefault(d.videoCodec),
                bitrateMbps = o.optInt("bitrateMbps", d.bitrateMbps),
                tenBitHlg = o.optBoolean("tenBitHlg", d.tenBitHlg),
                logProfile = o.optBoolean("logProfile", d.logProfile),
                cbr = o.optBoolean("cbr", d.cbr),
                stabilization = o.optBoolean("stabilization", d.stabilization),
                recordAudio = o.optBoolean("recordAudio", d.recordAudio),
                astroFrames = o.optInt("astroFrames", d.astroFrames),
                astroExposureS = o.optDouble("astroExposureS", d.astroExposureS.toDouble()).toFloat(),
                astroIso = o.optInt("astroIso", d.astroIso),
                astroStackMode = o.optInt("astroStackMode", d.astroStackMode),
                astroAlign = o.optBoolean("astroAlign", d.astroAlign),
                astroDarkFrames = o.optInt("astroDarkFrames", d.astroDarkFrames),
                grid = o.optBoolean("grid", d.grid),
                histogram = o.optBoolean("histogram", d.histogram),
                focusPeaking = o.optBoolean("focusPeaking", d.focusPeaking),
                zebras = o.optBoolean("zebras", d.zebras),
                level = o.optBoolean("level", d.level),
                volumeShutter = o.optBoolean("volumeShutter", d.volumeShutter),
                timerSeconds = o.optInt("timerSeconds", d.timerSeconds),
                shutterAngle = o.optBoolean("shutterAngle", d.shutterAngle),
                antibanding = o.optInt("antibanding", d.antibanding),
                frameGuide = o.optDouble("frameGuide", d.frameGuide.toDouble()).toFloat(),
                safeArea = o.optInt("safeArea", d.safeArea),
                falseColor = o.optBoolean("falseColor", d.falseColor),
                audioGainDb = o.optInt("audioGainDb", d.audioGainDb),
                audioMeters = o.optBoolean("audioMeters", d.audioMeters),
                timecodeTod = o.optBoolean("timecodeTod", d.timecodeTod),
                lockWbOnRecord = o.optBoolean("lockWbOnRecord", d.lockWbOnRecord),
            )
        }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    fun load(): AppSettings = try {
        prefs.getString("settings", null)?.let { AppSettings.fromJson(JSONObject(it)) } ?: AppSettings()
    } catch (_: Exception) {
        AppSettings()
    }

    fun save(s: AppSettings) = prefs.edit().putString("settings", s.toJson().toString()).apply()

    fun presets(): List<ProPreset> = try {
        val arr = JSONArray(prefs.getString("presets", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ProPreset(
                name = o.getString("name"),
                iso = if (o.has("iso")) o.getInt("iso") else null,
                shutterNs = if (o.has("shutter")) o.getLong("shutter") else null,
                evSteps = o.optInt("ev", 0),
                focusDiopters = if (o.has("focus")) o.getDouble("focus").toFloat() else null,
                wbKelvin = if (o.has("kelvin")) o.getInt("kelvin") else null,
                wbTint = o.optInt("tint", 0),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun savePresets(list: List<ProPreset>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                p.iso?.let { put("iso", it) }
                p.shutterNs?.let { put("shutter", it) }
                put("ev", p.evSteps)
                p.focusDiopters?.let { put("focus", it.toDouble()) }
                p.wbKelvin?.let { put("kelvin", it) }
                put("tint", p.wbTint)
            })
        }
        prefs.edit().putString("presets", arr.toString()).apply()
    }
}
