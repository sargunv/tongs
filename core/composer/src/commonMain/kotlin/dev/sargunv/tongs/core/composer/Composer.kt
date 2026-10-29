package dev.sargunv.tongs.core.composer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.data.DraftKey
import dev.sargunv.tongs.core.data.DraftRepository
import dev.sargunv.tongs.core.data.DraftWriter
import dev.sargunv.tongs.core.markdown.ForgeMarkdown
import dev.sargunv.tongs.core.markdown.MarkdownContext
import dev.sargunv.tongs.core.ui.ActionIcon
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.isDesktop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A field projection of the draft's app-owned writer. IME selection and preview stay local. */
@Stable
class ComposerState internal constructor(val draftKey: DraftKey, private val writer: DraftWriter?) {
  val text = TextFieldState()
  var preview by mutableStateOf(false)
  var ready by mutableStateOf(false)
    internal set

  var submitting by mutableStateOf(false)
    internal set

  var warning by mutableStateOf(false)
    internal set

  var error by mutableStateOf<String?>(null)
    internal set

  val editable: Boolean
    get() = ready && !submitting && writer?.locked?.value != true

  val isBlank: Boolean
    get() = text.text.isBlank()

  val inputTransformation = InputTransformation {
    if (!write(asCharSequence().toString())) revertAllChanges()
  }

  /** Programmatic edits (toolbar and suggestions) use the same write-through path as typing. */
  fun replaceText(body: String) {
    if (write(body)) text.setTextAndPlaceCursorAtEnd(body)
  }

  internal fun write(body: String): Boolean = editable && writer?.write(body) != false
}

/** Hydrates before input; every edit is queued immediately and survives closing the view. */
@Composable
fun rememberComposerState(draftKey: DraftKey, drafts: DraftRepository): ComposerState {
  val writer = remember(draftKey, drafts) { drafts.writer(draftKey) }
  val state = remember(writer) { ComposerState(draftKey, writer) }
  LaunchedEffect(writer) {
    writer.ready.first { it }
    state.text.setTextAndPlaceCursorAtEnd(writer.body.value)
    state.ready = true
    launch {
      writer.body.collect {
        if (state.text.text.toString() != it) state.text.setTextAndPlaceCursorAtEnd(it)
      }
    }
    launch { writer.locked.collect { state.submitting = it } }
    launch { writer.warning.collect { state.warning = it } }
    launch { writer.error.collect { state.error = it } }
  }
  return state
}

/** An unpersisted field for previews and screenshot tests. */
@Composable
fun rememberPreviewComposerState(text: String, draftKey: DraftKey): ComposerState {
  val state = remember(draftKey) { ComposerState(draftKey, null) }
  remember(state) {
    state.text.setTextAndPlaceCursorAtEnd(text)
    state.ready = true
  }
  return state
}

/** A button along the composer's bottom edge. */
data class ComposerAction(
  val label: String,
  val onClick: () -> Unit,
  val icon: ImageVector? = null,
  /** The main action, like Comment: filled, rightmost, and on Ctrl/⌘+Enter. */
  val primary: Boolean = false,
  /** Whether it works with no text, like Close without a comment. */
  val allowsEmpty: Boolean = false,
  val enabled: Boolean = true,
)

/**
 * Writes Markdown: an editor with a formatting toolbar, a rendered preview, and [actions] along the
 * bottom. Fills what it's given; the caller decides whether that's a full-screen sheet or a pane
 * beside the content. Keeps clear of the system bars and the keyboard itself.
 *
 * On desktop: Ctrl/⌘+Enter runs the primary action, Ctrl/⌘+B, I, E, and K format, and Escape
 * closes.
 *
 * @param title what's being written, like "Reply to #412"; [subtitle] can name the repo.
 * @param context resolves `#123` and `@user` in the preview.
 * @param subject what's being replied to, shown above the editor where it would be out of sight
 *   otherwise, as the lines of a line comment in a full-screen composer. It gets at most a third of
 *   the height and scrolls.
 */
@Composable
fun Composer(
  state: ComposerState,
  title: String,
  actions: List<ComposerAction>,
  onClose: () -> Unit,
  context: MarkdownContext,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  placeholder: String = "Write a comment",
  autoFocus: Boolean = true,
  subject: (@Composable () -> Unit)? = null,
) {
  val focus = remember { FocusRequester() }
  val currentActions by rememberUpdatedState(actions)
  fun run(action: ComposerAction) {
    val body = state.text.text.toString().trim()
    if (!state.editable || !action.enabled || (!action.allowsEmpty && body.isEmpty())) return
    action.onClick()
  }
  Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
    BoxWithConstraints {
      val third = maxHeight / 3
      Column(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          val command = event.isCtrlPressed || event.isMetaPressed
          when {
            event.key == Key.Escape -> onClose().let { true }
            command && event.key == Key.Enter ->
              currentActions.firstOrNull { it.primary }?.let(::run).let { true }
            command && !state.preview && state.editable -> format(state, event.key)
            else -> false
          }
        },
      ) {
        Header(state, title, subtitle, onClose)
        if (state.warning)
          Text(
            "May have been sent. Check the thread before sending again.",
            Modifier.padding(16.dp),
          )
        state.error?.let {
          Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (subject != null) {
          Box(Modifier.fillMaxWidth().heightIn(max = third).verticalScroll(rememberScrollState())) {
            subject()
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
          if (state.preview) {
            Preview(state, context)
          } else {
            Editor(state, placeholder, focus)
          }
        }
        if (!state.preview) FormattingBar(state)
        ActionBar(state, actions, ::run)
      }
    }
  }
  LaunchedEffect(state.ready) {
    if (state.ready && autoFocus && !state.preview) runCatching { focus.requestFocus() }
  }
}

