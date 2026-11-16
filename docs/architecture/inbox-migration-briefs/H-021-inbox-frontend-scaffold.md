---
id: H-021
title: "Inbox migration 2: TypeScript frontend scaffold, served by the Python inbox"
status: draft
priority: should
repos: [homelab]
build:
  kit:
    - {agent: frontend-developer}
---
## Problem
Every inbox page is an HTML string in `pages.py` plus `web/app.js`. A page cannot move to TypeScript until there is a frontend project, a build, CI and a way for the Python server to serve it next to the old pages.

## Outcome
`frontend/` (Vite, plain TypeScript, no UI framework) builds in CI and is installed on the box; the Python inbox serves it for pages that are marked ported and still renders the rest itself. No page is ported yet, so the user sees no change.

## Acceptance criteria
- [ ] `frontend/` has a pinned Vite and TypeScript build, a test runner, and types generated from `backend/openapi.yaml`; lint, typecheck, tests and build run in CI on every PR.
- [ ] `GET /api/session` returns the CSRF token; mutating `/api/*` requests need it as `X-CSRF-Token`; errors are `{"error": "..."}`. Existing pages keep working.
- [ ] A `PORTED_PAGES` setting makes the Python server serve `frontend/dist/index.html` for those paths (empty at first); other paths are unchanged, URLs and notification links stay valid.
- [ ] The install script builds `frontend/dist` without running npm as root (unprivileged build, `npm ci --ignore-scripts`) and checks the result itself; it carries a `# homelab-rerun:` line.
- [ ] `docs/dashboard.md` and `CHECKLIST.md` describe it.

## Tasks
- **F1** scaffold and CI (`frontend-developer`). Depends on: H-020 C1; decision Q4 (dev dependencies).
- **F2** `/api/session` and the JSON conventions in Python (`backend-developer`). Depends on: H-020 C1.
- **F3** install and serve `frontend/dist` with `PORTED_PAGES` (`backend-developer`; `change-reviewer` first). Depends on: F1, F2; decision Q3 (who runs npm). Needs the user to merge (root script).

## Out of scope
Porting pages (H-022); any Java.

## Open questions
Q3 and Q4 of `docs/architecture/inbox-split.md`.
