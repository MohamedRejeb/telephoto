package me.saket.telephoto.zoomable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.saket.telephoto.subsamplingimage.SubSamplingImageState

/** State for [ZoomableImageSource]. */
@Composable
fun rememberZoomableImageState(
  zoomableState: ZoomableState = rememberZoomableState()
): ZoomableImageState {
  return remember(zoomableState) {
    ZoomableImageState(zoomableState)
  }
}

class ZoomableImageState internal constructor(
  val zoomableState: ZoomableState
) {
  internal var subSamplingState: SubSamplingImageState? by mutableStateOf(null)
}