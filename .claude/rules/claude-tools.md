---
paths:
  - "config/claude.conf"
  - "scripts/18-claude.sh"
  - "claude/**"
---

# Claude's skills, agents and plugins

- Pinned in `config/claude.conf`, installed by `scripts/18-claude.sh` (run by `chris`).
- New commit of a skill or plugin: read all its files first.
- Only plugins without hooks or scripts; no security-guidance or claude-security (they call the Claude API
  themselves; the user's choice).
- The agents in `claude/agents/` are read-only.
