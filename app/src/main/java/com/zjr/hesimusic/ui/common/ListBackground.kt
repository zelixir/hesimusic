package com.zjr.hesimusic.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zjr.hesimusic.R
import coil.compose.AsyncImage
import coil.request.ImageRequest

private const val BACKGROUND_IMAGE_ALPHA = 0.18f

@Composable
fun ListBackground(
    imageUri: String?,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (!imageUri.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.settings_list_background_title),
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(BACKGROUND_IMAGE_ALPHA),
                contentScale = ContentScale.Crop
            )
        }
        content()
    }
}
