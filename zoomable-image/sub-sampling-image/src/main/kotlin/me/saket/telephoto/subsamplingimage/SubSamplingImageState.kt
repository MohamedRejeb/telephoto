@file:Suppress("NAME_SHADOWING")
@file:OptIn(ExperimentalCoroutinesApi::class)

package me.saket.telephoto.subsamplingimage

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import me.saket.telephoto.subsamplingimage.internal.BitmapLoader
import me.saket.telephoto.subsamplingimage.internal.BitmapRegionTileGrid
import me.saket.telephoto.subsamplingimage.internal.BitmapSampleSize
import me.saket.telephoto.subsamplingimage.internal.CanvasRegionTile
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import me.saket.telephoto.subsamplingimage.internal.LocalImageRegionDecoderFactory
import me.saket.telephoto.subsamplingimage.internal.calculateFor
import me.saket.telephoto.subsamplingimage.internal.fastMapNotNull
import me.saket.telephoto.subsamplingimage.internal.generate
import me.saket.telephoto.subsamplingimage.internal.maxScale
import me.saket.telephoto.subsamplingimage.internal.minDimension
import me.saket.telephoto.subsamplingimage.internal.overlaps
import me.saket.telephoto.subsamplingimage.internal.scaledAndOffsetBy
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableContentTransformation
import me.saket.telephoto.zoomable.ZoomableState
import java.io.IOException

// todo: doc.
// todo: should this accept a ZoomableContentTransformationProvider?
//  - the provider can have a setContentLocation function and a layoutSize field.
@Composable
fun rememberSubSamplingImageState(
  imageSource: SubSamplingImageSource,
  zoomableState: ZoomableState,
  bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
  errorReporter: SubSamplingImageErrorReporter = SubSamplingImageErrorReporter.NoOpInRelease
): SubSamplingImageState {
  val state = rememberSubSamplingImageState(
    imageSource = imageSource,
    transformation = zoomableState.contentTransformation,
    bitmapConfig = bitmapConfig,
    errorReporter = errorReporter,
  )

  // SubSamplingImage will apply the transformations on its own.
  zoomableState.autoApplyTransformations = false

  LaunchedEffect(state.imageSize) {
    zoomableState.setContentLocation(
      ZoomableContentLocation.unscaledAndTopStartAligned(state.imageSize?.toSize())
    )
  }

  return state
}

// todo: doc.
@Composable
fun rememberSubSamplingImageState(
  imageSource: SubSamplingImageSource,
  transformation: ZoomableContentTransformation,
  bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
  errorReporter: SubSamplingImageErrorReporter = SubSamplingImageErrorReporter.NoOpInRelease
): SubSamplingImageState {
  val errorReporter by rememberUpdatedState(errorReporter)
  val transformation by rememberUpdatedState(transformation)
  val decoder: ImageRegionDecoder? by createRegionDecoder(imageSource, bitmapConfig, errorReporter)

  val state = remember {
    SubSamplingImageState()
  }

  // Reset everything when a new image is set.
  LaunchedEffect(state, decoder) {
    state.imageSize = decoder?.imageSize
    state.tiles = emptyList()
  }

  decoder?.let { decoder ->
    val transformations = remember { snapshotFlow { transformation } }

    val scope = rememberCoroutineScope()
    LaunchedEffect(state, transformations, decoder) {
      val bitmapLoader = BitmapLoader(decoder, scope)
      val canvasSizeChanges = snapshotFlow { state.canvasSize }
        .filterNotNull()
        .filter { it.minDimension > 0f }

      canvasSizeChanges.flatMapLatest { canvasSize ->
        val tileGrid = BitmapRegionTileGrid.generate(
          canvasSize = canvasSize,
          unscaledImageSize = decoder.imageSize,
          minTileSize = canvasSize / 2,
        )

        combine(
          transformations,
          bitmapLoader.cachedBitmaps()
        ) { transformation, bitmaps ->
          val sampleSize = BitmapSampleSize.calculateFor(transformation.scale.maxScale)
          val foregroundRegions = tileGrid.foreground[sampleSize].orEmpty()

          val foregroundTiles = foregroundRegions.fastMapNotNull { tile ->
            val drawBounds = tile.bounds.scaledAndOffsetBy(transformation.scale, transformation.offset)
            if (drawBounds.overlaps(canvasSize)) {
              CanvasRegionTile(
                bounds = drawBounds,
                bitmap = bitmaps[tile],
                bitmapRegion = tile,
                isBaseTile = false,
              )
            } else {
              null
            }
          }

          // Fill any missing gaps in tiles by drawing the low-res base tile underneath as
          // a fallback. The base tile will hide again when all bitmaps have been loaded.
          val canDrawBaseTile = foregroundTiles.isEmpty() || foregroundTiles.fastAny { it.bitmap == null }

          // The base tile needs to be always present even if it isn't going to
          // be drawn. Otherwise BitmapLoader will remove its bitmap from cache.
          val baseTile = if (canDrawBaseTile) {
            tileGrid.base.let { tile ->
              CanvasRegionTile(
                bounds = tile.bounds.scaledAndOffsetBy(transformation.scale, transformation.offset),
                bitmap = bitmaps[tile],
                bitmapRegion = tile,
                isBaseTile = true,
              )
            }
          } else null

          // Side effect, ew :(.
          bitmapLoader.loadOrUnloadForTiles(listOf(tileGrid.base) + foregroundTiles.map { it.bitmapRegion })

          return@combine (listOfNotNull(baseTile) + foregroundTiles)
        }
      }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .collect { tiles ->
          state.tiles = tiles
        }
    }
  }

  return state
}

@Composable
private fun createRegionDecoder(
  imageSource: SubSamplingImageSource,
  bitmapConfig: Bitmap.Config,
  errorReporter: SubSamplingImageErrorReporter
): State<ImageRegionDecoder?> {
  val context = LocalContext.current
  val errorReporter by rememberUpdatedState(errorReporter)

  val decoder = remember(imageSource) { mutableStateOf<ImageRegionDecoder?>(null) }
  val isInPreviewMode = LocalInspectionMode.current

  if (!isInPreviewMode) {
    val factory = LocalImageRegionDecoderFactory.current
    LaunchedEffect(imageSource) {
      try {
        decoder.value = factory.create(context, imageSource, bitmapConfig)
      } catch (e: IOException) {
        errorReporter.onImageLoadingFailed(e, imageSource)
      }
    }
  }

  return decoder
}

// todo: doc.
@Stable
class SubSamplingImageState internal constructor() {
  var imageSize: IntSize? by mutableStateOf(null)
    internal set

  // todo: doc
  val isImageLoaded: Boolean by derivedStateOf {
    canvasSize != null && tiles.isNotEmpty()
      && (tiles.fastAny { it.isBaseTile && it.bitmap != null } || tiles.fastAll { it.bitmap != null })
  }

  // todo: doc
  val isImageLoadedInFullQuality: Boolean by derivedStateOf {
    isImageLoaded && tiles.fastAll { it.bitmap != null }
  }

  internal var tiles by mutableStateOf(emptyList<CanvasRegionTile>())
  internal var canvasSize: IntSize? by mutableStateOf(null)
  internal var showTileBounds = false  // Only used by tests.
}