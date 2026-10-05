package dev.rotatebrowser.bridge

import org.json.JSONObject

data class VideoState(
    val frameId: String,
    val playing: Boolean,
    val duration: Double,
    val currentTime: Double,
    val isLive: Boolean,
    val videoW: Int,
    val videoH: Int,
    val rect: FloatArray,
    val dpr: Float,
    val isTop: Boolean,
    val origin: String,
    val updatedAt: Long
) {
    val isPortraitVideo: Boolean get() = videoH > videoW
    val seekableWindow: Double get() = if (isLive) 0.0 else duration

    fun qualifies(): Boolean =
        playing && videoW >= 200 && videoH >= 200 && !isPortraitVideo

    companion object {
        fun from(frameId: String, json: JSONObject, origin: String, isMain: Boolean): VideoState {
            val playing = json.optBoolean("playing", false)
            val rectArr = json.optJSONArray("rect")
            val rect = if (rectArr != null && rectArr.length() == 4) {
                floatArrayOf(
                    rectArr.optDouble(0, 0.0).toFloat(),
                    rectArr.optDouble(1, 0.0).toFloat(),
                    rectArr.optDouble(2, 0.0).toFloat().coerceIn(0f, 10000f),
                    rectArr.optDouble(3, 0.0).toFloat().coerceIn(0f, 10000f)
                )
            } else floatArrayOf(0f, 0f, 0f, 0f)

            return VideoState(
                frameId = frameId,
                playing = playing,
                duration = json.optDouble("duration", 0.0).coerceIn(0.0, 86400.0),
                currentTime = json.optDouble("currentTime", 0.0).coerceIn(0.0, 86400.0),
                isLive = json.optBoolean("isLive", false),
                videoW = json.optInt("videoW", 0).coerceIn(0, 10000),
                videoH = json.optInt("videoH", 0).coerceIn(0, 10000),
                rect = rect,
                dpr = json.optDouble("dpr", 1.0).toFloat().coerceIn(0.5f, 5f),
                isTop = json.optBoolean("top", isMain),
                origin = origin.take(256),
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoState) return false
        return frameId == other.frameId && playing == other.playing
    }

    override fun hashCode(): Int = frameId.hashCode() * 31 + playing.hashCode()
}
