# Briefs: design, build, review (H-001)

From an idea to merged PRs without writing tickets or prompts yourself. The brief is
`homelab/briefs/H-001-designer-and-product-repo.md` in the private repo `chrisfaber95/product`.

```
you ──interview──> designer (~/dev/product) ──PR──> product repo ──you merge = approved
                                                         │
homelab page, tab Briefs: Build ──> planner (no tools) ──┘ splits the brief into 1..PLAN_MAX_TASKS inbox tasks
                                       │
               inbox task per repo ──> PR ──> brief reviewer ──miss──> one automatic rework round ──> PR updated
                                                    └── pass (or after the rework) ──> notification to you
               all tasks merged ──> PR in the product repo: status handed-off, PRs on the roadmap
```

## 1. Design

On the box, as `claude`: `cd ~/dev/product && claude`. The repo's `CLAUDE.md` and `.claude/` make that session the
designer: it reads the project's vision, roadmap and briefs and the second brain, asks two or three multiple-choice
rounds, and writes a brief with testable acceptance criteria. For questions about the code it asks the `feasibility`
subagent, which answers from the code map. Approve, and it opens a PR `brief/H-NNN`; **merging that PR approves the
brief**. Projects: `homelab`, `claude-dashboard`, `visual-shopping` (one folder each). New ids are `H-NNN`; `F-NNN`
came from team-hq and keep their ids.

## 2. Build

Tab **Briefs** on the homelab page (`/briefs`) lists the approved briefs on `main` of `PRODUCT_REPO`. **Build** starts
a build: a task with the pseudo-repo `brief`. Nothing starts without that button.

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

## 4. Handoff

When all tasks of a build are merged, the inbox opens a PR in the product repo (`handoff/<id>`) that sets the brief to
`status: handed-off` and adds the PRs to its roadmap line. Merge it to close the brief.

## Settings (`config/inbox.conf`)

`PRODUCT_REPO`, `PLAN_MAX_TASKS`, `REVIEW_REWORK_ROUNDS`, `REVIEW_MAX_DIFF`. The inbox's GitHub token covers all
repositories, so it can read the product repo and open the handoff PR; the product repo must stay private.
