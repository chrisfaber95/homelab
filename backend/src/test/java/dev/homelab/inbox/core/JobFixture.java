package dev.homelab.inbox.core;

import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Jobs.Job;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** What the job tests share: a clean database, the settings of the box, a parser and effects that record and answer
 * from a script. Nothing here touches the network or starts a process. */
final class JobFixture {

    private JobFixture() {}

    static final JobSettings SETTINGS = new JobSettings(10, 6, 25, 30, 80, 9000, ZoneId.of("Europe/Amsterdam"));
    static final CoreSettings ON = new CoreSettings(true, 4, 3, 80, 30, 7, 2);

    static void clean(ConnectionSource db) throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE tasks, job_runs, job_state, kv, drafts RESTART IDENTITY");
        }
    }

    private static Jobs parser;

    static synchronized Jobs parser() throws IOException {
        if (parser == null) {
            Path conf = Files.createTempDirectory("jobconf");
            Files.writeString(conf.resolve("inbox.conf"), "");
            parser = new Jobs(new Settings(conf, Map.of()), JsonMapper.builder().build());
        }
        return parser;
    }

    /** A job file parsed with the same rules as the Python test helpers: no known repos, no known skills. */
    static Job parse(String name, String text) throws IOException {
        return parser().parse(name, text, Set.of(), Set.of());
    }

    static final String RESEARCH_FILE = "---\nwhen: weekly\nschedule: \"0 9 * * 1\"\nkind: research\nmodel: claude\n%s---\n"
            + "What changed in Tailscale ACL syntax this year?\n";

    static Job research(String extra) throws IOException {
        return parse("acl-watch", RESEARCH_FILE.formatted(extra));
    }

    static final String NOTHING = "Nothing broke: answer NOTHING TO REPORT as the first line.";

    static Job local(String name, String deliver) throws IOException {
        return parse(name, "---\nwhen: daily\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: local\ndeliver: " + deliver
                + "\n---\n" + NOTHING + "\n");
    }

    /** The effects: records what the core asked, answers the reads from the fields. */
    static class Recorder extends ShadowEffects {
        record Push(String title, String body, String url, int limit) {}

        record Note(String author, String client, String kind, String text, String link, String ref) {}

        final List<Push> pushes = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();
        final Map<String, Object> github = new HashMap<>();
        String overview = "overview";
        String summary = "summary";
        Effects.ScriptResult script = new Effects.ScriptResult(0, "out");
        String pageText = "";
        IOException failure;

        @Override
        public void notify(String title, String body, String url) {
            super.notify(title, body, url);
            pushes.add(new Push(title, body, url, 300));
        }

        @Override
        public void notify(String title, String body, String url, int limit) {
            super.notify(title, body, url, limit);
            pushes.add(new Push(title, body, url, limit));
        }

        @Override
        public void note(String author, String client, String kind, String text, String link, String ref) {
            super.note(author, client, kind, text, link, ref);
            notes.add(new Note(author, client, kind, text, link, ref));
        }

        @Override
        public String boxOverview() throws IOException {
            super.boxOverview();
            if (failure != null) {
                throw failure;
            }
            return overview;
        }

        @Override
        public String fetchPage(String url) throws IOException {
            super.fetchPage(url);
            if (failure != null) {
                throw failure;
            }
            return pageText;
        }

        @Override
        public Object github(String path) throws IOException {
            super.github(path);
            if (failure != null) {
                throw failure;
            }
            return github.getOrDefault(path.split("\\?")[0], List.of());
        }

        @Override
        public Effects.ScriptResult runScript(String repo, String script, List<String> args, long runId) {
            super.runScript(repo, script, args, runId);
            return this.script;
        }

        @Override
        public String summarize(String jobName, String instruction, String text) throws IOException {
            super.summarize(jobName, instruction, text);
            if (failure != null) {
                throw failure;
            }
            return summary;
        }
    }
}
