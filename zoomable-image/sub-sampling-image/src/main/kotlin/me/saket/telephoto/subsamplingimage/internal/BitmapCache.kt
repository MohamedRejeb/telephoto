package me.saket.telephoto.subsamplingimage.internal

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.saket.telephoto.subsamplingimage.internal.BitmapCache.LoadingState.InFlight
import me.saket.telephoto.subsamplingimage.internal.BitmapCache.LoadingState.Loaded
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class BitmapCache(
  scope: CoroutineScope,
  private val decoder: ImageRegionDecoder,
) {
  private val cachedBitmaps = MutableStateFlow(emptyMap<BitmapRegionTile, LoadingState>())
  private val loadRequests = Channel<List<BitmapRegionTile>>(capacity = 10)

  private sealed interface LoadingState {
    data class Loaded(val bitmap: ImageBitmap) : LoadingState
    data class InFlight(val job: Job) : LoadingState
  }

  init {
    scope.launch {
      loadRequests.consumeAsFlow()
        // Throttle requests in case the image is animating its zoom.
        .throttleLatest(100.milliseconds)
        .collect { regions ->
          for (region in regions) {
            val job = launch {
              val bitmap = decoder.decodeRegion(region)
              cachedBitmaps.update { it + (region to Loaded(bitmap)) }
            }
            cachedBitmaps.update { it + (region to InFlight(job)) }
          }
        }
    }
  }

  fun cachedBitmaps(): Flow<Map<BitmapRegionTile, ImageBitmap>> {
    return cachedBitmaps.map { map ->
      buildMap(capacity = map.size) {
        map.forEach { (region, state) ->
          if (state is Loaded) {
            put(region, state.bitmap)
          }
        }
      }
    }.distinctUntilChanged()
  }

  fun loadOrUnloadForTiles(tiles: List<BitmapRegionTile>) {
    loadRequests.trySend(
      tiles.fastFilter { it !in cachedBitmaps.value }
    )

    val regionsToUnload = cachedBitmaps.value.keys
      .filter { it !in tiles }

    regionsToUnload.fastForEach { region ->
      val inFlight = cachedBitmaps.value[region] as? InFlight
      inFlight?.job?.cancel()
    }
    cachedBitmaps.update { it - regionsToUnload.toSet() }
  }
}

// Copied from https://github.com/Kotlin/kotlinx.coroutines/issues/1446#issuecomment-1198103541.
private fun <T> Flow<T>.throttleLatest(delay: Duration): Flow<T> {
  return conflate().transform {
    emit(it)
    delay(delay)
  }
}