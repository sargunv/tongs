package dev.sargunv.tongs.app

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.sargunv.tongs.core.data.AccountEntity
import dev.sargunv.tongs.core.data.AccountRepository
import dev.sargunv.tongs.core.data.DraftRepository
import dev.sargunv.tongs.core.data.ForgeClients
import dev.sargunv.tongs.core.data.InMemoryTokenStore
import dev.sargunv.tongs.core.data.ThreadRef
import dev.sargunv.tongs.core.data.ThreadRepository
import dev.sargunv.tongs.core.data.TongsDatabase
import dev.sargunv.tongs.core.data.TongsDatabaseConstructor
import dev.sargunv.tongs.forge.api.ForgeAdapter
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.RepoRef
import dev.sargunv.tongs.forge.api.StateReason
import dev.sargunv.tongs.forge.api.ThreadState
import dev.sargunv.tongs.forge.api.TimelineItem
import dev.sargunv.tongs.forge.github.GitHubAdapter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replying and closing, end to end against a fake GitHub GraphQL server: the real adapter and its
 * queries, Room, drafts, and the thread repository. Nothing here talks to github.com.
 */
class PostingTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val db: TongsDatabase =
    Room.inMemoryDatabaseBuilder<TongsDatabase>(factory = TongsDatabaseConstructor::initialize)
      .setDriver(BundledSQLiteDriver())
      .build()

  /** Operations the fake server received, with their variables, in order. */
  private val received = mutableListOf<Pair<String, Map<String, String>>>()
  private var closed = false
  private var refuseComments = false

  private val github =
    HttpClient(
      MockEngine { request ->
        val body =
          Json.parseToJsonElement(
              (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
            )
            .jsonObject
        val operation = body["operationName"]!!.jsonPrimitive.content
        val variables =
          body["variables"]?.jsonObject.orEmpty().mapValues { it.value.toString().trim('"') }
        synchronized(received) { received += operation to variables }
        val response =
          when (operation) {
            "Thread" ->
              if (closed) OPEN_ISSUE.replace("\"issueState\":\"OPEN\"", "\"issueState\":\"CLOSED\"")
              else OPEN_ISSUE
            "ThreadAddComment" ->
              if (refuseComments) {
                """{"data":null,"errors":[{"type":"FORBIDDEN","message":"Resource not accessible by integration"}]}"""
              } else ADD_COMMENT
            "ThreadCloseIssue" -> {
              closed = true
              """{"data":{"closeIssue":{"issue":{"id":"I_1"}}}}"""
            }
            else -> error("Unexpected $operation")
          }
        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
      },
    )

  private fun setUp(): Triple<ThreadRepository, DraftRepository, ThreadRef> = runBlocking {
    val tokens = InMemoryTokenStore()
    val id =
      db
        .accounts()
        .upsert(
          AccountEntity(
            forge = "GitHub",
            webUrl = "https://github.com",
            apiUrl = "https://api.github.com",
            userId = "U_1",
            login = "octocat",
            name = null,
            avatarUrl = null,
            authMethod = "ClassicToken",
            capabilities = "",
            missingScopes = "",
          ),
        )
    tokens.put(id, "token")
    val adapters = mapOf<ForgeKind, ForgeAdapter>(ForgeKind.GitHub to GitHubAdapter(github))
    val clients = ForgeClients(db, tokens, adapters)
    val accounts = AccountRepository(db, tokens, adapters, clients)
    val drafts = DraftRepository(db, scope, Clock.System)
    Triple(
      ThreadRepository(db, clients, accounts, drafts, scope, Clock.System),
      drafts,
      ThreadRef(id, RepoRef("octo", "hello"), 9),
    )
  }

  private suspend fun until(check: suspend () -> Boolean) =
    withTimeout(10.seconds) { while (!check()) delay(20) }

  @AfterTest
  fun tearDown() {
    scope.cancel()
    db.close()
  }

  @Test
  fun replyingPostsTheDraftAndClears() = runBlocking {
    val (threads, drafts, ref) = setUp()
    threads.refresh(ref)
    drafts.save(ref.replyDraft, "Thanks!\n\n> quoted")

    threads.comment(ref, "Thanks!\n\n> quoted")
    until { drafts.get(ref.replyDraft) == null }

    val (operation, variables) = received.last()
    assertEquals("ThreadAddComment", operation)
    assertEquals("I_1", variables["subjectId"])
    val last = threads.observe(ref).first()!!.items.last()
    assertFalse(last.pending)
    assertEquals("IC_new", last.item.id)
    assertEquals("octocat", (last.item as TimelineItem.Comment).author?.login)
  }

  @Test
  fun closingWithACommentPostsClosesAndRefetches() = runBlocking {
    val (threads, _, ref) = setUp()
    threads.refresh(ref)
    threads.comment(ref, "Fixed in #10", close = StateReason.Completed)
    until { synchronized(received) { received.count { it.first == "Thread" } } == 2 }
    until { threads.observe(ref).first()!!.detail.state == ThreadState.Closed }

    val operations = synchronized(received) { received.map { it.first } }
    assertEquals(listOf("Thread", "ThreadAddComment", "ThreadCloseIssue", "Thread"), operations)
    assertEquals("COMPLETED", received[2].second["reason"])
  }

  @Test
  fun aRefusedReplyKeepsTheDraft() = runBlocking {
    val (threads, drafts, ref) = setUp()
    threads.refresh(ref)
    drafts.save(ref.replyDraft, "Thanks!")
    refuseComments = true
    var failure: String? = null
    val collecting = scope.launchCollect(threads) { failure = it }

    threads.comment(ref, "Thanks!")
    until { failure != null }

    assertTrue(failure!!.contains("Your draft is saved"), failure)
    assertEquals("Thanks!", drafts.get(ref.replyDraft))
    assertTrue(threads.observe(ref).first()!!.items.none { it.pending })
    assertNull(threads.observe(ref).first()!!.items.find { it.item.id == "IC_new" })
    collecting.cancel()
  }
}

