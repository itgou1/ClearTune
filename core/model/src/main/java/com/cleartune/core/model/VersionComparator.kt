package com.cleartune.core.model

object VersionComparator {
    fun isNewer(candidate: String, current: String): Boolean {
        val left = candidate.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val right = current.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }
}
