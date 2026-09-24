package com.cleartune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cleartune.app.library.PlaylistSort
import com.cleartune.app.library.PlaylistSortField

@Composable
internal fun PlaylistSortMenu(
    expanded: Boolean,
    sort: PlaylistSort,
    onChange: (PlaylistSort) -> Unit,
    onDismiss: () -> Unit,
    favorites: Boolean = false,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(176.dp)) {
        PlaylistSortField.entries.forEach { field ->
            val active = sort.field == field
            val descending = if (favorites && field == PlaylistSortField.DEFAULT) !sort.descending else sort.descending
            val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                text = { Text(if (favorites && field == PlaylistSortField.ADDED) "最近收藏" else field.label, color = tint) },
                leadingIcon = {
                    if (active) Icon(Icons.Rounded.Check, null, tint = tint, modifier = Modifier.size(18.dp))
                    else Spacer(Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (active) Icon(if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                        null, tint = tint, modifier = Modifier.size(18.dp))
                    else Spacer(Modifier.size(18.dp))
                },
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.padding(horizontal = 4.dp)
                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        RoundedCornerShape(8.dp))
                    .semantics { selected = active; if (active) stateDescription = if (descending) "降序" else "升序" },
                onClick = {
                    onChange(if (active) sort.copy(descending = !sort.descending)
                        else PlaylistSort(field, field == PlaylistSortField.ADDED))
                    onDismiss()
                },
            )
        }
    }
}