private fun CoroutineScope.launchCollect(threads: ThreadRepository, onFailure: (String) -> Unit) =
  launch {
    threads.failures.collect(onFailure)
  }

private fun actor(login: String, type: String = "User") =
  """{"__typename":"$type","login":"$login","avatarUrl":"https://a/$login"}"""

private fun reactions(vararg groups: Triple<String, Int, Boolean>) =
  groups.joinToString(",", "[", "]") { (content, count, mine) ->
    """{"__typename":"ReactionGroup","content":"$content","viewerHasReacted":$mine,"reactors":{"totalCount":$count}}"""
  }

private fun pageInfo(start: String?, end: String?, previous: Boolean, next: Boolean) =
  """{"__typename":"PageInfo","startCursor":${start?.let { "\"$it\"" }},"endCursor":${end?.let { "\"$it\"" }},"hasPreviousPage":$previous,"hasNextPage":$next}"""

private val OPEN_ISSUE =
  """
  {"data":{"repository":{
    "viewerPermission":"READ","mergeCommitAllowed":true,"squashMergeAllowed":true,
    "rebaseMergeAllowed":true,"viewerDefaultMergeMethod":"MERGE",
    "issueOrPullRequest":{
      "__typename":"Issue","id":"I_1","number":9,"title":"New title","body":"It crashes",
      "url":"https://github.com/octo/hello/issues/9","createdAt":"2026-10-01T00:00:00Z",
      "updatedAt":"2026-10-05T00:00:00Z","lastEditedAt":"2026-10-02T00:00:00Z",
      "issueState":"OPEN","stateReason":null,"locked":false,"author":${actor("mona")},
      "labels":{"nodes":[]},"assignees":{"nodes":[${actor("mona")}]},"milestone":{"title":"v1.0"},
      "reactionGroups":[],"comments":{"totalCount":1},"viewerCanUpdate":true,
      "viewerCanReact":true,"viewerCanClose":true,"viewerCanReopen":false,
      "head":{"__typename":"IssueTimelineItemsConnection","filteredCount":5,
        "pageInfo":${pageInfo("S", "E", previous = false, next = false)},
        "nodes":[
          {"__typename":"IssueComment","id":"IC_1","createdAt":"2026-10-01T01:00:00Z",
           "author":${actor("hubot", "Bot")},"body":"Same here","lastEditedAt":null,
           "url":"https://github.com/octo/hello/issues/9#issuecomment-1","viewerCanReact":true,
           "isMinimized":false,"minimizedReason":null,
           "reactionGroups":${reactions(Triple("THUMBS_UP", 3, false), Triple("EYES", 0, false))}},
          {"__typename":"LabeledEvent","id":"LE_1","createdAt":"2026-10-01T02:00:00Z",
           "actor":${actor("github-actions", "Bot")},
           "label":{"__typename":"Label","name":"needs-triage","color":"D6393F","description":null}},
          {"__typename":"RenamedTitleEvent","id":"RTE_1","createdAt":"2026-10-02T00:00:00Z",
           "actor":${actor("mona")},"previousTitle":"Old title","currentTitle":"New title"},
          {"__typename":"CrossReferencedEvent","id":"CRE_1","createdAt":"2026-10-03T00:00:00Z",
           "actor":${actor("mona")},"willCloseTarget":true,
           "source":{"__typename":"PullRequest","number":44,"title":"Fix the crash",
             "url":"https://github.com/other/repo/pull/44","pullRequestState":"MERGED",
             "repository":{"__typename":"Repository","name":"repo","owner":{"__typename":"User","login":"other"}}}},
          {"__typename":"ClosedEvent","id":"CE_1","createdAt":"2026-10-05T00:00:00Z",
           "actor":${actor("mona")},"stateReason":"NOT_PLANNED",
           "closer":{"__typename":"Commit","oid":"c0ffee"}}
        ]},
      "tail":{"__typename":"IssueTimelineItemsConnection","filteredCount":5,
        "pageInfo":${pageInfo("S", "E", previous = false, next = false)},"nodes":[]}
    }
  }}}
  """

private val ADD_COMMENT =
  """
  {"data":{"addComment":{"commentEdge":{"node":{"__typename":"IssueComment","id":"IC_new",
    "createdAt":"2026-10-28T12:00:00Z","author":${actor("octocat")},"body":"Thanks!\n\n> quoted",
    "lastEditedAt":null,"url":"https://github.com/octo/hello/issues/9#issuecomment-2",
    "viewerCanReact":true,"isMinimized":false,"minimizedReason":null,"reactionGroups":[]}}}}}
  """
