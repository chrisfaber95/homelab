---
name: build-endpoint
description: "Procedure for implementing a server-side endpoint or use case: follow the project's layering, validate input, check authorization, keep business logic out of handlers, use parameterized queries and transactions, return consistent errors, and add tests with mocked external services. Followed by the backend-developer agent; in a main session, delegate backend implementation to that agent."
argument-hint: "<endpoint or use case to build>"
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep]
---

Implement: $ARGUMENTS

Wherever a step says to ask the user: in a main session, ask. When running as the
backend-developer subagent, state the assumption and return the question.

## Steps

1. Read the contract: the API design or spec if there is one, otherwise the request. Note the
   inputs, outputs, error cases, who may call it, and which data it touches. If the request and
   the spec disagree, report it instead of choosing silently.
2. Read two or three existing endpoints of the same kind and copy their structure: where the
   route is registered, how handlers are named and split, how services and data access are
   called, how errors are produced, how config is read.
3. Validate input at the boundary with whatever validation the project uses (a schema
   library, middleware, or explicit checks). Reject unknown or malformed input with a clear
   `4xx`. Never trust prices, totals, roles or ids supplied by the client for anything the server
   can compute or look up.
4. Authenticate and authorize: use the project's existing middleware. Check the caller may act
   on this specific object (a user may only read their own orders), not only that they are
   logged in.
5. Keep the handler thin: parse, call a use-case function, shape the response. Put business
   rules in a service or use-case function that does not know about HTTP, so it can be tested
   without a server. Put database access in the data layer.
6. Data access: parameterized queries or the project's query builder, never string-built SQL.
   Wrap multi-step writes that must succeed or fail together in a transaction. Filter, sort and
   paginate in the database, not in memory, and bound every list with a maximum size.
7. External calls (payments, email, other services): keep them on the server, set timeouts,
   make retried operations idempotent, and do slow work (sending email, image processing)
   outside the request path if the project has a way to do that.
8. Errors: use the project's error format and status codes. Log the cause with enough context
   to debug (request id, ids, not personal data or secrets); return a safe message to the
   caller with no stack trace or internals. Handle async errors so they cannot crash the
   process or leave a request hanging.
9. Tests, with the project's runner: unit tests for the use-case logic, plus an HTTP-level test
   of the endpoint covering success, validation failure, unauthorized and forbidden, and the
   main error path. Use a test database or fixtures and mock external services. If there is no
   test setup, say so and write the tests you would add; set up a runner only if the request
   covers it.
10. Verify: run the new tests, the existing suite, lint, and start the server to call the
    endpoint locally if that is possible against a test database. Report exactly what ran.
11. Update the API docs or spec the project keeps for this endpoint if the request covers it,
    otherwise list it under follow-ups.
12. Report: files changed, behavior added, test results, assumptions, and any contract or schema
    needs for others.
