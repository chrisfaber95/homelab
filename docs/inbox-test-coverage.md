# Python tests and their Java equivalents (H-026 final coverage check)

H-026 asks: every behaviour covered by the Python tests in `tests/` has an equivalent Java test, so the cut-over (H-027)
is a switch and not a rewrite. The brief spoke of 17 files; `tests/` now holds 49 (`test_*.py`, 541 cases), so this
document maps **all** of them. Java tests are in `backend/src/test/java/dev/homelab/inbox/`; `core/` is the queue, `door/`
the chat and kitt doors, `frontdoor/` access, CSRF and CSP.

H-028 adds the route-by-route view: for each route of `bin/homelab_inbox/server.py`, and for the doors, the gate and the
connector, the Java test that covers it (the "Route tables" sections and "Everything else, by route" below). What has no
Java test is in "Gaps" at the end; nothing is left unlisted.

Run both sides: `python3 -m unittest discover -s tests` and `cd backend && ./mvnw verify`.

Kinds of rows:

- **Java**: the behaviour is a Java test (the name of the Java test is given).
- **Python by design**: it tests Python-only code that H-026 does not move: the runner processes (`runner/*.py`, H-025:
  they stay Python, Java reads their output), the shell script `bin/homelab-inbox-task`, the HTTP handler and routes
  (switched in H-027, covered by the parity tests that compare both servers), and services that are not part of the core
  (search, router, forge, Discord bot). The Java side of a runner contract is tested where it reads the output.
- **Gap closed here**: no Java test existed; this task wrote one (and fixed the Java core where it failed).

## Gaps closed in this task

| Python behaviour | Was missing in Java | Now |
|---|---|---|
| `test_inbox_dead_session.RoundMode` (a retry whose session has no transcript starts a new one with the task text) | `RoundFlow` always resumed | `TaskCommands.session`, `RoundFlow` falls back to a new session; `RoundFinishTest.anExistingSessionIsResumed`, `aMissingSessionStartsANewOneWithTheTaskText` |
| `test_inbox_dead_session.EmptyRound` (a round without a single turn fails loudly) | no rule | `RoundLimits.checkEnded`; `RoundLimitsTest.anErrorResultWithoutTurnsFails...`, `anEmptyResultWithoutTurnsFailsTheRound`, `aRoundWithTurnsOrACapEndsFineAndNoResultIsAFailure` |
| `test_inbox_events.Notifications` (full text and link stored, push cut at 300, push survives a failing conversation) | only the `Notifier` interface | `Notifications`; `NotificationsTest` (5 cases) |
| `test_inbox_tasks_api` (list and detail data: queue, history, actions per status, build and children, log chunk) | no task page data | `TaskPages`; `TaskPagesTest` (9 cases) |
| `test_runner_draft.MakeDraftUsesRunner` and the result-line half of `Draft` (`make_draft` reads the runner) | not ported | `DraftRunnerOutput`; `DraftRunnerOutputTest` (8 cases) |
| Acceptance criterion 2: the core on a copy of the data with the scheduler off | parts were tested one by one | `CoreOnDataCopyTest` runs scheduler, recovery, sweep, follow-up settle, GitHub poll, job engine and brief settle over a copy with a task in every state and a due and a held job: no effect is called and the data is byte-for-byte unchanged |

## The areas of the brief

| Area | Python | Java |
|---|---|---|
| Limits (turns, time, cancel, max running, per repo, daily and research caps, task limit of a brief) | `test_runner_round.InboxUsesRunner`, `test_inbox_builds.TaskLimit`, `test_inbox_chat.StartTask`, `test_inbox_research.Ask` | `RoundLimitsTest` (9), `SchedulerTest.neverRunsMoreThan...`, `BriefsTest` (limit cases), `ChatActionsTest.theDailyCapRefusesClearly...`, `JobEngineTest` (cap cases) |
| Restart recovery | `RESTART_ROUNDS` and `recover()` (no Python test; behaviour of `queue.recover`) | `RecoveryTest` (6, written from `queue.recover`) |
| Follow-ups | `test_inbox_followups` | `FollowupChainTest` (15), `TaskActionsTest`, `GitHubPollerTest` |
| Conflict rounds | `test_inbox_catch_up` | `RoundFinishTest` (catch-up cases), `GitHubPollerTest` (`aCleanConflictIsMergedAndPushed`, `realConflictsGoBackToTheSessionWithTheFiles`, `afterTheConflictRounds...`) |
| Auto-merge rules | `test_inbox_auto_merge` (+ `merges_itself` in `test_runner_review`) | `GitHubPollerTest` (28), `BriefsTest.mergesItselfNeedsEveryCondition` |
| Brief review | `test_runner_review`, `test_inbox_builds`, `test_inbox_briefs_api` | `BriefsTest` (37) |

