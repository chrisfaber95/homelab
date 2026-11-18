package dev.homelab.inbox.core;

/**
 * The limits and switches of the core. Defaults and names are those of {@code config/inbox.conf} and the Python
 * inbox ({@code MAX_RUNNING}, {@code MAX_PER_REPO}, {@code MAX_TURNS}, {@code MAX_MINUTES}, {@code CAPPED_KEEP_DAYS},
 * {@code RESTART_ROUNDS}). {@code schedulerEnabled} is the shadow switch ({@code core.scheduler.enabled}, default off):
 * while it is false the scheduler loop does not exist and {@link Scheduler} and {@link Recovery} do nothing.
 */
public record CoreSettings(boolean schedulerEnabled, int maxRunning, int maxPerRepo, int maxTurns, int maxMinutes,
                           int cappedKeepDays, int restartRounds) {

    /** Pseudo-repos: the router and the brief planner are short and touch no repo. */
    public static final String AUTO = "auto";
    public static final String BRIEF = "brief";

    public static CoreSettings shadowDefaults() {
        return new CoreSettings(false, 4, 3, 80, 30, 7, 2);
    }

    public CoreSettings withSchedulerEnabled(boolean enabled) {
        return new CoreSettings(enabled, maxRunning, maxPerRepo, maxTurns, maxMinutes, cappedKeepDays, restartRounds);
    }
}
