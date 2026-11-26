package dev.homelab.inbox.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

/** The {@code share/<id>/} directories the service writes the prompt files in for the task account to read. */
final class Share {

    private Share() {}

    /** {@code mkdir -p}, mode 2750 (setgid: group inbox-share); prompt.txt and system.txt mode 640. */
    static void write(Path dir, String prompt, String system) {
        try {
            Files.createDirectories(dir);
            setMode(dir, 02750, "rwxr-x---");
            Files.writeString(dir.resolve("prompt.txt"), prompt == null ? "" : prompt);
            Files.writeString(dir.resolve("system.txt"), system);
            for (String f : new String[] {"prompt.txt", "system.txt"}) {
                Files.setPosixFilePermissions(dir.resolve(f), PosixFilePermissions.fromString("rw-r-----"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("prompt schrijven mislukt: " + e.getMessage(), e);
        }
    }

    private static void setMode(Path p, int mode, String fallback) throws IOException {
        try {
            Files.setAttribute(p, "unix:mode", mode);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(fallback));
        }
    }

    /** {@code shutil.rmtree(ignore_errors=True)} */
    static void remove(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort, as rmtree(ignore_errors=True)
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