## Core files, case by case

### test_inbox_followups (12)

| Python case | Java |
|---|---|
| `test_merged_pr_requeues_followups_in_a_chain` | `FollowupChainTest.aMergedPrMarksTheTaskMergedAndQueuesTheFollowupsInAChain`, `GitHubPollerTest.aMergedPrRequeuesFollowupsInAChain` |
| `test_open_or_closed_pr_changes_nothing` | `FollowupChainTest.anOpenOrUnmergedClosedPrChangesNothing`, `GitHubPollerTest.anOpenOrClosedPrChangesNothing` |
| `test_merge_does_not_restart_a_followup_from_before_this_feature` | `FollowupChainTest.aMergedPrDoesNotRestartAFollowupFromBeforeTheFeature`, `GitHubPollerTest.aMergeDoesNotRestart...` |
| `test_mark_as_fine_requeues_followups_and_not_the_task` | `FollowupChainTest.markAsFineQueuesTheFollowupsNotTheTask...` |
| `test_mark_as_fine_needs_a_blocked_followup` | `FollowupChainTest.markAsFineNeedsABlockedFollowup` |
| `test_unmarked_task_still_stops_followups` | `FollowupChainTest.anUnmarkedTaskStillStopsItsFollowups` |
| `test_retry_clears_the_mark` | `FollowupChainTest.aRetryClearsTheMark`, `TaskActionsTest.retryClearsTheAcceptedMark...` |
| `test_retry_a_refused_task` | `TaskActionsTest.retryOfARefusedTaskQuotes...`, `FollowupChainTest.retryingTheTaskQueuesItsFollowupsAgain` |
| `test_capped_round_keeps_its_working_copy_for_continue` | `TaskActionsTest.continueOfACappedTaskKeepsItsWorkingCopy`, `RoundFinishTest.aCappedRoundKeepsItsWorkingCopyAndContinueCarriesOn` |
| `test_sweep_removes_only_old_capped_working_copies` | `TaskActionsTest.theSweepRemovesOnlyOldCappedWorkingCopies...` (+ 4 more sweep cases) |
| `test_retry_note_of_a_task_that_never_ran_goes_in_its_text` | `TaskActionsTest.theNoteOfATaskThatNeverRan...` |
| `test_migration_flags_only_existing_followups` | `FollowupChainTest.theColumnNoAutoReviveDefaultsToZero` (the SQLite `ALTER TABLE` is replaced by the import) |

### test_inbox_auto_merge (4), test_inbox_catch_up (14), test_inbox_builds (12)

| Python case | Java |
|---|---|
| `AutoMerge.test_green_pr_is_merged`, `test_token_without_check_runs_reads_the_actions_runs`, `test_failed_actions_run_is_not_merged`, `test_unreadable_ci_is_reported_once_and_retried` | `GitHubPollerTest` `aGreenPrIsMerged`, `aTokenWithoutCheckRunsReadsTheActionsRuns`, `aFailedActionsRunIsNotMerged`, `unreadableCiIsReportedOnceAndRetried` |
| `CatchUpMain` (6) | `RoundFinishTest` `aPrIsLeftToTheGithubPoller`, `aBranchThatAlreadyHasMainIsNotTouched`, `noWorkingCopyIsNotTouched`, `aCleanMergeIsToldToTheTask`, `conflictsNameTheFiles`, `aFailedMergeHandsOverTheCommand` |
| `RoundGetsMain` (2) | `RoundFinishTest.aRetriedRoundMergesMainAndKeepsTheUsersNote`, `theFirstRoundDoesNotSync` |
| `BehindSubcommand` (6) | Python by design: `bin/homelab-inbox-task behind|sync` against real git; Java only calls it (`RoundFinishTest` fakes the answers 0/1/2 and 0/4) |
| `Builds` (6), `TaskLimit` (4) | `BriefsTest` cases of the same names |
| `ClaudeResult` (2) | Python by design: `helpers.claude_result` stays with the runner (Q7); the Java side gets a `ClaudeAnswer` |

