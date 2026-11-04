---
name: fix-flaky-tests
description: "Procedure for fixing flaky tests: reproduce the intermittent failure by repeated and isolated runs, find the root cause (shared state, order dependence, time, unawaited async work, randomness, network, resource contention), fix it properly instead of adding retries or sleeps, and prove the fix with many runs. Followed by the test-automator agent; in a main session, delegate flaky test fixes to that agent."
argument-hint: "<flaky test, file or suite>"
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep]
---

Fix the flaky test: $ARGUMENTS

Wherever a step says to ask the user: in a main session, ask. When running as the
test-automator subagent, state the assumption and return the question.

## Steps

1. Reproduce before changing anything. Run the failing test alone several times, then the whole
   file, then the whole suite, and record how often it fails in each. Use the runner's options
   for repetition, ordering and parallelism (for example Jest `--runInBand`, `--randomize`
   where supported, `--testNamePattern`; Vitest `--sequence.shuffle`, `--repeat`; Playwright is
   out of scope). Note the failure message and the first failing line each time.
2. Read the failure and the test with the code under test. Classify the cause from the pattern:
   - fails only in the full suite or after another test: shared state, leaked data, a global
     or module cache, a mock not reset, a database row left behind, a timer left running
   - fails only in parallel: shared resource such as a port, a file, a table or a temp folder
   - fails at certain times or dates: real time, time zones, `Date.now()`, expiry logic
   - fails randomly even alone: unawaited promises, a missing `await`, event ordering, a race
     between the test and background work, random or unordered data, a real network call
   - slow machines fail: tight timeouts and sleeps
3. Fix the root cause:
   - isolate state: create data per test, reset mocks and modules in `beforeEach` or
     `afterEach`, close servers and connections, use unique ports or let the OS pick one
   - await everything the test starts, and wait for a condition (an event, a promise, a
     polled assertion with a timeout) instead of a fixed sleep
   - control time and randomness with fake timers, an injected clock, or a seed
   - replace real network and services with stubs
   - make assertions order-independent when the order is not part of the behavior
4. Do not hide flakiness: no blind retries, no longer sleeps, no `skip`, no loosened assertion
   that stops testing the behavior. If you must quarantine a test, say so explicitly, keep it
   visible in the output, and give the reason and a way back.
5. If the flakiness comes from a real race in the production code (a genuine bug), report it as
   a finding with the failing evidence rather than hiding it in the test.
6. Prove the fix: run the test many times (for example 20 to 50 repetitions) alone, in the full
   suite, in shuffled order if the runner supports it, and in parallel if that is how it runs in
   CI. Compare with the failure rate you recorded in step 1.
7. Report: the cause with evidence, the change made, the failure rate before and after with the
   commands used, any production bug found, and anything still unstable.
