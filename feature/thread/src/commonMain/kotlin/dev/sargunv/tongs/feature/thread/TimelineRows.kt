package dev.sargunv.tongs.feature.thread

import androidx.compose.ui.graphics.vector.ImageVector
import dev.sargunv.tongs.core.data.ThreadSnapshot
import dev.sargunv.tongs.core.data.TimelineEntry
import dev.sargunv.tongs.core.ui.ConversationIcons
import dev.sargunv.tongs.core.ui.TongsIcons
import dev.sargunv.tongs.forge.api.Actor
import dev.sargunv.tongs.forge.api.EventKind
import dev.sargunv.tongs.forge.api.Label
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.ReviewThread
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadKind
import dev.sargunv.tongs.forge.api.ThreadState
import dev.sargunv.tongs.forge.api.TimelineItem
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One row of the timeline as shown. Runs of commits and of label changes by one person collapse
 * into one row each, the way you'd say it: "mona pushed 3 commits", "mona added bug and ui".
 */
internal sealed interface TimelineRow {
  val key: String

  data class Comment(val entry: TimelineEntry) : TimelineRow {
    val comment
      get() = entry.item as TimelineItem.Comment

    override val key
      get() = entry.item.id
  }

  data class Review(val review: TimelineItem.Review, val threads: List<ReviewThread>) :
    TimelineRow {
    override val key
      get() = review.id

    /** A review with no body that started no threads only replied in other reviews' threads. */
    val isReplyOnly
      get() = review.body.isBlank() && threads.isEmpty()
  }

  data class Commits(val commits: List<TimelineItem.Commit>) : TimelineRow {
    override val key
      get() = "commits:" + commits.first().id
  }

  data class Labels(
    val id: String,
    val actor: Actor?,
    val added: List<Label>,
    val removed: List<Label>,
    val at: Instant,
  ) : TimelineRow {
    override val key
      get() = "labels:$id"
  }

  data class Event(val event: TimelineItem.Event) : TimelineRow {
    override val key
      get() = event.id
  }

  /** The unloaded middle of a long timeline. */
  data class Gap(val count: Int) : TimelineRow {
    override val key
      get() = "gap"
  }
}

/** Label changes this close together by one person read as one change. */
private val LABEL_RUN = 10.minutes

internal fun timelineRows(snapshot: ThreadSnapshot): List<TimelineRow> {
  val rows = mutableListOf<TimelineRow>()
  fun add(entry: TimelineEntry) {
    val previous = rows.lastOrNull()
    when (val item = entry.item) {
      is TimelineItem.Comment -> rows += TimelineRow.Comment(entry)
      is TimelineItem.Review ->
        rows += TimelineRow.Review(item, snapshot.reviewThreads[item.id].orEmpty())
      is TimelineItem.Commit ->
        if (previous is TimelineRow.Commits) {
          rows[rows.lastIndex] = previous.copy(commits = previous.commits + item)
        } else rows += TimelineRow.Commits(listOf(item))
      is TimelineItem.Event -> {
        val label = item.label
        if (label != null && (item.kind == EventKind.Labeled || item.kind == EventKind.Unlabeled)) {
          val added = item.kind == EventKind.Labeled
          if (
            previous is TimelineRow.Labels &&
              previous.actor == item.actor &&
              item.createdAt - previous.at < LABEL_RUN
          ) {
            rows[rows.lastIndex] =
              if (added) previous.copy(added = previous.added + label)
              else previous.copy(removed = previous.removed + label)
          } else {
            rows +=
              TimelineRow.Labels(
                item.id,
                item.actor,
                if (added) listOf(label) else emptyList(),
                if (added) emptyList() else listOf(label),
                item.createdAt,
              )
          }
        } else rows += TimelineRow.Event(item)
      }
    }
  }
  // GitHub records a merge as both merged and closed; the merge says it all.
  val merges =
    snapshot.items.mapNotNull {
      (it.item as? TimelineItem.Event)?.takeIf { e -> e.kind == EventKind.Merged }?.createdAt
    }
  snapshot.items.forEachIndexed { index, entry ->
    val event = entry.item as? TimelineItem.Event
    val redundantClose =
      event?.kind == EventKind.Closed &&
        merges.any { (it - event.createdAt).absoluteValue < 1.minutes }
    if (index == snapshot.gapIndex && snapshot.hiddenCount > 0) {
      rows += TimelineRow.Gap(snapshot.hiddenCount)
    }
    if (!redundantClose) add(entry)
  }
  if (snapshot.gapIndex >= snapshot.items.size && snapshot.hiddenCount > 0) {
    rows += TimelineRow.Gap(snapshot.hiddenCount)
  }
  return rows
}

