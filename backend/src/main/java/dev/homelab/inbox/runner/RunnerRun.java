package dev.homelab.inbox.runner;

import java.util.concurrent.CompletableFuture;

/** A started runner: wait for {@link #result()} or {@link #cancel()} it. */
public interface RunnerRun {

    /** Completes when the process has ended and its output is read. Never completes exceptionally. */
    CompletableFuture<RunnerResult> result();

    /** Stop the runner (kill action, then the process group). Does nothing once it has ended. */
    void cancel();
}
