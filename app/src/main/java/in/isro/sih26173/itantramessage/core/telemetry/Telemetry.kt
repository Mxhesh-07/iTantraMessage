package `in`.isro.sih26173.itantramessage.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TelemetryState(
    val sttLatencyMs: Long = 0,
    val ttsLatencyMs: Long = 0,
    val wireTransferMs: Long = 0,
    val e2eLatencyMs: Long = 0,
    val rtf: Float = 0f,
    val ramMb: Long = 0,
    val bwSavedPct: Float = 0f,
    val packetSizeBytes: Int = 0,
    val rawAudioKb: Float = 0f
)

object Telemetry {
    private val _state = MutableStateFlow(TelemetryState())
    val state: StateFlow<TelemetryState> = _state.asStateFlow()

    fun update(transform: TelemetryState.() -> TelemetryState) {
        _state.value = _state.value.transform()
    }

    fun reset() {
        _state.value = TelemetryState()
    }
}
