package com.theveloper.pixelplay.data.equalizer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op equalizer manager. All android.media.audiofx instantiation has been removed
 * so that system-wide DSP (e.g. ViperFX) can attach to the audio session cleanly.
 * The public API is preserved so that EqualizerViewModel and MusicService compile
 * without any other changes.
 */
@Singleton
class EqualizerManager @Inject constructor() {

    companion object {
        private const val NUM_BANDS = 10
        private const val MIN_LEVEL = -15
        private const val MAX_LEVEL = 15
    }

    // Always report "not attached" so the service never tries to attach effects.
    val isAttached: Boolean get() = false

    val hasAnyEnabledEffects: Boolean
        get() = _isEnabled.value ||
            _bassBoostEnabled.value ||
            _virtualizerEnabled.value ||
            _loudnessEnhancerEnabled.value

    private val _bandLevels = MutableStateFlow(List(NUM_BANDS) { 0 })
    val bandLevels: StateFlow<List<Int>> = _bandLevels.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentPresetName = MutableStateFlow("flat")
    val currentPresetName: StateFlow<String> = _currentPresetName.asStateFlow()

    private val _bassBoostEnabled = MutableStateFlow(false)
    val bassBoostEnabled: StateFlow<Boolean> = _bassBoostEnabled.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(0)
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    private val _virtualizerEnabled = MutableStateFlow(false)
    val virtualizerEnabled: StateFlow<Boolean> = _virtualizerEnabled.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow(0)
    val virtualizerStrength: StateFlow<Int> = _virtualizerStrength.asStateFlow()

    private val _loudnessEnhancerEnabled = MutableStateFlow(false)
    val loudnessEnhancerEnabled: StateFlow<Boolean> = _loudnessEnhancerEnabled.asStateFlow()

    private val _loudnessEnhancerStrength = MutableStateFlow(0)
    val loudnessEnhancerStrength: StateFlow<Int> = _loudnessEnhancerStrength.asStateFlow()

    // No-op: never creates any AudioEffect object.
    suspend fun attachToAudioSession(audioSessionId: Int) { /* no-op */ }

    suspend fun attachToAudioSessionIfNeeded(audioSessionId: Int) { /* no-op */ }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    fun setBandLevel(bandIndex: Int, level: Int) {
        if (bandIndex !in 0 until NUM_BANDS) return
        val newLevels = _bandLevels.value.toMutableList()
        newLevels[bandIndex] = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        _bandLevels.value = newLevels
        _currentPresetName.value = "custom"
    }

    fun applyPreset(preset: EqualizerPreset) {
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        _bassBoostEnabled.value = enabled
    }

    fun setBassBoostStrength(strength: Int) {
        _bassBoostStrength.value = strength.coerceIn(0, 1000)
    }

    fun setVirtualizerEnabled(enabled: Boolean) {
        _virtualizerEnabled.value = enabled
    }

    fun setVirtualizerStrength(strength: Int) {
        _virtualizerStrength.value = strength.coerceIn(0, 1000)
    }

    fun setLoudnessEnhancerEnabled(enabled: Boolean) {
        _loudnessEnhancerEnabled.value = enabled
    }

    fun setLoudnessEnhancerStrength(strength: Int) {
        _loudnessEnhancerStrength.value = strength.coerceIn(0, 1000)
    }

    fun restoreState(
        enabled: Boolean,
        presetName: String,
        customBands: List<Int>,
        bassBoostEnabled: Boolean,
        bassBoostStrength: Int,
        virtualizerEnabled: Boolean,
        virtualizerStrength: Int,
        loudnessEnabled: Boolean,
        loudnessStrength: Int
    ) {
        _isEnabled.value = enabled
        _bassBoostEnabled.value = bassBoostEnabled
        _bassBoostStrength.value = bassBoostStrength
        _virtualizerEnabled.value = virtualizerEnabled
        _virtualizerStrength.value = virtualizerStrength
        _loudnessEnhancerEnabled.value = loudnessEnabled
        _loudnessEnhancerStrength.value = loudnessStrength.coerceIn(0, 1000)

        val preset = if (presetName == "custom") {
            EqualizerPreset.custom(customBands)
        } else {
            EqualizerPreset.fromName(presetName)
        }
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
    }

    fun getBandFrequencies(): List<Int> =
        listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    fun isBassBoostSupported(): Boolean = false

    fun isVirtualizerSupported(): Boolean = false

    fun isLoudnessEnhancerSupported(): Boolean = false

    fun release() { /* no-op */ }
}
