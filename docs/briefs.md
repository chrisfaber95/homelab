# Briefs: design, build, review (H-001)

From an idea to merged PRs without writing tickets or prompts yourself. The brief is
`homelab/briefs/H-001-designer-and-product-repo.md` in the private repo `chrisfaber95/product`.

```
you ──interview──> designer (~/dev/product) ──PR, merged by the designer──> product repo: status draft
                                                                                   │
homelab page, tab Briefs: read it, Approve & build (or ... without me) ──> status approved
                                                                                   │
                                     planner (no tools) <──────────────────────────┘ splits the brief into 1..PLAN_MAX_TASKS inbox tasks
                                       │
               inbox task per repo ──> PR ──> brief reviewer ──miss──> one automatic rework round ──> PR updated
                                                    └── pass (or after the rework) ──> notification to you
                                                    └── build without me: all criteria met + CI green ──> the inbox merges
               all tasks merged ──> PR in the product repo: status handed-off, PRs on the roadmap
```

## 1. Design

On the box, as `claude`: `cd ~/dev/product && claude`. The repo's `CLAUDE.md` and `.claude/` make that session the
designer: it reads the project's vision, roadmap and briefs and the second brain, asks two or three multiple-choice
rounds, and writes a brief with testable acceptance criteria. For questions about the code it asks the `feasibility`
subagent, which answers from the code map. When you're done, it opens a PR `brief/H-NNN` with `status: draft` and
merges it itself (since 2026-10-30). **Approving happens on the page** (section 2), not by merging. Projects: `homelab`, `claude-dashboard`, `visual-shopping` (one folder each). New ids are `H-NNN`; `F-NNN`
came from team-hq and keep their ids.

**Build kit** (skills and agents for the build). Before the brief is written, the designer asks one more round: which
skills and agents the build should have, from Chris's own marketplace (`chrisfaber95/claude-agent-skill-builder`),
Anthropic's skills and plugins (`anthropics/skills`, `anthropics/claude-plugins-official`), and one or two skills it
writes for this brief. For a new project it also asks the repo name and private or public. The choices go into the
brief as a `build:` block (`new_repo`, `visibility`, `kit`). After merging the brief, its `build-setup` subagent
creates a new repo if needed and opens a PR `setup/H-NNN` in each repo of the brief. That PR only adds `CLAUDE.md`,
`.claude/kit.md`, `.claude/agents/*.md` and `.claude/skills/<name>/`, each copied from a source pinned to a commit.
Nobody merges it by hand: the build does (section 2).

## 2. Build

Tab **Briefs** on the homelab page (`/briefs`) lists the briefs on `main` of `PRODUCT_REPO`: **To approve** (`draft`),
**Approved**, and **Needs design** (no buttons; that needs another designer session). Click an id to read the brief.

- **Approve & build** on a draft commits `status: approved` to the brief on `main` (the commit says it was approved on
  the page, by which device) and starts a build: a task with the pseudo-repo `brief`. On an approved brief the
  button is just **Build**.
- **Approve & build without me** does the same, as a build without you (below).
- **Park** commits `status: parked`; the brief leaves the page.

When the build starts, the inbox first adds a new project's repo (`build.new_repo`, only under `OWNER_GITHUB`) to its
own repo list (`new-repos.json` in its data folder, next to `config/repos.conf`). Then it merges each open setup PR
(`setup/<id>`) of the brief's repos: approving the brief approves that PR. A setup PR that changes anything other than
`CLAUDE.md` and `.claude/` agents, skills and `kit.md`, or that comes from a fork, stops the build with a notification.
Deploying a new project on the box still takes a line in `config/repos.conf` (and a unit or compose file).

Nothing starts without one of those buttons.

- The **planner** is Claude without tools (the same route as the router of "auto"). It gets the brief and the
  profiles of the brief's `repos` (only those in `config/repos.conf` plus `homelab`) and returns up to
  `PLAN_MAX_TASKS` tasks, each with its own acceptance criteria and optionally `after`. Criteria only you can check
  (hardware, a browser check) are listed on the build as "Needs you". If the brief is unclear, it asks one question
  on the page, like any inbox task.
- Each task is a normal inbox task: same queue, limits, auto mode and PR. It gets the full brief as context; its PR
  shows only the task and criteria (the product repo is private, the target repo may be public).
- A task with `after` starts only when its predecessor's PR is **merged**, so it builds on that code. If the
  predecessor fails, is refused or its PR is closed, the task doesn't start.

## 3. Review

After every push of a brief task, the **brief reviewer** (Claude without tools) reads the task's criteria and the PR's
diff (up to `REVIEW_MAX_DIFF` characters) and comments on the PR with ✅/❌/❔ per criterion.

- A ❌ in the first review sends the task back once (`REVIEW_REWORK_ROUNDS`) before you hear about it.
- After that, or when everything passes, you get the notification with the verdict. The verdict is also on the task
  page. Your own PR comments send the task back as always; the reviewer checks every new push again but sends it back
  automatically only once.
- The reviewer's comments carry `<!-- homelab-inbox review -->` and never trigger a round themselves.

## Build without me

A build started with **… without me** carries that mode; its page says so and has **Stop: I merge the rest myself**,
which turns it into a normal build from then on.

- The inbox merges a task PR itself (`merge`, as its own token) when the last brief review found **every** criterion
  met and CI on the PR's head commit is green, or the repo has no CI. CI that hasn't finished after 30 minutes counts
  as failed. Your own PR comments still send the task back first.
- Verdicts: ✅ met, ❌ missed, 🧪 only running it shows (tests, lint, typecheck, build pass): CI decides, so the PR
  waits for you if the repo has no CI; ❔ can't be judged from the diff (behaviour on the box, in a browser): counts
  as not met, the notification names it.
- Merged: a notification with the PR, so you can revert. A task with `after` then starts by itself.
- **Still waits for you**, with the usual notification: a task on **homelab** (a merge there runs root scripts), a
  missed or unclear criterion after the automatic rework round, red CI, a merge GitHub refuses, a conflict with main
  that is still there after `CONFLICT_ROUNDS` automatic rounds (see `docs/inbox.md`). The other tasks of the build
  carry on.
- **One merge at a time per repo**: per GitHub poll the inbox merges at most one PR per repo, and only once GitHub has
  checked it against the current main. The next PR follows in a later poll.
- When all tasks are merged, the inbox merges the **handoff** PR too, unless the build has criteria only you can
  check ("Needs you"): then that PR waits for you.

## 4. Handoff

When all tasks of a build are merged, the inbox opens a PR in the product repo (`handoff/<id>`) that sets the brief to
`status: handed-off` and adds the PRs to its roadmap line. Merge it to close the brief.

## Settings (`config/inbox.conf`)

`PRODUCT_REPO`, `PLAN_MAX_TASKS`, `REVIEW_REWORK_ROUNDS`, `REVIEW_MAX_DIFF`. The inbox's GitHub token covers all
repositories, so it can read the product repo and open the handoff PR; the product repo must stay private.
