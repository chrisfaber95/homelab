package dev.homelab.inbox.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.db.MemoryCommand;
import dev.homelab.inbox.memory.MemoryWriter.Author;
import dev.homelab.inbox.memory.MemoryWriter.Block;
import dev.homelab.inbox.memory.MemoryWriter.Refused;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.MemoryReader;
import dev.homelab.inbox.read.Settings;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The memory writes against a local bare repository standing in for DATA_REPO (REMOTE_HOMELAB_DATA, as in the Python tests). */
class MemoryWriterTest {

    private static final Author CHRIS = new Author("chrisfaber95 (via de pagina)", "homelab-inbox@homelab");
    private static final String FAKE_TOKEN = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4";

    @TempDir Path tmp;
    Path remote;
    Path home;
    Settings settings;
    MemoryRepo repo;
    MemoryReader reader;
    MemoryWriter writer;

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
        sh(tmp, "git", "init", "--bare", "--quiet", "-b", "memory", remote.toString());
        // one entry written "by the Python inbox", pushed to the branch before the import
        Path seed = tmp.resolve("seed");
        sh(tmp, "git", "clone", "--quiet", remote.toString(), seed.toString());
        sh(seed, "git", "checkout", "--quiet", "-B", "memory");
        Files.createDirectories(seed.resolve("feiten/box"));
        Files.writeString(seed.resolve("feiten/box/oud.md"), "---\nover: \"box\"\ntoegevoegd: \"2026-11-01T10:00:00+00:00\"\n"
                + "bron: \"inbox 5\"\n---\nOud feit\n");
        sh(seed, "git", "add", "-A");
        sh(seed, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "seed");
        sh(seed, "git", "push", "--quiet", "origin", "memory");
        home = tmp.resolve("home");
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(home.resolve("conf/inbox.conf"), "DATA_REPO=\"x/y\"\nMEMORY_BRANCH=\"memory\"\n");
        settings = new Settings(home.resolve("conf"), Map.of("INBOX_RUNNER_HOME", home.toString(),
                "REMOTE_HOMELAB_DATA", remote.toString(), "REPOS_CONF", home.resolve("repos.conf").toString()));
        Files.writeString(home.resolve("repos.conf"), "homelab x/homelab main\nkitt x/kitt main\n");
        repo = new MemoryRepo(settings);
        reader = new MemoryReader(settings, new Jobs(settings, null));
        writer = new MemoryWriter(repo, reader, new Jobs(settings, null));
    }

    Map<String, String> env() {
        return Map.of("INBOX_RUNNER_HOME", home.toString(), "REMOTE_HOMELAB_DATA", remote.toString(),
                "REPOS_CONF", home.resolve("repos.conf").toString());
    }

    int imported() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MemoryCommand.run(MemoryCommand.IMPORT, new String[] {"--config-dir", home.resolve("conf").toString()},
                new PrintStream(out), new PrintStream(err), env());
        assertThat(code).as(err.toString()).isZero();
        return code;
    }

    String onRemote(String file) throws Exception {
        Path check = tmp.resolve("check-" + System.nanoTime());
        sh(tmp, "git", "clone", "--quiet", "-b", "memory", remote.toString(), check.toString());
        Path f = check.resolve(file);
        return Files.exists(f) ? Files.readString(f) : null;
    }

    String lastCommit() throws Exception {
        return sh(remote, "git", "log", "-1", "--format=%an <%ae>%n%s", "memory").strip();
    }

    @Test
    void nothingIsWrittenBeforeTheImportAndTheImportBringsTheClonePlusMarker() throws Exception {
        assertThatThrownBy(() -> writer.add(Map.of("kind", "feiten", "about", "box", "name", "nieuw", "text", "x"), "w", CHRIS))
                .isInstanceOf(Refused.class).hasMessageContaining("not imported");
        assertThat(onRemote("feiten/box/nieuw.md")).isNull();
        imported();
        assertThat(repo.owned()).isTrue();
        assertThat(reader.countEntries()).isEqualTo(1);
        // a second import is a no-op with --if-not-imported and refused without it
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] again = {"--config-dir", home.resolve("conf").toString()};
        assertThat(MemoryCommand.run(MemoryCommand.IMPORT, new String[] {again[0], again[1], "--if-not-imported"},
                new PrintStream(out), new PrintStream(err), env())).isZero();
        assertThat(out.toString()).contains("already imported");
        assertThat(MemoryCommand.run(MemoryCommand.IMPORT, again, new PrintStream(out), new PrintStream(err), env())).isEqualTo(2);
    }

    @Test
    void addEditAndDeleteFromThePageCommitInTheOwnersName() throws Exception {
        imported();
        String go = writer.add(Map.of("kind", "feiten", "about", "kitt", "name", " nieuw ", "text", "Een \"feit\"\r\nregel 2 é"),
                "door jou op de pagina vanaf telefoon", CHRIS);
        assertThat(go).isEqualTo("/memory/e/feiten/kitt/nieuw");
        String text = onRemote("feiten/kitt/nieuw.md");
        assertThat(text).startsWith("---\nover: \"kitt\"\ntoegevoegd: \"20").contains("\nbron: \"door jou toegevoegd\"\n---\nEen \"feit\"\nregel 2 é\n")
                .doesNotContain("link:");
        assertThat(lastCommit()).isEqualTo("chrisfaber95 (via de pagina) <homelab-inbox@homelab>\n"
                + "geheugen: feiten/kitt/nieuw nieuw (door jou op de pagina vanaf telefoon)");
        assertThatThrownBy(() -> writer.add(Map.of("kind", "feiten", "about", "kitt", "name", "nieuw", "text", "x"), "w", CHRIS))
                .hasMessage("feiten/kitt/nieuw bestaat al; bewerk dat item");

        assertThat(writer.edit("feiten/kitt/nieuw", Map.of("text", "Aangepast"), "w", CHRIS)).isEqualTo("/memory/e/feiten/kitt/nieuw");
        assertThat(onRemote("feiten/kitt/nieuw.md")).contains("bron: \"door jou toegevoegd\"").endsWith("---\nAangepast\n");
        assertThat(writer.edit("feiten/box/oud", Map.of("text", "Nieuw"), "w", CHRIS)).isEqualTo("/memory/e/feiten/box/oud");
        assertThat(onRemote("feiten/box/oud.md")).contains("toegevoegd: \"2026-11-01T10:00:00+00:00\"")
                .contains("bron: \"inbox 5, bewerkt door jou\"");

        assertThat(writer.delete("feiten/box/oud", "w", CHRIS)).isEqualTo("/memory");
        assertThat(onRemote("feiten/box/oud.md")).isNull();
        assertThat(lastCommit()).endsWith("geheugen: feiten/box/oud gewist (w)");
        assertThatThrownBy(() -> writer.delete("feiten/box/oud", "w", CHRIS)).isInstanceOfSatisfying(Refused.class,
                e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> writer.edit("feiten/box/weg", Map.of("text", "x"), "w", CHRIS)).hasMessage("Niet gevonden.");
    }

    @Test
    void thePageRulesAreThoseOfThePythonCode() {
        imported();
        assertThatThrownBy(() -> writer.add(Map.of("kind", "geschiedenis", "about", "box", "name", "x", "text", "t"), "w", CHRIS))
                .hasMessageStartingWith("kies voorkeur of feit");
        assertThatThrownBy(() -> writer.add(Map.of("kind", "feiten", "about", "onbekend", "name", "x", "text", "t"), "w", CHRIS))
                .hasMessageStartingWith("kies voorkeur of feit");
        assertThatThrownBy(() -> writer.add(Map.of("kind", "feiten", "about", "box", "name", "Hoofd", "text", "t"), "w", CHRIS))
                .hasMessageStartingWith("kies voorkeur of feit");
        assertThatThrownBy(() -> writer.add(Map.of("kind", "feiten", "about", "box", "name", "x", "text", "  "), "w", CHRIS))
                .hasMessage("de tekst is leeg");
        assertThatThrownBy(() -> writer.add(Map.of("kind", "feiten", "about", "box", "name", "x", "text", "a".repeat(4001)), "w", CHRIS))
                .hasMessage("hooguit 4000 tekens");
        assertThatThrownBy(() -> writer.add(Map.of("kind", "feiten", "about", "box", "name", "x", "text", "token " + FAKE_TOKEN), "w", CHRIS))
                .hasMessageStartingWith("dit lijkt een token");
    }

    @Test
    void whatClaudeLearnedKeepsTheValidBlocksAndNotesTheRest() throws Exception {
        imported();
        List<String> notes = writer.learned(List.of(
                new Block("voorkeur", "algemeen", "kort", "Houd het kort."),
                new Block("feit", "box", "oud", "Bijgewerkt feit"),
                new Block("feit", "nergens", "x", "tekst"),
                new Block("feit", "box", "leeg", ""),
                new Block("feit", "box", "lang", "a".repeat(1501)),
                new Block("feit", "box", "geheim", "password: hunter2hunter2")), "inbox 7", "http://x/task/7", "taak 7");
        assertThat(notes).containsExactly("2 item(s) in het geheugen bewaard", "x: onbekend onderwerp nergens",
                "leeg: leeg of langer dan 1500 tekens", "lang: leeg of langer dan 1500 tekens",
                "geheim: niet bewaard, het lijkt een geheim te bevatten");
        assertThat(onRemote("voorkeuren/algemeen/kort.md")).contains("bron: \"inbox 7\"", "link: \"http://x/task/7\"");
        assertThat(onRemote("feiten/box/oud.md")).contains("toegevoegd: \"2026-11-01T10:00:00+00:00\"", "Bijgewerkt feit");
        assertThat(lastCommit()).isEqualTo("Claude (homelab-inbox) <noreply@anthropic.com>\n"
                + "geheugen: voorkeuren/algemeen/kort, feiten/box/oud (Claude, taak 7; bron: inbox 7)");
        assertThat(writer.learned(List.of(new Block("feit", "nergens", "x", "t")), "s", "l", "t")).containsExactly("x: onbekend onderwerp nergens");
    }

    @Test
    void historyIsScrubbedKeepsTheFirstDateAndSkipsAnEntryTheUserErased() throws Exception {
        imported();
        MemoryWriter.Task t = new MemoryWriter.Task(9, "2026-11-17T08:00:00+00:00", "homelab", "Inbox 9", "inbox 9", "http://x/task/9",
                "gemerged", "https://github.com/x/y/pull/1", "", "Titel", "Vraag met " + FAKE_TOKEN, "Gedaan", false);
        assertThat(writer.history(t)).isTrue();
        String text = onRemote("geschiedenis/2026-11/taak-9.md");
        assertThat(text).contains("over: \"homelab\"", "toegevoegd: \"2026-11-17T08:00:00+00:00\"", "bron: \"Inbox 9\"",
                "status: \"gemerged\"", "pr: \"https://github.com/x/y/pull/1\"").doesNotContain("job:", FAKE_TOKEN)
                .endsWith("---\n# Titel\n\nGevraagd:\nVraag met [verborgen]\n\nResultaat:\nGedaan\n");
        assertThat(lastCommit()).endsWith("geheugen: geschiedenis inbox 9 (gemerged)");
        // the user erases it on the page; a later update (remembered = true) leaves it erased
        writer.delete("geschiedenis/2026-11/taak-9", "w", CHRIS);
        assertThat(writer.history(new MemoryWriter.Task(9, t.created(), t.repo(), t.label(), t.labelLower(), t.link(), t.statusNl(),
                t.prUrl(), t.job(), t.title(), t.asked(), t.result(), true))).isFalse();
        assertThat(onRemote("geschiedenis/2026-11/taak-9.md")).isNull();
    }

    @Test
    void aChangeThatChangesNothingMakesNoCommit() throws Exception {
        imported();
        String before = sh(remote, "git", "rev-parse", "memory");
        writer.learned(List.of(new Block("feit", "nergens", "x", "t")), "s", "l", "t");
        assertThat(sh(remote, "git", "rev-parse", "memory")).isEqualTo(before);
    }

    @Test
    void exportRefusesWhileTheCloneHoldsWhatGitHubLacksAndThenReleasesTheMarker() throws Exception {
        imported();
        writer.add(Map.of("kind", "feiten", "about", "box", "name", "java", "text", "door java"), "w", CHRIS);
        // a commit that was never pushed (the push failed once): the export must not drop it silently
        Files.writeString(repo.dir().resolve("feiten/box/lokaal.md"), "---\nover: \"box\"\n---\nlokaal\n");
        sh(repo.dir(), "git", "add", "-A");
        sh(repo.dir(), "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "lokaal");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--config-dir", home.resolve("conf").toString()};
        assertThat(MemoryCommand.run(MemoryCommand.EXPORT, args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), env())).isEqualTo(1);
        assertThat(err.toString()).contains("EXPORT REFUSED", "lokaal");
        assertThat(repo.owned()).isTrue();
        sh(repo.dir(), "git", "push", "--quiet", "origin", "HEAD:refs/heads/memory");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(MemoryCommand.run(MemoryCommand.EXPORT, args, new PrintStream(out), new PrintStream(err), env())).isZero();
        assertThat(out.toString()).contains("export ok", "entries=3");
        assertThat(repo.owned()).isFalse();
        assertThat(onRemote("feiten/box/java.md")).contains("door java");
    }

    @Test
    void aMissingBranchOnGitHubGivesAnEmptyCloneThatCanBeWritten() throws Exception {
        sh(remote, "git", "update-ref", "-d", "refs/heads/memory");
        imported();
        assertThat(reader.countEntries()).isZero();
        writer.add(Map.of("kind", "voorkeuren", "about", "algemeen", "name", "eerste", "text", "hoi"), "w", CHRIS);
        assertThat(onRemote("README.md")).startsWith("# memory");
        assertThat(onRemote("voorkeuren/algemeen/eerste.md")).contains("hoi");
    }
}
