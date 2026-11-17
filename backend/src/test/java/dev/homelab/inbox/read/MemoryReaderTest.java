package dev.homelab.inbox.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Values pinned to what {@code memory.parse_entry}, {@code memory_view} and {@code overview.local} give in Python. */
class MemoryReaderTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private static void write(Path root, String id, String text) throws IOException {
        Path f = root.resolve(id + ".md");
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private MemoryReader reader(Path home) {
        Settings settings = new Settings(home.resolve("conf"), Map.of("INBOX_RUNNER_HOME", home.toString()));
        return new MemoryReader(settings, new Jobs(settings, null));
    }

    @Test
    void parsesTheFrontMatterAndFallsBackLikePython() {
        Map<String, Object> e = MemoryReader.parse("feiten/box/x",
                "---\nover: \"box\"\ntoegevoegd: \"2026-11-01T10:00:00+00:00\"\nbron: \"inbox 5\"\npr: 12\n---\nBody é\n");
        assertThat(e).containsEntry("about", "box").containsEntry("added", "2026-11-01T10:00:00+00:00")
                .containsEntry("changed", "2026-11-01T10:00:00+00:00").containsEntry("pr", "12").containsEntry("body", "Body é");
        assertThat(MemoryReader.parse("feiten/box/x", "just text\n")).containsEntry("about", "box").containsEntry("body", "just text");
        assertThat(MemoryReader.parse("geschiedenis/2026-11/taak-1", "---\nstatus: merged\n---\nb")).containsEntry("about", "algemeen");
        // broken YAML and a front matter that is no mapping: no metadata, the body after the second ---
        assertThat(MemoryReader.parse("feiten/box/x", "---\nover: [unclosed\n---\nb\n")).containsEntry("about", "box").containsEntry("body", "b");
        assertThat(MemoryReader.parse("feiten/box/x", "---\n- a\n---\nb\n")).containsEntry("about", "box");
        // a plain date stays the date as written (Python: str(date))
        assertThat(MemoryReader.parse("feiten/box/x", "---\ntoegevoegd: 2026-11-02\n---\nb\n")).containsEntry("added", "2026-11-02");
    }

    @Test
    void localIsTheDayAndTimeInTheBoxZone() {
        assertThat(Text.local("2026-11-01T10:00:00+00:00", AMS)).isEqualTo("01-11 11:00");
        assertThat(Text.local("2026-11-03T09:00:00", AMS)).isEqualTo("03-11 10:00"); // no offset: UTC
        assertThat(Text.local("2026-11-02", AMS)).isEqualTo("02-11 01:00");
        assertThat(Text.local("", AMS)).isEqualTo("-");
        assertThat(Text.local("gisteren", AMS)).isEqualTo("gisteren");
    }

    @Test
    void listsEntriesInPathOrderAndIgnoresFilesThatAreNoEntries(@TempDir Path home) throws IOException {
        Path root = home.resolve("memory-repo");
        write(root, "feiten/box/deploy", "---\nover: box\n---\na");
        write(root, "feiten/box/deploy-x", "---\nover: box\n---\nb");
        write(root, "feiten/box/Hoofd", "x");
        write(root, "voorkeuren/algemeen/klein", "c");
        write(root, "overig/algemeen/x", "x");
        List<Object> ids = reader(home).load().stream().map(m -> m.get("id")).toList();
        // Python sorts the paths part by part, with the extension: "deploy-x.md" comes before "deploy.md"
        assertThat(ids).containsExactly("feiten/box/deploy-x", "feiten/box/deploy", "voorkeuren/algemeen/klein");
    }

    @Test
    void anEntryThatDoesNotExistIsEmpty(@TempDir Path home) throws IOException {
        write(home.resolve("memory-repo"), "feiten/box/deploy", "---\nover: box\n---\nBody");
        MemoryReader reader = reader(home);
        assertThat(reader.entry("feiten/box/deploy")).isPresent();
        assertThat(reader.entry("feiten/box/other")).isEmpty();
        assertThat(reader.entry("../../etc/passwd")).isEmpty();
        assertThat(reader.entry("feiten/box/deploy/../deploy")).isEmpty();
    }
}
