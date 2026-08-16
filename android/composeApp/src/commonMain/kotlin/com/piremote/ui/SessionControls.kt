package com.piremote.ui


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import piremote.composeapp.generated.resources.*
import com.piremote.net.ContextUsageDto
import com.piremote.net.ModelDto
import com.piremote.net.SessionDetailDto
import com.piremote.net.SessionStatsDto
import com.piremote.net.fixed
import com.piremote.net.formatCost
import com.piremote.net.formatCount
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Which sheet is open, if any. Hoisted by the caller so the composer's
 *  "More" menu can open them. */
enum class SessionSheet { Model, Thinking, Info }

/**
 * The session's bottom sheets: the model and thinking-level pickers, and the
 * read-only info panel.
 *
 * Both pickers are scoped to this session: pi records the change as an entry in
 * this session's file, so two sessions can run different models at once.
 */
@Composable
fun SessionPickerSheets(
    detail: SessionDetailDto?,
    models: List<ModelDto>,
    sheet: SessionSheet?,
    onDismiss: () -> Unit,
    onPickModel: (ModelDto) -> Unit,
    onPickThinking: (String) -> Unit,
    loadStats: suspend () -> SessionStatsDto,
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
            title = stringResource(Res.string.model),
            options = models.map { PickerOption(it.key, it.name, it.provider) },
            selectedKey = currentModelDto?.key,
            onDismiss = onDismiss,
            onPick = { key ->
                models.firstOrNull { it.key == key }?.let(onPickModel)
                onDismiss()
            },
        )

        SessionSheet.Thinking -> PickerSheet(
            title = stringResource(Res.string.thinking_level),
            options = levels.map { PickerOption(it, it, null) },
            selectedKey = detail?.thinkingLevel,
            onDismiss = onDismiss,
            onPick = {
                onPickThinking(it)
                onDismiss()
            },
        )

        SessionSheet.Info -> SessionInfoSheet(onDismiss = onDismiss, loadStats = loadStats)

        null -> Unit
    }
}

/**
 * What this session has spent, as pi's own `/session` panel reports it.
 *
 * Fetched when the sheet opens rather than kept in the chat state: no push
 * carries these numbers, and they are only interesting while someone is looking
 * at them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionInfoSheet(onDismiss: () -> Unit, loadStats: suspend () -> SessionStatsDto) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val failed = stringResource(Res.string.err_load_stats)
    val load by produceState<Result<SessionStatsDto>?>(null, loadStats) {
        value = try {
            Result.success(loadStats())
        } catch (e: CancellationException) {
            throw e // the sheet closed mid-flight; not a failure to report
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(Res.string.session_info),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            val result = load
            when {
                result == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                result.isFailure -> Text(
                    result.exceptionOrNull()?.message ?: failed,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )

                else -> SessionInfoBody(result.getOrThrow())
            }
        }
    }
}

@Composable
private fun SessionInfoBody(stats: SessionStatsDto) {
    stats.name?.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(Res.string.session_info_name), it) }
    InfoRow(stringResource(Res.string.session_info_file), stats.file, mono = true)
    InfoRow(stringResource(Res.string.session_info_id), stats.id, mono = true)

    InfoHeader(stringResource(Res.string.session_info_messages))
    InfoRow(stringResource(Res.string.session_info_user), formatCount(stats.messages.user.toLong()))
    InfoRow(stringResource(Res.string.session_info_assistant), formatCount(stats.messages.assistant.toLong()))
    InfoRow(stringResource(Res.string.session_info_tool_calls), formatCount(stats.messages.toolCalls.toLong()))
    InfoRow(stringResource(Res.string.session_info_tool_results), formatCount(stats.messages.toolResults.toLong()))
    InfoRow(stringResource(Res.string.session_info_total), formatCount(stats.messages.total.toLong()), strong = true)

    InfoHeader(stringResource(Res.string.session_info_tokens))
    InfoRow(stringResource(Res.string.session_info_input), formatCount(stats.tokens.input))
    InfoRow(stringResource(Res.string.session_info_output), formatCount(stats.tokens.output))
    InfoRow(stringResource(Res.string.session_info_cache_read), formatCount(stats.tokens.cacheRead))
    // Only Anthropic-style APIs report cache writes; a zero row would be noise.
    if (stats.tokens.cacheWrite > 0) {
        InfoRow(stringResource(Res.string.session_info_cache_write), formatCount(stats.tokens.cacheWrite))
    }
    InfoRow(stringResource(Res.string.session_info_total), formatCount(stats.tokens.total), strong = true)

    InfoHeader(stringResource(Res.string.session_info_spend))
    InfoRow(stringResource(Res.string.session_info_cost), formatCost(stats.cost), strong = true)
    InfoRow(stringResource(Res.string.session_info_context), contextSummary(stats.context))
}

/** "12.5k / 1.0M · 12%", or just the used half when the window is unknown. */
@Composable
private fun contextSummary(context: ContextUsageDto): String {
    val used = context.tokens?.let { compactTokens(it.toLong()) } ?: "?"
    val window = context.contextWindow?.let { compactTokens(it.toLong()) } ?: "?"
    val percent = context.percent?.let { " · ${it.roundToInt()}%" } ?: ""
    return "$used / $window$percent"
}

/** `301360` → `301.4k`, `1048576` → `1.0M`. Small counts stay exact. */
private fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> "${fixed(n / 1_000_000.0, 1)}M"
    n >= 1_000 -> "${fixed(n / 1_000.0, 1)}k"
    else -> n.toString()
}

@Composable
private fun InfoHeader(text: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One `label — value` line. The value takes the remaining width and wraps, which
 * is what a session file path needs on a phone.
 */
@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, strong: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
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
                            contentDescription = stringResource(Res.string.selected),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
