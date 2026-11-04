---
name: harden-service
description: "Procedure for making a backend service safer and easier to run: validated configuration, secrets handling, structured logging without sensitive data, health checks, graceful shutdown, timeouts and limits, CORS and security headers, rate limiting, and error handling, applied surgically. Followed by the backend-developer agent; in a main session, delegate service hardening to that agent."
argument-hint: "<service or concern to harden>"
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep]
---

Harden: $ARGUMENTS

Wherever a step says to ask the user: in a main session, ask. When running as the
backend-developer subagent, state the assumption and return the question. Change only what the
request covers. For everything else you find, report it; do not fix it.

## Steps

1. Review the current state before changing anything and list what is missing or risky. Check:
   configuration, secrets, logging, health endpoints, shutdown, timeouts, limits, CORS, headers,
   rate limiting, error handling and dependency health.
2. Configuration: read all settings from environment variables in one place, validate them at
   startup and fail fast with a clear message when something required is missing. Keep an
   `.env.example` with placeholders; real `.env*` files stay out of git. Distinguish test and
   production settings; never let a test setting point at a live service.
3. Secrets: none in code, in logs, in error messages or in client-visible responses. If you find
   a committed secret, report it for rotation; removing it from the file does not undo exposure.
4. Logging: structured logs with a level and a request or correlation id. Log events and ids, not
   passwords, tokens, card data or full personal data. Log errors once, at the place that
   handles them.
5. Health: a cheap liveness endpoint, and a readiness endpoint that checks the dependencies the
   service cannot work without (for example the database). Do not expose internals on public
   health responses.
6. Shutdown: on `SIGTERM` and `SIGINT`, stop accepting new connections, let in-flight requests
   finish within a deadline, close the database pool and other connections, then exit. Make
   sure unhandled rejections and uncaught errors are logged and lead to a controlled exit rather
   than a half-alive process.
7. Timeouts and limits: timeouts on every outbound call and database query, a maximum request
   body size, limits on upload size and type, and bounded concurrency for expensive work.
8. HTTP surface: an explicit CORS allow-list (not `*` for authenticated APIs), security headers
   (for Node/Express, the `helmet` package if the project uses or accepts it), rate limiting on
   login, signup, password reset, search and other expensive or abusable endpoints, and
   cookies marked `HttpOnly`, `Secure` and `SameSite` where used.
9. Errors: one central error handler that returns a consistent safe response, maps known
   errors to the right status, and never leaks stack traces or SQL. Unknown errors become a
   generic `500` and are logged with their cause.
10. Dependencies: run the package manager's audit and report high and critical findings; do not
    upgrade as part of hardening unless the request says so.
11. Verify: run the existing tests and lint, add tests for new behavior where practical (for
    example the validation of config, the health endpoints, the error handler), and start the
    service locally to exercise startup, a health check and shutdown if possible.
12. Report: what you changed, what you found but left alone (ranked by risk), the verification
    results, and anything that needs a decision from the owner.
