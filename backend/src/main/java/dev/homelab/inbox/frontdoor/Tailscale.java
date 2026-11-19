package dev.homelab.inbox.frontdoor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The two {@code tailscale} commands the owner check needs; the tests replace it with a fake. */
public interface Tailscale {

    /** {@code tailscale status --json}, or an exception when it fails. */
    String status() throws IOException, InterruptedException;

    /** {@code tailscale whois --json <ip>}, or an exception when it fails. */
    String whois(String ip) throws IOException, InterruptedException;

    /** The real CLI, with the timeouts of {@code access.py} (10 s and 5 s). */
    Tailscale CLI = new Tailscale() {
        @Override
        public String status() throws IOException, InterruptedException {
            return run(10, "tailscale", "status", "--json");
        }

        @Override
        public String whois(String ip) throws IOException, InterruptedException {
            return run(5, "tailscale", "whois", "--json", ip);
        }

        private String run(int seconds, String... command) throws IOException, InterruptedException {
            Process p = new ProcessBuilder(List.of(command)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("tailscale timed out");
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    };
}
