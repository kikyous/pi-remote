package com.piremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.piremote.net.ModelDto
import com.piremote.net.SessionDetailDto

/** Which picker sheet is open, if any. Hoisted by the caller so the composer's
 *  "更多" menu can open them. */
enum class SessionSheet { Model, Thinking }

/**
 * Bottom-sheet pickers for the per-session model and thinking level.
 *
 * Both are scoped to this session: pi records the change as an entry in this
 * session's file, so two sessions can run different models at once.
 */
@Composable
fun SessionPickerSheets(
    detail: SessionDetailDto?,
    models: List<ModelDto>,
    sheet: SessionSheet?,
    onDismiss: () -> Unit,
    onPickModel: (ModelDto) -> Unit,
    onPickThinking: (String) -> Unit,
) {
    val currentModel = detail?.model
    val currentModelDto = models.firstOrNull {
        it.provider == currentModel?.provider && it.id == currentModel.modelId
    }
    // Only meaningful when the model reasons at all.
    val levels = detail?.availableThinkingLevels
        ?: currentModelDto?.thinkingLevels
        ?: emptyList()

    when (sheet) {
        SessionSheet.Model -> PickerSheet(
            title = "模型",
            options = models.map { PickerOption(it.key, it.name, it.provider) },
            selectedKey = currentModelDto?.key,
            onDismiss = onDismiss,
            onPick = { key ->
                models.firstOrNull { it.key == key }?.let(onPickModel)
                onDismiss()
            },
        )

        SessionSheet.Thinking -> PickerSheet(
            title = "思考等级",
            options = levels.map { PickerOption(it, it, null) },
            selectedKey = detail?.thinkingLevel,
            onDismiss = onDismiss,
            onPick = {
                onPickThinking(it)
                onDismiss()
            },
        )

        null -> Unit
    }
}

private data class PickerOption(val key: String, val label: String, val subtitle: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    title: String,
    options: List<PickerOption>,
    selectedKey: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(options, key = { it.key }) { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(option.key) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (option.key == selectedKey) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        option.subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (option.key == selectedKey) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已选",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
