package dev.homelab.inbox.frontdoor;

import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The CSRF token of the pages, the same one {@code access.py} makes: HMAC-SHA256 of {@code "inbox-form"} keyed with the
 * contents of {@code <INBOX_RUNNER_HOME>/csrf-secret} (created with 32 random bytes as hex, mode 0600, when missing).
 * Both inboxes read the same file, so a token from one is valid at the other.
 */
@Component
@FrontDoorEnabled
public class Csrf {

    private final Path secretFile;

    public Csrf(Settings settings) {
        this(settings.runnerHome().resolve("csrf-secret"));
    }

    public Csrf(Path secretFile) {
        this.secretFile = secretFile;
    }

    private synchronized byte[] secret() {
        try {
            if (!Files.exists(secretFile)) {
                byte[] raw = new byte[32];
                new SecureRandom().nextBytes(raw);
                Files.writeString(secretFile, HexFormat.of().formatHex(raw));
                Files.setPosixFilePermissions(secretFile, PosixFilePermissions.fromString("rw-------"));
            }
            return Files.readString(secretFile).strip().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read or create the CSRF secret " + secretFile, e);
        }
    }

    public String token() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal("inbox-form".getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code hmac.compare_digest}: a missing token counts as the empty one, and never matches. */
    public boolean matches(String sent) {
        byte[] a = (sent == null ? "" : sent).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, token().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code urlsplit(Origin).netloc == Host}: the Origin must be this page. No Origin gives an empty netloc, which
     * never equals a Host header, so a request without Origin is refused as well.
     */
    public static boolean originMatches(String origin, String host) {
        return netloc(origin == null ? "" : origin).equals(host == null ? "" : host);
    }

    static String netloc(String url) {
        int start;
        int scheme = url.indexOf("://");
        if (scheme > 0 && url.substring(0, scheme).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            start = scheme + 3;
        } else if (url.startsWith("//")) {
            start = 2;
        } else {
            return "";
        }
        int end = url.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int at = url.indexOf(c, start);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return url.substring(start, end);
    }
}