### test_inbox_events (12), test_inbox_dead_session (4)

| Python case | Java |
|---|---|
| `Notifications` (4) | `NotificationsTest` (gap closed) |
| `Notifications.test_questions_of_a_task_are_stored_in_full` | `RoundFinishTest.aQuestionIsKeptInFullInTheConversation...` |
| `Switches` (5), `Door` (2) | `ConversationStoreTest.stateAndSwitches`, `aSwitchedOffClientIsRefused...`, `ChatActionsTest.theApprovalsSwitchRefusesBothSteps`, `switchingDiscordOffStopsTasks`, `ChatDoorTest.theDiscordSwitchOffBlocksTheDoor`; the page half of `test_page_shows_both_switches` is `OverviewService` (M1 parity) |
| `RoundMode` (2), `EmptyRound` (2) | gap closed (see above) |

### test_inbox_tasks_api (8), test_inbox_briefs_api (7)

| Python case | Java |
|---|---|
| `test_list_has_the_form...`, `test_task_detail_carries...`, `test_actions_follow_the_status`, `test_a_brief_build_and_its_children`, `test_the_log_so_far...`, `test_no_log_is_an_empty_chunk` | `TaskPagesTest` (gap closed) |
| `test_unknown_task_is_a_json_404`, `test_the_box_itself_is_refused` | `TaskControllerTest.anUnknownTaskIsAJson404` (also an id too big for a task); the 403 is `frontdoor/TailnetAccessTest`, `FrontDoorTest` |
| `BriefsApi` (7) | `BriefsTest` (K4 table in `docs/inbox-backend.md`: data halves; redirects and status codes are the route's) |

### Task routes (H-028 W2a): every route of `server.py` for tasks, in `TaskControllerTest` (real HTTP behind the front door)

| Python route | Java test (`TaskControllerTest`) |
|---|---|
| `GET /api/tasks` | `listHasTheFormReposQueueAndHistory` |
| `GET /api/tasks/{id}` | `detailCarriesTheTaskAndTheLogSoFar`, `aTaskWithoutALogHasAnEmptyChunk`, `anUnknownTaskIsAJson404` |
| `GET /taken`, `GET /task/{id}` | `thePagesAre503WithoutABuildAndTheIndexWithOne`, `anUnknownTaskOrAQueryStringIsTheNotFoundPage` |
| `POST /tasks` | `aNewTaskIsQueuedAndRedirectsToItsPage`, `autoIsAValidRepo`, `anUnknownRepoOrAnEmptyTextIsThe400Page` |
| `POST /task/{id}/answer` | `anAnswerToAQuestionQueuesTheTaskAgain`, `anEmptyAnswerOrOneToARunningTaskChangesNothingButStillRedirects` |
| `POST /task/{id}/cancel` | `cancelStopsAQueuedTaskAndLeavesAFinishedOneAlone`, `cancellingARoutedMessageCancelsTheChildrenThatAreNotDone` |
| `POST /task/{id}/retry` | `retryPutsAFailedTaskBackInTheQueueWithTheNote`, `retryDoesNothingForABuildAnAutoTaskOrATaskThatCannotBeRetried` |
| `POST /task/{id}/accept` | `acceptMarksTheTaskFineAndRevivesItsFollowups`, `acceptWithoutBlockedFollowupsChangesNothing` |
| `POST /task/{id}/attended` | `attendedStopsABuildWithoutMe`, `attendedOnAnOrdinaryTaskChangesNothing` |
| unknown task or action | `anUnknownTaskOrActionIsThe404Page` |
| Origin and token on every mutating route | `everyMutatingTaskRouteRefusesARequestWithoutAToken` (403, also with a wrong token), `everyMutatingTaskRouteRefusesTheWrongOrNoOrigin`, `theApiReadsNeedNoTokenButOtherMethodsDo`; `FrontDoorTest.aPageFormStillUsesItsCsrfField` |

### Brief routes (H-028 W2c): every route of `server.py` for briefs, in `BriefControllerTest` (real HTTP behind the front door)

| Python route | Java test (`BriefControllerTest`) |
|---|---|
| `GET /api/briefs` | `theListGroupsBriefsByStatus`, `anUnreadableProductRepoIsA200WithAnError` |
| `GET /api/briefs/{id}` | `oneBriefComesWithItsBody`, `anUnknownBriefIsAJson404` |
| `GET /briefs`, `GET /briefs/{id}` | `thePagesAre503WithoutABuildAndTheIndexWithOne`, `anUnknownBriefOrAQueryStringWithoutABuildIsTheNotFoundPage` |
| `POST /briefs/{id}/park` | `parkingADraftCommitsItAndGoesBackToTheList`, `parkingSomethingThatIsNotADraftIsA400` |
| `POST /briefs/{id}/build` | `buildingAnApprovedBriefQueuesABuildAndGoesToItsPage`, `buildingADraftApprovesItFirstAndWithoutMeSetsUnattended`, `aBriefThatIsAlreadyBeingBuiltOrNotApprovedIsA400`, `aBuildWithAnUnreachableProductRepoIsA400` |
| unknown action, query string or id | `anUnknownActionOrAQueryStringIsTheNotFoundPage` |
| Origin and token on every mutating route | `everyMutatingBriefRouteRefusesARequestWithoutAToken` (403, also with a wrong token), `everyMutatingBriefRouteRefusesTheWrongOrNoOrigin`, `theApiReadsNeedNoTokenButOtherMethodsDo` |

### Draft and run routes (H-028 W2b): in `DraftRunControllerTest` (real HTTP behind the front door)

| Python route | Java test (`DraftRunControllerTest`) |
|---|---|
| `GET /api/draft/{id}` | `apiDraftGivesTheDraftWithItsFacts`, `apiDraftOfABadFileSaysWhyAndADraftingOneRefreshes`, `apiDraftUnknownIs404WithAJsonError` |
| `GET /api/run/{id}` | `apiRunGivesTheRun`, `apiRunUnknownIs404WithAJsonError` |
| `GET /api/log/{t\|r}{id}` | `apiLogReadsATaskAndARunLog` |
| `GET /draft/{id}`, `GET /run/{id}` | `thePagesExistOnlyForADraftOrRunThatExists` |
| `POST /draft/{id}/cancel` | `cancellingADraftRedirectsToJobs` |
| `POST /draft/{id}/confirm` | `confirmingADraftSavesTheJobAndRedirectsToIt`, `confirmingAChangeKeepsTheNameOfTheJob`, `confirmingARefusedFileIs400AndTheDraftStaysReady`, `aDraftThatIsNotReadyOrUnknownIsThe404Page` |
| `POST /run/{id}/cancel` | `cancellingARunningLocalRunStopsItAndRedirectsToIt`, `cancellingARunThatIsNotRunningChangesNothing`, `cancellingAClaudeRunSendsYouToItsTask`, `cancellingAnUnknownRunIsThe404Page`, `inShadowACancelledScriptRunKillsNothingForReal` |
| Origin and token on every mutating route | `everyMutatingDraftAndRunRouteRefusesARequestWithoutAToken` (403 without or with a wrong token, and a wrong Origin) |

### Job routes (H-028 W3a): in `JobControllerTest` (real HTTP behind the front door)

| Python route | Java test (`JobControllerTest`) |
|---|---|
| `GET /api/jobs-page` | `apiJobsPageListsTheJobs` |
| `GET /api/job/{name}` | `apiJobGivesTheJobWithItsFileAndFacts`, `apiJobOfABrokenFileSaysWhatIsWrong`, `apiJobUnknownIs404WithAJsonError` |
| `GET /jobs`, `GET /job/{name}` | `theJobPagesComeFromTheFrontendBuild` |
| `POST /jobs/draft` | `aDescriptionStartsADraftAndRedirectsToIt`, `aDraftRequestIsCutAt4000Characters`, `aDraftWithoutATextIsThe404Page`; the Claude run: `LiveEffectsTest.aJobDraftStartsDraftPyWithItsDraftIdAndReturnsTheJobFile`, `aJobDraftThatFailsOrTimesOutAnswersWithAnError` |
| `POST /job/{name}/run` | `runNowQueuesAManualRunAndRedirectsToTheJob`, `runNowOfABrokenJobDoesNothing` |
| `POST /job/{name}/(pause\|resume)` | `pausingAndResumingGoThroughTheJobStore`, `pausingABrokenJobDoesNothing` |
| `POST /job/{name}/save`, `/delete` | `savingAnEditedFileGoesThroughTheJobStore`, `savingWithoutATextChangesNothing`, `deletingRemovesTheJobAndRedirectsToJobs`, `aRefusedWriteIs400WithTheReason` |
| `POST /job/{name}/change` | `changeStartsADraftOfTheJobAndRedirectsToIt` |
| unknown job, query string | `anUnknownJobOrAQueryStringIsThe404Page` |
| Origin and token on every mutating route | `everyMutatingJobRouteRefusesARequestWithoutAToken` (403 without or with a wrong token, and a wrong Origin) |

### Research, agent and Discord-switch routes (H-028 W3b): in `JobControllerTest` (real HTTP behind the front door)

| Python route | Java test (`JobControllerTest`) |
|---|---|
| `GET /api/research` | `apiResearchGivesTheCounterTheModesAndTheLatestQuestions` |
| `GET /research` | `theResearchPageComesFromTheFrontendBuild` |
| `POST /research/ask` | `aQuestionQueuesOneRunAndRedirectsToTheResearchPage`, `aQuestionWithoutAModeIsAQuickOne`, `anEmptyQuestionOrAnUnknownModeIs400WithTheReason`, `aQuestionQueryStringIsThe404Page` |
| `POST /agents/{name}/on` | `switchingAnAgentOnMakesAReadyDraftAndRedirectsToIt` (also the unknown, broken and query-string 404s) |
| `POST /discord/(discord\|discord_approvals\|kitt)/(on\|off)` | `theDiscordSwitchesAreSetAndRedirectToTheirCard` |
| Origin and token on every mutating route | `everyMutatingResearchAgentAndSwitchRouteRefusesARequestWithoutAToken` |

### test_inbox_research (27), test_inbox_job_delivery (12), test_inbox_agents (14), test_inbox_job_pages_api (11), test_inbox_jobs_api (7)

Mapped case by case in `docs/inbox-backend.md` ("Core in shadow: jobs and research (H-026 K6)"): `JobFileTest`,
`JobTextTest`, `JobEngineTest`, `SourceReaderTest`, `AgentCatalogueTest`, `JobPagesTest`. Python by design: the route and
page cases (`test_pages_get_the_frontend_index`, `..._research_wins_over_the_research_page`, `..._without_a_build_...`,
`..._failed_posts_...`, `..._cancelling_a_draft_...`, `test_query_string_is_ignored...`), the greps of `bin/homelab-inbox-task`
(`Permissions`) and `Finish.test_system_prompt_is_the_read_only_one` (`rounds.write_prompt`).
`test_inbox_research.Ask.test_chat_*` are `ChatActionsTest.researchAnswersAreRecordedAndTheCapIsSaid` and `startingATaskNeedsThePermission`.

### test_inbox_chat (27), test_inbox_kitt (21)

| Python | Java |
|---|---|
| `StartTask` (6), `Answer` (2), `Lists` (2) | `ChatActionsTest` (`aTaskIsQueuedLikeAPageTask...`, `autoAndUnknownRepo`, `startingATaskNeedsThePermission`, `theDailyCapRefusesClearly...`, `theCapDefaultComesFromConfig`, `switchingDiscordOffStopsTasks`, `anAnswerContinuesTheTaskLikeThePage`, `notWaitingUnknownAndForbidden`, `stateListsRunningWaitingAndQueued`, `jobsAreListedAndRunNow`) |
| `Approval` (10), `Forbidden` (2) | `ChatActionsTest` (`aRequestAloneApprovesNothing` ... `theCapAppliesAtConfirm`, `theConfigCannotGrantTheForbiddenActions`, `notEvenWithEveryPermissionListed`) |
| `Door` (5) | `ChatDoorTest`, and over real HTTP (valid, missing and wrong token, non-loopback refused) `ChatDoorHttpTest` |
| kitt `Door` (5), `Approvals` (4), `Conversation` (3), `JobsAndLogs` (3) | `KittDoorTest` (`onlyKittsAccountActingForChris`, `theRealPeerUidIsTheOwnerOfTheConnection`, `unknownPathsAndNoForbiddenRoutes`, `theStateFollowsThePermissionListAndTheSwitch`, `theBellListsAndApprovesAsKitt`, ..., `healthChecksAreSortedAndWordsMapped`), `ProcPeersTest`; over real HTTP with the production `ProcPeers` (account, socket owner, header, owner login, non-loopback refused) `KittDoorHttpTest` |
| kitt `Gate` (5) | `KittGateTest` |
| kitt `Page.test_switch_form_and_route` | `KittDoorTest.theKittSwitchIsOnThePageAndOffStopsTheApp` (the form is frontend) |

### test_runner_* (route 9, round 10, review 9, draft 8, contract 10)

The runners are Python processes (H-025) and stay Python. The Java side reads their output:
`RouteRunnerOutputTest` (route), `RoundLimitsTest` (round events), `BriefsTest.theReviewReadsTheRunnersResult` (review),
`DraftRunnerOutputTest` (draft), `RunnerServiceTest` (the Java runner service contract). Process-level cases (arguments,
exit 2, no prompt or token in the command line, fixtures, `test_runner_contract`) stay Python: they test the scripts.

## Everything else

| Python file | Cases | Java |
|---|---|---|
| `test_inbox_conversation` | 18 | `ConversationStoreTest` (8), `ConversationImporterTest`, `ChatDoorTest`, `test_inbox_m4_parity` (Python against Java). Python by design: `python_holds_no_copy_of_the_store`, `without_the_backend...fails_closed`, `the_door_answers_503...` (the loopback client) and the `Speed` check |
| `test_inbox_session`, `test_inbox_ssh_api` | 15 + 7 | `frontdoor/` (`FrontDoorTest`, `CsrfTest`, `TailnetAccessTest`, `FrontDoorOffTest`), K8 table in `docs/inbox-backend.md` |
| `test_inbox_memory_api`, `test_inbox_memory_writes`, `test_inbox_skills_api`, `test_inbox_m5_parity` | 5 + 6 + 2 + 7 | `MemoryReaderTest`, `MemoryWriterTest` |
| `test_inbox_jobs_writes`, `test_inbox_m6_parity` | 8 + 7 | `JobsWriterTest` |
| `test_inbox_m1_parity`, `test_inbox_overview_api`, `test_inbox_ported_pages` | 6 + 4 + 11 | `read/` tests and the parity runs against Python (`tests/test_inbox_m1_parity.py`); page serving is the route's |
| `test_inbox_contract`, `test_inbox_openapi`, `test_inbox_routes`, `test_inbox_facade`, `test_parity` | 57 + 4 + 6 + 15 + 18 | Python by design: the HTTP contract of the Python server, the OpenAPI/route check and the facade that forwards to Java; `proxy/ProxyControllersTest`, `DeadUpstreamTest` test the Java side |
| `test_inbox_forge`, `test_inbox_search`, `test_search_index`, `test_search_query`, `test_llm_router`, `test_discord_bot` | 14 + 13 + 13 + 17 + 37 + 35 | Python by design: not part of the inbox core (forge proxy, search, LLM router, Discord bot stay Python services) |

## Not tested anywhere yet

- `Sessions.stream`, `Sessions.route`, `Sessions.plan`, the real GitHub client and the real ntfy `Push` were interfaces
  with shadow implementations only (H-026). `LiveEffects` (H-028 W1) wires them, tested with a fake command runner
  (`LiveEffectsTest`, `EffectsSwitchTest`); the real Claude, `gh` and ntfy are never called by the test suite.
- The Python tests that compare both servers (`test_parity`, `test_inbox_m*_parity`) need a running Java backend; they are
  not run by CI. They are run against the staging unit (H-028 S1).

## Built UI and static files (H-028 W5a)

| Python route (`server.py`) | Java test (`core/UiControllerTest`) |
|---|---|
| `/`, `/memory`, `/search`, `/skills`, `/ssh` (`ported_index`; 503 without a build) | `thePagesAreTheBuiltIndex`, `thePagesAre503WithoutABuild` |
| `/memory/e/{id}` (only for an entry that exists) | `aMemoryEntryHasItsPageOnlyWhenItExists` |
| `/app/*` (`dist_asset`, never leaves `frontend/dist`) | `builtAssetsComeFromDistAndNeverLeaveIt` |
| `/static/*` (`web/`: css, js, woff2) | `staticFilesAreTheStylesheetAndFontsOfWeb`, `theStylesheetAlsoComesFromTheJarWhenNoDirectoryHasIt` |

## Read routes, memory, session, ssh, forge, search, health (H-028 coverage check)

| Python route (`server.py`) | Java test |
|---|---|
| `GET /api/overview`, `GET /api/skills` | `read/ReadControllerTest.overviewAndSkillsAreJsonReads` (status, content type, no-store); the data: `read/` tests and `tests/test_inbox_m1_parity.py` |
| `GET /api/live` | `ReadControllerTest.liveHasItsQueueAndAnActivitySentence` |
| `GET /api/memory[?q=]`, `GET /api/memory/e/{id}` | `ReadControllerTest.theMemoryListTakesAQueryAndAnUnknownEntryIs404`; the data: `MemoryReaderTest` |
| `GET /api/log/{t\|r}{id}[?from=]`, `GET /api/log/live` (forge) | `ReadControllerTest.aLogIsReadByTaskOrRunAndAnythingElseIsNotFound` (the 400 for `from`, negative `from`); also `DraftRunControllerTest.apiLogReadsATaskAndARunLog`; the forge half: `ProxyControllersTest` |
| anything else under `/api/` (404 for GET and POST, 405 for PUT, PATCH and DELETE) | `ReadControllerTest.anythingElseUnderApiIsNotFoundOrNotSupported`, `FrontDoorTest.aPutOutsideTheApiIsUnsupported` |
| `GET /api/session` | `FrontDoorTest.sessionReturnsTheTokenOfThePages`, `sessionIsNotCachedAndIsForGetOnly`, `theBoxItselfDoesNotGetTheToken` |
| `GET /api/ssh`, `POST /api/ssh` | `FrontDoorTest` (`theSshPageIsNotRenderedByTheServer`, `postRequestsAKey`, `postRefusalIsA400WithTheMessage`, `postWhenHomelabAdminDoesNotAnswerIsA400`, `postWithoutTheTokenDoesNotAskHomelabAdmin`) |
| `GET /api/forge`, `POST /api/forge/(start\|stop)` | `ProxyControllersTest.forgeState`, `forgeStateReadsAStatusWordAndUnreachableIs502`, `forgeControl`; `DeadUpstreamTest.forgeIsNotReachable` |
| `GET /api/search/filters`, `GET /api/search/results`, `POST /api/search/ask`, `POST /api/search/feedback` | `ProxyControllersTest.searchFiltersAndResultsPassThrough`, `searchErrorsAreFlattenedAndUpstreamTroubleIs502`, `searchAsk`, `searchFeedback`; `DeadUpstreamTest.searchIsNotReachable` |
| `GET /api/backend/health` | `HealthControllerTest.healthAnswersOkAsJson` |
| Access check, CSRF and Origin on every route; the CSP; refusals for the box itself | `frontdoor/TailnetAccessTest`, `CsrfTest`, `FrontDoorTest` (`aMissingTokenIs403OnEveryMutatingMethod`, `aWrongOriginIs403EvenWithTheToken`, `everyAnswerCarriesPythonsContentSecurityPolicy`), `FrontDoorOffTest` (off by default) |

## Doors, connector and gate (H-028 W4 and W5)

| Python | Java test |
|---|---|
| `POST /api/chat/action` (`chat_door`: bearer token per client, loopback only) | `door/ChatDoorHttpTest` (`aValidTokenReachesTheAction`, `theTokenIsPerClient`, `aMissingOrWrongTokenIs401`, `anAddressThatIsNotLoopbackIsRefusedEvenWithAValidToken`), `ChatDoorTest`, `ChatActionsTest` |
| `/api/chat/entries`, `/api/chat/state` (data in the conversation store) | `ChatDoorTest` (through `ChatDoor`), `ConversationStoreTest`; **no HTTP test of `ConversationController`**: see Gaps |
| kitt door: `/api/clients/kitt`, `/api/conversation`, `/api/logs*`, `/api/approvals*`, `/api/jobs*`, `/api/health` (peer uid, loopback only) | `door/KittDoorTest`, `ProcPeersTest`, and over real HTTP `KittDoorHttpTest` (`theAccountThatOwnsTheConnectionActingForChrisIsLetIn`, `anotherAccountOnTheSameMachineIsRefusedByTheSocketOwner`, `theClientHeaderAndChrisIdentityAreChecked`, `anAddressThatIsNotLoopbackIsRefusedEvenWithEveryHeader`, `aCallWithoutTheClientHeaderGoesOnToTheNormalChecks`) |
| both doors refused on the tailnet connector | `PublicConnectorTest.theChatDoorStaysRefusedOnIt`, `theKittDoorStaysRefusedOnIt`, `pathVariantsOfTheChatDoorAreRecognised` |
| `gate.py` (device check, forwarding to `KITT_UPSTREAM`) | `door/KittGateTest` (owner passes and forged headers are dropped, another tailnet user and the box itself refused, an unknown peer refused, kitt down is a 502, off by default, a wildcard host keeps it off, LAN refused) |
| the tailnet connector (`inbox.public.port`; no Python equivalent: Python binds `INBOX_HOST`) | `PublicConnectorTest` (second connector answers, pages behind the front door, a missing interface stops the start, an address outside the tailnet range refused, bad ports), `PublicConnectorOffTest` (no value, no second connector) |
| `ShadowModeTest`: in shadow no task, merge or notification starts | `core/ShadowModeTest`, `door/ShadowDoorTest`, `EffectsSwitchTest` (the scheduler starts only with `core.effects=live` and `core.scheduler.enabled=true`) |

## Gaps

Routes or behaviour of the Python inbox with no Java test, or no Java route, as of this check. They are listed, not
hidden; each should be closed or accepted before S1 (staging) or X1b (cut-over).

| Gap | What is missing | Effect |
|---|---|---|
| `POST /memory/add`, `POST /memory/e/{id}/(save\|delete)` | **No public Java route.** `MemoryController` serves only `/internal/memory/*` (called by the Python server), and `MemoryWriterTest` tests the writer, not an HTTP route. After the cut-over the memory forms of the page would get a 404. | Port the three routes (and test them with the front door and CSRF) before X1b; until then the cut-over loses editing memory from the page. |
| `GET /api/chat/entries`, `GET /api/chat/state`, `POST /api/chat/entries` over HTTP | `ConversationController` takes the client from the `X-Inbox-Client` header behind the facade secret; the bearer-token check is Python's `chat_door`. `ChatDoorController` serves only `/api/chat/action`. No Java test sends these three with a bearer token. With the front door on, the Discord bot's calls from loopback would be refused as "the box itself". | Serve the three paths through the chat door (bearer token, loopback only) and add them to `ChatDoorHttpTest` before X1b; check it on staging with the Discord bot's contract cases. |
| `GET /api/overview`, `/api/live`, `/api/skills`, `/api/memory*` data | `ReadControllerTest` covers status, content type and errors with an empty data directory. Content parity is a Python run (`tests/test_inbox_m1_parity.py`, `backend/parity/parity.py`), not run by CI. | Run the parity harness against the staging unit. |
| Pages `/skills`, `/search`, `/memory` forms in HTML | Frontend (`frontend/`), not Java; covered by the TypeScript tests there. | None. |
| Runner processes, `bin/homelab-inbox-task`, search, router, forge service, Discord bot | Python by design (see "Everything else"); not in the Java inbox. | None. |