@Composable
private fun Header(state: ComposerState, title: String, subtitle: String?, onClose: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
  ) {
    ActionIcon(TongsIcons.Close, "Close; your draft is kept", onClose)
    Column(Modifier.weight(1f).padding(start = 4.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val saved = if (state.isBlank) null else "Draft"
      val line = listOfNotNull(subtitle, saved).joinToString(" · ")
      if (line.isNotEmpty()) {
        Text(
          line,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    // One toggle rather than Write and Preview tabs, so the title keeps its room on a phone.
    TextButton(onClick = { state.preview = !state.preview }) {
      Icon(
        if (state.preview) TongsIcons.Edit else ConversationIcons.Preview,
        null,
        Modifier.size(ButtonDefaults.IconSize),
      )
      Text(
        if (state.preview) "Edit" else "Preview",
        Modifier.padding(start = ButtonDefaults.IconSpacing),
      )
    }
  }
}

@Composable
private fun Editor(state: ComposerState, placeholder: String, focus: FocusRequester) {
  val colors = MaterialTheme.colorScheme
  val style = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
  BasicTextField(
    state = state.text,
    enabled = state.editable,
    inputTransformation = state.inputTransformation,
    textStyle = style,
    cursorBrush = SolidColor(colors.primary),
    modifier = Modifier.fillMaxSize().focusRequester(focus).testTag("composer-body"),
    decorator = { field ->
      Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (state.text.text.isEmpty()) {
          Text(placeholder, style = style.copy(color = colors.onSurfaceVariant.copy(alpha = 0.7f)))
        }
        field()
      }
    },
  )
}

@Composable
private fun Preview(state: ComposerState, context: MarkdownContext) {
  Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    if (state.isBlank) {
      Text(
        "Nothing to preview",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      ForgeMarkdown(state.text.text.toString(), context)
    }
  }
}

private class Format(val icon: ImageVector, val label: String, val apply: (TextEdit) -> TextEdit)

private val formats =
  listOf(
    Format(ConversationIcons.Bold, "Bold") { MarkdownFormatting.wrap(it, "**", "bold") },
    Format(ConversationIcons.Italic, "Italic") { MarkdownFormatting.wrap(it, "_", "italic") },
    Format(ConversationIcons.Strikethrough, "Strikethrough") {
      MarkdownFormatting.wrap(it, "~~", "text")
    },
    Format(ConversationIcons.Heading, "Heading") { MarkdownFormatting.prefixLines(it, "### ") },
    Format(ConversationIcons.Code, "Code") { MarkdownFormatting.code(it) },
    Format(ConversationIcons.Link, "Link") { MarkdownFormatting.link(it) },
    Format(ConversationIcons.Quote, "Quote") { MarkdownFormatting.prefixLines(it, "> ") },
    Format(ConversationIcons.BulletedList, "Bulleted list") {
      MarkdownFormatting.prefixLines(it, "- ")
    },
    Format(ConversationIcons.NumberedList, "Numbered list") {
      MarkdownFormatting.prefixLines(it, "1. ")
    },
    Format(ConversationIcons.TaskList, "Task list") {
      MarkdownFormatting.prefixLines(it, "- [ ] ")
    },
    Format(ConversationIcons.Mention, "Mention") { MarkdownFormatting.wrap(it, "", "@") },
  )

private fun apply(state: ComposerState, format: (TextEdit) -> TextEdit) {
  if (!state.editable) return
  val current = TextEdit(state.text.text.toString(), state.text.selection)
  val result = format(current)
  if (!state.write(result.text)) return
  state.text.edit {
    replace(0, length, result.text)
    selection = result.selection
  }
}

/** Keyboard shortcuts for the toolbar. Returns whether [key] was one. */
private fun format(state: ComposerState, key: Key): Boolean {
  val format =
    when (key) {
      Key.B -> formats[0]
      Key.I -> formats[1]
      Key.E -> formats[4]
      Key.K -> formats[5]
      else -> return false
    }
  apply(state, format.apply)
  return true
}

@Composable
private fun FormattingBar(state: ComposerState) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
    ) {
      for (format in formats) {
        val label =
          if (isDesktop && format.label in shortcuts) "${format.label} (${shortcuts[format.label]})"
          else format.label
        ActionIcon(format.icon, label, { apply(state, format.apply) })
      }
    }
  }
}

private val shortcuts =
  mapOf("Bold" to "Ctrl+B", "Italic" to "Ctrl+I", "Code" to "Ctrl+E", "Link" to "Ctrl+K")

@Composable
private fun ActionBar(
  state: ComposerState,
  actions: List<ComposerAction>,
  run: (ComposerAction) -> Unit,
) {
  val blank = state.isBlank
  FlowRow(
    itemVerticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    for (action in actions.sortedBy { it.primary }) {
      val enabled = state.editable && action.enabled && (action.allowsEmpty || !blank)
      val content: @Composable () -> Unit = {
        if (action.icon != null) {
          Icon(action.icon, null, Modifier.size(ButtonDefaults.IconSize))
          Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(action.label, maxLines = 1)
      }
      if (action.primary) {
        Button(onClick = { run(action) }, enabled = enabled) { content() }
      } else {
        OutlinedButton(onClick = { run(action) }, enabled = enabled) { content() }
      }
    }
  }
}
