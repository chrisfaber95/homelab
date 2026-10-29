---
paths:
  - "llm/**"
  - "bin/llm"
  - "bin/llm.cmd"
  - "config/llm.conf"
  - "scripts/17-llm.sh"
---

# Testing the LLM helper (F-017)

- Test without root with your own `ollama serve` on another port (`/dev/nvidia*` is 0666) and the helper with
  `OLLAMA_URL`/`LLM_PORT`/`LLM_DB`.
