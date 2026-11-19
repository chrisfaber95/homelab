package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.core.AgentCatalogue.Agent;
import dev.homelab.inbox.read.Jobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent catalogue. Ports of test_inbox_agents.py (Catalogue, FirstAgents). */
class AgentCatalogueTest {

    private static final String GOOD = "---\nwhen: daily\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: local\n---\nReport what failed.\n";
    private static final Path REPO_AGENTS = Path.of("..", "agents");

    @TempDir Path dir;
    private ConnectionSource db;
    private JobRuns runs;
    private TaskStore store;

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        JobFixture.clean(db);
        runs = new JobRuns(db);
        store = new TaskStore(db, new CoreFixture.MutableClock("2026-11-18T10:00:00Z"));
    }

    private AgentCatalogue catalogue(Path at) throws Exception {
        return new AgentCatalogue(at, JobFixture.parser(), runs, store);
    }

    @Test // Catalogue.test_repo_example_loads
    void theRepoExamplesLoad() throws Exception {
        var agents = catalogue(REPO_AGENTS).load(Set.of());
        assertThat(agents).isNotEmpty();
        assertThat(agents).allMatch(a -> a.error() == null);
    }

    @Test // Catalogue.test_broken_file_does_not_hide_the_others
    void aBrokenFileDoesNotHideTheOthers() throws Exception {
        Files.writeString(dir.resolve("good.md"), GOOD);
        Files.writeString(dir.resolve("bad.md"), "no header");
        Files.writeString(dir.resolve("worse.md"), GOOD.replace("local", "gpt"));
        Map<String, Agent> by = catalogue(dir).byName(Set.of());
        assertThat(by.get("good").job()).isNotNull();
        assertThat(by.get("bad").error()).contains("kop");
        assertThat(by.get("worse").error()).contains("model");
        assertThat(by.get("good").on()).isFalse();
    }

    @Test // Catalogue.test_switch_on_makes_only_a_draft
    void switchingOnMakesOnlyADraft() throws Exception {
        Files.writeString(dir.resolve("good.md"), GOOD);
        long id = catalogue(dir).switchOn("good", Set.of()).orElseThrow();
        Map<String, Object> d = runs.draft(id).orElseThrow();
        assertThat(d.get("status")).isEqualTo("ready");
        assertThat(AgentCatalogue.draftName((String) d.get("text"))).isEqualTo("good");
        assertThat(JobFixture.parse("good", (String) d.get("text")).kind()).isEqualTo("box-report");
        assertThat(runs.runs(null, 10)).isEmpty(); // nothing ran and no job exists until the draft is confirmed
        assertThat(d.get("request")).isEqualTo("Agent aanzetten: good");
    }

    @Test // Catalogue.test_broken_or_unknown_agent_cannot_be_switched_on
    void aBrokenOrUnknownAgentCannotBeSwitchedOn() throws Exception {
        Files.writeString(dir.resolve("bad.md"), "no header");
        assertThat(catalogue(dir).switchOn("bad", Set.of())).isEmpty();
        assertThat(catalogue(dir).switchOn("nope", Set.of())).isEmpty();
    }

    @Test // Catalogue.test_on_once_a_job_exists
    void anAgentIsOnOnceAJobOfThatNameExists() throws Exception {
        Files.writeString(dir.resolve("good.md"), GOOD);
        assertThat(catalogue(dir).byName(Set.of("good")).get("good").on()).isTrue();
    }

    @Test // (written from jobs.agent_job_text: a file that already has a name keeps its text)
    void aFileThatHasANameKeepsItsText() {
        Agent named = new Agent("x", "---\nname: x\nkind: digest\n---\nbody\n", false, null, null, null);
        assertThat(AgentCatalogue.jobText(named)).isEqualTo(named.text());
        Agent bare = new Agent("x", "---\nkind: digest\n---\nbody\n", false, null, null, null);
        assertThat(AgentCatalogue.jobText(bare)).isEqualTo("---\nname: x\nkind: digest\n---\nbody\n");
    }

    @Test // FirstAgents.test_the_three_are_there_and_none_is_broken
    void theThreeFirstAgentsAreThereAndNoneIsBroken() throws Exception {
        Map<String, Agent> by = catalogue(REPO_AGENTS).byName(Set.of());
        for (String n : new String[] {"morning-digest", "box-report", "repo-watcher"}) {
            assertThat(by).containsKey(n);
            assertThat(by.get(n).error()).isNull();
            Jobs.Job j = by.get(n).job();
            assertThat(j.deliver()).isEqualTo("conversation");
            assertThat(j.model()).isEqualTo("local");
            assertThat(JobText.nothingStatement(j)).contains(JobText.NOTHING_MARKER);
        }
    }

    @Test // FirstAgents.test_sources_start_empty_and_are_asked_for
    void theSourcesStartEmptyAreAskedForAndCanBeFilledIn() throws Exception {
        Map<String, Agent> by = catalogue(REPO_AGENTS).byName(Set.of());
        for (String n : new String[] {"morning-digest", "repo-watcher"}) {
            assertThat(by.get(n).job().sources()).isEmpty();
            assertThat(by.get(n).fill()).contains("sources");
        }
        long id = catalogue(REPO_AGENTS).switchOn("morning-digest", Set.of()).orElseThrow();
        String text = (String) runs.draft(id).orElseThrow().get("text");
        assertThat(text).contains("sources: []");
        String filled = text.replace("sources: []", "sources: [https://example.org/feed.xml]");
        assertThat(JobFixture.parse("morning-digest", filled).sources()).containsExactly("https://example.org/feed.xml");
        String watcher = Files.readString(REPO_AGENTS.resolve("repo-watcher.md"));
        assertThat(JobFixture.parse("repo-watcher", watcher.replace("sources: []", "sources: [github:chrisfaber95/homelab]")).kind())
                .isEqualTo("repo-watch");
        assertThat(by.keySet().stream().collect(Collectors.toSet())).contains("repo-watcher");
    }
}
