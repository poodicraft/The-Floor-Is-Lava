package com.lava.floorislava

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lava.floorislava.processing.ImageProcessor
import com.lava.floorislava.processing.LevelData
import com.lava.floorislava.processing.ProcessingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the captured sketch and the level derived from it.
 *
 * Living in a `ViewModel` means the level survives rotation and navigation between the
 * capture / tune / play screens without re-running the (relatively expensive) detection,
 * and that a screen leaving the composition cannot cancel an in-flight analysis.
 */
class SketchGameViewModel : ViewModel() {

    var sketch: Bitmap? by mutableStateOf(null)
        private set

    var config: ProcessingConfig by mutableStateOf(ProcessingConfig())
        private set

    var level: LevelData? by mutableStateOf(null)
        private set

    var isProcessing: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    private var processingJob: Job? = null

    /** A fresh photo arrived: reset the tuning and analyse it. */
    fun onSketchCaptured(bitmap: Bitmap) {
        sketch = bitmap
        config = ProcessingConfig()
        level = null
        reprocess(debounce = false)
    }

    /** Slider moved on the tuning screen. */
    fun updateConfig(newConfig: ProcessingConfig) {
        if (newConfig == config) return
        config = newConfig
        reprocess(debounce = true)
    }

    fun clearSketch() {
        processingJob?.cancel()
        sketch = null
        level = null
        errorMessage = null
        isProcessing = false
    }

    private fun reprocess(debounce: Boolean) {
        val bitmap = sketch ?: return
        val currentConfig = config
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            isProcessing = true
            errorMessage = null
            try {
                // Sliders fire continuously; wait for the finger to settle before re-analysing.
                if (debounce) delay(REPROCESS_DEBOUNCE_MS)
                level = withContext(Dispatchers.Default) {
                    ImageProcessor.process(bitmap, currentConfig)
                }
                isProcessing = false
            } catch (cancellation: CancellationException) {
                // A newer request superseded this one; it owns `isProcessing` now.
                throw cancellation
            } catch (error: Throwable) {
                errorMessage = "Could not read that image: ${error.message}"
                isProcessing = false
            }
        }
    }

    private companion object {
        const val REPROCESS_DEBOUNCE_MS = 180L
    }
}
