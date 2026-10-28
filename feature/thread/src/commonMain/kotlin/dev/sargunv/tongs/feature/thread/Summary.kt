package dev.sargunv.tongs.feature.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.sargunv.tongs.core.ui.Avatar
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.core.ui.stateColors
import dev.sargunv.tongs.forge.api.CheckState
import dev.sargunv.tongs.forge.api.ChecksSummary
import dev.sargunv.tongs.forge.api.Mergeable
import dev.sargunv.tongs.forge.api.PullRequestSummary
import dev.sargunv.tongs.forge.api.ReviewDecision
import dev.sargunv.tongs.forge.api.ReviewState
import dev.sargunv.tongs.forge.api.ThreadDetail
import dev.sargunv.tongs.forge.api.ThreadState

/**
 * The thread at a glance, above the description: for a PR, its changes and how far you've read
 * them, its checks, its reviews, and whether it merges; for both, labels, assignees, and milestone.
 * Each row is one line, so the card stays short on a folded phone.
 */
@Composable
internal fun SummaryCard(
  detail: ThreadDetail,
  onOpenChanges: (() -> Unit)?,
  openInBrowser: (String) -> Unit,
) {
  val pr = detail.pullRequest
  val hasMeta =
    detail.labels.isNotEmpty() || detail.assignees.isNotEmpty() || detail.milestone != null
  if (pr == null && !hasMeta) return
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(vertical = 6.dp)) {
      val rows =
        buildList<@Composable () -> Unit> {
          if (pr != null) {
            add { ChangesRow(pr, onOpenChanges) }
            pr.checks?.let { add { ChecksRow(it, openInBrowser) } }
            // A merged or closed PR's reviews only matter if someone approved or objected.
            if (detail.state == ThreadState.Open || pr.reviews.isNotEmpty()) add { ReviewsRow(pr) }
            if (detail.state == ThreadState.Open) add { MergeRow(pr) }
          }
          if (detail.labels.isNotEmpty()) add { LabelsRow(detail) }
          if (detail.assignees.isNotEmpty()) add { AssigneesRow(detail) }
          detail.milestone?.let {
            add { SummaryRow(ConversationIcons.Label, null) { SummaryText("Milestone ", it) } }
          }
        }
      rows.forEachIndexed { i, row ->
        if (i > 0) {
          HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 48.dp, end = 16.dp),
          )
        }
        row()
      }
    }
  }
}

@Composable
private fun SummaryRow(
  icon: ImageVector,
  tint: Color?,
  onClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    Icon(
      icon,
      null,
      Modifier.size(20.dp),
      tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.padding(start = 12.dp).weight(1f)) { content() }
    trailing?.invoke()
  }
}

/** A row's text: a quiet [lead] and an emphasized [value]. */
@Composable
private fun SummaryText(lead: String, value: String, tail: String = "") {
  val colors = MaterialTheme.colorScheme
  Text(
    buildAnnotatedString {
      withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(lead) }
      withStyle(SpanStyle(color = colors.onSurface, fontWeight = FontWeight.Medium)) {
        append(value)
      }
      withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(tail) }
    },
    style = MaterialTheme.typography.bodyMedium,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun ChangesRow(pr: PullRequestSummary, onOpenChanges: (() -> Unit)?) {
  val colors = stateColors()
  SummaryRow(
    TongsIcons.Changes,
    MaterialTheme.colorScheme.primary,
    onClick = onOpenChanges,
    trailing = {
      if (onOpenChanges != null)
        Icon(
          ConversationIcons.ChevronRight,
          "Open changes",
          Modifier.size(20.dp).padding(start = 2.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
  ) {
    Text(
      buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
          append(plural(pr.changedFiles, "file"))
        }
        append("  ")
        withStyle(SpanStyle(color = colors.open, fontFamily = FontFamily.Monospace)) {
          append("+${pr.additions}")
        }
        append(" ")
        withStyle(SpanStyle(color = colors.closed, fontFamily = FontFamily.Monospace)) {
          append("−${pr.deletions}")
        }
        pr.commitCount?.let {
          withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            append("  ·  ${plural(it, "commit")}")
          }
        }
      },
      style = MaterialTheme.typography.bodyMedium,
    )
    val viewed = pr.viewedFiles
    if (viewed != null && pr.changedFiles > 0) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        LinearProgressIndicator(
          progress = { viewed.toFloat() / pr.changedFiles },
          strokeCap = StrokeCap.Round,
          gapSize = 0.dp,
          drawStopIndicator = {},
          modifier = Modifier.weight(1f).size(height = 4.dp, width = 0.dp),
        )
        Text(
          "$viewed of ${pr.changedFiles} viewed",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 10.dp),
        )
      }
    }
  }
}

