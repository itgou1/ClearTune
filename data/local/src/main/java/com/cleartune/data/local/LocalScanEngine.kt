package com.cleartune.data.local

class LocalScanEngine {
    fun diff(
        previous: List<LocalAudioSnapshot>,
        incoming: List<LocalAudioSnapshot>,
    ): LocalScanDiff {
        val warnings = mutableListOf<String>()
        val acceptedByKey = linkedMapOf<String, LocalAudioSnapshot>()
        incoming.forEach { snapshot ->
            if (acceptedByKey.putIfAbsent(snapshot.sourceKey, snapshot) != null) {
                warnings.add("Duplicate source key: ${snapshot.sourceKey}")
            }
        }
        val previousByKey = previous.associateBy(LocalAudioSnapshot::sourceKey)
        val added = mutableListOf<LocalAudioSnapshot>()
        val updated = mutableListOf<LocalAudioSnapshot>()
        var unchanged = 0
        acceptedByKey.forEach { (key, snapshot) ->
            when (val old = previousByKey[key]) {
                null -> added.add(snapshot)
                snapshot -> unchanged++
                else -> if (old != snapshot) updated.add(snapshot)
            }
        }
        return LocalScanDiff(
            accepted = acceptedByKey.values.toList(),
            added = added,
            updated = updated,
            removedSourceKeys = previousByKey.keys - acceptedByKey.keys,
            unchangedCount = unchanged,
            warnings = warnings,
        )
    }
}
