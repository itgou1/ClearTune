package com.cleartune.core.model

@JvmInline value class TrackId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class SourceId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class LocationId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class AlbumId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class ArtistId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class PlaylistId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class PlaylistItemId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class DownloadId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class QueueItemId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class CredentialAlias(val value: String) { init { require(value.isNotBlank()) } }
