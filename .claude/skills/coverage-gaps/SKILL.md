---
name: coverage-gaps
description: "Procedure for finding important code that lacks tests: run the project's coverage tool if one exists, map untested logic by business risk rather than percentage, and propose or write the highest-value tests, reporting only the coverage numbers actually measured. Followed by the test-automator agent; in a main session, delegate coverage analysis to that agent."
argument-hint: "<package, folder or feature to analyze>"
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep]
---

Find coverage gaps in: $ARGUMENTS

Wherever a step says to ask the user: in a main session, ask. When running as the
test-automator subagent, state the assumption and return the question. Analysis first; write
tests only if the request asks for them.

## Steps

1. Establish what exists: the test runner, whether a coverage tool is configured (Jest
   `--coverage`, Vitest with `@vitest/coverage-v8`, `c8`, `nyc`, pytest-cov), and the baseline
   test results. If coverage tooling is missing, you can still map gaps by reading; installing
   a coverage tool is allowed only if the request covers it, as a dev dependency in the named
   package.
2. Measure if you can: run the project's coverage command in the package folder and keep the
   report in a temp folder or the tool's default output, which should already be git-ignored.
   Record the real figures per file (statements, branches, functions, lines). If you cannot run
   it, say the figures were not measured and work from reading only.
3. Map the code to the business behavior that matters, using the project's own list of what
   must be tested if it has one (for example persistence, price and cart calculation, order
   creation, payment flows and webhooks, authentication and permissions, search and filtering,
   error handling). For each important behavior, find the tests that cover it and judge whether
   they would actually fail if the behavior broke (read the assertions, not the percentage).
4. Rank gaps by risk: **critical** (money, authentication and authorization, data integrity,
   security rules, with no meaningful test), **important** (core business rules, error paths,
   input validation), **low** (glue code, simple getters, logging, generated code, extreme edge
   cases). Do not chase a percentage; a file at 100% with weak assertions is a worse state than
   a small file honestly marked untested.
5. For the top gaps propose the tests: what behavior, what inputs, what to mock at the
   boundary, and roughly how big. If the request asks you to write them, use the
   `write-tests` skill's approach and keep to the highest-value few.
6. Flag test-quality problems you saw: tests without assertions, assertions that cannot fail,
   everything mocked so nothing real is exercised, tests that depend on live services, and
   placeholder test scripts.
7. Report: the real coverage figures and how they were produced (or "not measured"), the
   ranked gaps with file and behavior, the proposed tests, test-quality problems, and what you
   chose not to cover and why.
