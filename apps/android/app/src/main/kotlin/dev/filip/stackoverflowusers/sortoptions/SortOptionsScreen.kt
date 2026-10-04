package dev.filip.stackoverflowusers.sortoptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.filip.stackoverflowusers.core.SortDirection
import dev.filip.stackoverflowusers.core.SortField
import dev.filip.stackoverflowusers.ui.Tags

private val SortField.label
    get() = when (this) {
        SortField.REPUTATION -> "Reputation"
        SortField.NAME -> "Name"
        SortField.CREATION_DATE -> "Date created"
        SortField.MODIFIED_DATE -> "Date updated"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortOptionsScreen(state: SortOptionsState, onIntent: (SortOptionsIntent) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Sort options") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.selectableGroup()) {
                SortField.entries.forEach { field ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.draft.field == field,
                                onClick = { onIntent(SortOptionsIntent.SelectField(field)) },
                                role = Role.RadioButton,
                            )
                            .testTag(Tags.sortField(field.name))
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.draft.field == field, onClick = null)
                        Text(field.label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            val directions = SortDirection.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                directions.forEachIndexed { index, direction ->
                    SegmentedButton(
                        selected = state.draft.direction == direction,
                        onClick = { onIntent(SortOptionsIntent.SelectDirection(direction)) },
                        shape = SegmentedButtonDefaults.itemShape(index, directions.size),
                        modifier = Modifier.testTag(Tags.sortDirection(direction.name)),
                    ) {
                        Text(if (direction == SortDirection.ASCENDING) "Ascending" else "Descending")
                    }
                }
            }
            Button(
                onClick = { onIntent(SortOptionsIntent.Apply) },
                modifier = Modifier.fillMaxWidth().testTag(Tags.SORT_APPLY),
            ) { Text("Apply") }
            OutlinedButton(
                onClick = { onIntent(SortOptionsIntent.Cancel) },
                modifier = Modifier.fillMaxWidth().testTag(Tags.SORT_CANCEL),
            ) { Text("Cancel") }
        }
    }
}
