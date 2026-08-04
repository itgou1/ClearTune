package com.cleartune.core.designsystem.component

import com.cleartune.core.model.ArtistId

fun artistAvatarIndex(artistId: ArtistId): Int = Math.floorMod(artistId.value.hashCode(), 8)
