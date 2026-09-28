package com.pranav.atminigame.audio.parser

import android.content.Context
import com.pranav.atminigame.audio.model.*
import org.xmlpull.v1.XmlPullParser

/**
 * Parses track configurations from game_music.xml.
 */
class GameMusicParser(private val context: Context) {

    fun parse(resourceId: Int): MusicLibrary {
        val tracks = mutableMapOf<String, TrackConfig>()
        val parser = context.resources.getXml(resourceId)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "track") {
                val track = parseTrack(parser)
                tracks[track.id] = track
            }
            eventType = parser.next()
        }
        return MusicLibrary(tracks)
    }

    private fun parseTrack(parser: XmlPullParser): TrackConfig {
        val id = parser.getAttributeValue(null, "id") ?: "unknown"
        val tempo = parser.getAttributeValue(null, "tempo")?.toIntOrNull() ?: 76
        val root = parser.getAttributeValue(null, "root") ?: "D"
        val scale = parser.getAttributeValue(null, "scale") ?: "phrygian"

        var bass: BassConfig? = null
        var pad: PadConfig? = null
        var arpeggio: ArpConfig? = null

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name == "track")) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "bass" -> bass = BassConfig(
                        waveform = parser.getAttributeValue(null, "waveform") ?: "square",
                        octave = parser.getAttributeValue(null, "octave")?.toIntOrNull() ?: 1,
                        volume = parser.getAttributeValue(null, "volume")?.toFloatOrNull() ?: 0.4f
                    )
                    "pad" -> pad = PadConfig(
                        waveform = parser.getAttributeValue(null, "waveform") ?: "sawtooth",
                        attack = parser.getAttributeValue(null, "attack")?.toFloatOrNull() ?: 1.5f,
                        release = parser.getAttributeValue(null, "release")?.toFloatOrNull() ?: 3.0f,
                        volume = parser.getAttributeValue(null, "volume")?.toFloatOrNull() ?: 0.25f,
                        wobble = parser.getAttributeValue(null, "wobble")?.toFloatOrNull() ?: 0.3f,
                        octave = parser.getAttributeValue(null, "octave")?.toIntOrNull() ?: 3
                    )
                    "arpeggio" -> {
                        val patternStr = parser.getAttributeValue(null, "pattern") ?: "1,4,5,8"
                        val pattern = patternStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                        arpeggio = ArpConfig(
                            pattern = pattern,
                            noteLength = parser.getAttributeValue(null, "noteLength") ?: "sixteenth",
                            volume = parser.getAttributeValue(null, "volume")?.toFloatOrNull() ?: 0.5f
                        )
                    }
                }
            }
            eventType = parser.next()
        }

        return TrackConfig(
            id = id,
            tempo = tempo,
            root = root,
            scale = scale,
            bass = bass ?: BassConfig("square", 1, 0.4f),
            pad = pad ?: PadConfig("sawtooth", 1.5f, 3.0f, 0.25f, 0.3f, 3),
            arpeggio = arpeggio ?: ArpConfig(listOf(1, 4, 5, 8), "sixteenth", 0.5f)
        )
    }
}
