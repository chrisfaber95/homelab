package dev.homelab.inbox.door;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the door needs from parts of the inbox that other H-026 tasks port (briefs: K4, jobs and research: K6, the
 * private search): interfaces, so the door can be tested and run in shadow without any of them. Each has a shadow
 * implementation in {@link ShadowPorts} that starts nothing; the real ones are wired when those tasks land.
 */
public final class Ports {

    private Ports() {}

    /** A brief on main of the product repo: only what the door shows and checks. */
    public record Brief(String id, String title, String status) {}

    /** The briefs of the product repo ({@code briefs.py}). */
    public interface Briefs {
        /** A brief read fresh (no cache); empty when there is none with this id. */
        Optional<Brief> byId(String id);

        /** All briefs on main. */
        List<Brief> all();

        /** Whether a build of this brief still counts ({@code live_build}). */
        boolean liveBuild(String id);

        /**
         * Approve a draft (a commit to the product repo) and queue its build task; returns the task id. Throws
         * {@link IllegalStateException} with the reason when it cannot ({@code start_build}'s ValueError).
         */
        long startBuild(String id, boolean unattended, String who, String source);
    }

    /** A job file as the door shows it; {@code error} is set for a job that does not parse. */
    public record JobInfo(String name, String error, String kind, String when, String schedule, String model,
                          String deliver, boolean paused, dev.homelab.inbox.read.Cron cron) {

        public boolean usable() {
            return error == null;
        }
    }

    /** A row of {@code job_runs}. */
    public record RunInfo(long id, String job, String status, String created, String started, String ended, Long taskId,
                          String summary, String note) {}

    /** Job files and runs ({@code jobs.py}). */
    public interface JobsPort {
        Map<String, JobInfo> jobs();

        /** Newest first; {@code name} null for all jobs. */
        List<RunInfo> runs(String name, int limit);

        Optional<RunInfo> run(long id);

        /** Start a manual run of the job now ({@code dispatch(job, now, manual=True)}); returns the run id. */
        long dispatch(String name);

        /** Pause or resume: the job file is changed and committed. {@link Unavailable} when that cannot be done. */
        void pause(String name, boolean paused, String message);

        /** One research question ({@code ask_research}): status and body as the page gets them. */
        Result research(String question, String mode, String source);
    }

    /** The private search ({@code search.ask}): {@code found}, {@code answer}, {@code citations}. */
    public interface Search {
        Result ask(String question);
    }

    /** The monitor's checks ({@code overview.health()["checks"]}): name to {@code {status, text}}. */
    public interface Health {
        Map<String, Map<String, Object>> checks();
    }

    /** The log of a task or run: lines of {@code logs/<key>.log} from line {@code start}, at most 500. */
    public interface Logs {
        Chunk read(String key, int start);
    }

    public record Chunk(List<Map<String, Object>> lines, int next) {}

    /** Whoever the door asks is the owner of the tailnet and the tailnet identity of a peer ({@code Access}). */
    public interface OwnerCheck {
        /** The owner's login name, or null when it is not known (then nobody is let in). */
        String owner();

        /** Whether this tailnet address belongs to the owner's devices, and the device name to show. */
        Verdict allowed(String ip);
    }

    public record Verdict(boolean ok, String who) {}

    /** The user ids of the connection and of kitt's account ({@code kitt.peer_uid}, {@code kitt.account_uid}). */
    public interface Peers {
        /** The user id of kitt's service account, or empty when its unit is not running. */
        java.util.OptionalLong accountUid();

        /** The user id that owns the loopback connection from {@code ip:port} to our {@code serverPort}. */
        java.util.OptionalLong peerUid(String ip, int port, int serverPort);
    }

    /** The job file could not be changed (backend down, bad state): not the same as a bad request. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
