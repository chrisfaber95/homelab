package dev.homelab.inbox.read;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The built UI inside the jar (H-028 W5a): the Maven profile {@code ui} puts {@code frontend/dist} under
 * {@code ui/dist/} and the stylesheet and fonts ({@code web/}) under {@code ui/web/} on the classpath. The servlet
 * code reads plain directories, so the first call copies them to a private temp directory (the unit has
 * {@code PrivateTmp}). Without {@code ui/} in the jar (a plain {@code package}, the tests) both are {@code null}.
 */
final class EmbeddedUi {

    private static Path root;
    private static boolean tried;

    private EmbeddedUi() {}

    static Path dist() {
        return sub("dist");
    }

    static Path web() {
        return sub("web");
    }

    private static Path sub(String name) {
        Path r = extract();
        return r == null || !Files.isDirectory(r.resolve(name)) ? null : r.resolve(name);
    }

    private static synchronized Path extract() {
        if (tried) {
            return root;
        }
        tried = true;
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] found = resolver.getResources("classpath*:ui/**");
            Path dir = null;
            for (Resource r : found) {
                String url = r.getURL().toString();
                int at = url.lastIndexOf("/ui/");
                if (!r.isReadable() || url.endsWith("/") || at < 0) {
                    continue;
                }
                if (dir == null) {
                    dir = Files.createTempDirectory("inbox-ui");
                }
                Path target = dir.resolve(url.substring(at + 4)).normalize();
                if (!target.startsWith(dir)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = r.getInputStream()) {
                    Files.copy(in, target);
                }
            }
            root = dir;
        } catch (IOException e) {
            System.err.println("warning: cannot unpack the embedded UI: " + e);
        }
        return root;
    }
}
