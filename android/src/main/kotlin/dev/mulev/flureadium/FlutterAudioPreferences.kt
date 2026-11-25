package dev.mulev.flureadium

import kotlinx.serialization.Serializable
import org.json.JSONObject
import org.readium.adapter.exoplayer.audio.ExoPlayerPreferences
import org.readium.r2.navigator.preferences.Configurable

/**
 * Audio preferences for Flutter Readium with extra properties.
 */
@Serializable
data class FlutterAudioPreferences(
    val volume: Double? = null,
    val pitch: Double? = null,
    val speed: Double? = null,
    val seekInterval: Double = 30.0,
    val allowExternalSeeking: Boolean = true,
    val controlPanelInfoType: ControlPanelInfoType? = ControlPanelInfoType.STANDARD,
) : Configurable.Preferences<FlutterAudioPreferences> {

    override fun plus(other: FlutterAudioPreferences): FlutterAudioPreferences =
        FlutterAudioPreferences(
            volume = other.volume ?: volume,
            pitch = other.pitch ?: pitch,
            speed = other.speed ?: speed,
            seekInterval = other.seekInterval,
            allowExternalSeeking = other.allowExternalSeeking,
            controlPanelInfoType = other.controlPanelInfoType
        )

    /**
     * Converts FlutterAudioPreferences to ExoPlayerPreferences.
     */
    fun toExoPlayerPreferences(): ExoPlayerPreferences {
        return ExoPlayerPreferences(
            pitch = this.pitch,
            speed = this.speed
        )
    }

    companion object {
        /**
         * Creates FlutterAudioPreferences from a JSON string.
         */
        fun fromJSON(json: String): FlutterAudioPreferences {
            return fromJSON(JSONObject(json))
        }

        /**
         * Creates FlutterAudioPreferences from a JSON object.
         * Uses opt* methods with sensible defaults to avoid JSONException when keys are absent.
         */
        fun fromJSON(jsonObject: JSONObject): FlutterAudioPreferences {
            return FlutterAudioPreferences(
                volume = jsonObject.optDouble("volume").let { if (it.isNaN()) null else it },
                pitch = jsonObject.optDouble("pitch").let { if (it.isNaN()) null else it },
                speed = jsonObject.optDouble("speed").let { if (it.isNaN()) null else it },
                seekInterval = jsonObject.optDouble("seekInterval", 30.0),
                allowExternalSeeking = jsonObject.optBoolean("allowExternalSeeking", true),
                controlPanelInfoType = ControlPanelInfoType.fromString(
                    jsonObject.optString("controlPanelInfoType", "standard")
                )
            )
        }

        /**
         * Converts FlutterAudioPreferences to a JSON object.
         */
        fun toJSON(preferences: FlutterAudioPreferences): JSONObject {
            val jsonObject = JSONObject()
            jsonObject.put("volume", preferences.volume)
            jsonObject.put("pitch", preferences.pitch)
            jsonObject.put("speed", preferences.speed)
            jsonObject.put("seekInterval", preferences.seekInterval)
            jsonObject.put("allowExternalSeeking", preferences.allowExternalSeeking)
            jsonObject.put("controlPanelInfoType", preferences.controlPanelInfoType?.toString())
            return jsonObject
        }

        /**
         * Creates FlutterAudioPreferences from a Map.
         */
        fun fromMap(prefs: Map<*, *>): FlutterAudioPreferences {
            return FlutterAudioPreferences(
                volume = prefs["volume"] as? Double ?: 1.0,
                pitch = prefs["pitch"] as? Double ?: 1.0,
                speed = prefs["speed"] as? Double ?: 1.0,
                seekInterval = prefs["seekInterval"] as? Double ?: 30.0,
                allowExternalSeeking = prefs["allowExternalSeeking"] as? Boolean ?: true,
                // TODO: Not sure if this is correct
                controlPanelInfoType = ControlPanelInfoType.fromString( prefs["controlPanelInfoType"] as? String ?: "standard"),
            )
        }
    }
}

