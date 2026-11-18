package dev.homelab.inbox.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.db.JobsCommand;
import dev.homelab.inbox.jobs.JobsWriter.Refused;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Settings;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The job-file writes against a local bare repository standing in for DATA_REPO (REMOTE_HOMELAB_DATA, as in the Python tests). */
class JobsWriterTest {

    private static final String GOOD = "---\nwhen: daily\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: local\n---\nReport what failed.\n";

    @TempDir Path tmp;
    Path remote;
    Path home;
    Settings settings;
    JobsRepo repo;
    Jobs jobs;
    JobsWriter writer;

    static String sh(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
        }
        return out;
    }

    @BeforeEach
    void setUp() throws Exception {
        remote = tmp.resolve("remote.git");
        sh(tmp, "git", "init", "--bare", "--quiet", "-b", "jobs", remote.toString());
        // one job written "by the Python inbox", pushed to the branch before the import
        Path seed = tmp.resolve("seed");
        sh(tmp, "git", "clone", "--quiet", remote.toString(), seed.toString());
        sh(seed, "git", "checkout", "--quiet", "-B", "jobs");
        Files.createDirectories(seed.resolve("jobs"));
        Files.writeString(seed.resolve("jobs/oud.md"), GOOD);
        sh(seed, "git", "add", "-A");
        sh(seed, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "seed");
        sh(seed, "git", "push", "--quiet", "origin", "jobs");
        home = tmp.resolve("home");
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(home.resolve("conf/inbox.conf"), "DATA_REPO=\"x/y\"\nJOBS_BRANCH=\"jobs\"\n");
        Files.writeString(home.resolve("repos.conf"), "homelab x/homelab main\nkitt x/kitt main\n");
        settings = new Settings(home.resolve("conf"), Map.of("INBOX_RUNNER_HOME", home.toString(),
                "REMOTE_HOMELAB_DATA", remote.toString(), "REPOS_CONF", home.resolve("repos.conf").toString()));
        repo = new JobsRepo(settings);
        jobs = new Jobs(settings, null);
        writer = new JobsWriter(repo, jobs);
    }

    Map<String, String> env() {
        return Map.of("INBOX_RUNNER_HOME", home.toString(), "REMOTE_HOMELAB_DATA", remote.toString(),
                "REPOS_CONF", home.resolve("repos.conf").toString());
    }

    int run(String command, String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = new String[extra.length + 2];
        args[0] = "--config-dir";
        args[1] = home.resolve("conf").toString();
        System.arraycopy(extra, 0, args, 2, extra.length);
        lastOut = out;
        lastErr = err;
        return JobsCommand.run(command, args, new PrintStream(out), new PrintStream(err), env());
    }

    ByteArrayOutputStream lastOut;
    ByteArrayOutputStream lastErr;

    String onRemote(String file) throws Exception {
        Path check = tmp.resolve("check-" + System.nanoTime());
        sh(tmp, "git", "clone", "--quiet", "-b", "jobs", remote.toString(), check.toString());
        Path f = check.resolve(file);
        return Files.exists(f) ? Files.readString(f) : null;
    }

    String lastCommit() throws Exception {
        return sh(remote, "git", "log", "-1", "--format=%an <%ae>%n%s", "jobs").strip();
    }

    @Test
    void nothingIsWrittenBeforeTheImportAndTheImportBringsTheClonePlusMarker() throws Exception {
        assertThatThrownBy(() -> writer.save("nieuw", GOOD, "jobs: nieuw nieuw (w)"))
                .hasMessageContaining("not imported");
        assertThat(onRemote("jobs/nieuw.md")).isNull();
        assertThat(run(JobsCommand.IMPORT)).as(lastErr.toString()).isZero();
        assertThat(lastOut.toString()).contains("import ok: job files=1");
        assertThat(repo.owned()).isTrue();
        assertThat(jobs.load()).containsOnlyKeys("oud");
        assertThat(run(JobsCommand.IMPORT, "--if-not-imported")).isZero();
        assertThat(lastOut.toString()).contains("already imported");
        assertThat(run(JobsCommand.IMPORT)).isEqualTo(2);
        assertThat(run(JobsCommand.IMPORT, "--replace")).isZero();
    }

    @Test
    void saveEditPauseResumeAndDeleteCommitAsTheClonesUserWithTheMessageOfTheCaller() throws Exception {
        run(JobsCommand.IMPORT);
        assertThat(writer.save("nieuw", GOOD + "\n\n", "jobs: nieuw nieuw (bevestigd op de pagina vanaf x)")).isTrue();
        assertThat(onRemote("jobs/nieuw.md")).isEqualTo(GOOD.strip() + "\n");
        assertThat(onRemote("README.md")).startsWith("# jobs");
        assertThat(lastCommit()).isEqualTo("homelab-inbox <homelab-inbox@homelab>\njobs: nieuw nieuw (bevestigd op de pagina vanaf x)");
        String edited = GOOD.replace("Report what failed.", "Report more.");
        assertThat(writer.save("nieuw", edited, "jobs: nieuw bewerkt (w)")).isTrue();
        assertThat(onRemote("jobs/nieuw.md")).contains("Report more.");
        assertThat(writer.pause("nieuw", true, "jobs: nieuw gepauzeerd (w)")).isTrue();
        assertThat(onRemote("jobs/nieuw.md")).isEqualTo("---\nwhen: daily\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: local\n"
                + "paused: true\n---\nReport more.\n");
        assertThat(jobs.load().get("nieuw").paused()).isTrue();
        assertThat(writer.pause("nieuw", true, "again")).isFalse(); // nothing changes: no commit
        assertThat(writer.pause("nieuw", false, "jobs: nieuw hervat (w)")).isTrue();
        assertThat(onRemote("jobs/nieuw.md")).isEqualTo(edited.strip() + "\n");
        assertThat(writer.save("nieuw", null, "jobs: nieuw verwijderd (w)")).isTrue();
        assertThat(onRemote("jobs/nieuw.md")).isNull();
        assertThat(onRemote("jobs/oud.md")).isEqualTo(GOOD); // the seeded job is untouched
        assertThat(writer.save("nieuw", null, "again")).isFalse();
    }

    @Test
    void theRulesAreThoseOfTheJobFileAndTheNameIsAPlainName() {
        run(JobsCommand.IMPORT);
        assertThatThrownBy(() -> writer.save("../etc/x", GOOD, "m")).isInstanceOf(Refused.class)
                .hasMessage("de naam mag alleen kleine letters, cijfers en streepjes hebben");
        assertThatThrownBy(() -> writer.save("Nieuw", GOOD, "m")).isInstanceOf(Refused.class);
        assertThatThrownBy(() -> writer.save("x", "geen kop", "m")).hasMessage("het bestand begint niet met een kop tussen --- en ---");
        assertThatThrownBy(() -> writer.save("x", GOOD.replace("local", "gpt"), "m")).hasMessage("model moet local of claude zijn");
        assertThatThrownBy(() -> writer.save("x", GOOD.replace("kind: box-report", "kind: digest"), "m"))
                .hasMessageContaining("een digest heeft sources nodig");
        assertThatThrownBy(() -> writer.save("x", GOOD.replace("model: local", "model: local\nrepo: nergens"), "m"))
                .hasMessageContaining("repo nergens staat niet in de lijst");
        assertThatThrownBy(() -> writer.save("x", GOOD, " ")).hasMessage("a commit message is required");
        assertThatThrownBy(() -> writer.pause("nergens", true, "m")).isInstanceOfSatisfying(Refused.class,
                e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void aBrokenFileCannotBePaused() throws Exception {
        run(JobsCommand.IMPORT);
        Files.writeString(settings.jobsDir().resolve("kapot.md"), "geen kop");
        // the clone is brought to GitHub before the edit, so the broken local file is gone: the job does not exist
        assertThatThrownBy(() -> writer.pause("kapot", true, "m")).isInstanceOfSatisfying(Refused.class,
                e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void setPausedKeepsTheOtherHeaderLinesAndKnowsPythonsLineBreaks() {
        assertThat(JobsWriter.setPaused("---\na: 1\npaused: false\nb: 2\n---\nx\n", true)).isEqualTo("---\na: 1\nb: 2\npaused: true\n---\nx\n");
        assertThat(JobsWriter.setPaused("---\na: 1\r\npaused: true\r\n---\nx\n", false)).isEqualTo("---\na: 1\n---\nx\n");
        assertThat(JobsWriter.setPaused("---\n\n---\nx\n", true)).isEqualTo("---\npaused: true\n---\nx\n");
        // no closing line: Python's partition finds none and keeps everything as the header
        assertThat(JobsWriter.setPaused("---\n---\nx\n", true)).isEqualTo("---\n---\nx\npaused: true");
    }

    @Test
    void exportRefusesWhileTheCloneHoldsWhatGitHubLacksAndThenReleasesTheMarker() throws Exception {
        run(JobsCommand.IMPORT);
        writer.save("nieuw", GOOD, "jobs: nieuw nieuw (w)");
        assertThat(run(JobsCommand.EXPORT)).isZero(); // everything Java wrote is on GitHub
        assertThat(lastOut.toString()).contains("export ok: job files=2");
        assertThat(repo.owned()).isFalse();
        assertThat(run(JobsCommand.EXPORT)).isEqualTo(2); // not Java's any more
        run(JobsCommand.IMPORT);
        Files.writeString(settings.jobsDir().resolve("lokaal.md"), GOOD); // an uncommitted file Java would lose
        assertThat(run(JobsCommand.EXPORT)).isEqualTo(1);
        assertThat(lastErr.toString()).contains("EXPORT REFUSED").contains("lokaal.md");
        assertThat(repo.owned()).isTrue();
    }

    @Test
    void aMissingBranchOnGitHubGivesAnEmptyCloneThatCanBeWritten() throws Exception {
        sh(remote, "git", "update-ref", "-d", "refs/heads/jobs");
        assertThat(run(JobsCommand.IMPORT)).as(lastErr.toString()).isZero();
        assertThat(jobs.load()).isEmpty();
        assertThat(writer.save("eerste", GOOD, "jobs: eerste nieuw (w)")).isTrue();
        assertThat(onRemote("jobs/eerste.md")).isEqualTo(GOOD);
    }

    @Test
    void whatSomeoneElsePushedInBetweenIsPartOfTheNextWrite() throws Exception {
        run(JobsCommand.IMPORT);
        Path hand = tmp.resolve("hand");
        sh(tmp, "git", "clone", "--quiet", "-b", "jobs", remote.toString(), hand.toString());
        Files.writeString(hand.resolve("jobs/hand.md"), GOOD);
        sh(hand, "git", "add", "-A");
        sh(hand, "git", "-c", "user.name=hand", "-c", "user.email=h@h", "commit", "--quiet", "-m", "hand");
        sh(hand, "git", "push", "--quiet", "origin", "jobs");
        writer.save("daarna", GOOD, "jobs: daarna nieuw (w)");
        assertThat(onRemote("jobs/hand.md")).isEqualTo(GOOD);
        assertThat(onRemote("jobs/daarna.md")).isEqualTo(GOOD);
    }
}
