---
id: H-022
title: "Inbox migration 3: port the inbox pages to TypeScript, one page per PR"
status: draft
priority: should
repos: [homelab]
build:
  kit:
    - {agent: frontend-developer}
---
## Problem
After H-021 the frontend exists but every page still comes from `pages.py`.

## Outcome
Every inbox page is TypeScript on a JSON API and `pages.py` and `web/app.js` are gone. The result is useful on its own: stopping here is fine (decision Q8).

## Acceptance criteria
- [ ] Each page below works the same as before (same URL, same actions, same texts) and is served from `frontend/dist`: Skills, Memory, Overview, Jobs with the catalogue, Search, SSH, Tasks (task page, live log), Briefs.
- [ ] Each page has a JSON endpoint in the Python server that returns what `pages.py` rendered, listed in `backend/openapi.yaml`, with a test.
- [ ] The CSS and fonts are carried over unchanged first.
- [ ] A ported page's code is deleted from `pages.py` and `web/app.js` in the same PR.
- [ ] After the last page, `pages.py` and `web/app.js` are removed, docs and the CLAUDE.md map are updated and the existing tests pass.

## Tasks (in this order; each an "a" JSON-endpoint PR then a "b" page PR)
- **F4** Skills, **F5** Memory, **F6** Overview, **F7** Jobs and catalogue, **F8** Search (no "a"), **F9** SSH, **F10** Tasks, **F11** Briefs. Each "a" depends on H-021 F2; each "b" on H-021 F3 and its own "a". Different pages can run in parallel.
- **F12** remove `pages.py` and `web/app.js`. Depends on: all "b" tasks.

## Out of scope
New features, a UI framework, server-sent events (polling stays).

## Open questions
Q8: stop after F12 or continue to Java.
