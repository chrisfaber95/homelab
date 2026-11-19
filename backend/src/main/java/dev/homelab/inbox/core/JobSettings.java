package dev.homelab.inbox.core;

import java.time.ZoneId;

/** The settings of jobs and research ({@code config/inbox.conf}): the daily cap of Claude runs ({@code JOB_CLAUDE_CAP}),
 * the limits of a quick research run and of a deep one (those of an inbox task), the time zone of schedules. */
public record JobSettings(int dailyCap, int quickMinutes, int quickTurns, int maxMinutes, int maxTurns, int localMaxChars,
                          ZoneId tz) {

    public static final int LOCAL_MAX_CHARS = 9000; // fits the context of the local model with the instruction

    /** {@code research_limits}: {minutes, turns}; quick has its own small limits, deep those of an inbox task. */
    public int[] researchLimits(String mode) {
        return "quick".equals(mode) ? new int[] {quickMinutes, quickTurns} : new int[] {maxMinutes, maxTurns};
    }

    public JobSettings withDailyCap(int cap) {
        return new JobSettings(cap, quickMinutes, quickTurns, maxMinutes, maxTurns, localMaxChars, tz);
    }
}
