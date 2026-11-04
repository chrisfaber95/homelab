---
name: write-tests
description: "Procedure for writing unit, integration and API tests with the project's existing runner: understand the behavior, reproduce bugs with a failing test first, mock at the boundaries, test behavior rather than implementation, keep tests deterministic, and report the real run. Followed by the test-automator agent; in a main session, delegate test writing to that agent."
argument-hint: "<code, behavior, bug or change to test>"
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep]
---

Write tests for: $ARGUMENTS

Wherever a step says to ask the user: in a main session, ask. When running as the
test-automator subagent, state the assumption and return the question. Tests must not use live
databases, payment providers or mail servers.

## Steps

1. Run the existing tests for the package and record the baseline. Find two or three existing
   tests that look like the ones you will write and copy their structure, naming, helpers and
   mocking approach. If there is no test setup at all, stop and report it; set up a runner only
   if the request covers it (then pick the runner that fits the runtime and the project, such as
   the built-in `node:test`, Vitest or Jest, and add a working `test` script).
2. Understand the behavior to cover from the code, the request, and any spec or documentation:
   inputs, outputs, side effects, errors, and the business rules that matter. List the
   behaviors before writing tests: the normal case, each important rule, the error paths, and
   the boundaries that matter. Respect the project's stated limits, for example no extreme edge
   cases.
3. For a bug: write the test first and run it to see it fail for the right reason (the symptom
   from the report, not a typo). Report the failing output. If you are also asked to fix the
   bug, make the smallest fix and show it pass; otherwise stop at the failing test and say so.
4. Structure each test as arrange, act, assert. One behavior per test, named for the behavior
   ("rejects an order with an empty cart"), not the function. Test through the public interface
   and the result a caller sees; do not assert on private details or call counts that would
   break on a harmless refactor.
5. Isolation and determinism: each test sets up its own data and cleans up after itself, does
   not depend on test order, and does not share mutable state. Control time with fake timers or
   an injected clock, randomness with a seed or a stub, and never use real sleeps or depend on
   the network.
6. Boundaries: replace the database with a test database or an in-memory substitute that the
   project already uses; replace payment providers, mail transports, HTTP clients and the file
   system with mocks or stubs at the module boundary. Assert on what was sent to them (the
   payment request, the email recipients and subject). Use obviously fake data.
7. For HTTP APIs, test through the route (for example with `supertest` if the project uses it,
   or the framework's test client) covering success, validation errors, unauthorized and
   forbidden, not found, and a server error path, and check status codes and response shapes.
8. Keep the tests readable: small helpers and factories for fixtures instead of long copied
   setup, clear failure messages, and no clever logic inside tests.
9. Run the new tests, then the whole package suite, and lint if configured. Run the new tests
   more than once to catch order or timing dependence. Report the exact commands and results.
10. Report: files written, behaviors covered and deliberately not covered, any bug found (with
    the failing test), the baseline and final results, and anything that could not be tested
    without live services or a testability change.
