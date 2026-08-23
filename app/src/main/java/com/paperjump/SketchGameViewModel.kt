package com.paperjump

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperjump.data.LevelRecord
import com.paperjump.data.LevelSource
import com.paperjump.data.LevelStore
import com.paperjump.data.PlayerStats
import com.paperjump.data.RecordKey
import com.paperjump.data.RecordStore
import com.paperjump.data.SavedLevelMeta
import com.paperjump.ai.AiLevelClient
import com.paperjump.ai.AiOutcome
import com.paperjump.ai.PlanPainter
import com.paperjump.data.SettingsRepository
import com.paperjump.draw.DrawingController
import com.paperjump.draw.DrawingState
import com.paperjump.draw.StrokeRasterizer
import com.paperjump.game.GameSetup
import com.paperjump.processing.ImageProcessor
import com.paperjump.processing.LevelData
import com.paperjump.processing.ProcessingConfig
import com.paperjump.ui.AiBuildState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns everything that outlives a single screen: the current sketch and level, the
 * sketchpad, the library, the records and the settings.
 *
 * Living in a `ViewModel` means the level survives rotation and navigation without
 * re-running detection, that a screen leaving the composition cannot cancel an in-flight
 * analysis, and that a half-finished drawing is still there after a trip to Settings.
 */
class SketchGameViewModel(application: Application) : AndroidViewModel(application) {

    private val levelStore = LevelStore(application)
    private val recordStore = RecordStore(application)

    val settingsRepository = SettingsRepository(application)

    /** The sketchpad, kept here so a drawing survives navigating away and back. */
    val drawingController = DrawingController()

    /**
     * A second, separate sketchpad for the AI route.
     *
     * Its own document on purpose: the two ways of drawing mean different things, and
     * wandering into "Draw anything" should not scribble over the level you were halfway
     * through drawing by the colour code.
     */
    val freeDrawController = DrawingController()

    /** The optional "what is it?" note that goes to the model with the picture. */
    var freeDrawHint: String by mutableStateOf("")
        private set

    var aiState: AiBuildState by mutableStateOf(AiBuildState.Idle)
        private set

    /**
     * Set when an AI build finished cleanly and the game picker should open itself.
     *
     * A one-shot flag rather than a navigation call from the view model: the view model has
     * no business knowing about the back stack, and a plain "level is ready" boolean would
     * fire again every time the player walked back to this screen.
     */
    var aiLevelReady: Boolean by mutableStateOf(false)
        private set

    fun consumeAiLevelReady(): Boolean {
        if (!aiLevelReady) return false
        aiLevelReady = false
        return true
    }

    var sketch: Bitmap? by mutableStateOf(null)
        private set

    var config: ProcessingConfig by mutableStateOf(ProcessingConfig())
        private set

    var level: LevelData? by mutableStateOf(null)
        private set

    var source: LevelSource by mutableStateOf(LevelSource.PHOTO)
        private set

    /** The game and twist chosen for the next run. */
    var setup: GameSetup by mutableStateOf(GameSetup())
        private set

    var isProcessing: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    var savedLevels: List<SavedLevelMeta> by mutableStateOf(emptyList())
        private set

    /** Set when the current level came from — or has been written to — the library. */
    var currentSavedId: String? by mutableStateOf(null)
        private set

    var records: Map<RecordKey, LevelRecord> by mutableStateOf(emptyMap())
        private set

    /** What the records add up to, for the home screen's scoreboard. */
    val stats: PlayerStats get() = PlayerStats.of(records)

    /** True when the run that just finished beat the stored best time. */
    var lastRunWasBest: Boolean by mutableStateOf(false)
        private set

    private var processingJob: Job? = null

    init {
        refreshLibrary()
        viewModelScope.launch { records = recordStore.all() }
    }

    // ---- making a level -------------------------------------------------------------

    /** A fresh photo, sample or drawing arrived: reset the tuning and analyse it. */
    fun onSketchCaptured(bitmap: Bitmap, from: LevelSource) {
        sketch = bitmap
        source = from
        config = ProcessingConfig()
        level = null
        currentSavedId = null
        reprocess(debounce = false, saveToLibrary = true)
    }

