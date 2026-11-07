package org.kineograph.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A drop-down whose closed state shows a picture of the current choice. [menu] holds the options, built with
 * [VisualOption] and [OptionGroupHeading].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun VisualDropdown(
    label: String,
    valueText: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    tag: String,
    leading: @Composable () -> Unit,
    menu: @Composable ColumnScope.() -> Unit,
) {
    val state = stringResource(if (expanded) R.string.dropdown_expanded else R.string.dropdown_collapsed)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = valueText,
            onValueChange = {},
            readOnly = true,
            // On one line it would scroll sideways instead of ending neatly, and large text would cut the value off in the middle of a letter.
            singleLine = false,
            maxLines = MAX_VALUE_LINES,
            label = { Text(label) },
            leadingIcon = { Box(Modifier.padding(start = 12.dp, end = 4.dp)) { leading() } },
            trailingIcon = {
                Icon(KgIcons.ChevronDown, contentDescription = null, Modifier.rotate(if (expanded) CHEVRON_OPEN_DEGREES else 0f))
            },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics { stateDescription = state }
                    .testTag(tag),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }, content = menu)
    }
}

/** One choice in a [VisualDropdown]: a picture of it, its name, and a few words on what it is for. */
@Composable
public fun VisualOption(
    title: String,
    hint: String,
    selected: Boolean,
    tag: String,
    preview: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { preview() },
        trailingIcon = { if (selected) Icon(KgIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
        // The check mark only speaks to the eye. TalkBack has to be told which of the options it is.
        modifier =
            Modifier
                .semantics {
                    this.selected = selected
                    role = Role.RadioButton
                }.testTag(tag),
    )
}

/** A label above a group of options. */
@Composable
public fun OptionGroupHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

private const val CHEVRON_OPEN_DEGREES = 180f
private const val MAX_VALUE_LINES = 3