/** A piece of an event's sentence. */
internal sealed interface Part {
  data class Text(val text: String) : Part

  /** A name, set in the emphasis color. */
  data class Strong(val text: String) : Part

  data class Code(val text: String) : Part

  data class LabelChip(val label: Label) : Part

  /** Another issue or PR, which opens in the app. */
  data class Link(val text: String, val url: String) : Part
}

/** [tone] colors the icon for state changes, so a close or a merge stands out in the timeline. */
internal class EventLine(val icon: ImageVector, val parts: List<Part>, val tone: Tone = Tone.Quiet)

internal enum class Tone {
  Quiet,
  Open,
  Done,
  Closed,
  Merged,
  Skipped,
}

/** Says what an event did, after its actor's name. Null for kinds we don't describe. */
internal fun describe(
  event: TimelineItem.Event,
  repo: RepoRef? = null,
  onPullRequest: Boolean = false,
): EventLine {
  val subject = event.subject
  fun text(vararg parts: Any): List<Part> = parts.map {
    if (it is Part) it else Part.Text(it.toString())
  }
  fun short(oid: String?) = oid?.take(7).orEmpty()
  return when (event.kind) {
    EventKind.Assigned ->
      if (subject == null || subject == event.actor?.login)
        EventLine(ConversationIcons.Person, text("self-assigned this"))
      else EventLine(ConversationIcons.Person, text("assigned ", Part.Strong(subject)))
    EventKind.Unassigned ->
      if (subject == null || subject == event.actor?.login)
        EventLine(ConversationIcons.Person, text("removed their assignment"))
      else EventLine(ConversationIcons.Person, text("unassigned ", Part.Strong(subject)))
    EventKind.Closed ->
      EventLine(
        if (event.stateReason == StateReason.NotPlanned) ConversationIcons.Skipped
        else ConversationIcons.CheckCircle,
        when {
          event.stateReason == StateReason.NotPlanned -> text("closed this as not planned")
          event.stateReason == StateReason.Duplicate -> text("closed this as a duplicate")
          event.reference != null ->
            event.reference!!.let {
              text("closed this in ", Part.Link("#${it.number}", it.webUrl.orEmpty()))
            }
          event.commitOid != null -> text("closed this in ", Part.Code(short(event.commitOid)))
          else -> text("closed this")
        },
        when {
          onPullRequest -> Tone.Closed
          event.stateReason == StateReason.NotPlanned ||
            event.stateReason == StateReason.Duplicate -> Tone.Skipped
          else -> Tone.Done
        },
      )
    EventKind.Reopened -> EventLine(TongsIcons.Issue, text("reopened this"), Tone.Open)
    EventKind.Merged ->
      EventLine(
        TongsIcons.PullRequest,
        text(
          "merged ",
          Part.Code(short(event.commitOid)),
          " into ",
          Part.Code(subject ?: "the base"),
        ),
        Tone.Merged,
      )
    EventKind.CrossReferenced -> {
      val ref = event.reference
      if (ref == null) EventLine(ConversationIcons.Link, text("mentioned this"))
      else {
        val kind = if (ref.kind == ThreadKind.PullRequest) "a pull request" else "an issue"
        EventLine(
          ConversationIcons.Link,
          text(
            if (event.willClose) "linked $kind that will close this: " else "mentioned this in ",
            Part.Link(refLabel(ref.repo, repo, ref.number, ref.title), ref.webUrl.orEmpty()),
            if (ref.state == ThreadState.Merged) " (merged)"
            else if (ref.state == ThreadState.Closed) " (closed)" else "",
          ),
        )
      }
    }
    EventKind.Referenced ->
      EventLine(
        TongsIcons.Commit,
        text(
          "referenced this in ",
          Part.Code(short(event.commitOid)),
          subject?.let { " $it" }.orEmpty(),
        ),
      )
    EventKind.Renamed ->
      EventLine(
        TongsIcons.Edit,
        text(
          "changed the title from ",
          Part.Strong(event.previous.orEmpty()),
          " to ",
          Part.Strong(subject.orEmpty()),
        ),
      )
    EventKind.ReviewRequested ->
      EventLine(
        TongsIcons.RateReview,
        text("requested a review from ", Part.Strong(subject ?: "someone")),
      )
    EventKind.ReviewRequestRemoved ->
      EventLine(
        TongsIcons.RateReview,
        text("removed the review request for ", Part.Strong(subject ?: "someone")),
      )
    EventKind.ReviewDismissed ->
      EventLine(
        TongsIcons.RateReview,
        text("dismissed ", Part.Strong(subject ?: "a"), "'s review"),
      )
    EventKind.HeadRefForcePushed ->
      EventLine(
        TongsIcons.Commit,
        text(
          "force-pushed ",
          Part.Code(subject ?: "the branch"),
          " from ",
          Part.Code(short(event.previous)),
          " to ",
          Part.Code(short(event.commitOid)),
        ),
      )
    EventKind.HeadRefDeleted ->
      EventLine(
        TongsIcons.Commit,
        text("deleted the ", Part.Code(subject ?: "head"), " branch"),
      )
    EventKind.ReadyForReview ->
      EventLine(TongsIcons.RateReview, text("marked this ready for review"))
    EventKind.ConvertToDraft -> EventLine(TongsIcons.Edit, text("marked this as a draft"))
    EventKind.Milestoned ->
      EventLine(ConversationIcons.Label, text("added this to ", Part.Strong(subject.orEmpty())))
    EventKind.Demilestoned ->
      EventLine(ConversationIcons.Label, text("removed this from ", Part.Strong(subject.orEmpty())))
    EventKind.MarkedAsDuplicate ->
      EventLine(
        ConversationIcons.Skipped,
        text(
          "marked this as a duplicate of ",
          event.reference?.let { Part.Link("#${it.number}", it.webUrl.orEmpty()) } ?: "another",
        ),
      )
    EventKind.Locked ->
      EventLine(
        TongsIcons.Lock,
        text("locked this", subject?.let { " as ${it.lowercase().replace('_', ' ')}" }.orEmpty()),
      )
    EventKind.Unlocked -> EventLine(ConversationIcons.LockOpen, text("unlocked this"))
    EventKind.BaseRefChanged ->
      EventLine(
        TongsIcons.Commit,
        text(
          "changed the base from ",
          Part.Code(event.previous.orEmpty()),
          " to ",
          Part.Code(subject.orEmpty()),
        ),
      )
    EventKind.AutoMergeEnabled -> EventLine(TongsIcons.PullRequest, text("enabled auto-merge"))
    EventKind.AutoMergeDisabled -> EventLine(TongsIcons.PullRequest, text("disabled auto-merge"))
    EventKind.Labeled ->
      EventLine(
        ConversationIcons.Label,
        text("added ", *listOfNotNull(event.label?.let(Part::LabelChip)).toTypedArray()),
      )
    EventKind.Unlabeled ->
      EventLine(
        ConversationIcons.Label,
        text("removed ", *listOfNotNull(event.label?.let(Part::LabelChip)).toTypedArray()),
      )
    is EventKind.Other -> EventLine(ConversationIcons.History, text(humanize(event.kind.raw)))
  }
}

/** `#415 Title` within the repo, `owner/repo#415 Title` across repos. */
private fun refLabel(repo: RepoRef, here: RepoRef?, number: Int, title: String) =
  (if (repo == here) "#$number" else "$repo#$number") + " " + title

/** `ConnectedEvent` → "connected". */
private fun humanize(raw: String) =
  raw.removeSuffix("Event").replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