    /** Rasterises the sketchpad and sends it through the same detector as a photograph. */
    fun onDrawingFinished(document: DrawingState, aspect: Float) {
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            isProcessing = true
            errorMessage = null
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    StrokeRasterizer.rasterize(document.strokes, aspect)
                }
                sketch = bitmap
                source = LevelSource.DRAWN
                config = ProcessingConfig()
                currentSavedId = null
                level = withContext(Dispatchers.Default) { ImageProcessor.process(bitmap, config) }
                isProcessing = false
                autoSave()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                errorMessage = "Could not turn that drawing into a level: ${error.message}"
                isProcessing = false
            }
        }
    }

    // ---- the AI level designer ------------------------------------------------------

    fun updateFreeDrawHint(text: String) {
        freeDrawHint = text
    }

    /** Called when the screen is left, so a stale error is not waiting on the way back in. */
    fun clearAiState() {
        aiState = AiBuildState.Idle
        aiLevelReady = false
    }

    /**
     * Sends a free drawing to the model and builds whatever it designs.
     *
     * The model's answer is painted as an ordinary sketch and read by the ordinary
     * detector — see [PlanPainter] — so what lands in the library is a normal level that
     * needs neither a key nor a signal ever again.
     *
     * If anything at all goes wrong, the drawing is still read the ordinary way. Coming
     * back from "turn this into a game" with nothing to play would be the worst outcome of
     * the lot, and a drawing always makes *some* level.
     */
    fun buildLevelWithAi(document: DrawingState, aspect: Float) {
        val settings = settingsRepository.settings
        val note = freeDrawHint

        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            aiState = AiBuildState.Working("Reading your drawing…")
            aiLevelReady = false
            isProcessing = true
            errorMessage = null
            currentSavedId = null
            level = null

            try {
                val drawn = withContext(Dispatchers.Default) {
                    StrokeRasterizer.rasterize(document.strokes, aspect)
                }

                aiState = AiBuildState.Working("Designing a level from it…")
                val outcome = AiLevelClient.planLevel(
                    drawing = drawn,
                    hint = note,
                    token = settings.aiToken,
                    model = settings.aiModel,
                    proxyUrl = settings.aiProxyUrl,
                    appSecret = BuildConfig.AI_APP_SECRET,
                )

                // Remember whichever model actually answered, so the next drawing does not
                // spend a round trip rediscovering that the first choice is not being served.
                (outcome as? AiOutcome.Success)?.let { success ->
                    if (success.model != settings.aiModel) {
                        settingsRepository.update { it.copy(aiModel = success.model) }
                    }
                }

                val designed = (outcome as? AiOutcome.Success)?.plan
                val page = if (designed != null) {
                    withContext(Dispatchers.Default) { PlanPainter.paint(designed, aspect) }
                } else {
                    drawn
                }

                aiState = AiBuildState.Working("Building it…")
                sketch = page
                source = LevelSource.DRAWN
                config = ProcessingConfig()
                level = withContext(Dispatchers.Default) { ImageProcessor.process(page, config) }
                isProcessing = false

                // A clean build walks straight on to the game picker. A failed one stays
                // put, so the player finds out *why* rather than silently getting the
                // ordinary reading of their drawing and wondering where the AI went.
                aiState = when (outcome) {
                    is AiOutcome.Success -> AiBuildState.Idle
                    is AiOutcome.Failure -> AiBuildState.Failed(outcome.message, builtAnyway = true)
                }
                aiLevelReady = outcome is AiOutcome.Success
                autoSave(name = designed?.title)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                isProcessing = false
                errorMessage = "Could not build that drawing: ${error.message}"
                aiState = AiBuildState.Failed(
                    message = "Could not build that drawing: ${error.message}",
                    builtAnyway = false,
                )
            }
        }
    }

    /** Slider moved on the tuning screen. */
    fun updateConfig(newConfig: ProcessingConfig) {
        if (newConfig == config) return
        config = newConfig
        reprocess(debounce = true)
    }

    /**
     * Not `setSetup`: `var setup` already compiles to a `setSetup` on the JVM, and a
     * function of that name would clash with it.
     */
    fun selectSetup(newSetup: GameSetup) {
        setup = newSetup
    }

    fun clearSketch() {
        processingJob?.cancel()
        sketch = null
        level = null
        errorMessage = null
        isProcessing = false
        currentSavedId = null
    }

    private fun reprocess(debounce: Boolean, saveToLibrary: Boolean = false) {
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
                if (saveToLibrary) autoSave() else updateSavedTuning()
            } catch (cancellation: CancellationException) {
                // A newer request superseded this one; it owns `isProcessing` now.
                throw cancellation
            } catch (error: Throwable) {
                errorMessage = "Could not read that image: ${error.message}"
                isProcessing = false
            }
        }
    }

    // ---- the library ----------------------------------------------------------------

    /**
     * Puts a newly made level straight into the library.
     *
     * Nobody wants to lose a drawing because they forgot to press save, and a level is
     * cheap to keep — an image and four numbers. Renaming and deleting are still there for
     * tidying up afterwards.
     */
    private fun autoSave(name: String? = null) {
        val bitmap = sketch ?: return
        if (currentSavedId != null) return
        val currentConfig = config
        val currentSource = source
        // The model names what it designed; everything else is named after when it was made.
        val title = name?.trim()?.ifBlank { null } ?: autoSaveName(currentSource)
        viewModelScope.launch {
            val meta = levelStore.save(bitmap, title, currentSource, currentConfig)
            currentSavedId = meta.id
            refreshLibrary()
        }
    }

    /**
     * Keeps an already-saved level's stored tuning in step with the sliders.
     *
     * Without this, re-opening a level from the library would quietly undo any adjustment
     * made after it was first saved.
     */
    private fun updateSavedTuning() {
        val id = currentSavedId ?: return
        val currentConfig = config
        viewModelScope.launch { levelStore.updateConfig(id, currentConfig) }
    }

    private fun autoSaveName(from: LevelSource): String {
        val stamp = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
        val what = when (from) {
            LevelSource.DRAWN -> "Drawing"
            LevelSource.PHOTO -> "Photo"
            LevelSource.SAMPLE -> "Sample"
        }
        return "$what · $stamp"
    }

    fun refreshLibrary() {
        viewModelScope.launch { savedLevels = levelStore.list() }
    }

    /** Saves (or updates) the current level. Safe to call when there is nothing to save. */
    fun saveCurrentLevel(name: String) {
        val bitmap = sketch ?: return
        val currentConfig = config
        val currentSource = source
        val id = currentSavedId
        viewModelScope.launch {
            val meta = if (id == null) {
                levelStore.save(bitmap, name, currentSource, currentConfig)
            } else {
                levelStore.save(bitmap, name, currentSource, currentConfig, id)
            }
            currentSavedId = meta.id
            refreshLibrary()
        }
    }

    fun openSavedLevel(meta: SavedLevelMeta, onReady: () -> Unit) {
        viewModelScope.launch {
            isProcessing = true
            errorMessage = null
            val bitmap = levelStore.load(meta.id)
            if (bitmap == null) {
                errorMessage = "That level's image is missing."
                isProcessing = false
                return@launch
            }
            sketch = bitmap
            source = meta.source
            config = meta.config
            currentSavedId = meta.id
            level = withContext(Dispatchers.Default) { ImageProcessor.process(bitmap, meta.config) }
            isProcessing = false
            onReady()
        }
    }

    fun renameSavedLevel(meta: SavedLevelMeta, name: String) {
        viewModelScope.launch {
            levelStore.rename(meta.id, name)
            refreshLibrary()
        }
    }

    fun deleteSavedLevel(meta: SavedLevelMeta) {
        viewModelScope.launch {
            levelStore.delete(meta.id)
            if (currentSavedId == meta.id) currentSavedId = null
            refreshLibrary()
        }
    }

    fun clearLibrary() {
        viewModelScope.launch {
            levelStore.deleteAll()
            currentSavedId = null
            refreshLibrary()
        }
    }

    suspend fun loadThumbnail(id: String): Bitmap? = levelStore.loadThumbnail(id)

    // ---- records --------------------------------------------------------------------

    /**
     * Records are keyed by the level's *content*, so a level keeps its best times whether
     * or not it was ever saved to the library.
     */
    private val levelKey: String?
        get() = level?.let { Integer.toHexString(it.hashCode()) }

    fun recordFor(forSetup: GameSetup): LevelRecord {
        val key = levelKey ?: return LevelRecord()
        return records[RecordKey(key, forSetup)] ?: LevelRecord()
    }

    /** Folds a finished run into the records and flags a new personal best. */
    fun onRunFinished(won: Boolean, seconds: Float, coins: Int) {
        val key = levelKey ?: return
        val played = setup
        lastRunWasBest = won && recordFor(played).isNewBestTime(seconds)
        viewModelScope.launch {
            recordStore.record(key, played, won, seconds, coins)
            records = recordStore.all()
        }
    }

    fun clearRecords() {
        viewModelScope.launch {
            recordStore.clear()
            records = emptyMap()
        }
    }

    private companion object {
        const val REPROCESS_DEBOUNCE_MS = 180L
    }
}
