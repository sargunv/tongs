package dev.sargunv.tongs.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.data.InboxQuery
import dev.sargunv.tongs.core.data.InboxRow
import dev.sargunv.tongs.core.data.InboxViewEntity
import dev.sargunv.tongs.core.ui.TongsIcons

/**
 * The views, as chips: the whole inbox, then each saved view. The last chip saves the current
 * filter as a view.
 */
@Composable
internal fun ViewChips(state: InboxUiState, actions: InboxActions, onNewView: () -> Unit) {
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = PaddingValues(horizontal = 16.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    item(key = "inbox") {
      FilterChip(
        selected = state.viewId == null,
        onClick = { actions.selectView(null) },
        label = { Text("Inbox") },
        leadingIcon = { Icon(TongsIcons.Inbox, null, Modifier.size(FilterChipDefaults.IconSize)) },
      )
    }
    items(state.views, key = { it.id }) { view ->
      FilterChip(
        selected = state.viewId == view.id,
        onClick = { actions.selectView(view.id) },
        label = { Text(view.name) },
      )
    }
    item(key = "new") {
      FilterChip(
        selected = false,
        onClick = onNewView,
        label = {
          Text(if (state.queryEdited && !state.query.isEmpty) "Save view" else "New view")
        },
        leadingIcon = { Icon(TongsIcons.Add, null, Modifier.size(FilterChipDefaults.IconSize)) },
      )
    }
  }
}

/** Common filters, as chips that add or remove their term. */
private val SUGGESTIONS =
  listOf(
    "Unread" to "is:unread",
    "PRs" to "is:pr",
    "Issues" to "is:issue",
    "Review requested" to "reason:review-requested",
    "Mentioned" to "reason:mention",
    "Assigned" to "reason:assign",
    "Open" to "is:open",
    "Merged" to "is:merged",
    "Closed" to "is:closed",
    "Snoozed" to "is:snoozed",
    READ_ELSEWHERE to "is:${InboxQuery.READ_ELSEWHERE}",
  )

/**
 * The filter, in GitHub's vocabulary, with chips for common terms and the busiest repos. Escape
 * hands the keyboard back to the list.
 */
@Composable
internal fun FilterBar(
  query: InboxQuery,
  rows: List<InboxRow>,
  focus: FocusRequester,
  /** Takes the keyboard each time this changes, as `/` asks. */
  focusRequests: Int,
  onChange: (String) -> Unit,
  onDone: () -> Unit,
) {
  androidx.compose.runtime.LaunchedEffect(focusRequests) {
    if (focusRequests > 0) runCatching { focus.requestFocus() }
  }
  Column(Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = query.text,
      onValueChange = onChange,
      singleLine = true,
      placeholder = { Text("reason:mention is:pr repo:owner/name") },
      leadingIcon = { Icon(TongsIcons.FilterList, null) },
      trailingIcon = {
        if (query.text.isNotEmpty()) {
          IconButton(onClick = { onChange("") }) { Icon(TongsIcons.Close, "Clear filter") }
        }
      },
      isError = query.unknown.isNotEmpty(),
      supportingText =
        query.unknown
          .takeIf { it.isNotEmpty() }
          ?.let { { Text("Not a filter Tongs knows: ${it.joinToString()}") } },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { onDone() }),
      shape = MaterialTheme.shapes.extraLarge,
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp)
          .focusRequester(focus)
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
              onDone()
              true
            } else false
          },
    )
    // The repos with the most threads in view, as quick filters.
    val repos =
      remember(rows) {
        rows
          .groupingBy { "${it.repoOwner}/${it.repoName}" }
          .eachCount()
          .entries
          .sortedByDescending { it.value }
          .take(3)
          .map { it.key.substringAfter('/') to "repo:${it.key}" }
      }
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      items(SUGGESTIONS + repos, key = { it.second }) { (label, term) ->
        FilterChip(
          selected = query.has(term),
          onClick = { onChange(query.toggle(term)) },
          label = { Text(label) },
        )
      }
    }
  }
}

/** Saves the filter as a view, or edits or deletes [editing]. */
@Composable
internal fun ViewDialog(
  editing: InboxViewEntity?,
  query: String,
  groupByRepo: Boolean,
  onSave: (name: String, query: String, groupByRepo: Boolean) -> Unit,
  onDelete: (() -> Unit)?,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(editing?.name ?: "") }
  var text by remember { mutableStateOf(query) }
  var group by remember { mutableStateOf(groupByRepo) }
  val parsed = remember(text) { InboxQuery.parse(text) }
  val nameFocus = remember { FocusRequester() }
  androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (editing != null) "Edit view" else "New view") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
        )
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text("Filter") },
          placeholder = { Text("reason:review-requested is:open") },
          singleLine = true,
          isError = parsed.unknown.isNotEmpty(),
          supportingText = {
            Text(
              if (parsed.unknown.isNotEmpty())
                "Not a filter Tongs knows: ${parsed.unknown.joinToString()}"
              else "reason:, is:pr, is:issue, is:unread, is:open, is:merged, repo:, org:",
            )
          },
          modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = group, onCheckedChange = { group = it })
          Text("Group by repo")
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(name, text, group) }, enabled = name.isNotBlank()) {
        Text("Save")
      }
    },
    dismissButton = {
      Row {
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
  )
}

/** The inbox's keyboard shortcuts, for `?`. */
@Composable
internal fun ShortcutsDialog(onDismiss: () -> Unit) {
  val shortcuts =
    listOf(
      "j / k" to "Next / previous",
      "Enter or o" to "Open",
      "e" to "Done",
      "s" to "Snooze",
      "Shift+U" to "Mark read or unread",
      "Shift+I" to "Mark read",
      "z" to "Undo",
      "/" to "Filter",
      "Esc" to "Back to the list; clear the filter",
      "r" to "Refresh",
    )
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Keyboard shortcuts") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((keys, what) in shortcuts) {
          Row {
            Text(
              keys,
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(end = 16.dp).fillMaxWidth(0.35f),
            )
            Text(what, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}
