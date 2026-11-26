package dev.homelab.inbox.core;

import dev.homelab.inbox.memory.MemoryRepo;
import dev.homelab.inbox.memory.MemoryWriter;
import dev.homelab.inbox.read.MemoryReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The memory store (the clone, its writer and its reader, all in this process since H-024 M5) as {@link LiveMemory} sees it. */
final class MemoryDoor implements LiveMemory.Store {

    private final MemoryRepo repo;
    private final MemoryWriter writer;
    private final MemoryReader reader;

    MemoryDoor(MemoryRepo repo, MemoryWriter writer, MemoryReader reader) {
        this.repo = repo;
        this.writer = writer;
        this.reader = reader;
    }

    @Override
    public void sync() {
        repo.sync();
    }

    @Override
    public Path file(String id) {
        return repo.dir().resolve(id + ".md");
    }

    @Override
    public List<Map<String, Object>> entries() {
        return reader.entries();
    }

    @Override
    public List<String> learned(List<String[]> blocks, String source, String link, String labelLower) {
        List<MemoryWriter.Block> out = new ArrayList<>();
        for (String[] b : blocks) {
            out.add(new MemoryWriter.Block(b[0], b[1], b[2], b[3]));
        }
        return writer.learned(out, source, link, labelLower);
    }

    @Override
    public boolean history(Task t, String label, String labelLower, String link, String statusNl, String title, String asked,
                           String result) {
        return writer.history(new MemoryWriter.Task(t.id(), t.str("created"), t.repo(), label, labelLower, link, statusNl,
                t.has("pr_url") ? t.str("pr_url") : "", t.has("job") ? t.str("job") : "", title, asked, result,
                t.num("remembered") != 0));
    }
}
