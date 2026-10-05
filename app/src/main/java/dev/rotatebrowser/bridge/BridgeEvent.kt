package dev.rotatebrowser.bridge

sealed class BridgeEvent {
    data class FullscreenOk(val frameId: String) : BridgeEvent()
    data class FullscreenFailed(val frameId: String) : BridgeEvent()
    data class VideoStateUpdate(val frameId: String, val state: VideoState) : BridgeEvent()
    data class Ready(val frameId: String, val isTop: Boolean, val origin: String) : BridgeEvent()
}
