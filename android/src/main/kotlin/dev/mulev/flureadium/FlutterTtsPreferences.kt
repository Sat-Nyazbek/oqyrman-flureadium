package dev.mulev.flureadium

import kotlinx.serialization.Serializable
import org.json.JSONObject
import dev.mulev.flureadium.navigators.SherpaPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language

/**
 * TTS preferences used in the Flutter Readium plugin.
 */
@Serializable
data class FlutterTtsPreferences(
    val language: String? = null,
    val pitch: Double? = null,
    val speed: Double? = null,
    val voices: Map<String, String>? = null,
    val controlPanelInfoType: ControlPanelInfoType? = ControlPanelInfoType.STANDARD,
) {
        @OptIn(ExperimentalReadiumApi::class)
    fun toSherpaPreferences(): SherpaPreferences = SherpaPreferences(
        language = language?.let { Language(it) },
        speed = speed,
        // Pick voice for the current language; fall back to first available if language unknown.
        voiceIdentifier = language?.let { voices?.get(it) } ?: voices?.values?.firstOrNull(),
    )

    fun plus(other: FlutterTtsPreferences): FlutterTtsPreferences =
        FlutterTtsPreferences(
            language = other.language ?: language,
            pitch = other.pitch ?: pitch,
            speed = other.speed ?: speed,
            voices = other.voices ?: voices,
            controlPanelInfoType = other.controlPanelInfoType ?: controlPanelInfoType
        )

    companion object {
        /**
         * Create FlutterTtsPreferences from JSON string.
         */
        fun fromJSON(json: String): FlutterTtsPreferences {
            return fromJSON(JSONObject(json))
        }

        /**
         * Create FlutterTtsPreferences from JSON object.
         */
        fun fromJSON(jsonObject: JSONObject): FlutterTtsPreferences {
            val voicesMap = mutableMapOf<String, String>()
            if (jsonObject.has("voices")) {
                val voicesJson = jsonObject.getJSONObject("voices")
                for (key in voicesJson.keys()) {
                    voicesMap[key] = voicesJson.getString(key)
                }
            }
            return FlutterTtsPreferences(
                language = jsonObject.optString("language", null),
                pitch = jsonObject.optDouble("pitch").let { if (it.isNaN()) null else it },
                speed = jsonObject.optDouble("speed").let { if (it.isNaN()) null else it },
                voices = voicesMap.ifEmpty { null },
                controlPanelInfoType = ControlPanelInfoType.fromString(
                    jsonObject.optString(
                        "controlPanelInfoType",
                        "standard"
                    )
                )
            )
        }

        /**
         * Convert FlutterTtsPreferences to JSON object.
         */
        fun toJSON(preferences: FlutterTtsPreferences): JSONObject {
            val jsonObject = JSONObject()
            jsonObject.put("language", preferences.language)
            jsonObject.put("pitch", preferences.pitch)
            jsonObject.put("speed", preferences.speed)
            preferences.voices?.let { voices ->
                val voicesJson = JSONObject()
                voices.forEach { (key, value) -> voicesJson.put(key, value) }
                jsonObject.put("voices", voicesJson)
            }
            jsonObject.put("controlPanelInfoType", preferences.controlPanelInfoType?.toString())
            return jsonObject
        }

        /**
         * Create FlutterTtsPreferences from a map.
         */
        fun fromMap(prefs: Map<*, *>?): FlutterTtsPreferences {
            val voices = (prefs?.get("voices") as? Map<*, *>)?.mapNotNull {
                val key = it.key as? String
                val value = it.value as? String
                if (key != null && value != null) key to value else null
            }?.toMap()
            return FlutterTtsPreferences(
                language = prefs?.get("language") as? String,
                pitch = prefs?.get("pitch") as? Double,
                speed = prefs?.get("speed") as? Double,
                voices = voices,
                controlPanelInfoType = ControlPanelInfoType.fromString(
                    prefs?.get("controlPanelInfoType") as? String ?: "standard"
                )
            )
        }
    }
}
