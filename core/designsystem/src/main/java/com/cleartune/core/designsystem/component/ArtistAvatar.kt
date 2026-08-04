package com.cleartune.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.cleartune.core.designsystem.R
import com.cleartune.core.model.ArtistId

@DrawableRes
fun artistAvatarResource(artistId: ArtistId): Int = when (artistAvatarIndex(artistId)) {
    0 -> R.drawable.avatar_artist_01
    1 -> R.drawable.avatar_artist_02
    2 -> R.drawable.avatar_artist_03
    3 -> R.drawable.avatar_artist_04
    4 -> R.drawable.avatar_artist_05
    5 -> R.drawable.avatar_artist_06
    6 -> R.drawable.avatar_artist_07
    else -> R.drawable.avatar_artist_08
}

@Composable
fun ArtistAvatar(
    artistId: ArtistId,
    artistName: String,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(artistAvatarResource(artistId)),
        contentDescription = artistName,
        modifier = modifier.clip(CircleShape),
    )
}