@Composable
private fun ChecksRow(checks: ChecksSummary, openInBrowser: (String) -> Unit) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val summary = buildList {
    if (checks.failed > 0) add("${checks.failed} failing")
    if (checks.pending > 0) add("${checks.pending} running")
    if (checks.passed > 0) add("${checks.passed} passed")
    if (checks.skipped > 0) add("${checks.skipped} skipped")
  }
  val headline =
    when {
      checks.failed > 0 -> "Checks failing"
      checks.pending > 0 -> "Checks running"
      checks.total == 0 -> "No checks"
      else -> "Checks passed"
    }
  Column {
    SummaryRow(
      checkIcon(checks.state),
      checkColor(checks.state),
      onClick = if (checks.checks.isNotEmpty()) ({ expanded = !expanded }) else null,
      trailing = {
        if (checks.checks.isNotEmpty()) {
          Icon(
            if (expanded) ConversationIcons.ExpandLess else ConversationIcons.ExpandMore,
            if (expanded) "Hide checks" else "Show checks",
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
    ) {
      SummaryText("", headline, if (summary.isEmpty()) "" else "  ·  " + summary.joinToString(", "))
    }
    AnimatedVisibility(expanded) {
      Column(Modifier.padding(start = 48.dp, end = 16.dp, bottom = 8.dp)) {
        for (check in checks.checks) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth()
                .clickable(enabled = check.url != null) { check.url?.let(openInBrowser) }
                .padding(vertical = 5.dp),
          ) {
            Icon(checkIcon(check.state), null, Modifier.size(16.dp), tint = checkColor(check.state))
            Text(
              check.name,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            check.summary?.let {
              Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).width(120.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ReviewsRow(pr: PullRequestSummary) {
  val approved = pr.reviews.filter { it.state == ReviewState.Approved }.map { it.reviewer.login }
  val changes =
    pr.reviews.filter { it.state == ReviewState.ChangesRequested }.map { it.reviewer.login }
  val (icon, tint) =
    when {
      changes.isNotEmpty() || pr.reviewDecision == ReviewDecision.ChangesRequested ->
        ConversationIcons.ErrorFilled to stateColors().closed
      pr.reviewDecision == ReviewDecision.Approved ||
        (approved.isNotEmpty() && pr.reviewDecision == null) ->
        ConversationIcons.CheckCircle to stateColors().open
      else -> TongsIcons.RateReview to null
    }
  SummaryRow(icon, tint) {
    val lines = buildList {
      if (changes.isNotEmpty()) add("Changes requested by " to changes.joinToString(", "))
      if (approved.isNotEmpty()) add("Approved by " to approved.joinToString(", "))
      if (pr.requestedReviewers.isNotEmpty())
        add("Waiting on " to pr.requestedReviewers.joinToString(", "))
      if (isEmpty()) {
        add(
          when (pr.reviewDecision) {
            ReviewDecision.ReviewRequired -> "" to "Review required"
            // Comment-only reviews don't count, so this isn't "no reviews".
            else -> "" to "No approvals yet"
          },
        )
      }
    }
    for ((lead, value) in lines) SummaryText(lead, value)
  }
}

@Composable
private fun MergeRow(pr: PullRequestSummary) {
  val colors = stateColors()
  val (icon, tint, text) =
    when {
      pr.isDraft -> Triple(TongsIcons.Edit, colors.neutral, "Draft: not ready to merge")
      pr.mergeable == Mergeable.Conflicting ->
        Triple(ConversationIcons.ErrorFilled, colors.closed, "Has conflicts")
      pr.mergeStateRaw == "BEHIND" ->
        Triple(ConversationIcons.ErrorFilled, colors.pending, "Behind its base branch")
      pr.mergeStateRaw == "BLOCKED" -> Triple(TongsIcons.Lock, colors.neutral, "Merging is blocked")
      pr.mergeable == Mergeable.Yes ->
        Triple(ConversationIcons.CheckCircle, colors.open, "No conflicts")
      else -> Triple(TongsIcons.Schedule, colors.neutral, "Checking mergeability…")
    }
  SummaryRow(icon, tint) {
    SummaryText("", text, if (pr.autoMerge) "  ·  auto-merge on" else "")
    val head = pr.headOwner?.let { "$it:${pr.headRef}" } ?: pr.headRef
    Text(
      "$head → ${pr.baseRef}",
      style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}

@Composable
private fun LabelsRow(detail: ThreadDetail) {
  SummaryRow(ConversationIcons.Label, null) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      for (label in detail.labels) LabelChip(label)
    }
  }
}

@Composable
private fun AssigneesRow(detail: ThreadDetail) {
  SummaryRow(ConversationIcons.Person, null) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      for (assignee in detail.assignees) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Avatar(assignee.avatarUrl, assignee.login, 20.dp)
          Text(
            assignee.login,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 6.dp),
          )
        }
      }
    }
  }
}

/** Whether to offer Merge first: an open, approved PR that merges cleanly and you can merge. */
internal fun ThreadDetail.readyToMerge(): Boolean {
  val pr = pullRequest ?: return false
  return state == ThreadState.Open &&
    !pr.isDraft &&
    viewer.mergeMethods.isNotEmpty() &&
    pr.mergeable == Mergeable.Yes &&
    pr.reviewDecision != ReviewDecision.ChangesRequested &&
    pr.reviewDecision != ReviewDecision.ReviewRequired &&
    pr.checks?.let { it.failed == 0 && it.state != CheckState.Pending } != false &&
    pr.mergeStateRaw !in setOf("BLOCKED", "DIRTY", "BEHIND")
}
